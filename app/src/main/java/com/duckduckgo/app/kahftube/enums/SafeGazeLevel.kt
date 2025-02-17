package com.duckduckgo.app.kahftube.enums

sealed class SafeGazeLevel(val name: String) {
    data object FullImage : SafeGazeLevel("FullImage")
    data object HumanOnly : SafeGazeLevel("HumanOnly")
    data object Off : SafeGazeLevel("Off")

    companion object {
        fun get(name: String) = when (name) {
            "FullImage" -> FullImage
            "HumanOnly" -> HumanOnly
            else -> Off
        }

        fun isEnabled(name: String) = get(name) != Off
    }
}
