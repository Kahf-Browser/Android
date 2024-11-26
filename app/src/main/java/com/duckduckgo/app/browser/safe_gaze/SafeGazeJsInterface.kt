package com.duckduckgo.app.browser.safe_gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.graphics.toRect
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
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
import com.google.common.hash.Hashing
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
    dispatcher: DispatcherProvider,
    private val onUpdateBlur: (blur: Float) -> Unit,
    private val onImageClassified: (uid: String, detectionResultJson: String, base64Image: String?) -> Unit
) {
    private val onDeviceModelCachedResults = mutableMapOf<String, Boolean>()

    private val urlQueue: ConcurrentLinkedQueue<UrlInfo> = ConcurrentLinkedQueue()
    private var processingJob: Job? = null
    private val scope = CoroutineScope(dispatcher.io() + Job())
    private var paused: AtomicBoolean = AtomicBoolean(false)
    private val gson = Gson()
    private val faceDetector = FaceDetector(context)

    private suspend fun shouldBlurImage(
        url: String,
        mScope: CoroutineScope,
    ): SafeGazeResult {
        return suspendCoroutine { continuation ->
            mScope.launch {
                if (isInvalidImageUrl(url)) {
                    continuation.resume(SafeGazeResult(isNsfw = false, persons = emptyList()))
                    return@launch
                }

                val bitmap = getBitmapFromUrl(url)
                val hashedUrl = hash(url)

                if (bitmap!= null && bitmap.height >= SAFE_GAZE_MIN_IMG_SIZE && bitmap.width >= SAFE_GAZE_MIN_IMG_SIZE) {
                    val nsfwPrediction = nsfwDetector.isNsfw(bitmap)

                    if (nsfwPrediction.isSafe()) {
                        val faceRectList = faceDetector.detectFaces(bitmap)

                        movenet.estimatePoses(bitmap).let {
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

                        // TODO fallback to NSFW detection,
                        // TODO Save to local DB
                        /*val genderPrediction = genderDetector.predict(bitmap)

                        if (genderPrediction.hasFemale) {
                            Timber.d("kLog Female (${genderPrediction.femaleConfidence}) $url")

                            // Insert to local DB
                            kahfImageBlockedDao.insert(
                                KahfImageBlocked(
                                    imageUrl = hashedUrl,
                                    tag = "female",
                                    score = genderPrediction.femaleConfidence.toDouble(),
                                ),
                            )
                        } else {
                            Timber.d("kLog SFW $url")
                        }*/


                    } else {
                        nsfwPrediction.getLabelWithConfidence().let {
                            Timber.d("kLog Nsfw: ${it.first} (${it.second}) $url")

                            // Insert to local DB
                            kahfImageBlockedDao.insert(
                                KahfImageBlocked(
                                    imageUrl = hashedUrl,
                                    tag = it.first,
                                    score = it.second.toDouble(),
                                ),
                            )

                            continuation.resume(SafeGazeResult(true, emptyList(), bitmap.width, bitmap.height, VisualizationUtils.bitmapToBase64(bitmap)))
                        }
                    }
                } else {
                    continuation.resume(SafeGazeResult(false, emptyList(), bitmap?.width ?: 0, bitmap?.height ?: 0, VisualizationUtils.bitmapToBase64(bitmap)))
                }
            }
        }
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
                    .apply(RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.DATA))
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
            val hashedUrl = hash(imageUrl)

            // TODO turn off caching temporarily
            /*if (onDeviceModelCachedResults.containsKey(hashedUrl)) {
                callSafegazeOnDeviceModelHandler(onDeviceModelCachedResults[hashedUrl]!!, uid, true)
            } else {
                addTaskToQueue(imageUrl, uid)
            }*/
            addTaskToQueue(imageUrl, uid)
        }
    }

    private fun addTaskToQueue(
        url: String,
        uid: String
    ) {
        urlQueue.add(UrlInfo(url, uid))
        processQueue()
    }

    private fun processQueue() {
        if (!paused.get() && processingJob?.isActive != true) {
            processingJob = scope.launch {
                while (urlQueue.isNotEmpty()) {
                    val task = urlQueue.poll()

                    task?.let {
                        val t1 = System.currentTimeMillis()
                        val result = shouldBlurImage(it.url, this)
                        val inferenceTime = System.currentTimeMillis() - t1

                        onDeviceModelCachedResults[hash(it.url)] = result.isNsfw
                        if (result.persons.isNotEmpty()) {
                            // debugDraw(it.url, result.persons)
                            callSafegazeOnDeviceModelHandler(it.uid, VisualizationUtils.toJson(gson, result), result.base64Image)
                            Timber.d("kLog Inference time: $inferenceTime ms -- ${it.url}")
                        } else if (result.base64Image.isNullOrEmpty()) {
                            // Image download failed or image is too small or image is svg/gif
                            callSafegazeOnDeviceModelHandler(it.uid, "null", "null")
                        } else {
                            callSafegazeOnDeviceModelHandler(it.uid, nsfwJson(result.isNsfw), result.base64Image)
                            Timber.d("kLog Inference time: $inferenceTime ms  --  ${it.url}")
                        }
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

    private fun hash(content: String) =
        Hashing.murmur3_128().hashString(content, Charsets.UTF_8).toString()

    private suspend fun loadCacheFromDisk() {
        kahfImageBlockedDao.getAllBlockedImageDetails().first().forEach { kahfImageBlocked->
            onDeviceModelCachedResults[kahfImageBlocked.imageUrl] = true
        }

        Timber.d("kLog loading cache to disk from memory(${onDeviceModelCachedResults.size})")

        if (onDeviceModelCachedResults.isEmpty()) {
            onDeviceModelCachedResults["--"] = false
        }
    }

    private fun isInvalidImageUrl(url: String): Boolean {
        return url.endsWith(".svg") || url.endsWith(".gif") || url.contains("image/gif")
            || url.contains("image/svg") || url.contains("/assets/thesun/images/teaser")
    }
}

