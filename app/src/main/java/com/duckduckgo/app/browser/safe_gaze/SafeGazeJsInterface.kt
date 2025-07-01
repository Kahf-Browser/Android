package com.duckduckgo.app.browser.safe_gaze

import android.content.Context
import android.webkit.JavascriptInterface
import com.duckduckgo.app.analytics.AnalyticsEvent
import com.duckduckgo.app.analytics.AnalyticsParam
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwPrediction
import com.duckduckgo.app.trackerdetection.db.KahfImageBlocked
import com.duckduckgo.app.trackerdetection.db.KahfImageBlockedDao
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.extensions.md5
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.kahf.kahf_segmentation.ImageDownloader
import io.kahf.kahf_segmentation.ImageProcessor
import io.kahf.kahf_segmentation.InputImage
import io.kahf.kahf_segmentation.OutputImage
import io.kahf.video_filter.VideoFrameProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import timber.log.Timber
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

class SafeGazeJsInterface(
    private val context: Context,
    private val nsfwDetector: NsfwDetector,
    private val kahfImageBlockedDao: KahfImageBlockedDao,
    private val dispatcher: DispatcherProvider,
    private val analytics: AnalyticsService,
    private val onImageClassified: (type: String, result: OutputImage?) -> Unit,
    private var grayBlur: Boolean = false,
    private var shouldCoverFace: Boolean = false,
    private val imageDetector: ImageProcessor,
    private val videoDetector: VideoFrameProcessor,
) {
    private val inferenceTimes = mutableListOf<Long>()
    private val waitingTimes = mutableListOf<Long>()

    private val urlQueue: PriorityBlockingQueue<InputImage> = PriorityBlockingQueue(20, compareBy { it.order })
    private var processingJob: Job? = null
    private val scope = CoroutineScope(dispatcher.io() + Job())
    private var paused: AtomicBoolean = AtomicBoolean(false)
    private val gson = Gson()
    private val imageDownloader = ImageDownloader()

    private val downloadTracker = ConcurrentHashMap<String?, DownloadStatus>()
    private val downloadSemaphore = Semaphore(5)

    init {
        scope.launch {
            imageDetector.updateFaceCoverMode(shouldCoverFace)
            imageDetector.updateBlurMode(grayBlur)
        }
    }

    fun updateBlurMode(boolean: Boolean) {
        grayBlur = boolean
        imageDetector.updateBlurMode(boolean)
    }

    fun updateFaceCoverMode(boolean: Boolean) {
        shouldCoverFace = boolean
        imageDetector.updateFaceCoverMode(boolean)
    }

    @JavascriptInterface
    fun sendMessageFromWebView(messageType: String, data: String) {
        when (messageType) {
            "detectImg" -> addTaskToQueue(parseImageInfo(data))
            // "detectVideoFrame" -> runVideoDetection(parseImageInfo(data))
        }
    }

    private fun runVideoDetection(imgInfo: InputImage?) {
        imgInfo?.let {
            scope.launch {
                val result = videoDetector.detectVideoFrame(it)
                onImageClassified("videoResult", result)
            }
        }
    }

    private fun addTaskToQueue(input: InputImage?) {
        if (input == null || isInvalidImageUrl(input.src)) {
            onImageClassified("detectionResult", OutputImage(
                result = "null",
                id = input?.id ?: "",
                width = input?.width ?: 0,
                height = input?.height ?: 0,
            ))
            return
        }

        if (urlQueue.any { it.id == input.id } || downloadTracker.containsKey(input.id)) {
            return
        }

        startImageDownload(input)

        urlQueue.add(input)
        processQueue()
    }

    private fun startImageDownload(input: InputImage) {
        downloadTracker[input.id] = DownloadStatus.Pending

        scope.launch {
            try {
                downloadSemaphore.acquire()

                val downloadTime = measureTimeMillis {
                    val startTime = System.currentTimeMillis()
                    val bitmap = withTimeout(2000) {
                        imageDownloader.downloadImageWithAspectRatio(
                            context, input.src, input.baseImg, input.width, input.height
                        )
                    }
                    val downloadTime = System.currentTimeMillis() - startTime

                    if (bitmap != null) {
                        downloadTracker[input.id] = DownloadStatus.Success(bitmap)
                        Timber.d("kLog Downloaded in ${downloadTime}ms for ${input.id}: ${bitmap.width}x${bitmap.height}")
                    } else {
                        downloadTracker[input.id] = DownloadStatus.Failed
                        Timber.d("kLog Download failed for ${input.id} - bitmap is null")
                    }
                }

                Timber.d("kLog Download time for ${input.id}: $downloadTime ms")

            } catch (e: Exception) {
                Timber.e("kLog Image download failed for ${input.id}: ${e.message}")
                downloadTracker[input.id] = DownloadStatus.Failed
            } finally {
                downloadSemaphore.release()

                // If processing is idle, kick-start processing
                if (!paused.get() && processingJob?.isActive != true) {
                    urlQueue.peek()?.let {
                        if (it.id == input.id) {
                            processQueue()
                        }
                    }
                }
            }
        }
    }

    private fun processQueue() {
        if (!paused.get() && processingJob?.isActive != true) {
            processingJob = scope.launch {
                while (urlQueue.isNotEmpty()) {
                    val peekedTask = urlQueue.peek() ?: continue

                    when (val downloadStatus = downloadTracker[peekedTask.id]) {
                        null, is DownloadStatus.Pending -> {
                            // Image download not started or still in progress. Wait a bit.
                            delay(50)
                            continue
                        }
                        is DownloadStatus.Failed -> {
                            // Download failed, remove from queue and tracker
                            urlQueue.poll()
                            downloadTracker.remove(peekedTask.id)
                            onImageClassified("detectionResult", OutputImage(
                                result = "null",
                                id = peekedTask.id ?: "",
                                width = peekedTask.width ?: 0,
                                height = peekedTask.height ?: 0,
                            ))
                            continue
                        }
                        is DownloadStatus.Success -> {
                            // Download completed successfully, remove from queue and process
                            val task = urlQueue.poll()

                            // Small delay to avoid overwhelming the processor
                            delay(10)

                            var output = OutputImage(
                                result = "null",
                                id = task?.id ?: "",
                                width = task?.width ?: 0,
                                height = task?.height ?: 0,
                            )

                            task?.let {
                                val maskType = getMaskType()
                                val cachedResult = kahfImageBlockedDao.findByUrl(
                                    "${it.src?.md5()}_$maskType"
                                )

                                if (cachedResult != null) {
                                    output = OutputImage(
                                        result = cachedResult.responseStr,
                                        id = it.id ?: "",
                                        width = it.width ?: 0,
                                        height = it.height ?: 0
                                    )
                                    Timber.d("kLog cache hit")
                                    downloadTracker.remove(it.id)
                                    return@let
                                }

                                val waitingTime = System.currentTimeMillis() - task.insertedAt
                                waitingTimes.add(waitingTime)
                                if (waitingTimes.size >= 30) {
                                    val avg = waitingTimes.average().toLong()
                                    analytics.logEvent(
                                        AnalyticsEvent.AvgQueueTime,
                                        mapOf(AnalyticsParam.AvgQueueTimeMs to avg.toString()),
                                    )
                                    waitingTimes.clear()
                                }

                                try {
                                    val bmp = downloadStatus.bitmap

                                    Timber.d("kLog Using pre-downloaded bitmap: ${bmp.width}x${bmp.height}")

                                    val nsfwResult: NsfwPrediction?
                                    val nsfwInference = measureTimeMillis {
                                        nsfwResult = nsfwDetector.isNsfw(bmp)
                                    }
                                    Timber.d("kLog NSFW - ${nsfwResult?.isSafe()?.not()}. Inference time: $nsfwInference ms")

                                    if (nsfwResult?.isSafe() == false) {
                                        output = OutputImage(
                                            result = "nsfw",
                                            id = task.id ?: "",
                                            width = task.width ?: 0,
                                            height = task.height ?: 0,
                                            isManipulated = true
                                        )
                                    } else {
                                        val segmentationInf = measureTimeMillis {
                                            output = imageDetector.downloadAndStore(task.copy(imgBitmap = bmp))
                                        }
                                        Timber.d("kLog Segmentation inference time: $segmentationInf ms")

                                        val inferenceTime = nsfwInference + segmentationInf
                                        inferenceTimes.add(inferenceTime)
                                        Timber.d("kLog Total inference time: $inferenceTime ms, NSFW: $nsfwInference, seg: $segmentationInf")
                                    }
                                } catch (e: Exception) {
                                    Timber.e("kLog Error processing image: ${e.message}")
                                    return@let
                                } finally {
                                    downloadTracker.remove(task.id)
                                }

                                if (inferenceTimes.size >= 30) {
                                    val p90 = inferenceTimes.sorted()[inferenceTimes.size * 90 / 100]
                                    analytics.logEvent(
                                        AnalyticsEvent.P90ImageProcessing,
                                        mapOf(AnalyticsParam.ImageProcessingTime to p90.toString()),
                                    )
                                    inferenceTimes.clear()
                                }

                                kahfImageBlockedDao.insert(
                                    KahfImageBlocked(
                                        imageUrl = "${it.src?.md5()}_$maskType",
                                        responseStr = output.result,
                                        isIndecent = output.isManipulated,
                                        imageWidth = output.width.toFloat(),
                                        imageHeight = output.height.toFloat(),
                                        modifiedAt = System.currentTimeMillis(),
                                        maskType = maskType,
                                    ),
                                )
                            }
                            onImageClassified("detectionResult", output)
                        }
                    }
                }
            }
        }
    }

    private fun getMaskType(): Int {
        return if (grayBlur) {
            if (shouldCoverFace) 1 else 0
        } else {
            if (shouldCoverFace) 3 else 2
        }
    }

    fun resetProcessingQueue() {
        urlQueue.clear()
        downloadTracker.clear()
        Timber.d("kLog cancel ongoing image processing")
    }

    fun cancelOngoingImageProcessing() {
        processingJob?.cancel()
        scope.cancel()
    }

    fun onTabPaused(tabId: String) {
        paused.set(true)
        processingJob?.cancel()
    }

    fun onTabResumed(tabId: String) {
        paused.set(false)

        processingJob?.let {
            if (urlQueue.isNotEmpty() && !it.isActive) {
                processQueue()
                Timber.d("kLog processing job resumed for $tabId")
            }
        }
    }

    private fun parseImageInfo(jsonString: String): InputImage? {
        return try {
            gson.fromJson(jsonString, InputImage::class.java).apply {
                insertedAt = System.currentTimeMillis()
            }
        } catch (e: JsonSyntaxException) {
            null
        }
    }

    private fun isInvalidImageUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) {
            return true
        }
        return listOfEndsWith.any { url.endsWith(it, ignoreCase = true) } || listOfContains.any { url.contains(it) }
    }

    private val listOfContains = listOf("image/gif", "image/svg", "/assets/thesun/images/teaser", "grey-placeholder")
    private val listOfEndsWith = listOf(".svg", ".gif")
}

