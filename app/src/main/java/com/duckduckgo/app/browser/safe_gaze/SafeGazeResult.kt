package com.duckduckgo.app.browser.safe_gaze

import com.duckduckgo.app.safegaze.poseDetection.Person

data class SafeGazeResult(
    val isNsfw: Boolean,
    val persons: List<Person>,
    val imageWidth: Int,
    val imageHeight: Int,
    val base64Image: String? = null,
)
