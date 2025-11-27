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

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database for Safe Browsing feature
 *
 * Stores cached URL check results, user settings, and statistics.
 */
@Database(
    entities = [
        SafeBrowsingCacheEntity::class,
        SafeBrowsingSettingsEntity::class,
        SafeBrowsingStatisticsEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SafeBrowsingDatabase : RoomDatabase() {
    abstract fun safeBrowsingCacheDao(): SafeBrowsingCacheDao
    abstract fun safeBrowsingSettingsDao(): SafeBrowsingSettingsDao
    abstract fun safeBrowsingStatisticsDao(): SafeBrowsingStatisticsDao
}
