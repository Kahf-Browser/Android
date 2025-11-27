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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * DAO for Safe Browsing settings operations
 */
@Dao
interface SafeBrowsingSettingsDao {

    /**
     * Get current settings
     *
     * @return Settings entity or null if not initialized
     */
    @Query("SELECT * FROM safe_browsing_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): SafeBrowsingSettingsEntity?

    /**
     * Insert or update settings
     *
     * @param entity The settings to store
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSettings(entity: SafeBrowsingSettingsEntity)

    /**
     * Enable or disable Safe Browsing
     *
     * @param enabled true to enable, false to disable
     */
    @Query("UPDATE safe_browsing_settings SET enabled = :enabled WHERE id = 1")
    suspend fun setEnabled(enabled: Boolean)

    /**
     * Check if Safe Browsing is enabled
     *
     * @return true if enabled, false otherwise (defaults to true if not set)
     */
    @Query("SELECT COALESCE((SELECT enabled FROM safe_browsing_settings WHERE id = 1), 1)")
    suspend fun isEnabled(): Boolean
}
