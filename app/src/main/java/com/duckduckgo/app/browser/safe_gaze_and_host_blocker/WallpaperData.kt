package com.duckduckgo.app.browser.safe_gaze_and_host_blocker

import android.graphics.Bitmap
import com.google.gson.annotations.SerializedName

data class WallpaperData(
    val name: String = "",
    @SerializedName("download_url") val downloadUrl: String = "",
    val title: String = "",
    val subtitle: String = "",
    val credit: String = "",
    val url: String = "",
    val bitmap: Bitmap,
)
