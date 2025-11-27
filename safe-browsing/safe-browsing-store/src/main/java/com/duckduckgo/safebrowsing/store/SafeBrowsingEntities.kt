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

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached Safe Browsing check result
 *
 * Stores the result of a URL check to avoid repeated API calls.
 * Cache expires after 24 hours.
 */
@Entity(tableName = "safe_browsing_cache")
data class SafeBrowsingCacheEntity(
    @PrimaryKey
    val url: String,
    val isSafe: Boolean,
    val threatType: String? = null, // null if safe, otherwise ThreatType name
    val lastChecked: Long, // timestamp in milliseconds
    val expiresAt: Long // timestamp in milliseconds (lastChecked + 24 hours)
)

/**
 * Safe Browsing settings
 *
 * Stores user preferences for Safe Browsing feature.
 * Single row table (id always = 1)
 */
@Entity(tableName = "safe_browsing_settings")
data class SafeBrowsingSettingsEntity(
    @PrimaryKey
    val id: Int = 1, // Always 1, singleton settings
    val enabled: Boolean = true,
    val statisticsEnabled: Boolean = true
)

/**
 * Safe Browsing statistics
 *
 * Tracks threats blocked and recent threat information.
 * Single row table (id always = 1)
 */
@Entity(tableName = "safe_browsing_statistics")
data class SafeBrowsingStatisticsEntity(
    @PrimaryKey
    val id: Int = 1, // Always 1, singleton statistics
    val threatsBlocked: Int = 0,
    val lastBlockedUrl: String? = null,
    val lastBlockedTimestamp: Long? = null
)
