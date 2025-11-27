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

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.duckduckgo.safebrowsing.api.SafeBrowsingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Lifecycle observer for Safe Browsing feature
 *
 * Manages SafetyNet API lifecycle according to best practices:
 * - Initialize in onResume()
 * - Shutdown in onPause()
 *
 * This ensures the SafetyNet session stays fresh and reduces internal errors.
 */
class SafeBrowsingLifecycleObserver(
    private val safeBrowsingManager: SafeBrowsingManager,
    private val coroutineScope: CoroutineScope
) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "SafeBrowsingLifecycle"
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        Timber.d("$TAG: Lifecycle onResume - initializing Safe Browsing")
        coroutineScope.launch {
            safeBrowsingManager.initialize()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        Timber.d("$TAG: Lifecycle onPause - shutting down Safe Browsing")
        coroutineScope.launch {
            safeBrowsingManager.shutdown()
        }
    }
}
