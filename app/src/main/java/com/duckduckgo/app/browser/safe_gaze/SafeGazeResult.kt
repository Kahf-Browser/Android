package com.duckduckgo.app.browser.safe_gaze

import com.duckduckgo.app.safegaze.poseDetection.Person

data class SafeGazeResult(
    val isNsfw: Boolean,
    val persons: List<Person>,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val base64Image: String? = null,
)
