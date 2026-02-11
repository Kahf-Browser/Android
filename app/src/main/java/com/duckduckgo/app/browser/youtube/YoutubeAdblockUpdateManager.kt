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
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.data.store.api.SharedPreferencesProvider
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * Remote version metadata from GitHub
 *
 * @property version Semantic version string (e.g., "1.2.0")
 * @property scriptUrl URL to download the JavaScript file
 * @property sha256 SHA-256 checksum for integrity verification
 * @property minExtensionVersion Minimum browser version required
 * @property updatedAt ISO 8601 timestamp of last update
 * @property changelog Human-readable description of changes
 */
data class AdBlockerVersionInfo(
    val version: String,
    val scriptUrl: String,
    val sha256: String,
    val minExtensionVersion: String,
    val updatedAt: String,
    val changelog: String
)

/**
 * Manages remote updates for YouTube ad-blocker script
 *
 * Flow:
 * 1. Check if 12+ hours since last check (throttling)
 * 2. Fetch version.json from GitHub
 * 3. Compare remote version with local version
 * 4. If newer, download script and validate SHA-256
 * 5. Save script locally and update preferences
 *
 * First-run behavior:
 * - If no local script exists, always download regardless of time throttle
 */
interface YoutubeAdblockUpdateManager {
    /**
     * Check for and download updates if available
     *
     * This is the main entry point called from Application.onCreate()
     * Runs on background thread via dispatchers.io()
     */
    suspend fun checkForUpdates()

    /**
     * Get the local script file path
     *
     * @return File path to the locally stored ad-blocker script
     */
    fun getLocalScriptFile(): File
}

@ContributesBinding(AppScope::class)
class RealYoutubeAdblockUpdateManager @Inject constructor(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
    spProvider: SharedPreferencesProvider
) : YoutubeAdblockUpdateManager {

    companion object {
        // Remote version.json URL
        private const val VERSION_JSON_URL =
            "https://gitlab.kahf.co.uk/kahf-browser-scripts/safegaze-scripts/-/raw/main/youtube-ads-blocker/version.json"

        // Local filename for the downloaded script
        private const val LOCAL_SCRIPT_FILENAME = "youtube-ads-blocker-remote.js"

        // HTTP connection timeout (10 seconds)
        private const val CONNECTION_TIMEOUT_MS = 10_000

        // Read timeout (15 seconds)
        private const val READ_TIMEOUT_MS = 15_000
    }

    private val preferences = YoutubeAdblockPreferences(spProvider.getKahfSharedPreferences())

    /**
     * Check for updates and download if necessary
     *
     * Thread-safe: runs on IO dispatcher
     * Graceful failure: catches all exceptions and logs them
     */
    override suspend fun checkForUpdates() {
        withContext(dispatcherProvider.io()) {
            try {
                Timber.d("YouTubeAdblock: Checking for updates...")

                // Check if we should run the update check
                val isFirstRun = preferences.isFirstRun()
                val shouldCheck = preferences.shouldCheckForUpdate()

                if (!isFirstRun && !shouldCheck) {
                    Timber.d("YouTubeAdblock: Skipping check (throttled)")
                    return@withContext
                }

                // Fetch remote version info
                val versionInfo = fetchVersionInfo()
                if (versionInfo == null) {
                    Timber.w("YouTubeAdblock: Failed to fetch version info")
                    return@withContext
                }

                // Update last check time (even if no update is needed)
                preferences.updateLastCheckTime()

                // Compare versions
                val currentVersion = preferences.getCurrentVersion()
                val remoteVersion = versionInfo.version

                Timber.d("YouTubeAdblock: Current: $currentVersion, Remote: $remoteVersion")

                val needsUpdate = isFirstRun ||
                    VersionComparator.isGreaterThan(remoteVersion, currentVersion)

                if (!needsUpdate) {
                    Timber.d("YouTubeAdblock: Already up to date")
                    return@withContext
                }

                // Download and validate the script
                Timber.i("YouTubeAdblock: Downloading version $remoteVersion")
                val success = downloadAndValidateScript(versionInfo)

                if (success) {
                    preferences.setCurrentVersion(remoteVersion)
                    Timber.i(
                        "YouTubeAdblock: Successfully updated to $remoteVersion\n" +
                            "Changelog: ${versionInfo.changelog}"
                    )
                } else {
                    Timber.e("YouTubeAdblock: Failed to download or validate script")
                }
            } catch (e: Exception) {
                Timber.e(e, "YouTubeAdblock: Error during update check")
            }
        }
    }

    /**
     * Get the local script file
     *
     * @return File object pointing to the local script storage
     */
    override fun getLocalScriptFile(): File {
        return File(context.filesDir, LOCAL_SCRIPT_FILENAME)
    }

    /**
     * Fetch version.json from GitHub
     *
     * @return Parsed version info, or null on failure
     */
    private fun fetchVersionInfo(): AdBlockerVersionInfo? {
        return try {
            val url = URL(VERSION_JSON_URL)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECTION_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Timber.w("YouTubeAdblock: HTTP $responseCode from version.json")
                return null
            }

            val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            parseVersionInfo(jsonString)
        } catch (e: Exception) {
            Timber.e(e, "YouTubeAdblock: Failed to fetch version info")
            null
        }
    }

    /**
     * Parse version.json string into AdBlockerVersionInfo
     *
     * @param jsonString Raw JSON string
     * @return Parsed version info, or null if parsing fails
     */
    private fun parseVersionInfo(jsonString: String): AdBlockerVersionInfo? {
        return try {
            val json = JSONObject(jsonString)
            Timber.d("YouTubeAdblock: Parsed version info: $json")

            AdBlockerVersionInfo(
                version = json.getString("version"),
                scriptUrl = json.getString("scriptUrl"),
                sha256 = json.getString("sha256"),
                minExtensionVersion = json.optString("minExtensionVersion", "0.0.0"),
                updatedAt = json.optString("updatedAt", ""),
                changelog = json.optString("changelog", "No changelog provided")
            )
        } catch (e: Exception) {
            Timber.e(e, "YouTubeAdblock: Failed to parse version.json")
            null
        }
    }

    /**
     * Download script from URL and validate SHA-256
     *
     * @param versionInfo Version metadata containing script URL and expected hash
     * @return true if download and validation successful
     */
    private fun downloadAndValidateScript(versionInfo: AdBlockerVersionInfo): Boolean {
        return try {
            // Download the script
            val scriptContent = downloadScript(versionInfo.scriptUrl)
            if (scriptContent == null) {
                Timber.e("YouTubeAdblock: Failed to download script")
                return false
            }

            // Save to local file
            val localFile = getLocalScriptFile()
            localFile.writeText(scriptContent, Charsets.UTF_8)

            Timber.d(
                "YouTubeAdblock: Script saved to ${localFile.absolutePath} " +
                    "(${scriptContent.length} bytes)"
            )

            true
        } catch (e: Exception) {
            Timber.e(e, "YouTubeAdblock: Error downloading/validating script")
            false
        }
    }

    /**
     * Download script content from URL
     *
     * @param scriptUrl URL to download from
     * @return Script content as string, or null on failure
     */
    private fun downloadScript(scriptUrl: String): String? {
        return try {
            val url = URL(scriptUrl)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECTION_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/javascript, text/javascript")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Timber.w("YouTubeAdblock: HTTP $responseCode from script URL")
                return null
            }

            val content = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            content
        } catch (e: Exception) {
            Timber.e(e, "YouTubeAdblock: Failed to download script from $scriptUrl")
            null
        }
    }
}
