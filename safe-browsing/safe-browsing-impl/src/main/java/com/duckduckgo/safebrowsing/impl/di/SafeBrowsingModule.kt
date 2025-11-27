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

package com.duckduckgo.safebrowsing.impl.di

import android.content.Context
import androidx.room.Room
import com.duckduckgo.common.utils.CurrentTimeProvider
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.safebrowsing.store.RealSafeBrowsingRepository
import com.duckduckgo.safebrowsing.store.SafeBrowsingDatabase
import com.duckduckgo.safebrowsing.store.SafeBrowsingRepository
import com.squareup.anvil.annotations.ContributesTo
import dagger.Module
import dagger.Provides
import dagger.SingleInstanceIn

/**
 * Dagger module for Safe Browsing dependency injection
 */
@Module
@ContributesTo(AppScope::class)
class SafeBrowsingModule {

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun provideSafeBrowsingDatabase(context: Context): SafeBrowsingDatabase {
        return Room.databaseBuilder(
            context,
            SafeBrowsingDatabase::class.java,
            "safe_browsing.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun provideSafeBrowsingRepository(
        database: SafeBrowsingDatabase,
        timeProvider: CurrentTimeProvider
    ): SafeBrowsingRepository {
        return RealSafeBrowsingRepository(database, timeProvider)
    }
}
