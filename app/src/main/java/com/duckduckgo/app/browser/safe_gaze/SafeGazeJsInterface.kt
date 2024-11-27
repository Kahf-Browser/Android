package com.duckduckgo.app.browser.safe_gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.graphics.toRect
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.duckduckgo.app.safegaze.genderdetection.FaceDetector
import com.duckduckgo.app.safegaze.genderdetection.GenderDetector
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
import com.duckduckgo.app.safegaze.poseDetection.MoveNetMultiPose
import com.duckduckgo.app.safegaze.poseDetection.Person
import com.duckduckgo.app.safegaze.poseDetection.VisualizationUtils
import com.duckduckgo.app.trackerdetection.db.KahfImageBlocked
import com.duckduckgo.app.trackerdetection.db.KahfImageBlockedDao
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.SAFE_GAZE_MIN_IMG_SIZE
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal data class UrlInfo(
    val url: String,
    val uid: String
)

class SafeGazeJsInterface(
    private val context: Context,
    private val nsfwDetector: NsfwDetector,
    private val genderDetector: GenderDetector,
    private val movenet: MoveNetMultiPose,
    private val kahfImageBlockedDao: KahfImageBlockedDao,
    private val dispatcher: DispatcherProvider,
    private val onUpdateBlur: (blur: Float) -> Unit,
    private val onImageClassified: (uid: String, detectionResultJson: String, base64Image: String?) -> Unit
) {
    private val onDeviceModelCachedResults = mutableMapOf<String, String>()

    private val urlQueue: ConcurrentLinkedQueue<UrlInfo> = ConcurrentLinkedQueue()
    private var processingJob: Job? = null
    private val scope = CoroutineScope(dispatcher.io() + Job())
    private var paused: AtomicBoolean = AtomicBoolean(false)
    private val gson = Gson()
    private val faceDetector = FaceDetector(context)

    init {
        scope.launch {
            movenet.warmup()
        }
    }

    private suspend fun shouldBlurImage(
        url: String,
        mScope: CoroutineScope,
    ): SafeGazeResult {
        return suspendCoroutine { continuation ->
            mScope.launch {
                val bitmap = getBitmapFromUrl(url)
                if (bitmap == null || bitmap.height < SAFE_GAZE_MIN_IMG_SIZE || bitmap.width < SAFE_GAZE_MIN_IMG_SIZE) {
                    continuation.resume(SafeGazeResult(false, emptyList(), bitmap?.width ?: 0, bitmap?.height ?: 0, VisualizationUtils.bitmapToBase64(bitmap)))
                    return@launch
                }

                val nsfwPrediction = nsfwDetector.isNsfw(bitmap)

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
                                val genderPrediction = genderDetector.predictGender(faceBitmap)

                                person.isFemale = !genderPrediction.isMale
                                person.genderScore = genderPrediction.genderScore

                                Timber.d("kLog genderPrediction 1: $genderPrediction ${person.id}")
                            }
                        }
                    }
                    continuation.resume(SafeGazeResult(false, personList, bitmap.width, bitmap.height, VisualizationUtils.bitmapToBase64(bitmap)))
                }
            }
        }
    }

    private suspend fun runFaceAndPoseDetectionInParallel(bitmap: Bitmap): Pair<List<Rect>, List<Person>> = coroutineScope {
        val faceDetectionDeferred = async { faceDetector.detectFaces(bitmap) }
        val poseDetectionDeferred = async { movenet.estimatePoses(bitmap) }

        val faceList = faceDetectionDeferred.await()
        val poseList = poseDetectionDeferred.await()

        faceList to poseList
    }

    private suspend fun getBitmapFromUrl(url: String): Bitmap? {
        return if (url.startsWith("data:image")) {
            val base64Image = url.substringAfter(",")  // Remove the 'data:image/jpeg;base64,' prefix
            val byteArrayImage = Base64.decode(base64Image, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(byteArrayImage, 0, byteArrayImage.size)
        } else {
            downloadBitmap(url, context)
        }
    }

    private suspend fun downloadBitmap(
        url: String,
        context: Context
    ): Bitmap? {
        return suspendCoroutine { continuation ->
            try {
                Glide.with(context)
                    .asBitmap()
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .into(
                        object : CustomTarget<Bitmap>() {
                            override fun onResourceReady(
                                resource: Bitmap,
                                transition: Transition<in Bitmap>?
                            ) {
                                continuation.resume(resource)
                            }

                            override fun onLoadFailed(errorDrawable: Drawable?) {
                                continuation.resume(null)
                            }

                            override fun onLoadCleared(placeholder: Drawable?) {
                                // No op
                            }
                        },
                    )
            } catch (e: Exception) {
                e.printStackTrace()
                continuation.resume(null)
            }
        }
    }

    @JavascriptInterface
    fun callSafegazeOnDeviceModelHandler(
        uid: String,
        detectionResultJson: String,
        base64Image: String?
    ) {
        onImageClassified(uid, detectionResultJson, base64Image)
    }

    @JavascriptInterface
    fun updateBlur(blur: Float) {
        onUpdateBlur(blur)
    }

    @JavascriptInterface
    fun sendMessage(message: String) {
        if (message.startsWith("coreML/-/")) {
            val parts = message.split("/-/")
            val imageUrl = if (parts.size >= 2) parts[1] else ""
            val uid = (if (parts.size >= 2) parts[2] else "0")

            if (onDeviceModelCachedResults.containsKey(imageUrl)) {
                returnResultFromCache(uid, imageUrl)
            } else {
                addTaskToQueue(uid, imageUrl)
            }
        }
    }

    private fun returnResultFromCache(uid: String, imageUrl: String) {
        CoroutineScope(dispatcher.io()).launch {
            val bitmap = getBitmapFromUrl(imageUrl)
            val base64Image = VisualizationUtils.bitmapToBase64(bitmap) ?: "null"

            callSafegazeOnDeviceModelHandler(
                uid,
                detectionResultJson = onDeviceModelCachedResults[imageUrl] ?: "null",
                base64Image = base64Image
            )
        }
    }

    private fun addTaskToQueue(
        uid: String,
        url: String
    ) {
        // If same url is already in queue, don't add it again
        if (urlQueue.any { it.url == url }) {
            return
        }
        // Skip if image is svg, gif, placeholder
        if (isInvalidImageUrl(url)) {
            callSafegazeOnDeviceModelHandler(uid, nsfwJson(false), null)
            return
        }

        urlQueue.add(UrlInfo(url, uid))
        processQueue()
    }

    private fun processQueue() {
        if (!paused.get() && processingJob?.isActive != true) {
            processingJob = scope.launch {
                while (urlQueue.isNotEmpty()) {
                    val task = urlQueue.poll()

                    task?.let {
                        // Again check on cache
                        if (onDeviceModelCachedResults.containsKey(it.url)) {
                            returnResultFromCache(it.url, it.uid)
                            return@launch
                        }

                        val t1 = System.currentTimeMillis()
                        val result = shouldBlurImage(it.url, this)
                        val inferenceTime = System.currentTimeMillis() - t1

                        val resultJson: String

                        if (result.base64Image.isNullOrEmpty()) {
                            // Image download failed or image is too small or image is svg/gif
                            resultJson = "null"
                            callSafegazeOnDeviceModelHandler(it.uid, resultJson, "null")
                        } else if (result.persons.isNotEmpty()) {
                            resultJson = VisualizationUtils.toJson(gson, result)
                            callSafegazeOnDeviceModelHandler(it.uid, resultJson, result.base64Image)

                            Timber.d("kLog Inference time: $inferenceTime ms -- ${it.url}")
                        } else {
                            resultJson = nsfwJson(result.isNsfw)
                            callSafegazeOnDeviceModelHandler(it.uid, resultJson, result.base64Image)

                            Timber.d("kLog Inference time: $inferenceTime ms  --  ${it.url}")
                        }

                        // Insert to local DB
                        onDeviceModelCachedResults[it.url] = resultJson

                        kahfImageBlockedDao.insert(
                            KahfImageBlocked(
                                imageUrl = it.url,
                                responseStr = resultJson,
                                isIndecent = result.isNsfw || result.persons.any { p -> p.isFemale },
                                imageWidth = result.imageWidth.toFloat(),
                                imageHeight = result.imageHeight.toFloat(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun nsfwJson(isNsfw: Boolean) = "{\"isNSFW\":$isNsfw}"

    private suspend fun debugDraw(url: String, personList: List<Person>) {
        val bitmap = getBitmapFromUrl(url)
        val outputBitmap = VisualizationUtils.debugDraw(bitmap, personList, drawFace = false, drawPose = false, drawBodyMask = true)

        Timber.d("$outputBitmap")
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

