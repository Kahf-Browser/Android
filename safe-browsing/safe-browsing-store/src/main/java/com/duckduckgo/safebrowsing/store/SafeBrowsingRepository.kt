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

package com.duckduckgo.safebrowsing.store

import com.duckduckgo.safebrowsing.api.SafeBrowsingStatistics
import com.duckduckgo.safebrowsing.api.ThreatType

/**
 * Repository interface for Safe Browsing data access
 */
interface SafeBrowsingRepository {

    // Cache operations
    suspend fun getCachedResult(url: String): CachedSafeBrowsingResult?
    suspend fun cacheResult(url: String, isSafe: Boolean, threatType: ThreatType? = null)
    suspend fun clearCache()
    suspend fun cleanupExpiredEntries()

    // Settings operations
    suspend fun isEnabled(): Boolean
    suspend fun setEnabled(enabled: Boolean)
    suspend fun getSettings(): SafeBrowsingSettings
    suspend fun updateSettings(settings: SafeBrowsingSettings)

    // Statistics operations
    suspend fun getStatistics(): SafeBrowsingStatistics
    suspend fun incrementThreatsBlocked(url: String, timestamp: Long)
    suspend fun resetStatistics()
}

/**
 * Cached Safe Browsing result
 */
data class CachedSafeBrowsingResult(
    val url: String,
    val isSafe: Boolean,
    val threatType: ThreatType?,
    val lastChecked: Long,
    val isExpired: Boolean
)

/**
 * Safe Browsing settings
 */
data class SafeBrowsingSettings(
    val enabled: Boolean = true,
    val statisticsEnabled: Boolean = true
)
