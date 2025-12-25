/*
 * Copyright (c) 2025 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.safegaze.enums

import android.content.SharedPreferences
import android.util.Log
import com.duckduckgo.common.utils.SAFE_GAZE_DEFAULT
import com.duckduckgo.common.utils.SAFE_GAZE_MODE
import com.duckduckgo.common.utils.VIDEO_BLUR_MODE

sealed class SafeGazeLevel(val name: String) {
    data object PixelationWithoutFaceBlur : SafeGazeLevel("PixelationWithoutFaceBlur")
    data object PixelationWithoutHeadBlur : SafeGazeLevel("PixelationWithoutHeadBlur")
    data object SolidWithFaceBlur : SafeGazeLevel("SolidWithFaceBlur")
    data object SolidWithoutFaceBlur : SafeGazeLevel("SolidWithoutFaceBlur")
    data object Off : SafeGazeLevel("Off")

    companion object {
        fun get(name: String) = when (name) {
            "PixelationWithoutFaceBlur" -> PixelationWithoutFaceBlur
            "PixelationWithoutHeadBlur" -> PixelationWithoutHeadBlur
            "SolidWithFaceBlur" -> SolidWithFaceBlur
            "SolidWithoutFaceBlur" -> SolidWithoutFaceBlur
            else -> Off
        }

        fun isEnabled(name: String) = get(name) != Off

        fun getImageBlurLevel(pref: SharedPreferences): SafeGazeLevel {
            val currentMode = pref.getString(SAFE_GAZE_MODE, SAFE_GAZE_DEFAULT) ?: SAFE_GAZE_DEFAULT
            return get(currentMode)
        }

        fun updateImageBlurLevel(pref: SharedPreferences, level: SafeGazeLevel) {
            pref.edit().putString(SAFE_GAZE_MODE, level.name).apply()
        }

        fun getVideoBlurLevel(pref: SharedPreferences, calledFrom: String): SafeGazeLevel {
            val currentMode = pref.getString(VIDEO_BLUR_MODE, SAFE_GAZE_DEFAULT) ?: SAFE_GAZE_DEFAULT
            Log.d("safegazelog", "get videoBlurMode: $currentMode calledFrom: $calledFrom")
            return get(currentMode)
        }

        fun updateVideoBlurLevel(pref: SharedPreferences, level: SafeGazeLevel) {
            Log.d("safegazelog", "imgLog Received set videoBlurMode: $level")
            pref.edit().putString(VIDEO_BLUR_MODE, level.name).apply()
        }
    }
}
