/*
 * Copyright (c) 2021 DuckDuckGo
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

package com.duckduckgo.app.trackerdetection.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Duration
import java.time.Instant

@Dao
interface KahfImageBlockedDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(blocked: KahfImageBlocked)

    @Query("DELETE FROM kahf_image_blocked")
    fun deleteAll()

    @Query("SELECT * FROM kahf_image_blocked")
    fun getAllBlockedImageDetails(): Flow<List<KahfImageBlocked>>

    @Query("SELECT SUM(CASE WHEN isIndecent THEN 1 ELSE 0 END) FROM kahf_image_blocked")
    fun getTotalBlockCount(): Flow<Int>

    @Query("DELETE FROM kahf_image_blocked WHERE modifiedAt < :milliseconds")
    fun deleteImagesOlderThan(milliseconds: Long): Int

    @Query("SELECT * FROM kahf_image_blocked WHERE imageUrl = :urlMd5")
    fun findByUrl(urlMd5: String): KahfImageBlocked?

    fun deleteOlderImages(days: Long): Int {
        return deleteImagesOlderThan(Instant.now().minus(Duration.ofDays(days)).toEpochMilli())
    }
}
