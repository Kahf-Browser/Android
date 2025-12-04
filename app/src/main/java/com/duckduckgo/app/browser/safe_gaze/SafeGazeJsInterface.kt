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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
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
    private val nsfwProcessingTimes = mutableListOf<Long>()
    private val coreInferenceTimes = mutableListOf<Long>()
    private val maskingTimes = mutableListOf<Long>()
    private val pixelationTimes = mutableListOf<Long>()
    private val waitingTimes = mutableListOf<Long>()

    private val urlQueue: PriorityBlockingQueue<InputImage> = PriorityBlockingQueue(20, compareBy { it.order })
    private var processingJob: Job? = null
    private val scope = CoroutineScope(dispatcher.io() + Job())
    private var paused: AtomicBoolean = AtomicBoolean(false)
    private val gson = Gson()
    private val imageDownloader = ImageDownloader()

    private val downloadTracker = ConcurrentHashMap<String?, DownloadStatus>()
    private val downloadSemaphore = Semaphore(5)

    // CRITICAL FIX: Track bitmaps for cleanup
    private val activeBitmaps = ConcurrentHashMap<String?, Bitmap>()

    // CRITICAL FIX: Synchronize ImageDownloader access to prevent native crashes (SIGSEGV)
    // ImageDownloader uses Glide's .submit().get() which is not thread-safe from multiple coroutines
    private val downloaderLock = Any()

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
        Timber.d("kLog: imgLog Received message from WebView: type: $messageType, data: ${gson.toJson(data)}")
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
            Timber.d("kLog imgLog: 1. imageId: ${input?.id}")
            onImageClassified("detectionResult", OutputImage(
                result = "null",
                id = input?.id ?: "",
                width = input?.width ?: 0,
                height = input?.height ?: 0,
                from = "addTaskToQueue"
            ))
            return
        }

        if (urlQueue.any { it.id == input.id } || downloadTracker.containsKey(input.id)) {
            return
        }

        // CRITICAL FIX: Check cache BEFORE downloading to save bandwidth
        scope.launch {
            val maskType = getMaskType()
            val cacheKey = "${input.src?.md5()}_$maskType"
            val cachedResult = kahfImageBlockedDao.findByUrl(cacheKey)

            if (cachedResult != null) {
                Timber.d("kLog imgLog: Cache hit BEFORE download for ${input.id} - saved bandwidth!")
                onImageClassified("detectionResult", OutputImage(
                    result = cachedResult.responseStr,
                    id = input.id ?: "",
                    width = input.width ?: 0,
                    height = input.height ?: 0,
                    from = "cache-early"
                ))
            } else {
                // Cache miss - proceed with download
                startImageDownload(input)
                urlQueue.add(input)
                processQueue()
            }
        }
    }

    private fun startImageDownload(input: InputImage) {
        downloadTracker[input.id] = DownloadStatus.Pending
        var acquired = false

        scope.launch {
            var downloadedBitmap: Bitmap? = null
            try {
                // CRITICAL FIX: Validate inputs before attempting download to prevent native crashes
                if (!isValidDownloadInput(input)) {
                    Timber.w("kLog Invalid download input for ${input.id}: src=${input.src}, width=${input.width}, height=${input.height}")
                    downloadTracker[input.id] = DownloadStatus.Failed
                    return@launch
                }

                downloadSemaphore.acquire()
                acquired = true

                val downloadTime = measureTimeMillis {
                    val startTime = System.currentTimeMillis()

                    try {
                        // CRITICAL FIX: Properly handle timeout and exceptions
                        downloadedBitmap = withTimeout(5000) {
                            try {
                                // CRITICAL FIX: Synchronize access to ImageDownloader to prevent SIGSEGV
                                // Multiple coroutines calling Glide's .submit().get() simultaneously
                                // can cause native memory corruption and crashes
                                synchronized(downloaderLock) {
                                    imageDownloader.downloadImageWithAspectRatio(
                                        context, input.src, input.baseImg, input.width, input.height
                                    )
                                }
                            } catch (e: OutOfMemoryError) {
                                Timber.e("kLog OOM during download for ${input.id}")
                                System.gc() // Suggest garbage collection
                                null
                            } catch (e: IllegalArgumentException) {
                                Timber.e("kLog Invalid argument in Glide download for ${input.id}: ${e.message}")
                                null
                            } catch (e: NullPointerException) {
                                Timber.e("kLog NPE in download for ${input.id}: ${e.message}")
                                null
                            } catch (e: Exception) {
                                Timber.e("kLog Download exception for ${input.id}: ${e.message}")
                                null
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        Timber.w("kLog Download timeout for ${input.id}")
                        downloadedBitmap = null
                    }

                    val downloadDuration = System.currentTimeMillis() - startTime
                    val newBitmap = downloadedBitmap

                    if (newBitmap != null && !newBitmap.isRecycled) {
                        // CRITICAL FIX: Convert to safe bitmap format immediately
                        val safeBitmap = convertToSafeBitmap(newBitmap, input.id)

                        if (safeBitmap != null) {
                            // Clean up original if we made a copy
                            if (safeBitmap !== newBitmap) {
                                newBitmap.recycle()
                            }

                            activeBitmaps[input.id] = safeBitmap
                            downloadTracker[input.id] = DownloadStatus.Success(safeBitmap)
                            Timber.d("kLog Downloaded in ${downloadDuration}ms for ${input.id}: ${safeBitmap.width}x${safeBitmap.height}")
                        } else {
                            newBitmap.recycle()
                            downloadTracker[input.id] = DownloadStatus.Failed
                            Timber.w("kLog Failed to convert bitmap for ${input.id}")
                        }
                    } else {
                        downloadTracker[input.id] = DownloadStatus.Failed
                        Timber.d("kLog Download failed for ${input.id} - bitmap is null or recycled")
                    }
                }

                Timber.d("kLog Download time for ${input.id}: $downloadTime ms")

            } catch (e: OutOfMemoryError) {
                Timber.e("kLog OOM in download coroutine for ${input.id}")
                downloadedBitmap?.recycle()
                downloadTracker[input.id] = DownloadStatus.Failed
                System.gc()
            } catch (e: Exception) {
                Timber.e("kLog Image download failed for ${input.id}: ${e.message}", e)
                downloadedBitmap?.recycle()
                downloadTracker[input.id] = DownloadStatus.Failed
            } finally {
                if (acquired) {
                    downloadSemaphore.release()
                }

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


    /**
     * CRITICAL FIX: Convert any bitmap to a safe ARGB_8888 format
     * This prevents OpenGL "no context" errors when processing hardware bitmaps
     */
    private fun convertToSafeBitmap(original: Bitmap, imageId: String?): Bitmap? {
        try {
            if (original.isRecycled) {
                Timber.w("kLog convertToSafeBitmap: bitmap already recycled for $imageId")
                return null
            }

            val config = original.config

            // Check if it's already in a safe format
            if (config == Bitmap.Config.ARGB_8888 && original.config != Bitmap.Config.HARDWARE) {
                return original
            }

            Timber.d("kLog Converting bitmap from $config to ARGB_8888 for $imageId")

            // CRITICAL: Create a software bitmap copy to avoid GL context issues
            val safeBitmap = try {
                // If it's a hardware bitmap, we MUST copy it to software
                if (config == Bitmap.Config.HARDWARE) {
                    Timber.d("kLog Converting hardware bitmap to software for $imageId")
                    val software = original.copy(Bitmap.Config.ARGB_8888, false)
                    software
                } else {
                    // For non-hardware bitmaps, just ensure ARGB_8888
                    original.copy(Bitmap.Config.ARGB_8888, false)
                }
            } catch (e: IllegalArgumentException) {
                Timber.e("kLog IllegalArgumentException copying bitmap for $imageId: ${e.message}")
                null
            } catch (e: OutOfMemoryError) {
                Timber.e("kLog OOM copying bitmap for $imageId")
                System.gc()
                // Try one more time with lower quality
                try {
                    original.copy(Bitmap.Config.RGB_565, false)?.let { rgb565 ->
                        rgb565.copy(Bitmap.Config.ARGB_8888, false).also {
                            rgb565.recycle()
                        }
                    }
                } catch (e2: Exception) {
                    Timber.e("kLog Retry copy also failed for $imageId")
                    null
                }
            } catch (e: Exception) {
                Timber.e("kLog Unexpected error copying bitmap for $imageId: ${e.message}", e)
                null
            }

            if (safeBitmap == null) {
                Timber.w("kLog Failed to create safe bitmap for $imageId")
                return null
            }

            // Verify the copy is valid
            if (safeBitmap.isRecycled) {
                Timber.w("kLog Safe bitmap was immediately recycled for $imageId")
                return null
            }

            return safeBitmap

        } catch (e: Exception) {
            Timber.e("kLog convertToSafeBitmap: unexpected error for $imageId", e)
            return null
        }
    }

    private fun processQueue() {
        if (!paused.get() && processingJob?.isActive != true) {
            processingJob = scope.launch {
                while (urlQueue.isNotEmpty()) {
                    try {
                        // Find the first task that's ready to process
                        val readyTask = urlQueue.find { task ->
                            val status = downloadTracker[task.id]
                            status is DownloadStatus.Success || status is DownloadStatus.Failed
                        }

                        if (readyTask == null) {
                            delay(50)
                            continue
                        }

                        urlQueue.remove(readyTask)

                        when (val downloadStatus = downloadTracker[readyTask.id]) {
                            null, is DownloadStatus.Pending -> {
                                continue
                            }
                            is DownloadStatus.Failed -> {
                                // Clean up and notify
                                cleanupBitmap(readyTask.id)
                                downloadTracker.remove(readyTask.id)
                                Timber.d("kLog imgLog: 2. imageId: ${readyTask.id}")
                                onImageClassified("detectionResult", OutputImage(
                                    result = "null",
                                    id = readyTask.id ?: "",
                                    width = readyTask.width ?: 0,
                                    height = readyTask.height ?: 0,
                                    from = "statusFailed"
                                ))
                                continue
                            }
                            is DownloadStatus.Success -> {
                                delay(10)
                                Timber.d("kLog imgLog: 3. imageId: ${readyTask.id}")

                                processImageTask(readyTask, downloadStatus.bitmap)
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e("kLog Error in processQueue loop: ${e.message}", e)
                        delay(100) // Back off on errors
                    }
                }
            }
        }
    }

    private suspend fun processImageTask(task: InputImage, bitmap: Bitmap) {
        try {
            // Verify bitmap is still valid
            if (bitmap.isRecycled) {
                Timber.w("kLog Bitmap already recycled for ${task.id}")
                cleanupTask(task.id, "null", "bitmapRecycled")
                return
            }

            val maskType = getMaskType()
            val cacheKey = "${task.src?.md5()}_$maskType"

            // Check cache
            val cachedResult = kahfImageBlockedDao.findByUrl(cacheKey)
            if (cachedResult != null) {
                Timber.d("kLog imgLog: 4. imageId: ${task.id} - cache hit")
                onImageClassified("detectionResult", OutputImage(
                    result = cachedResult.responseStr,
                    id = task.id ?: "",
                    width = task.width ?: 0,
                    height = task.height ?: 0,
                    from = "cache"
                ))
                cleanupTask(task.id)
                return
            }

            // Track waiting time
            val waitingTime = System.currentTimeMillis() - task.insertedAt
            waitingTimes.add(waitingTime)
            if (waitingTimes.size >= 30) {
                val avg = waitingTimes.average().toLong()
                analytics.logEvent(
                    AnalyticsEvent.AvgQueueTime,
                    mapOf(AnalyticsParam.AvgQueueTimeMs to avg.toString())
                )
                waitingTimes.clear()
            }

            Timber.d("kLog Using bitmap: ${bitmap.width}x${bitmap.height}, config: ${bitmap.config}")

            // NSFW Detection
            val nsfwResult: NsfwPrediction?
            val nsfwInference = measureTimeMillis {
                nsfwResult = try {
                    nsfwDetector.isNsfw(bitmap)
                } catch (e: Exception) {
                    Timber.e("kLog NSFW detection error for ${task.id}: ${e.message}", e)
                    null
                }
            }

            nsfwProcessingTimes.add(nsfwInference)
            Timber.d("kLog NSFW - ${nsfwResult?.isSafe()?.not()}. Inference time: $nsfwInference ms")

            if (nsfwResult?.isSafe() == false) {
                onImageClassified("detectionResult", OutputImage(
                    result = "nsfw",
                    id = task.id ?: "",
                    width = task.width ?: 0,
                    height = task.height ?: 0,
                    isManipulated = true,
                    from = "nsfw"
                ))
                cleanupTask(task.id)
            } else {
                // Segmentation
                val segmentationInf = measureTimeMillis {
                    try {
                        imageDetector.downloadAndStore(task.copy(imgBitmap = bitmap)) { result ->
                            onImageClassified("detectionResult", result)

                            kahfImageBlockedDao.insert(
                                KahfImageBlocked(
                                    imageUrl = cacheKey,
                                    responseStr = result.result,
                                    isIndecent = result.isManipulated,
                                    imageWidth = result.width.toFloat(),
                                    imageHeight = result.height.toFloat(),
                                    modifiedAt = System.currentTimeMillis(),
                                    maskType = maskType,
                                )
                            )

                            coreInferenceTimes.add(result.coreInferenceTime ?: 0L)
                            if (grayBlur) {
                                maskingTimes.add(result.maskOrPixelationTime ?: 0L)
                            } else {
                                pixelationTimes.add(result.maskOrPixelationTime ?: 0L)
                            }
                            Timber.d("kLog imgLog: 5. imageId: ${result.id}")
                        }
                    } catch (e: Exception) {
                        Timber.e("kLog Segmentation error for ${task.id}: ${e.message}", e)
                    }
                }

                val inferenceTime = nsfwInference + segmentationInf
                inferenceTimes.add(inferenceTime)
                Timber.d("kLog Total inference: $inferenceTime ms (NSFW: $nsfwInference, Seg: $segmentationInf)")

                cleanupTask(task.id)
            }

            // Log analytics
            logAnalytics()

        } catch (e: OutOfMemoryError) {
            Timber.e("kLog OOM processing image ${task.id}")
            cleanupTask(task.id, "null", "oom")
            System.gc()
        } catch (e: Exception) {
            Timber.e("kLog Error processing image ${task.id}: ${e.message}", e)
            cleanupTask(task.id, "null", "error")
        }
    }

    private fun cleanupTask(taskId: String?, result: String? = null, from: String? = null) {
        downloadTracker.remove(taskId)
        cleanupBitmap(taskId)

        if (result != null && taskId != null) {
            // Notify failure
            urlQueue.find { it.id == taskId }?.let { task ->
                onImageClassified("detectionResult", OutputImage(
                    result = result,
                    id = taskId,
                    width = task.width ?: 0,
                    height = task.height ?: 0,
                    from = from ?: "cleanup"
                ))
            }
        }
    }

    private fun cleanupBitmap(imageId: String?) {
        activeBitmaps.remove(imageId)?.let { bitmap ->
            if (!bitmap.isRecycled) {
                try {
                    bitmap.recycle()
                    Timber.d("kLog Recycled bitmap for $imageId")
                } catch (e: Exception) {
                    Timber.e("kLog Error recycling bitmap for $imageId: ${e.message}")
                }
            }
        }
    }

    private fun logAnalytics() {
        if (nsfwProcessingTimes.size >= 30) {
            val p90NSFW = nsfwProcessingTimes.sorted()[nsfwProcessingTimes.size * 90 / 100]
            analytics.logEvent(
                AnalyticsEvent.P90NSFWProcessing,
                mapOf(AnalyticsParam.NSFWProcessingTime to p90NSFW.toString())
            )
            nsfwProcessingTimes.clear()
        }

        if (coreInferenceTimes.size >= 30) {
            val p90CoreInference = coreInferenceTimes.sorted()[coreInferenceTimes.size * 90 / 100]
            analytics.logEvent(
                AnalyticsEvent.P90CoreInference,
                mapOf(AnalyticsParam.CoreInferenceTime to p90CoreInference.toString())
            )
            coreInferenceTimes.clear()
        }

        if (maskingTimes.size >= 30) {
            val p90Masking = maskingTimes.sorted()[maskingTimes.size * 90 / 100]
            analytics.logEvent(
                AnalyticsEvent.P90ApplySolidMask,
                mapOf(AnalyticsParam.ApplySolidMaskTime to p90Masking.toString())
            )
            maskingTimes.clear()
        }

        if (pixelationTimes.size >= 30) {
            val p90Pixelation = pixelationTimes.sorted()[pixelationTimes.size * 90 / 100]
            analytics.logEvent(
                AnalyticsEvent.P90ApplyPixelationMask,
                mapOf(AnalyticsParam.ApplyPixelationMaskTime to p90Pixelation.toString())
            )
            pixelationTimes.clear()
        }

        if (inferenceTimes.size >= 30) {
            val p90 = inferenceTimes.sorted()[inferenceTimes.size * 90 / 100]
            analytics.logEvent(
                AnalyticsEvent.P90ImageProcessing,
                mapOf(AnalyticsParam.ImageProcessingTime to p90.toString())
            )
            inferenceTimes.clear()
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

        // CRITICAL FIX: Clean up all active bitmaps
        activeBitmaps.keys.toList().forEach { key ->
            cleanupBitmap(key)
        }

        Timber.d("kLog Reset processing queue and cleaned up bitmaps")
    }

    fun cancelOngoingImageProcessing() {
        processingJob?.cancel()

        // CRITICAL FIX: Clean up all bitmaps before canceling scope
        activeBitmaps.keys.toList().forEach { key ->
            cleanupBitmap(key)
        }

        scope.cancel()
        Timber.d("kLog Canceled image processing and cleaned up resources")
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
            Timber.e("kLog JSON parse error: ${e.message}")
            null
        }
    }

    private fun isInvalidImageUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) {
            return true
        }
        return listOfEndsWith.any { url.endsWith(it, ignoreCase = true) } ||
            listOfContains.any { url.contains(it) }
    }

    /**
     * CRITICAL FIX: Validate download inputs to prevent native crashes (SIGSEGV)
     * Ensures all parameters are valid before passing to Glide's native layer
     */
    private fun isValidDownloadInput(input: InputImage): Boolean {
        // Validate dimensions
        val width = input.width ?: 0
        val height = input.height ?: 0
        if (width < 1 || height < 1 || width > 10000 || height > 10000) {
            Timber.w("kLog Invalid dimensions: ${width}x${height} for ${input.id}")
            return false
        }

        // Validate source URL or base64
        val hasValidUrl = !input.src.isNullOrBlank() && input.src!!.length < 10000
        val hasValidBase64 = !input.baseImg.isNullOrBlank() &&
                             input.baseImg != "none" &&
                             input.baseImg!!.startsWith("data:image/")

        if (!hasValidUrl && !hasValidBase64) {
            Timber.w("kLog No valid image source for ${input.id}")
            return false
        }

        // Validate URL format if using URL (not base64)
        if (hasValidUrl && !hasValidBase64) {
            val url = input.src ?: return false

            // Check for common malformed URL patterns that cause "Error resolving host"
            if (!url.matches(Regex("^(https?://|data:image/).*", RegexOption.IGNORE_CASE))) {
                Timber.w("kLog Invalid URL scheme for ${input.id}: $url")
                return false
            }

            // Check for invalid characters that could cause native crashes
            if (url.contains('\u0000') || url.contains('\n') || url.contains('\r')) {
                Timber.w("kLog URL contains invalid characters for ${input.id}")
                return false
            }
        }

        return true
    }

    fun closePordaSegment() {
        // Clean up all bitmaps before closing
        activeBitmaps.keys.toList().forEach { key ->
            cleanupBitmap(key)
        }
        imageDetector.closePordaSegment()
    }

    private val listOfContains = listOf(
        "image/gif", "image/svg", "/assets/thesun/images/teaser", "grey-placeholder"
    )
    private val listOfEndsWith = listOf(".svg", ".gif")
}
