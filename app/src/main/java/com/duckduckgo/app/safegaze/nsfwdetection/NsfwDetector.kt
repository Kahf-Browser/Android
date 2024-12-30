package com.duckduckgo.app.safegaze.nsfwdetection

import android.content.Context
import android.graphics.Bitmap
import com.duckduckgo.app.browser.ml.Nsfw
import org.tensorflow.lite.DataType
import org.tensorflow.lite.DataType.FLOAT32
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod.BILINEAR
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class NsfwDetector(val context: Context) {
    private val inputImageSize = 224
    var modelInitializationTime = 0L
        private set

    lateinit var model: Nsfw
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputImageSize, inputImageSize, BILINEAR))
        .add(NormalizeOp(0f, 255f))
        .build()

    fun isNsfw(bitmap: Bitmap): NsfwPrediction {
        // initializing mode in IO thread
        if (!::model.isInitialized) {
            val t1 = System.currentTimeMillis()
            model = Nsfw.newInstance(context)
            modelInitializationTime = System.currentTimeMillis() - t1
        }

        val inputFeature = TensorBuffer.createFixedSize(intArrayOf(1, inputImageSize, inputImageSize, 3), DataType.FLOAT32)

        val buffer = TensorImage(FLOAT32).let {
            it.load(bitmap)
            imageProcessor.process(it)
        }.tensorBuffer.buffer

        inputFeature.loadBuffer(buffer)
        val outputs = model.process(inputFeature)
        val outputFeature = outputs.outputFeature0AsTensorBuffer
        val prediction = NsfwPrediction(outputFeature.floatArray)
        return prediction
    }

    fun dispose() {
        model.close()
    }

}
