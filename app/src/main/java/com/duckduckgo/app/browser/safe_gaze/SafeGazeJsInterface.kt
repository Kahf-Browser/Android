package com.duckduckgo.app.browser.safe_gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.webkit.JavascriptInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.duckduckgo.app.analytics.AnalyticsEvent
import com.duckduckgo.app.analytics.AnalyticsParam
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwPrediction
import com.duckduckgo.app.trackerdetection.db.KahfImageBlockedDao
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.SAFE_GAZE_MAX_IMG_SIZE
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.kahf.porda_segmentation.BufferCacheSeg
import io.kahf.porda_segmentation.DownloadImage
import io.kahf.porda_segmentation.InputImage
import io.kahf.porda_segmentation.OutputImage
import io.kahf.video_filter.VideoFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.set
import kotlin.coroutines.resume
import kotlin.system.measureTimeMillis

class SafeGazeJsInterface(
    private val context: Context,
    private val nsfwDetector: NsfwDetector,
    private val kahfImageBlockedDao: KahfImageBlockedDao,
    private val dispatcher: DispatcherProvider,
    private val analytics: AnalyticsService,
    private val onUpdateBlur: (blur: Float) -> Unit,
    private val onImageClassified: (uid: String, detectionResultJson: String, base64Image: String?, updateBlurCount: Boolean) -> Unit,
    private val onVideoFrameClassified: (type: String, result: OutputImage?) -> Unit,
    private var grayBlur: Boolean = false
) {
    private val onDeviceModelCachedResults = mutableMapOf<String, String>()
    private val inferenceTimes = mutableListOf<Long>()
    private val waitingTimes = mutableListOf<Long>()

    private val urlQueue: ConcurrentLinkedQueue<InputImage> = ConcurrentLinkedQueue()
    private var processingJob: Job? = null
    private val scope = CoroutineScope(dispatcher.io() + Job())
    private var paused: AtomicBoolean = AtomicBoolean(false)
    private val gson = Gson()
    private val videoDetector = VideoFilter(context, dispatcher)
    private val imageDetector = BufferCacheSeg(context, dispatcher, grayBlur) { result ->
        onVideoFrameClassified("detectionResult", result)
    }
    private val imageDownloader = DownloadImage()

    companion object {
        private const val MAX_INFERENCE_TIME_MS = 2000L
    }

    init {
        scope.launch {
            loadCacheFromDisk()
        }
    }

    private suspend fun downloadBitmap(
        url: String,
        context: Context
    ): Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            Glide.with(context)
                .asBitmap()
                .load(url)
                .apply(
                    RequestOptions()
                        .downsample(DownsampleStrategy.AT_MOST)
                        .override(SAFE_GAZE_MAX_IMG_SIZE, SAFE_GAZE_MAX_IMG_SIZE)
                        .format(DecodeFormat.PREFER_ARGB_8888),
                )
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .into(
                    object : CustomTarget<Bitmap>() {
                        override fun onResourceReady(
                            resource: Bitmap,
                            transition: Transition<in Bitmap>?
                        ) {
                            if (continuation.isActive) {
                                continuation.resume(resource)
                            }
                        }

                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            // No-op
                        }
                    },
                )
        }
    }

    @JavascriptInterface
    fun callSafegazeOnDeviceModelHandler(
        uid: String,
        detectionResultJson: String,
        base64Image: String?,
        updateBlurCount: Boolean
    ) {
        onImageClassified(uid, detectionResultJson, base64Image, updateBlurCount)
    }

    @JavascriptInterface
    fun updateBlur(blur: Float) {
        onUpdateBlur(blur)
    }

    fun updateBlurMode(boolean: Boolean) {
        grayBlur = boolean
        imageDetector.updateBlurMode(boolean)
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
                onVideoFrameClassified("videoResult", result)
            }
        }
    }

    private fun addTaskToQueue(input: InputImage?) {
        if (input == null) {
            return
        }

        // If same url is already in queue, don't add it again
        if (urlQueue.any { it.src == input.src }) {
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
                                bmp = imageDownloader.downloadImageWithAspectRatio(
                                    context, task.src, task.width, task.height,
                                )
                            }
                            Timber.d("kLog image download time: $imageDownloadTime ms")

                            if (bmp == null) {
                                return@let
                            }

                            // Run NSFW detection
                            val nsfwResult: NsfwPrediction
                            val nsfwInference = measureTimeMillis {
                                nsfwResult = nsfwDetector.isNsfw(bmp)
                            }
                            Timber.d("kLog nsfw classified. IsSafe: ${nsfwResult.isSafe()}. Inference time: $nsfwInference ms")

                            // Image is not safe, blur the whole image
                            if (!nsfwResult.isSafe()) {
                                onVideoFrameClassified(
                                    "detectionResult",
                                    OutputImage(result = "nsfw", id = task.id, width = task.width, height = task.height),
                                )
                                return@let
                            }

                            // Run Segmentation model
                            val segmentationInf = measureTimeMillis {
                                imageDetector.downloadAndStore(task.copy(imgBitmap = bmp))
                            }

                            val inferenceTime = imageDownloadTime + nsfwInference + segmentationInf
                            inferenceTimes.add(inferenceTime)
                        } catch (e: TimeoutCancellationException) {
                            Timber.e("kLog Timeout occurred while processing image: ")
                            // analytics.logEvent(AnalyticsEvent.ImageProcessingTimeout, mapOf(AnalyticsParam.TimedOutImageUrl to it.url))
                            return@let
                        }

                        // TODO Log P90 inference time for every 30 images to GA
                        /*if (inferenceTimes.size >= 30) {
                            val p90 = inferenceTimes.sorted()[inferenceTimes.size * 90 / 100]
                            analytics.logEvent(
                                AnalyticsEvent.P90ImageProcessing,
                                mapOf(AnalyticsParam.ImageProcessingTime to p90.toString()),
                            )
                            inferenceTimes.clear()
                        }

                        // TODO Increase nsfw count and save result cache to local DB
                        onDeviceModelCachedResults[it.url] = resultJson

                        // Don't save data uri images to save space
                        if (it.url.isDataUri().not()) {
                            kahfImageBlockedDao.insert(
                                KahfImageBlocked(
                                    imageUrl = it.url,
                                    responseStr = resultJson,
                                    isIndecent = result.isNsfw || result.persons.any { p -> p.isFemale },
                                    imageWidth = result.imageWidth.toFloat(),
                                    imageHeight = result.imageHeight.toFloat(),
                                    modifiedAt = System.currentTimeMillis()
                                ),
                            )
                        }*/
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

    /**
     * Check if the hardware is compatible with running the on-device models
     * We run the models on a test image and check if the inference time is within limits
     * @return true if the hardware is compatible, false otherwise
     */
    suspend fun isHardwareCompatible(): Boolean {
        Timber.d("kLog checking hardware compatibility")
        
        val bitmap = context.assets.open("test_image.webp").use {
            // convert input stream to byte array
            val buffer = ByteArray(it.available())
            it.read(buffer)
            it.close()

            BitmapFactory.decodeByteArray(
                buffer, 0, buffer.size,
                BitmapFactory.Options().also { op ->
                    op.inSampleSize = 3
                },
            )
        }

        val t1 = System.currentTimeMillis()

        val nsfwPrediction = nsfwDetector.isNsfw(bitmap)
        Timber.d("kLog nsfw classified. IsSafe: ${nsfwPrediction.isSafe()}")

        val inferenceTime = System.currentTimeMillis() - t1

        return if (inferenceTime > 1000) {
            Timber.e("kLog Will make it slower: $inferenceTime ms")
            false
        } else {
            Timber.d("kLog Will run fine: $inferenceTime ms")
            true
        }
    }

    private fun parseImageInfo(jsonString: String): InputImage? {
        return try {
            gson.fromJson(jsonString, InputImage::class.java)
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

    private fun isInvalidImageUrl(url: String): Boolean {
        return listOfEndsWith.any { url.endsWith(it) } || listOfContains.any { url.contains(it) }
    }

    private val listOfContains = listOf("image/gif", "image/svg", "/assets/thesun/images/teaser", "grey-placeholder")
    private val listOfEndsWith = listOf(".svg", ".gif")
}

