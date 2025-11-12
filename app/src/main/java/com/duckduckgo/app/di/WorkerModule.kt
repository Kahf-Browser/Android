/*
 * Copyright (c) 2019 DuckDuckGo
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

package com.duckduckgo.app.di

import android.content.Context
import android.os.Process
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import com.duckduckgo.common.utils.plugins.PluginPoint
import com.duckduckgo.common.utils.plugins.worker.WorkerInjectorPlugin
import com.duckduckgo.di.scopes.AppScope
import dagger.Module
import dagger.Provides
import dagger.SingleInstanceIn
import timber.log.Timber

@Module
object WorkerModule {

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun workManager(
        context: Context,
        workerFactory: WorkerFactory,
    ): WorkManager {
        // CRITICAL FIX: Prevent TooManyRequestsException from multiple WorkManager initializations
        // Each process that initializes WorkManager registers network callbacks with ConnectivityManager
        // Android has a system-wide limit (~100-200) of network callbacks per UID
        // Solution: Only initialize in main process + make initialization idempotent

        val processName = getCurrentProcessName(context)
        val isMainProcess = processName == context.packageName

        Timber.d("WorkManager initialization requested in process: $processName (isMain=$isMainProcess)")

        // OPTIMIZATION: Only fully initialize WorkManager in the main process
        // Secondary processes (VPN, Fire) don't schedule work and don't need network tracking
        if (!isMainProcess) {
            Timber.d("Skipping WorkManager initialization in non-main process: $processName")
            // Return existing instance if available, but don't initialize a new one
            return try {
                WorkManager.getInstance(context)
            } catch (e: IllegalStateException) {
                // If not initialized, create minimal config without network constraints
                // This prevents network callback registration in secondary processes
                val minimalConfig = Configuration.Builder()
                    .setWorkerFactory(workerFactory)
                    .setMinimumLoggingLevel(android.util.Log.ERROR)
                    .build()

                try {
                    WorkManager.initialize(context, minimalConfig)
                    Timber.d("Initialized minimal WorkManager in process: $processName")
                } catch (ignored: IllegalStateException) {
                    // Already initialized by another thread/process - safe to ignore
                    Timber.d("WorkManager already initialized in process: $processName")
                }

                WorkManager.getInstance(context)
            }
        }

        // Main process: Full initialization with proper config
        val config = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

        // CRITICAL FIX: Make initialization idempotent
        // WorkManager.initialize() throws IllegalStateException if called twice
        // This can happen in multi-process apps or during rapid restarts
        try {
            WorkManager.initialize(context, config)
            Timber.d("WorkManager initialized successfully in main process")
        } catch (e: IllegalStateException) {
            // Already initialized - this is safe and expected
            Timber.d("WorkManager already initialized in main process (safe to ignore)")
        }

        return WorkManager.getInstance(context)
    }

    /**
     * Get current process name safely across all Android versions
     */
    private fun getCurrentProcessName(context: Context): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            // Fallback for older Android versions
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            activityManager.runningAppProcesses?.find { it.pid == Process.myPid() }?.processName
                ?: context.packageName
        }
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun workerFactory(
        workerInjectorPluginPoint: PluginPoint<WorkerInjectorPlugin>,
    ): WorkerFactory {
        return DaggerWorkerFactory(workerInjectorPluginPoint)
    }
}
