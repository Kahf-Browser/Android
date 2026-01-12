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

    /**
     * Provides WorkManager instance using on-demand initialization.
     *
     * IMPORTANT: This provider does NOT call WorkManager.initialize() directly.
     * Instead, the Application class implements Configuration.Provider to provide
     * the configuration when WorkManager is first accessed. This defers the heavy
     * initialization (NetworkStateTracker, etc.) off the main thread startup path.
     *
     * The actual initialization happens lazily when WorkManager.getInstance() is called,
     * and the Application provides the Configuration via getWorkManagerConfiguration().
     */
    @Provides
    @SingleInstanceIn(AppScope::class)
    fun workManager(context: Context): WorkManager {
        Timber.d("WorkManager: Providing lazy instance (on-demand initialization)")
        // WorkManager will use on-demand initialization via Configuration.Provider
        // implemented in DuckDuckGoApplication
        return WorkManager.getInstance(context)
    }

    /**
     * Provides the WorkManager Configuration.
     * This is used by DuckDuckGoApplication's getWorkManagerConfiguration() method
     * for on-demand initialization.
     */
    @Provides
    @SingleInstanceIn(AppScope::class)
    fun workManagerConfiguration(workerFactory: WorkerFactory): Configuration {
        Timber.d("WorkManager: Creating configuration with custom WorkerFactory")
        return Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMaxSchedulerLimit(20) // Limit concurrent work to prevent TooManyRequestsException
            .setMinimumLoggingLevel(
                if (timber.log.Timber.treeCount > 0) android.util.Log.DEBUG
                else android.util.Log.INFO
            )
            .build()
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun workerFactory(
        workerInjectorPluginPoint: PluginPoint<WorkerInjectorPlugin>,
    ): WorkerFactory {
        return DaggerWorkerFactory(workerInjectorPluginPoint)
    }
}
