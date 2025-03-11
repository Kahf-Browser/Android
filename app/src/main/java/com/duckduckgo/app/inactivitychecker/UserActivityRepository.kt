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

package com.duckduckgo.app.inactivitychecker

import com.duckduckgo.data.store.api.SharedPreferencesProvider
import javax.inject.Inject

class UserActivityRepository @Inject constructor(
    private val sharedPreferencesProvider: SharedPreferencesProvider
) {
    private val preferences by lazy {
        sharedPreferencesProvider.getKahfSharedPreferences()
    }

    fun updateLastOpenedTimestamp() {
        preferences.edit().putLong(KEY_LAST_OPENED_TIMESTAMP, System.currentTimeMillis()).apply()
    }

    private fun getLastOpenedTimestamp(): Long {
        return preferences.getLong(KEY_LAST_OPENED_TIMESTAMP, 0)
    }

    fun shownInactivityNotificationsCount(): Int {
        return preferences.getInt(KEY_NOTIFICATION_COUNT, 0)
    }

    fun increaseNotificationCount() {
        preferences.edit().putInt(KEY_NOTIFICATION_COUNT, shownInactivityNotificationsCount() + 1).apply()
    }

    fun isInactiveForDays(days: Int): Boolean {
        val lastOpenTimestamp = getLastOpenedTimestamp()
        if (lastOpenTimestamp == 0L) return false

        val currentTime = System.currentTimeMillis()
        val inactivityThreshold = days * 24 * 60 * 60 * 1000L
        return (currentTime - lastOpenTimestamp) >= inactivityThreshold
    }

    companion object {
        private const val KEY_LAST_OPENED_TIMESTAMP = "last_opened_timestamp"
        private const val KEY_NOTIFICATION_COUNT = "notification_count"
    }
}
