/*package com.duckduckgo.app.safegaze.personDetection

import android.content.Context
import android.graphics.Bitmap
import com.duckduckgo.app.browser.ml.DetectPerson
import org.tensorflow.lite.support.image.TensorImage

class PersonDetector(val context: Context) {
    var model: DetectPerson  = DetectPerson.newInstance(context)

    fun hasPerson(bitmap: Bitmap): Boolean {
        val image = TensorImage.fromBitmap(bitmap)

        val outputs = model.process(image)
        val detectionResult = outputs.detectionResultList[0]

        val category = detectionResult.categoryAsString
        val score = detectionResult.scoreAsFloat

        return category == "person" && score > 0.5f
    }

    fun dispose() {
        model.close()
    }
}*/
