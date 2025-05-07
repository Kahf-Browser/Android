package com.duckduckgo.app

import android.content.SharedPreferences
import com.duckduckgo.common.utils.SAFE_GAZE_FACE_COVER
import com.duckduckgo.common.utils.SAFE_GAZE_LOCK
import androidx.core.content.edit

fun SharedPreferences.isFaceCoverEnabled() = this.getBoolean(SAFE_GAZE_FACE_COVER, false)

fun SharedPreferences.setFaceCoverMode(enabled: Boolean) {
    this.edit { putBoolean(SAFE_GAZE_FACE_COVER, enabled) }
}

fun SharedPreferences.isSgLockEnabled() = this.getBoolean(SAFE_GAZE_LOCK, false)

fun SharedPreferences.setSgLockMode(enabled: Boolean) {
    this.edit { putBoolean(SAFE_GAZE_LOCK, enabled) }
}
