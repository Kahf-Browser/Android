package com.duckduckgo.app.safegaze.poseDetection

import android.graphics.RectF

data class Person(
    var id: Int = -1, // default id is -1
    val keyPoints: List<KeyPoint>,
    val poseBox: RectF? = null,
    val score: Float,
    var isFemale: Boolean = true,
    var femaleConfidence: Float = 0f,
    var faceBox: RectF? = null,
)
