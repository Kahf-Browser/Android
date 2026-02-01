/*
 * Copyright (c) 2022 DuckDuckGo
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

package com.duckduckgo.downloads.impl

import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import logcat.logcat
import okhttp3.ResponseBody
import retrofit2.Call

interface UrlFileDownloadCallManager {
    fun add(downloadId: Long, call: Call<ResponseBody>)
    fun remove(downloadId: Long)
    fun pause(downloadId: Long)
    fun resume(downloadId: Long)
    fun isPaused(downloadId: Long): Boolean
}

@ContributesBinding(
    scope = AppScope::class,
    boundType = UrlFileDownloadCallManager::class,
)
@SingleInstanceIn(AppScope::class)
class RealUrlFileDownloadCallManager @Inject constructor() : UrlFileDownloadCallManager {
    private val callsMap = ConcurrentHashMap<Long, Call<ResponseBody>>()
    private val pausedSet = ConcurrentHashMap.newKeySet<Long>()

    override fun remove(downloadId: Long) {
        logcat { "Removing download $downloadId" }
        callsMap[downloadId]?.cancel()
        callsMap.remove(downloadId)
        pausedSet.remove(downloadId)
    }

    override fun add(downloadId: Long, call: Call<ResponseBody>) {
        logcat { "Adding download $downloadId" }
        callsMap[downloadId] = call
    }

    override fun pause(downloadId: Long) {
        logcat { "Pausing download $downloadId" }
        pausedSet.add(downloadId)
    }

    override fun resume(downloadId: Long) {
        logcat { "Resuming download $downloadId" }
        pausedSet.remove(downloadId)
    }

    override fun isPaused(downloadId: Long): Boolean {
        return pausedSet.contains(downloadId)
    }
}
