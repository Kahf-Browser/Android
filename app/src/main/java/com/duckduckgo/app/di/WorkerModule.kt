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
        val processName = getCurrentProcessName(context)
        val isMainProcess = processName == context.packageName

        Timber.d("WorkManager initialization requested in process: $processName (isMain=$isMainProcess)")

        if (!isMainProcess) {
            // In non-main processes, initialize WorkManager with a minimal configuration
            // This avoids registering unnecessary network callbacks
            val minimalConfig = Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(android.util.Log.ERROR)
                .setMaxSchedulerLimit(20) // Limit concurrent work to prevent TooManyRequestsException
                .build()

            try {
                WorkManager.initialize(context, minimalConfig)
                Timber.d("Initialized minimal WorkManager in process: $processName")
            } catch (ignored: IllegalStateException) {
                // WorkManager might already be initialized by another thread, which is safe to ignore
                Timber.d("WorkManager already initialized in process: $processName")
            }
        } else {
            // In the main process, initialize WorkManager with the full configuration
            // Limit the maximum number of scheduled jobs to prevent ConnectivityManager.TooManyRequestsException
            // This exception occurs when too many network callbacks are registered (Android limit is ~100-200)
            val config = Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMaxSchedulerLimit(20) // Reduced from 50 to 20 to prevent TooManyRequestsException
                .setMinimumLoggingLevel(if (timber.log.Timber.treeCount > 0) android.util.Log.DEBUG else android.util.Log.INFO)
                .build()

            try {
                WorkManager.initialize(context, config)
                Timber.d("WorkManager initialized successfully in main process with maxSchedulerLimit=20")
            } catch (e: IllegalStateException) {
                // This is expected if WorkManager is already initialized, so we can safely ignore it
                Timber.d("WorkManager already initialized in main process (safe to ignore)")
            } catch (e: Exception) {
                // Catch any other exceptions during initialization to prevent crashes
                Timber.e(e, "Failed to initialize WorkManager with standard config")
            }
        }

        // Get WorkManager instance with comprehensive exception handling
        // NOTE: Do NOT call pruneWork() synchronously here as it blocks the main thread and causes ANR
        // Pruning should be done asynchronously in the Application.onCreate() method instead
        return try {
            WorkManager.getInstance(context)
        } catch (e: RuntimeException) {
            // Check if this is a TooManyRequestsException (android.net.ConnectivityManager$TooManyRequestsException)
            // We check by class name because the exception class may not be available at compile time
            val isTooManyRequestsException = e.javaClass.name.contains("TooManyRequestsException")

            if (isTooManyRequestsException) {
                // This happens when WorkManager tries to register too many network callbacks
                // Android has a system limit of ~100-250 callbacks per UID depending on API level
                Timber.e(e, "TooManyRequestsException: Network callback limit exceeded. Attempting recovery...")

                // Recovery strategy: Clear all work and reinitialize with minimal configuration
                try {
                    // Force-clear the WorkManager database to remove all pending work
                    val workManager = WorkManager.getInstance(context)
                    workManager.cancelAllWork()
                    workManager.pruneWork()
                    Timber.d("Cleared all work to recover from TooManyRequestsException")
                } catch (clearException: Exception) {
                    Timber.e(clearException, "Failed to clear work during TooManyRequestsException recovery")

                    // Last resort: Reinitialize with zero scheduled work limit
                    try {
                        val minimalConfig = Configuration.Builder()
                            .setWorkerFactory(workerFactory)
                            .setMaxSchedulerLimit(0) // Disable all scheduled work
                            .setMinimumLoggingLevel(android.util.Log.ERROR)
                            .build()

                        WorkManager.initialize(context, minimalConfig)
                        Timber.w("Reinitialized WorkManager with zero scheduled work limit as last resort")
                    } catch (reinitException: Exception) {
                        Timber.e(reinitException, "Failed to reinitialize WorkManager - app may have stability issues")
                    }
                }

                // Return the WorkManager instance (may have reduced functionality)
                WorkManager.getInstance(context)
            } else {
                // Re-throw if it's a different RuntimeException
                throw e
            }
        } catch (e: Exception) {
            // Catch any other unexpected exceptions to prevent app crash
            Timber.e(e, "Unexpected exception while getting WorkManager instance")
            WorkManager.getInstance(context)
        }
    }

    /**
     * Get current process name safely across all Android versions
     */
    private fun getCurrentProcessName(context: Context): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            // Fallback for older Android versions
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.find { it.pid == Process.myPid() }?.processName ?: context.packageName
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
