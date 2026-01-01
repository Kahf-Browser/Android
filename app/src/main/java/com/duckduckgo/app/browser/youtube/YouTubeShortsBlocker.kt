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
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import javax.inject.Inject

/**
 * YouTube Shorts Blocker
 *
 * Provides YouTube shorts blocking functionality through:
 * - JavaScript injection for DOM manipulation
 * - Redirects shorts URLs to regular video format
 */
interface YouTubeShortsBlocker {
    /**
     * Get the shorts blocker JavaScript to inject into YouTube pages
     * @return The JavaScript code as a string
     */
    fun getShortsBlockerScript(): String

    /**
     * Check if a URL is a YouTube URL
     * @param url The URL to check
     * @return true if the URL is a YouTube domain
     */
    fun isYouTubeUrl(url: String?): Boolean
}

@ContributesBinding(AppScope::class)
class RealYouTubeShortsBlocker @Inject constructor(
    private val context: Context,
    private val updateManager: YoutubeShortsBlockerUpdateManager,
) : YouTubeShortsBlocker {

    /**
     * Load shorts-blocker script with fallback mechanism
     *
     * Priority:
     * 1. Try local remote-downloaded file first
     * 2. Fall back to bundled asset file if remote unavailable
     */
    private val cachedShortsBlockerScript: String by lazy {
        Timber.d("YouTubeShortsBlocker: Initializing script loader (lazy)")

        // TEMPORARY: Force using asset file for debugging
        /*Timber.w("YouTubeShortsBlocker: ⚠️ FORCING ASSET FILE (debugging mode)")
        loadFallbackAssetScript()*/

         //ORIGINAL CODE - Temporarily disabled for debugging
        try {
            // Try loading from remote-downloaded local file first
            val localFile = updateManager.getLocalScriptFile()
            Timber.d("YouTubeShortsBlocker: Checking remote file - Path: ${localFile.absolutePath}, Exists: ${localFile.exists()}, Size: ${localFile.length()} bytes")

            if (localFile.exists() && localFile.length() > 0) {
                val script = localFile.readText(Charsets.UTF_8)
                Timber.d(
                    "YouTubeShortsBlocker: ✓ Loaded REMOTE script from ${localFile.name} " +
                        "(${script.length} chars)"
                )
                script
            } else {
                // Fall back to bundled asset
                Timber.w("YouTubeShortsBlocker: ✗ Remote file not found or empty, using bundled asset")
                loadFallbackAssetScript()
            }
        } catch (e: Exception) {
            Timber.e(e, "YouTubeShortsBlocker: ✗ Failed to load remote script, using bundled asset")
            loadFallbackAssetScript()
        }
    }

    /**
     * Load the fallback script from assets
     *
     * Used when remote script is unavailable or fails to load
     */
    private fun loadFallbackAssetScript(): String {
        Timber.d("YouTubeShortsBlocker: Loading fallback script from assets/youtube-shorts-blocker.js")
        return try {
            readAssetFile("youtube-shorts-blocker.js").also {
                Timber.d("YouTubeShortsBlocker: ✓ Loaded ASSET fallback script (${it.length} chars)")
            }
        } catch (e: Exception) {
            Timber.e(e, "YouTubeShortsBlocker: ✗ CRITICAL - Failed to load asset fallback script")
            ""
        }
    }

    override fun getShortsBlockerScript(): String {
        Timber.d("YouTubeShortsBlocker: getShortsBlockerScript() called, returning ${cachedShortsBlockerScript.length} chars")
        return cachedShortsBlockerScript
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
            Timber.e(e, "YouTubeShortsBlocker: Error reading asset file: $fileName")
        }
        return stringBuilder.toString()
    }
}
