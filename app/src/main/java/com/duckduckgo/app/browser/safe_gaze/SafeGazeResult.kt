package com.duckduckgo.app.browser.safe_gaze


data class SafeGazeResult(
    val isNsfw: Boolean,
    val persons: List<Int>,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val base64Image: String? = null,
)
