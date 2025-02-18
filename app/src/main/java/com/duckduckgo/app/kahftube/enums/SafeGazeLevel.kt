package com.duckduckgo.app.kahftube.enums

import android.content.SharedPreferences
import com.duckduckgo.common.utils.SAFE_GAZE_DEFAULT
import com.duckduckgo.common.utils.SAFE_GAZE_MODE

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

        fun getCurrentLevel(pref: SharedPreferences): SafeGazeLevel {
            val currentMode = pref.getString(SAFE_GAZE_MODE, SAFE_GAZE_DEFAULT) ?: SAFE_GAZE_DEFAULT
            return get(currentMode)
        }

        fun updateLevel(pref: SharedPreferences, level: SafeGazeLevel) {
            pref.edit().putString(SAFE_GAZE_MODE, level.name).apply()
        }
    }
}
