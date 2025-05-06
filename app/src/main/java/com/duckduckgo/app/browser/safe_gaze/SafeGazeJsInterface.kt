package com.duckduckgo.app.browser.safe_gaze

import android.content.Context
import android.graphics.Bitmap
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
import io.kahf.porda_segmentation.ImageProcessor
import io.kahf.porda_segmentation.ImageDownloader
import io.kahf.porda_segmentation.InputImage
import io.kahf.porda_segmentation.OutputImage
import io.kahf.video_filter.VideoFrameProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.set
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
    private val onDeviceModelCachedResults = mutableMapOf<String, String>()
    private val inferenceTimes = mutableListOf<Long>()
    private val waitingTimes = mutableListOf<Long>()

    private val urlQueue: ConcurrentLinkedQueue<InputImage> = ConcurrentLinkedQueue()
    private var processingJob: Job? = null
    private val scope = CoroutineScope(dispatcher.io() + Job())
    private var paused: AtomicBoolean = AtomicBoolean(false)
    private val gson = Gson()
    private val imageDownloader = ImageDownloader()

    init {
        scope.launch {
            imageDetector.updateFaceCoverMode(shouldCoverFace)
            imageDetector.updateBlurMode(grayBlur)
            loadCacheFromDisk()
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
            "detectVideoFrame" -> runVideoDetection(parseImageInfo(data))
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

        // If same url is already in queue, don't add it again
        if (urlQueue.any { it.id == input.id }) {
            return
        }

        urlQueue.add(input)
        processQueue()
    }

    private fun processQueue() {
        if (!paused.get() && processingJob?.isActive != true) {
            processingJob = scope.launch {
                while (urlQueue.isNotEmpty()) {
                    val task = urlQueue.poll()
                    // Add a small delay to avoid overwhelming the processor
                    delay(10)

                    val result: OutputImage
                    task?.let {
                        // Log average waiting time for every 30 images to GA
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
                            // Download bitmap
                            val bmp: Bitmap?
                            val imageDownloadTime = measureTimeMillis {
                                bmp = withTimeout(1500) {
                                    imageDownloader.downloadImageWithAspectRatio(
                                        context, task.src, task.baseImg, task.width, task.height,
                                    )
                                }
                            }
                            Timber.d("kLog Download time: $imageDownloadTime ms (${bmp?.width}x${bmp?.height})")

                            if (bmp == null) {
                                return@let
                            }

                            // Run NSFW detection
                            val nsfwResult: NsfwPrediction
                            val nsfwInference = measureTimeMillis {
                                nsfwResult = nsfwDetector.isNsfw(bmp)
                            }
                            Timber.d("kLog NSFW - ${nsfwResult.isSafe().not()}. Inference time: $nsfwInference ms")

                            if (!nsfwResult.isSafe()) {
                                // Image is not safe, blur the whole image
                                result = OutputImage(result = "nsfw", id = task.id ?: "", width = task.width ?: 0, height = task.height ?: 0, isManipulated = true)
                            } else {
                                // Run Segmentation model
                                val segmentationInf = measureTimeMillis {
                                    result = imageDetector.downloadAndStore(task.copy(imgBitmap = bmp))
                                }
                                Timber.d("kLog Segmentation inference time: $segmentationInf ms")

                                val inferenceTime = imageDownloadTime + nsfwInference + segmentationInf
                                inferenceTimes.add(inferenceTime)
                                Timber.d("kLog Total inference time: $inferenceTime ms")
                            }
                        } catch (e: TimeoutCancellationException) {
                            Timber.e("kLog Timeout occurred while processing image: ")
                            analytics.logEvent(AnalyticsEvent.ImageProcessingTimeout)
                            return@let
                        }
                        onImageClassified("detectionResult", result)

                        // Log P90 inference time for every 30 images to GA
                        if (inferenceTimes.size >= 30) {
                            val p90 = inferenceTimes.sorted()[inferenceTimes.size * 90 / 100]
                            analytics.logEvent(
                                AnalyticsEvent.P90ImageProcessing,
                                mapOf(AnalyticsParam.ImageProcessingTime to p90.toString()),
                            )
                            inferenceTimes.clear()
                        }

                        // TODO Save result to local DB
                        // onDeviceModelCachedResults[it.url] = resultJson

                        kahfImageBlockedDao.insert(
                            KahfImageBlocked(
                                imageUrl = it.src?.md5() ?: "",
                                responseStr = "",
                                isIndecent = result.isManipulated,
                                imageWidth = result.width.toFloat(),
                                imageHeight = result.height.toFloat(),
                                modifiedAt = System.currentTimeMillis()
                            ),
                        )
                    }
                }
            }
        }
    }

    fun resetProcessingQueue() {
        urlQueue.clear()
        Timber.d("kLog cancel ongoing image processing")
    }

    fun cancelOngoingImageProcessing() {
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

    private suspend fun loadCacheFromDisk() {
        kahfImageBlockedDao.getAllBlockedImageDetails().first().forEach { kahfImageBlocked->
            onDeviceModelCachedResults[kahfImageBlocked.imageUrl] = kahfImageBlocked.responseStr
        }

        Timber.d("kLog loading cache to disk from memory(${onDeviceModelCachedResults.size})")

        if (onDeviceModelCachedResults.isEmpty()) {
            onDeviceModelCachedResults["--"] = ""
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

