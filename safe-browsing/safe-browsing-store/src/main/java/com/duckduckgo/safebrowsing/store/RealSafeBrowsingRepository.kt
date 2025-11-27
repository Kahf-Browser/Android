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

import com.duckduckgo.common.utils.CurrentTimeProvider
import com.duckduckgo.safebrowsing.api.SafeBrowsingStatistics
import com.duckduckgo.safebrowsing.api.ThreatType

/**
 * Real implementation of SafeBrowsingRepository
 */
class RealSafeBrowsingRepository(
    private val database: SafeBrowsingDatabase,
    private val timeProvider: CurrentTimeProvider
) : SafeBrowsingRepository {

    companion object {
        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val MAX_CACHE_SIZE = 1000
    }

    private val cacheDao = database.safeBrowsingCacheDao()
    private val settingsDao = database.safeBrowsingSettingsDao()
    private val statisticsDao = database.safeBrowsingStatisticsDao()

    override suspend fun getCachedResult(url: String): CachedSafeBrowsingResult? {
        val entity = cacheDao.getCachedResult(url) ?: return null
        val currentTime = timeProvider.currentTimeMillis()
        val isExpired = entity.expiresAt < currentTime

        return CachedSafeBrowsingResult(
            url = entity.url,
            isSafe = entity.isSafe,
            threatType = entity.threatType?.let { ThreatType.valueOf(it) },
            lastChecked = entity.lastChecked,
            isExpired = isExpired
        )
    }

    override suspend fun cacheResult(url: String, isSafe: Boolean, threatType: ThreatType?) {
        val currentTime = timeProvider.currentTimeMillis()
        val entity = SafeBrowsingCacheEntity(
            url = url,
            isSafe = isSafe,
            threatType = threatType?.name,
            lastChecked = currentTime,
            expiresAt = currentTime + CACHE_DURATION_MS
        )
        cacheDao.insertCacheResult(entity)

        // Trim cache if it exceeds maximum size
        val cacheSize = cacheDao.getCacheSize()
        if (cacheSize > MAX_CACHE_SIZE) {
            cacheDao.trimCache(MAX_CACHE_SIZE)
        }
    }

    override suspend fun clearCache() {
        cacheDao.clearCache()
    }

    override suspend fun cleanupExpiredEntries() {
        val currentTime = timeProvider.currentTimeMillis()
        cacheDao.deleteExpiredEntries(currentTime)
    }

    override suspend fun isEnabled(): Boolean {
        return settingsDao.isEnabled()
    }

    override suspend fun setEnabled(enabled: Boolean) {
        val currentSettings = getSettings()
        val newSettings = currentSettings.copy(enabled = enabled)
        updateSettings(newSettings)
    }

    override suspend fun getSettings(): SafeBrowsingSettings {
        val entity = settingsDao.getSettings()
        return if (entity != null) {
            SafeBrowsingSettings(
                enabled = entity.enabled,
                statisticsEnabled = entity.statisticsEnabled
            )
        } else {
            // Initialize with defaults
            val defaultSettings = SafeBrowsingSettings()
            updateSettings(defaultSettings)
            defaultSettings
        }
    }

    override suspend fun updateSettings(settings: SafeBrowsingSettings) {
        val entity = SafeBrowsingSettingsEntity(
            enabled = settings.enabled,
            statisticsEnabled = settings.statisticsEnabled
        )
        settingsDao.updateSettings(entity)
    }

    override suspend fun getStatistics(): SafeBrowsingStatistics {
        val entity = statisticsDao.getStatistics()
        return if (entity != null) {
            SafeBrowsingStatistics(
                threatsBlocked = entity.threatsBlocked,
                lastBlockedUrl = entity.lastBlockedUrl,
                lastBlockedTimestamp = entity.lastBlockedTimestamp
            )
        } else {
            // Initialize with defaults
            val defaultStats = SafeBrowsingStatistics()
            statisticsDao.updateStatistics(
                SafeBrowsingStatisticsEntity(
                    threatsBlocked = 0,
                    lastBlockedUrl = null,
                    lastBlockedTimestamp = null
                )
            )
            defaultStats
        }
    }

    override suspend fun incrementThreatsBlocked(url: String, timestamp: Long) {
        // Ensure statistics entity exists
        if (statisticsDao.getStatistics() == null) {
            statisticsDao.updateStatistics(SafeBrowsingStatisticsEntity())
        }

        // Increment counter and update last blocked info
        statisticsDao.incrementThreatsBlocked()
        statisticsDao.updateLastBlocked(url, timestamp)
    }

    override suspend fun resetStatistics() {
        statisticsDao.resetStatistics()
    }
}
