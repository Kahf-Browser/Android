/*
 * Copyright (c) 2024 DuckDuckGo
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

package com.duckduckgo.app.browser.youtube

import android.content.Context
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import javax.inject.Inject

/**
 * YouTube Ad Blocker
 *
 * Provides YouTube ad blocking functionality through:
 * - Layer 1: JavaScript injection for DOM manipulation and player data interception
 * - Layer 2: Network-level URL pattern blocking
 * - Layer 3: Fallback DOM-based ad detection
 */
interface YouTubeAdBlocker {
    /**
     * Check if a URL should be blocked based on ad patterns
     * @param url The URL to check
     * @return true if the URL matches ad patterns and should be blocked
     */
    fun shouldBlockRequest(url: String): Boolean

    /**
     * Get the ad blocker JavaScript to inject into YouTube pages
     * @return The JavaScript code as a string
     */
    fun getAdBlockerScript(): String

    /**
     * Check if a URL is a YouTube URL
     * @param url The URL to check
     * @return true if the URL is a YouTube domain
     */
    fun isYouTubeUrl(url: String?): Boolean
}

@ContributesBinding(AppScope::class)
class RealYouTubeAdBlocker @Inject constructor(
    private val context: Context,
    private val updateManager: YoutubeAdblockUpdateManager,
) : YouTubeAdBlocker {

    private val cachedBlockedPatterns: List<Regex> by lazy {
        try {
            val json = context.assets.open("youtube-blocked-patterns.json")
                .bufferedReader().use { it.readText() }

            val patternsJson = JSONObject(json)
            val regexArray = patternsJson
                .getJSONObject("regex")
                .getJSONArray("android")

            (0 until regexArray.length()).map { index ->
                val pattern = regexArray.getString(index)
                Regex(pattern)
            }.also {
                Timber.d("YouTubeAdBlocker: Loaded ${it.size} blocking patterns")
            }
        } catch (e: Exception) {
            Timber.e(e, "YouTubeAdBlocker: Failed to load blocking patterns")
            emptyList()
        }
    }

    /**
     * Load ad-blocker script with fallback mechanism
     *
     * Priority:
     * 1. Try local remote-downloaded file first
     * 2. Fall back to bundled asset file if remote unavailable
     */
    private val cachedAdBlockerScript: String by lazy {
        try {
            // Try loading from remote-downloaded local file first
            val localFile = updateManager.getLocalScriptFile()
            if (localFile.exists() && localFile.length() > 0) {
                val script = localFile.readText(Charsets.UTF_8)
                Timber.d(
                    "YouTubeAdBlocker: Loaded REMOTE script from ${localFile.name} " +
                        "(${script.length} chars)"
                )
                script
            } else {
                // Fall back to bundled asset
                Timber.w("YouTubeAdBlocker: Remote file not found, using bundled asset")
                loadFallbackAssetScript()
            }
        } catch (e: Exception) {
            Timber.e(e, "YouTubeAdBlocker: Failed to load remote script, using bundled asset")
            loadFallbackAssetScript()
        }
    }

    /**
     * Load the fallback script from assets
     *
     * Used when remote script is unavailable or fails to load
     */
    private fun loadFallbackAssetScript(): String {
        return try {
            readAssetFile("youtube-ads-blocker.js").also {
                Timber.d("YouTubeAdBlocker: Loaded ASSET fallback script (${it.length} chars)")
            }
        } catch (e: Exception) {
            Timber.e(e, "YouTubeAdBlocker: Failed to load asset fallback script")
            ""
        }
    }

    override fun shouldBlockRequest(url: String): Boolean {
        return try {
            cachedBlockedPatterns.any { pattern ->
                pattern.matches(url)
            }
        } catch (e: Exception) {
            Timber.e(e, "YouTubeAdBlocker: Error checking URL: $url")
            false
        }
    }

    override fun getAdBlockerScript(): String {
        return cachedAdBlockerScript
    }

    override fun isYouTubeUrl(url: String?): Boolean {
        if (url == null) return false
        return url.contains("youtube.com", ignoreCase = true) ||
               url.contains("youtu.be", ignoreCase = true)
    }

    private fun readAssetFile(fileName: String): String {
        val stringBuilder = StringBuilder()
        try {
            val inputStream = context.assets.open(fileName)
            val bufferedReader = BufferedReader(InputStreamReader(inputStream))

            var line: String?
            while (bufferedReader.readLine().also { line = it } != null) {
                stringBuilder.append(line).append('\n')
            }
            bufferedReader.close()
        } catch (e: IOException) {
            Timber.e(e, "YouTubeAdBlocker: Error reading asset file: $fileName")
        }
        return stringBuilder.toString()
    }
}
