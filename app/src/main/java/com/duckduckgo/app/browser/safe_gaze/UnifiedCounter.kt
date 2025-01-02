package com.duckduckgo.app.browser.safe_gaze

import android.content.SharedPreferences
import com.duckduckgo.common.utils.SAFE_GAZE_API_CALLS_COUNT
import com.duckduckgo.common.utils.SAFE_GAZE_LAST_RESET_DATE
import com.duckduckgo.common.utils.SAFE_GAZE_QUOTA_LIMIT

class UnifiedCounter(
    private val preferences: SharedPreferences
) {
    private var dailyCount = 0
    private var lastResetDate = 0L
    private var dirtyCount = 0

    init {
        // Initialize counters from SharedPreferences
        lastResetDate = preferences.getLong(SAFE_GAZE_LAST_RESET_DATE, 0)
        dailyCount = preferences.getInt(SAFE_GAZE_API_CALLS_COUNT, 0)
        checkAndResetQuota()
    }

    fun incrementDailyQuota(isPositiveDetection: Boolean) {
        if (isPositiveDetection) {
            dailyCount++
        }
        dirtyCount++

        // Save to preferences every 20 images or when quota is exceeded
        if (dirtyCount >= 20 || dailyCount == SAFE_GAZE_QUOTA_LIMIT) {
            saveToPreferences()
        }
    }

    fun incrementSessionAndAllTimeCount(isPositiveDetection: Boolean) {
        dirtyCount++

        // Save to preferences every 20 images
        if (dirtyCount >= 20) {
            saveToPreferences()
        }
    }

    fun checkAndResetQuota() {
        val currentDate = System.currentTimeMillis() / 86400000 // Current day since epoch
        if (currentDate > lastResetDate) {
            lastResetDate = currentDate
            dailyCount = 0
            saveToPreferences()
        }
    }

    fun isQuotaExceeded() = dailyCount >= SAFE_GAZE_QUOTA_LIMIT

    fun saveToPreferences() {
        preferences.edit()
            .putLong(SAFE_GAZE_LAST_RESET_DATE, lastResetDate)
            .putInt(SAFE_GAZE_API_CALLS_COUNT, dailyCount)
            .apply()
        dirtyCount = 0
    }

    fun resetSession() {
        saveToPreferences()
    }
}
