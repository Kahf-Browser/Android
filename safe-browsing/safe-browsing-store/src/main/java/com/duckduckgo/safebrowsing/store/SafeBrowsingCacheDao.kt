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
 * DAO for Safe Browsing cache operations
 */
@Dao
interface SafeBrowsingCacheDao {

    /**
     * Get cached result for a URL
     *
     * @param url The URL to lookup
     * @return Cached result or null if not found or expired
     */
    @Query("SELECT * FROM safe_browsing_cache WHERE url = :url LIMIT 1")
    suspend fun getCachedResult(url: String): SafeBrowsingCacheEntity?

    /**
     * Insert or update a cache entry
     *
     * @param entity The cache entity to store
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCacheResult(entity: SafeBrowsingCacheEntity)

    /**
     * Delete all expired cache entries
     *
     * @param currentTime Current timestamp in milliseconds
     */
    @Query("DELETE FROM safe_browsing_cache WHERE expiresAt < :currentTime")
    suspend fun deleteExpiredEntries(currentTime: Long)

    /**
     * Clear all cache entries
     */
    @Query("DELETE FROM safe_browsing_cache")
    suspend fun clearCache()

    /**
     * Get total number of cached entries
     *
     * @return Count of cache entries
     */
    @Query("SELECT COUNT(*) FROM safe_browsing_cache")
    suspend fun getCacheSize(): Int

    /**
     * Delete oldest cache entries if cache exceeds limit
     *
     * Keeps only the most recent 1000 entries
     *
     * @param limit Maximum number of entries to keep
     */
    @Query("DELETE FROM safe_browsing_cache WHERE url IN (SELECT url FROM safe_browsing_cache ORDER BY lastChecked ASC LIMIT (SELECT COUNT(*) - :limit FROM safe_browsing_cache WHERE (SELECT COUNT(*) FROM safe_browsing_cache) > :limit))")
    suspend fun trimCache(limit: Int = 1000)
}
