package com.duckduckgo.app.prayers.constants

class PrayersConstants {

    object NotificationTypes {
        const val MUTED = "MUTED"
        const val UNMUTED = "UNMUTED"
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
