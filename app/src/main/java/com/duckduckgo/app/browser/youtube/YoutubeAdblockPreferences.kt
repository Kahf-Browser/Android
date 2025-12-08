/*
 * Copyright (c) 2024 DuckDuckGo
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

package com.duckduckgo.app.browser.youtube

import android.content.SharedPreferences
import timber.log.Timber
import androidx.core.content.edit

/**
 * Manages SharedPreferences for YouTube ad-blocker version control
 *
 * Stores:
 * - Current installed version
 * - Last update check timestamp
 * - Update availability flags
 */
class YoutubeAdblockPreferences(
    private val sharedPreferences: SharedPreferences
) {

    companion object {
        // Default version for first-time users (no script installed yet)
        private const val DEFAULT_VERSION = "0.0.0"

        // Check for updates every 12 hours (in milliseconds)
        private const val UPDATE_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

        // Preference keys
        private const val KEY_CURRENT_VERSION = "youtube_adblocker_version"
        private const val KEY_LAST_CHECK_TIME = "youtube_adblocker_last_check_time"
    }

    /**
     * Get the currently installed ad-blocker version
     *
     * @return Version string (e.g., "1.2.3"), or "0.0.0" if not installed
     */
    fun getCurrentVersion(): String {
        return sharedPreferences.getString(KEY_CURRENT_VERSION, DEFAULT_VERSION) ?: DEFAULT_VERSION
    }

    /**
     * Save the newly installed ad-blocker version
     *
     * @param version Version string to save
     */
    fun setCurrentVersion(version: String) {
        Timber.d("YouTubeAdblock: Saving version $version")
        sharedPreferences.edit {
            putString(KEY_CURRENT_VERSION, version)
        }
    }

    /**
     * Get the timestamp of the last update check
     *
     * @return Timestamp in milliseconds, or 0 if never checked
     */
    fun getLastCheckTime(): Long {
        return sharedPreferences.getLong(KEY_LAST_CHECK_TIME, 0L)
    }

    /**
     * Save the current time as the last update check time
     */
    fun updateLastCheckTime() {
        val currentTime = System.currentTimeMillis()
        Timber.d("YouTubeAdblock: Updating last check time to $currentTime")
        sharedPreferences.edit {
            putLong(KEY_LAST_CHECK_TIME, currentTime)
        }
    }

    /**
     * Check if enough time has passed to perform another update check
     *
     * @return true if more than 12 hours have passed since last check
     */
    fun shouldCheckForUpdate(): Boolean {
        val lastCheck = getLastCheckTime()
        val currentTime = System.currentTimeMillis()
        val timeSinceLastCheck = currentTime - lastCheck

        val shouldCheck = timeSinceLastCheck >= UPDATE_CHECK_INTERVAL_MS

        Timber.d(
            "YouTubeAdblock: Should check for update? $shouldCheck " +
                "(${timeSinceLastCheck / 1000 / 60} minutes since last check)",
        )

        return shouldCheck
    }

    /**
     * Check if this is the first run (no version installed)
     *
     * @return true if no script has been downloaded yet
     */
    fun isFirstRun(): Boolean {
        return getCurrentVersion() == DEFAULT_VERSION
    }

    /**
     * Clear all preferences (for testing/debugging)
     */
    fun clear() {
        Timber.w("YouTubeAdblock: Clearing all preferences")
        sharedPreferences.edit {
            remove(KEY_CURRENT_VERSION)
                .remove(KEY_LAST_CHECK_TIME)
        }
    }
}
