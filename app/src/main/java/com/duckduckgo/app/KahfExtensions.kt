package com.duckduckgo.app

import android.content.SharedPreferences
import android.os.Build
import com.duckduckgo.common.utils.SAFE_GAZE_FACE_COVER
import com.duckduckgo.common.utils.SAFE_GAZE_LOCK
import androidx.core.content.edit
import com.duckduckgo.common.utils.DEFAULT_FACE_COVER

fun SharedPreferences.isFaceCoverEnabled() = this.getBoolean(SAFE_GAZE_FACE_COVER, DEFAULT_FACE_COVER)

fun SharedPreferences.setFaceCoverMode(enabled: Boolean) {
    this.edit { putBoolean(SAFE_GAZE_FACE_COVER, enabled) }
}

fun SharedPreferences.isSgLockEnabled() = this.getBoolean(SAFE_GAZE_LOCK, false)

fun SharedPreferences.setSgLockMode(enabled: Boolean) {
    this.edit { putBoolean(SAFE_GAZE_LOCK, enabled) }
}

fun isZikrTab(): Boolean {
    val model = Build.MODEL
    // val manufacturer = Build.MANUFACTURER

    return if (model.equals("E101GCM", ignoreCase = true) /*&& manufacturer.equals("google", ignoreCase = true)*/) {
        // println("DeviceCheck -- Zikr detected")
        true
    } else {
        // println("DeviceCheck -- Not zikr $model")
        false
    }
}
