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
 * DAO for Safe Browsing statistics operations
 */
@Dao
interface SafeBrowsingStatisticsDao {

    /**
     * Get current statistics
     *
     * @return Statistics entity or null if not initialized
     */
    @Query("SELECT * FROM safe_browsing_statistics WHERE id = 1 LIMIT 1")
    suspend fun getStatistics(): SafeBrowsingStatisticsEntity?

    /**
     * Insert or update statistics
     *
     * @param entity The statistics to store
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateStatistics(entity: SafeBrowsingStatisticsEntity)

    /**
     * Increment the threats blocked counter
     */
    @Query("UPDATE safe_browsing_statistics SET threatsBlocked = threatsBlocked + 1 WHERE id = 1")
    suspend fun incrementThreatsBlocked()

    /**
     * Update last blocked URL and timestamp
     *
     * @param url The blocked URL
     * @param timestamp The timestamp in milliseconds
     */
    @Query("UPDATE safe_browsing_statistics SET lastBlockedUrl = :url, lastBlockedTimestamp = :timestamp WHERE id = 1")
    suspend fun updateLastBlocked(url: String, timestamp: Long)

    /**
     * Reset all statistics
     */
    @Query("UPDATE safe_browsing_statistics SET threatsBlocked = 0, lastBlockedUrl = NULL, lastBlockedTimestamp = NULL WHERE id = 1")
    suspend fun resetStatistics()
}
