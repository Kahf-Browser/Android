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
 * Manager interface for Google Safe Browsing functionality
 *
 * This service checks URLs against Google's Safe Browsing threat database
 * to protect users from phishing, malware, and other threats.
 */
interface SafeBrowsingManager {

    /**
     * Initialize the Safe Browsing service
     *
     * This should be called in the app's onResume() lifecycle method
     * to ensure the SafetyNet API is properly initialized before use.
     *
     * @return true if initialization succeeded, false otherwise
     */
    suspend fun initialize(): Boolean

    /**
     * Shutdown the Safe Browsing service
     *
     * This should be called in the app's onPause() lifecycle method
     * to free resources and maintain a fresh session.
     */
    suspend fun shutdown()

    /**
     * Check if a URL is safe or contains threats
     *
     * This method performs an async check against Google Safe Browsing API.
     * Results are cached locally for 24 hours to reduce API calls.
     *
     * @param url The URL to check (HTTP/HTTPS only)
     * @return SafeBrowsingResult indicating if URL is safe, a threat, or an error occurred
     */
    suspend fun checkUrl(url: String): SafeBrowsingResult

    /**
     * Check if Safe Browsing is currently enabled
     *
     * @return true if enabled, false otherwise
     */
    fun isEnabled(): Boolean

    /**
     * Enable or disable Safe Browsing protection
     *
     * When disabled, URL checks will not be performed.
     *
     * @param enabled true to enable, false to disable
     */
    suspend fun setEnabled(enabled: Boolean)

    /**
     * Clear the local cache of Safe Browsing results
     *
     * This will force fresh API checks for all URLs.
     */
    suspend fun clearCache()

    /**
     * Get statistics about threats blocked
     *
     * @return SafeBrowsingStatistics with counts and recent threats
     */
    suspend fun getStatistics(): SafeBrowsingStatistics
}
