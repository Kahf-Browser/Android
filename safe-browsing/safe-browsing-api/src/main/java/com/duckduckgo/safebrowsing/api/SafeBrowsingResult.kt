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
 * Represents the result of a Safe Browsing URL check
 */
sealed class SafeBrowsingResult {
    /**
     * URL is safe - no threats detected
     * @param url The checked URL
     * @param fromCache Whether this result came from local cache
     */
    data class Safe(
        val url: String,
        val fromCache: Boolean = false
    ) : SafeBrowsingResult()

    /**
     * URL has been flagged as a threat
     * @param url The checked URL
     * @param threatType Type of threat detected
     * @param fromCache Whether this result came from local cache
     */
    data class Threat(
        val url: String,
        val threatType: ThreatType,
        val fromCache: Boolean = false
    ) : SafeBrowsingResult()

    /**
     * An error occurred during the check
     * @param url The checked URL
     * @param exception The exception that occurred
     * @param message Human-readable error message
     */
    data class Error(
        val url: String,
        val exception: Exception? = null,
        val message: String? = null
    ) : SafeBrowsingResult()
}
