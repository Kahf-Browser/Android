package com.duckduckgo.app.kahftube.enums

sealed class SafeGazeLevel(val name: String) {
    data object Pixelation : SafeGazeLevel("Pixelation")
    data object Blur : SafeGazeLevel("Blur")
    data object Off : SafeGazeLevel("Off")

    companion object {
        fun get(name: String) = when (name) {
            "Pixelation" -> Pixelation
            "Blur" -> Blur
            else -> Off
        }

        fun isEnabled(name: String) = get(name) != Off
    }
}
