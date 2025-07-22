package com.duckduckgo.app

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import com.duckduckgo.common.utils.DEFAULT_FACE_COVER
import com.duckduckgo.common.utils.SAFE_GAZE_FACE_COVER
import com.duckduckgo.common.utils.SAFE_GAZE_LOCK

fun SharedPreferences.isFaceCoverEnabled() = this.getBoolean(SAFE_GAZE_FACE_COVER, DEFAULT_FACE_COVER)

fun SharedPreferences.setFaceCoverMode(enabled: Boolean) {
    this.edit { putBoolean(SAFE_GAZE_FACE_COVER, enabled) }
}

fun SharedPreferences.isSgLockEnabled() = this.getBoolean(SAFE_GAZE_LOCK, false)

fun SharedPreferences.setSgLockMode(enabled: Boolean) {
    this.edit { putBoolean(SAFE_GAZE_LOCK, enabled) }
}

fun isZikrTab(): Boolean {
    // val model = Build.MODEL
    val manufacturer = Build.MANUFACTURER

    return if (manufacturer.equals("Zikr", ignoreCase = true) /*&& manufacturer.equals("google", ignoreCase = true)*/) {
        // println("DeviceCheck -- Zikr detected")
        true
    } else {
        // println("DeviceCheck -- Not zikr $model")
        false
    }
}

fun isMyBrowserDefault(
    packageManager: PackageManager,
    myPackageName: String
): Boolean {
    val browseIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.example.com"))
    val defaultResolutionInfo = packageManager.resolveActivity(browseIntent, PackageManager.MATCH_DEFAULT_ONLY)
    return defaultResolutionInfo?.activityInfo?.packageName == myPackageName
}

fun Int.Days(): Long = this.toLong() * 24 * 60 * 60 * 1000
