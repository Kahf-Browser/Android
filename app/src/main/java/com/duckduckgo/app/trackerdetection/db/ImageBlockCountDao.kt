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

package com.duckduckgo.app.trackerdetection.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageBlockCountDao {
    @Query("INSERT INTO image_block_count (id, count) VALUES (0, 1)")
    fun insertItemWithIdZero()

    @Update
    fun update(count: ImageBlockCount)

    @Query("SELECT COALESCE((SELECT count FROM image_block_count WHERE id = 0), 0)")
    fun getCount(): Flow<Int>

    @Query("SELECT COALESCE((SELECT count FROM image_block_count WHERE id = 0), 0)")
    fun getCountSync(): Int

    @Query("SELECT * FROM image_block_count WHERE id = :id")
    fun hasItemWithId(id: Int): ImageBlockCount?

    @Transaction
    fun incrementCount() {
        hasItemWithId(0)?.let {
            update(ImageBlockCount(0, getCountSync() + 1))
        } ?: insertItemWithIdZero()
    }
}
