package com.duckduckgo.app.safegaze.genderdetection

import android.content.Context
import android.graphics.Bitmap
import com.duckduckgo.app.safegaze.poseDetection.VisualizationUtils
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.DataType.FLOAT32
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod.BILINEAR
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GenderDetector (val context: Context) {
    private val inputImageSize = 224
    private var interpreter: Interpreter? = null

    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputImageSize, inputImageSize, BILINEAR))
        .add(NormalizeOp(0f, 255f))
        .build()

    private val faceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setMinFaceSize(80f)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
        .build()

    private val faceDetector = FaceDetection.getClient(faceDetectorOptions)

    private fun initializeModel() {
        try {
            val model = context.assets.open("mobilenet_v2_gender.tflite").use { it.readBytes() }
            val modelBuffer = ByteBuffer.allocateDirect(model.size)
            modelBuffer.order(ByteOrder.nativeOrder())
            modelBuffer.put(model)

            val options = Interpreter.Options().apply{
                this.setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)

            // warmUpModel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun predict(bitmap: Bitmap): GenderPrediction {

        // initializing mode in IO thread
        if (interpreter == null) {
            initializeModel()
        }

        return suspendCoroutine { continuation ->
            val prediction = GenderPrediction()
            val image = InputImage.fromBitmap(bitmap, 0)

            faceDetector.process(image).addOnSuccessListener { faces ->
                prediction.faceCount = faces.size

                for (i in 0 until faces.size) {
                    val face = faces[i]
                    val subjectFace = VisualizationUtils.cropToBBox(bitmap, face.boundingBox) ?: continue

                    val genderPredictions = getGenderPrediction(subjectFace)
                    prediction.maleConfidence = genderPredictions
                    prediction.femaleConfidence = 1 - genderPredictions

                    val isMale = genderPredictions > 0.5
                    prediction.hasMale = prediction.hasMale || isMale
                    prediction.boundingBox.add(face.boundingBox)

                    if (!isMale) {
                        prediction.hasFemale = true
                        break
                    }
                }

                continuation.resume(prediction)
            }.addOnFailureListener {
                Timber.e("kLog Exception while detecting faces: $it")
                continuation.resume(prediction)
            }
        }
    }

    private fun getGenderPrediction(bitmap: Bitmap): Float {
        val inputFeature = TensorBuffer.createFixedSize(intArrayOf(1, inputImageSize, inputImageSize, 3), FLOAT32)
        val byteBuffer = TensorImage(FLOAT32).let {
            it.load(bitmap)
            imageProcessor.process(it)
        }.tensorBuffer.buffer
        inputFeature.loadBuffer(byteBuffer)

        return try {
            val outputBuffer = Array(1) { FloatArray(1) }
            interpreter?.run(byteBuffer, outputBuffer)
            outputBuffer[0][0]
        } catch (e: Exception) {
           0f
        }
    }

    private fun warmUpModel() {
        val dummyInput = ByteBuffer.allocateDirect(inputImageSize * inputImageSize * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
            for (i in 0 until inputImageSize * inputImageSize * 3) {
                putFloat(0.0f)  // Using zeros as dummy data
            }
            rewind()
        }

        val outputBuffer = Array(1) { FloatArray(1) }
        interpreter?.run(dummyInput, outputBuffer)

        Timber.d("kLog Gender model wormed up")
    }

    fun dispose() {
        interpreter?.close()
        faceDetector.close()
    }
}
