package com.duckduckgo.app.safegaze.genderdetection

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType.FLOAT32
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod.BILINEAR
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GenderDetector (val context: Context) {
    private var interpreter: Interpreter? = null
    private val inputImageSize = 224
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputImageSize, inputImageSize, BILINEAR))
        .add(NormalizeOp(0f, 255f))
        .build()

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

    suspend fun predictGender(faceBitmap: Bitmap): GenderPrediction {
        // initializing mode in IO thread
        if (interpreter == null) {
            initializeModel()
        }

        return suspendCoroutine { continuation ->
            val prediction = GenderPrediction()

            val genderPredictions = getGenderPrediction(faceBitmap)
            prediction.isMale = genderPredictions > 0.5
            prediction.genderScore = if (prediction.isMale) genderPredictions else 1f - genderPredictions
            continuation.resume(prediction)
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

    fun dispose() {
        interpreter?.close()
    }
}
