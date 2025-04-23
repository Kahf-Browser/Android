package com.duckduckgo.app.browser.safe_gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Base64
import android.webkit.JavascriptInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.system.measureTimeMillis

internal data class UrlInfo(
    val url: String,
    val uid: String,
    val imageData: String? = null,
    val insertedAt: Long = System.currentTimeMillis(),
)

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

    private val urlQueue: ConcurrentLinkedQueue<UrlInfo> = ConcurrentLinkedQueue()
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

    private suspend fun shouldBlurImage(
        url: String,
        imageData: String?
    ): SafeGazeResult {
        return SafeGazeResult(
            false,
            emptyList(),
            0,
            0,
            null
        )
        /*return suspendCoroutine { continuation ->
            scope.launch {
                val bitmap = if (imageData != null) {
                    base64ToBitmap(imageData)
                } else {
                    getBitmapFromUrl(url)
                }

                if (bitmap == null || bitmap.height < SAFE_GAZE_MIN_IMG_SIZE || bitmap.width < SAFE_GAZE_MIN_IMG_SIZE) {
                    continuation.resume(SafeGazeResult(false, emptyList(), bitmap?.width ?: 0, bitmap?.height ?: 0, VisualizationUtils.bitmapToBase64(bitmap)))
                    // recycleBitmap(bitmap)
                    return@launch
                }

                val nsfwPrediction = try {
                    nsfwDetector.isNsfw(bitmap)
                } catch (e: Exception) {
                    Timber.e(e, "kLog Error while classifying image: $e")
                    continuation.resume(SafeGazeResult(false, emptyList(), bitmap.width, bitmap.height, VisualizationUtils.bitmapToBase64(bitmap)))
                    return@launch
                }

                if (!nsfwPrediction.isSafe()) {
                    nsfwPrediction.getLabelWithConfidence().let {
                        Timber.d("kLog Nsfw: ${it.first} (${it.second}) $url")
                        continuation.resume(SafeGazeResult(true, emptyList(), bitmap.width, bitmap.height, VisualizationUtils.bitmapToBase64(bitmap)))
                        return@launch
                    }
                }

                val (faceRectList, poseList) = runFaceAndPoseDetectionInParallel(bitmap)

                poseList.let {
                    val personList = VisualizationUtils.matchFacesToPoses(it, faceRectList)

                    personList.forEach { person ->
                        person.faceBox?.let { faceBox ->
                            val faceBitmap = VisualizationUtils.cropToBBox(bitmap, faceBox.toRect())

                            if (faceBitmap != null) {

                                person.isFemale = !genderPrediction.isMale
                                person.genderScore = genderPrediction.genderScore

                                Timber.d("kLog genderPrediction 1: $genderPrediction ${person.id}")
                            }
                        }
                    }
                    val imageDataFinal = imageData ?: VisualizationUtils.bitmapToBase64(bitmap)
                    continuation.resume(SafeGazeResult(false, personList, bitmap.width, bitmap.height, imageDataFinal))
                    // recycleBitmap(bitmap)
                }
            }
        }*/
    }

    private suspend fun runFaceAndPoseDetectionInParallel(bitmap: Bitmap): Pair<List<Rect>, List<Rect>> = coroutineScope {
        /*val faceDetectionDeferred = async { faceDetector.detectFaces(bitmap) }
        val poseDetectionDeferred = async { movenet.estimatePoses(bitmap) }

        val faceList = faceDetectionDeferred.await()
        val poseList = poseDetectionDeferred.await()

        faceList to poseList*/
        Pair(emptyList(), emptyList())
    }

    private suspend fun getBitmapFromUrl(url: String): Bitmap? {
        return if (url.startsWith("data:image")) {
            val base64Image = url.substringAfter(",")  // Remove the 'data:image/jpeg;base64,' prefix
            base64ToBitmap(base64Image)
        } else {
            try {
                downloadBitmap(url, context)
            } catch (e: Exception) {
                Timber.e(e, "kLog Error downloading image: $url")
                null
            }
        }
    }

    // method to convert base64 to bitmap (handle possible exception when decoding base64)
    private fun base64ToBitmap(base64Image: String): Bitmap? {
        return try {
            val result: Bitmap?
            val time = measureTimeMillis {
                val byteArrayImage = Base64.decode(base64Image, Base64.DEFAULT)

                // Check the original image dimensions
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(byteArrayImage, 0, byteArrayImage.size, options)

                val width = options.outWidth
                val height = options.outHeight

                // Downsample the image if it exceeds the maximum size
                var inSampleSize = 1

                if (width > SAFE_GAZE_MAX_IMG_SIZE || height > SAFE_GAZE_MAX_IMG_SIZE) {
                    inSampleSize = maxOf(
                        ceil(width / SAFE_GAZE_MAX_IMG_SIZE.toDouble()).toInt(),
                        ceil(height / SAFE_GAZE_MAX_IMG_SIZE.toDouble()).toInt()
                    )
                }

                options.apply {
                    inJustDecodeBounds = false
                    this.inSampleSize = inSampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                result = BitmapFactory.decodeByteArray(byteArrayImage, 0, byteArrayImage.size, options)
            }

            Timber.d("kLog base64ToBitmap: $time ms")
            result
        } catch (e: Exception) {
            Timber.e(e, "kLog base64ToBitmap: ${e.message}")
            null
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
                .apply(RequestOptions()
                    .downsample(DownsampleStrategy.AT_MOST)
                    .override(SAFE_GAZE_MAX_IMG_SIZE, SAFE_GAZE_MAX_IMG_SIZE)
                    .format(DecodeFormat.PREFER_ARGB_8888)
                )
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .into(object : CustomTarget<Bitmap>() {
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
                })
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
    fun sendMessage(message: String) {
        if (message.startsWith("coreML/-/")) {
            val parts = message.split("/-/")
            val imageUrl = if (parts.size >= 2) parts[1] else ""
            val uid = (if (parts.size >= 3) parts[2] else "0")

            // Skip if image is svg, gif, placeholder
            if (isInvalidImageUrl(imageUrl)) {
                callSafegazeOnDeviceModelHandler(uid, nsfwJson(false), null, false)
                return
            }

            val imageData = if (parts.size >= 4) parts[3] else ""

            if (onDeviceModelCachedResults.containsKey(imageUrl)) {
                returnResultFromCache(uid, imageUrl)
            } else {
                if (imageData.isEmpty()) {
                    addTaskToQueue(uid, imageUrl)
                } else {
                    addTaskToQueue(uid, imageUrl, imageData)
                }
            }
        }
    }

    @JavascriptInterface
    fun sendMessageFromWebView(messageType: String, data: String) {
        when (messageType) {
            "detectImg" -> runImageDetection(parseImageInfo(data))
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

    private fun runImageDetection(imgInfo: InputImage?) {
        imgInfo?.let {
            scope.launch {
                Timber.i("kLog image received for detection ${it.id}")
                imageDetector.downloadAndStore(it)

                /*getBitmapFromUrl(it.src ?: "")?.let { bmp->
                    Timber.d("kLog image downloaded")
                    val nsfwResponse = nsfwDetector.isNsfw(bmp)
                    if (nsfwResponse.isSafe()) {
                        Timber.d("kLog safe. now check for porda")
                        imageDetector.downloadAndStore(it)
                    } else {
                        Timber.d("kLog Nsfw: ${nsfwResponse.getLabelWithConfidence()} ${it.src}")
                    }
                } ?: run {
                    Timber.e("kLog Error downloading image: ${it.src}")
                }*/
            }
        }
    }

    private fun returnResultFromCache(uid: String, imageUrl: String) {
        /*CoroutineScope(dispatcher.io()).launch {
            val bitmap = getBitmapFromUrl(imageUrl)
            val base64Image = VisualizationUtils.bitmapToBase64(bitmap) ?: "null"

            callSafegazeOnDeviceModelHandler(
                uid,
                detectionResultJson = onDeviceModelCachedResults[imageUrl] ?: "null",
                base64Image = base64Image,
                false,
            )
        }*/
    }

    private fun addTaskToQueue(
        uid: String,
        url: String,
        imageData: String? = null
    ) {
        // If same url is already in queue, don't add it again
        if (urlQueue.any { it.url == url }) {
            return
        }

        urlQueue.add(UrlInfo(url, uid, imageData))
        processQueue()
    }

    private fun processQueue() {
        /*if (!paused.get() && processingJob?.isActive != true) {
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

                        // Again check on cache
                        if (onDeviceModelCachedResults.containsKey(it.url)) {
                            returnResultFromCache(it.url, it.uid)
                            Timber.d("kLog cache hit for ${it.url}")
                            return@let
                        }
                        var inferenceTime = 0L

                        val result = try {
                            val t1 = System.currentTimeMillis()
                            val shouldBlurResult = withTimeout(MAX_INFERENCE_TIME_MS) {
                                shouldBlurImage(it.url, it.imageData)
                            }
                            inferenceTime = System.currentTimeMillis() - t1
                            inferenceTimes.add(inferenceTime)

                            shouldBlurResult
                        } catch (e: TimeoutCancellationException) {
                            Timber.e("kLog Timeout occurred while processing image: ${it.url}")
                            analytics.logEvent(AnalyticsEvent.ImageProcessingTimeout, mapOf(AnalyticsParam.TimedOutImageUrl to it.url))
                            null
                        }

                        // If result is null, then timeout occurred. No need to process further or cache the result
                        if (result == null) {
                            callSafegazeOnDeviceModelHandler(it.uid, "null", "null", false)
                            return@let
                        }

                        // Log P90 inference time for every 30 images to GA
                        if (inferenceTimes.size >= 30) {
                            val p90 = inferenceTimes.sorted()[inferenceTimes.size * 90 / 100]
                            analytics.logEvent(
                                AnalyticsEvent.P90ImageProcessing,
                                mapOf(AnalyticsParam.ImageProcessingTime to p90.toString()),
                            )
                            inferenceTimes.clear()
                        }

                        val resultJson: String

                        if (result.base64Image.isNullOrEmpty()) {
                            // Image download failed or image is too small or image is svg/gif
                            resultJson = "null"
                            callSafegazeOnDeviceModelHandler(it.uid, resultJson, "null", false)

                            Timber.d("kLog invalid or failed image: -- ${if (it.url.isDataUri()) "data:url" else it.url}")
                        } else if (result.persons.isNotEmpty()) {
                            resultJson = VisualizationUtils.toJson(gson, result)
                            callSafegazeOnDeviceModelHandler(it.uid, resultJson, result.base64Image, result.persons.any { p -> p.isFemale })

                            Timber.d("kLog Inference time: $inferenceTime ms -- ${if (it.url.isDataUri()) "data:url" else it.url}")
                        } else {
                            resultJson = nsfwJson(result.isNsfw)
                            callSafegazeOnDeviceModelHandler(it.uid, resultJson, result.base64Image, result.isNsfw)

                            Timber.d("kLog Inference time: $inferenceTime ms  --  ${if (it.url.isDataUri()) "data:url" else it.url}")
                        }

                        // Insert to local DB
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
                        }
                    }
                }
            }
        }*/
    }

    private fun nsfwJson(isNsfw: Boolean) = "{\"isNSFW\":$isNsfw}"

    /*private suspend fun debugDraw(url: String, personList: List<Person>) {
        val bitmap = getBitmapFromUrl(url)
        val outputBitmap = VisualizationUtils.debugDraw(bitmap, personList, drawFace = false, drawPose = false, drawBodyMask = true)

        Timber.d("$outputBitmap")
    }*/

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

            BitmapFactory.decodeByteArray(buffer, 0, buffer.size, BitmapFactory.Options().also { op ->
                op.inSampleSize = 3
            })
        }

        val t1 = System.currentTimeMillis()

        val nsfwPrediction = nsfwDetector.isNsfw(bitmap)
        Timber.d("kLog nsfw classified. IsSafe: ${nsfwPrediction.isSafe()}")

        val (faceRectList, poseList) = runFaceAndPoseDetectionInParallel(bitmap)
        Timber.d("kLog ${faceRectList.size} faces and ${poseList.size} poses detected")

        val inferenceTime = System.currentTimeMillis() - t1

        return if (inferenceTime > 1000) {
            Timber.e("kLog Will make it slower: $inferenceTime ms")
            false
        } else {
            Timber.d("kLog Will run fine: $inferenceTime ms")
            true
        }
    }

    private fun recycleBitmap(bitmap: Bitmap?) {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
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

