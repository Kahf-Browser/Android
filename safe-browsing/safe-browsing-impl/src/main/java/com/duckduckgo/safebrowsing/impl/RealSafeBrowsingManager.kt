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

package com.duckduckgo.safebrowsing.impl

import android.content.Context
import com.duckduckgo.common.utils.CurrentTimeProvider
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.safebrowsing.api.SafeBrowsingManager
import com.duckduckgo.safebrowsing.api.SafeBrowsingResult
import com.duckduckgo.safebrowsing.api.SafeBrowsingStatistics
import com.duckduckgo.safebrowsing.api.ThreatType
import com.duckduckgo.safebrowsing.store.SafeBrowsingRepository
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.safetynet.SafeBrowsingThreat
import com.google.android.gms.safetynet.SafetyNet
import com.squareup.anvil.annotations.ContributesBinding
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject

/**
 * Real implementation of SafeBrowsingManager using Google SafetyNet API
 */
@ContributesBinding(AppScope::class)
class RealSafeBrowsingManager @Inject constructor(
    private val context: Context,
    private val repository: SafeBrowsingRepository,
    private val apiKeyProvider: SafeBrowsingApiKeyProvider,
    private val timeProvider: CurrentTimeProvider
) : SafeBrowsingManager {

    companion object {
        private const val TAG = "SafeBrowsingManager"
        private const val URL_SCHEME_HTTP = "http"
        private const val URL_SCHEME_HTTPS = "https"
    }

    private var isInitialized = false
    private val safetyNetClient = SafetyNet.getClient(context)

    override suspend fun initialize(): Boolean {
        return try {
            Timber.d("$TAG: Initializing SafetyNet Safe Browsing")
            safetyNetClient.initSafeBrowsing().await()
            isInitialized = true
            Timber.d("$TAG: SafetyNet Safe Browsing initialized successfully")

            // Cleanup expired cache entries on initialization
            repository.cleanupExpiredEntries()

            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize SafetyNet Safe Browsing")
            isInitialized = false
            false
        }
    }

    override suspend fun shutdown() {
        try {
            Timber.d("$TAG: Shutting down SafetyNet Safe Browsing")
            safetyNetClient.shutdownSafeBrowsing()
            isInitialized = false
            Timber.d("$TAG: SafetyNet Safe Browsing shut down successfully")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error shutting down SafetyNet Safe Browsing")
        }
    }

    override suspend fun checkUrl(url: String): SafeBrowsingResult {
        // Check if Safe Browsing is enabled
        if (!isEnabled()) {
            Timber.d("$TAG: Safe Browsing is disabled, skipping check for $url")
            return SafeBrowsingResult.Safe(url, fromCache = false)
        }

        // Validate URL scheme
        if (!isValidUrlScheme(url)) {
            Timber.d("$TAG: Invalid URL scheme for Safe Browsing: $url")
            return SafeBrowsingResult.Safe(url, fromCache = false)
        }

        // Check cache first
        val cachedResult = repository.getCachedResult(url)
        if (cachedResult != null && !cachedResult.isExpired) {
            Timber.d("$TAG: Returning cached result for $url (isSafe: ${cachedResult.isSafe})")
            return if (cachedResult.isSafe) {
                SafeBrowsingResult.Safe(url, fromCache = true)
            } else {
                SafeBrowsingResult.Threat(
                    url = url,
                    threatType = cachedResult.threatType ?: ThreatType.UNKNOWN,
                    fromCache = true
                )
            }
        }

        // Ensure SafetyNet is initialized
        if (!isInitialized) {
            val initSuccess = initialize()
            if (!initSuccess) {
                return SafeBrowsingResult.Error(
                    url = url,
                    message = "SafetyNet API not initialized"
                )
            }
        }

        // Perform API check
        return performSafetyNetCheck(url)
    }

    private suspend fun performSafetyNetCheck(url: String): SafeBrowsingResult {
        val apiKey = apiKeyProvider.getApiKey()
        if (apiKey.isBlank()) {
            Timber.e("$TAG: Safe Browsing API key is not configured")
            return SafeBrowsingResult.Error(
                url = url,
                message = "API key not configured"
            )
        }

        Timber.d("$TAG: API key length: ${apiKey.length}, starts with: ${apiKey.take(10)}...")

        return try {
            Timber.d("$TAG: Checking URL with SafetyNet: $url")

            val response = safetyNetClient.lookupUri(
                url,
                apiKey,
                SafeBrowsingThreat.TYPE_POTENTIALLY_HARMFUL_APPLICATION,
                SafeBrowsingThreat.TYPE_SOCIAL_ENGINEERING
            ).await()

            val detectedThreats = response.detectedThreats

            Timber.d("$TAG: API Response - Detected threats count: ${detectedThreats.size}")
            detectedThreats.forEachIndexed { index, threat ->
                Timber.d("$TAG: Threat $index - Type: ${threat.threatType}")
            }

            if (detectedThreats.isEmpty()) {
                // URL is safe
                Timber.d("$TAG: No threats detected for $url")
                repository.cacheResult(url, isSafe = true, threatType = null)
                SafeBrowsingResult.Safe(url, fromCache = false)
            } else {
                // Threat detected
                val threatType = mapThreatType(detectedThreats[0].threatType)
                Timber.w("$TAG: Threat detected for $url: $threatType")

                // Cache the threat result
                repository.cacheResult(url, isSafe = false, threatType = threatType)

                // Update statistics
                repository.incrementThreatsBlocked(url, timeProvider.currentTimeMillis())

                SafeBrowsingResult.Threat(
                    url = url,
                    threatType = threatType,
                    fromCache = false
                )
            }
        } catch (e: ApiException) {
            handleApiException(url, e)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Unexpected error checking URL: $url")
            SafeBrowsingResult.Error(
                url = url,
                exception = e,
                message = "Unexpected error: ${e.message}"
            )
        }
    }

    private suspend fun handleApiException(url: String, e: ApiException): SafeBrowsingResult {
        val statusCode = e.statusCode
        val errorMessage = CommonStatusCodes.getStatusCodeString(statusCode)

        Timber.e("$TAG: SafetyNet API error for $url: $errorMessage (code: $statusCode)")

        return when (statusCode) {
            CommonStatusCodes.API_NOT_CONNECTED -> {
                // Try to reinitialize once
                Timber.d("$TAG: API not connected, attempting to reinitialize")
                val initSuccess = initialize()
                if (initSuccess) {
                    // Retry the check once
                    performSafetyNetCheck(url)
                } else {
                    SafeBrowsingResult.Error(
                        url = url,
                        exception = e,
                        message = "SafetyNet API not connected"
                    )
                }
            }
            else -> {
                // For other errors, return error result but don't fail silently
                SafeBrowsingResult.Error(
                    url = url,
                    exception = e,
                    message = errorMessage
                )
            }
        }
    }

    private fun mapThreatType(safetyNetThreatType: Int): ThreatType {
        return when (safetyNetThreatType) {
            SafeBrowsingThreat.TYPE_POTENTIALLY_HARMFUL_APPLICATION -> ThreatType.MALWARE
            SafeBrowsingThreat.TYPE_SOCIAL_ENGINEERING -> ThreatType.PHISHING
            else -> ThreatType.UNKNOWN
        }
    }

    private fun isValidUrlScheme(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return lowerUrl.startsWith(URL_SCHEME_HTTP) || lowerUrl.startsWith(URL_SCHEME_HTTPS)
    }

    override fun isEnabled(): Boolean {
        // Use runBlocking for synchronous access
        // This is acceptable here as it's just a settings check
        return kotlinx.coroutines.runBlocking {
            repository.isEnabled()
        }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        Timber.d("$TAG: Setting Safe Browsing enabled: $enabled")
        repository.setEnabled(enabled)
    }

    override suspend fun clearCache() {
        Timber.d("$TAG: Clearing Safe Browsing cache")
        repository.clearCache()
    }

    override suspend fun getStatistics(): SafeBrowsingStatistics {
        return repository.getStatistics()
    }
}
