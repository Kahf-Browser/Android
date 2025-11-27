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

package com.duckduckgo.safebrowsing.api

/**
 * Statistics about Safe Browsing threat detection
 *
 * @param threatsBlocked Total number of threats blocked
 * @param lastBlockedUrl The most recently blocked URL (null if none)
 * @param lastBlockedTimestamp Timestamp of last blocked threat in milliseconds (null if none)
 */
data class SafeBrowsingStatistics(
    val threatsBlocked: Int = 0,
    val lastBlockedUrl: String? = null,
    val lastBlockedTimestamp: Long? = null
)
