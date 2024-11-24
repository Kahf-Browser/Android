package com.duckduckgo.app.browser.safe_gaze

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.graphics.toRect
import androidx.core.graphics.toRectF
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
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
    private val onImageClassified: (uid: String, detectionResultJson: String) -> Unit
) {
    private val onDeviceModelCachedResults = mutableMapOf<String, Boolean>()

    private val urlQueue: ConcurrentLinkedQueue<UrlInfo> = ConcurrentLinkedQueue()
    private var processingJob: Job? = null
    private val scope = CoroutineScope(dispatcher.io() + Job())
    private var paused: AtomicBoolean = AtomicBoolean(false)
    private val gson = Gson()

    private suspend fun shouldBlurImage(
        url: String,
        mScope: CoroutineScope,
    ): Pair<Boolean, List<Person>?> {
        return suspendCoroutine { continuation ->
            mScope.launch {
                val bitmap = getBitmapFromUrl(url)
                val hashedUrl = hash(url)

                if (bitmap!= null && bitmap.height >= SAFE_GAZE_MIN_IMG_SIZE && bitmap.width >= SAFE_GAZE_MIN_IMG_SIZE) {
                    val nsfwPrediction = nsfwDetector.isNsfw(bitmap)

                    if (nsfwPrediction.isSafe()) {
                        movenet.estimatePoses(bitmap).let { personList ->
                            personList.forEach { person ->
                                person.poseBox?.let { boundingBox ->
                                    val singlePersonBitmap = VisualizationUtils.cropToBBox(bitmap, boundingBox.toRect())

                                    if (singlePersonBitmap != null) {
                                        val genderPrediction = genderDetector.predict(singlePersonBitmap)

                                        person.isFemale = genderPrediction.hasFemale
                                        person.genderScore = genderPrediction.genderScore
                                        person.faceBox = genderPrediction.boundingBox.firstOrNull()?.toRectF()

                                        Timber.d("kLog genderPrediction 1: $genderPrediction ${person.id}")
                                    }
                                }
                            }
                            continuation.resume(Pair(false, personList))
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

                            continuation.resume(Pair(true, null))
                        }
                    }
                    // }
                } else {
                    continuation.resume(Pair(false, null))
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
                    .apply(RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.NONE))
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
        detectionResultJson: String
    ) {
        onImageClassified(uid, detectionResultJson)
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
                        // callSafegazeOnDeviceModelHandler(it.uid, "[{\"keypoints\":[{\"score\":0.9997987747192383,\"name\":\"nose\",\"y\":330.93210126824886,\"x\":747.2617076435906},{\"y\":234.36786097589638,\"x\":833.0182262264801,\"name\":\"left_eye\",\"score\":0.9986704587936401},{\"y\":223.5193201948233,\"x\":681.5944915244552,\"name\":\"right_eye\",\"score\":0.9987133741378784},{\"y\":268.15913682874543,\"x\":926.8803307629746,\"name\":\"left_ear\",\"score\":0.610142171382904},{\"y\":261.1519626249136,\"x\":576.7359131356623,\"name\":\"right_ear\",\"score\":0.7610119581222534},{\"y\":664.3736544675866,\"x\":906.9267021542857,\"name\":\"left_shoulder\",\"score\":0.9164822697639465},{\"y\":762.9549825386316,\"x\":489.0956632673509,\"name\":\"right_shoulder\",\"score\":0.8185280561447144},{\"y\":1143.6312083530058,\"x\":973.6100069669435,\"name\":\"left_elbow\",\"score\":0.013435578905045986},{\"y\":1092.5218218940709,\"x\":442.38979004514823,\"name\":\"right_elbow\",\"score\":0.010079923085868359},{\"y\":1104.7147512695672,\"x\":933.3002393036966,\"name\":\"left_wrist\",\"score\":0.004424526821821928},{\"y\":1072.8138022255807,\"x\":453.2235134380801,\"name\":\"right_wrist\",\"score\":0.002776280976831913},{\"y\":1069.3186738722986,\"x\":730.7598022245712,\"name\":\"left_hip\",\"score\":0.002407087478786707},{\"y\":1062.5008605259404,\"x\":602.6102417526542,\"name\":\"right_hip\",\"score\":0.003100668080151081},{\"y\":1023.7212464484726,\"x\":817.4952083988413,\"name\":\"left_knee\",\"score\":0.0029775435104966164},{\"y\":1028.3141214151792,\"x\":539.4750167776175,\"name\":\"right_knee\",\"score\":0.004313590470701456},{\"y\":1072.1348315064547,\"x\":777.162782183881,\"name\":\"left_ankle\",\"score\":0.0019776015542447567},{\"y\":1031.7471672737183,\"x\":532.718031851698,\"name\":\"right_ankle\",\"score\":0.002423505298793316}],\"poseScore\":0.3618390217204304,\"faceBox\":{\"xMin\":553.6716552972794,\"xMax\":933.627791762352,\"yMin\":124.58239036798477,\"yMax\":504.5382652282715,\"width\":379.95613646507263,\"height\":379.9558748602867},\"isFemale\":true,\"genderScore\":0.9999994742598801}]")
                        val t1 = System.currentTimeMillis()
                        val shouldBlur = shouldBlurImage(it.url, this)
                        val inferenceTime = System.currentTimeMillis() - t1

                        onDeviceModelCachedResults[hash(it.url)] = shouldBlur.first
                        if (shouldBlur.second?.isNotEmpty() == true) {
                            callSafegazeOnDeviceModelHandler(it.uid, gson.toJson(shouldBlur.second))
                        } else {
                            callSafegazeOnDeviceModelHandler(it.uid, "{\"isNSFW\":${shouldBlur.first}}")
                        }
                        Timber.d("kLog Inference time: $inferenceTime ms")
                    }
                }
            }
        }
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
}

