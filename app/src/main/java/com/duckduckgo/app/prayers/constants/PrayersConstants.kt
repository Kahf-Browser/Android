package com.duckduckgo.app.prayers.constants

class PrayersConstants {

    object NotificationTypes {
        const val MUTED = "MUTED"
        const val UNMUTED = "UNMUTED"
        const val VOICE_ENABLED = "VOICE_ENABLED"
    }

    enum class PrayerTime(val type: String) {
        FAJR("FAJR"),
        SUNRISE("SUNRISE"),
        DHUHR("DHUHR"),
        ASR("ASR"),
        MAGHRIB("MAGHRIB"),
        ISHA("ISHA")
    }
}
