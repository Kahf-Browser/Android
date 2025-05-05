package com.duckduckgo.app

import android.content.SharedPreferences
import com.duckduckgo.common.utils.SAFE_GAZE_FACE_COVER
import androidx.core.content.edit

fun SharedPreferences.isFaceCoverEnabled() = this.getBoolean(SAFE_GAZE_FACE_COVER, false)

fun SharedPreferences.setFaceCoverMode(enabled: Boolean) {
    this.edit { putBoolean(SAFE_GAZE_FACE_COVER, enabled) }
}
