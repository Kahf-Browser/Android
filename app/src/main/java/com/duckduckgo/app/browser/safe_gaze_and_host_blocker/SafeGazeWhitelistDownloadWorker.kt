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

package com.duckduckgo.app.browser.safe_gaze_and_host_blocker

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.duckduckgo.anvil.annotations.ContributesWorker
import com.duckduckgo.app.lifecycle.MainProcessLifecycleObserver
import com.duckduckgo.app.trackerdetection.db.SafeGazeWhitelistDao
import com.duckduckgo.app.trackerdetection.db.SafeGazeWhitelistEntity
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.SAFE_GAZE_WHITELIST
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesMultibinding
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@ContributesWorker(AppScope::class)
class SafeGazeWhitelistDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Inject
    lateinit var whitelistDao: SafeGazeWhitelistDao

    override suspend fun doWork(): Result {
        return withContext(dispatchers.io()) {
            try {
                downloadAndSaveWhitelist()
                Timber.i("wlLog Whitelist downloaded successfully.")

                Result.success()
            } catch (e: Exception) {
                Timber.e(e, "wlLog Error downloading SafeGaze whitelist")
                Result.failure()
            }
        }
    }

    private fun downloadAndSaveWhitelist() {
        Timber.i("wlLog Downloading SafeGaze whitelist")

        val hostsByteArray = URL(SAFE_GAZE_WHITELIST).readBytes()
        val hosts = String(hostsByteArray)
        hosts.split("\n").forEach {
            if (it.isNotBlank()) {
                whitelistDao.insert(SafeGazeWhitelistEntity(host = "www.$it"))
            }
        }
    }
}

// The following annotation automatically includes SafeGazeWhitelistDownloadWorkerScheduler in the lifecycle observers.
// [DuckDuckGoApplication::onMainProcessCreate] handles all lifecycle observers with
// ProcessLifecycleOwner.get().lifecycle.primaryLifecycleObserverPluginPoint.addObservers()
@ContributesMultibinding(
    scope = AppScope::class,
    boundType = MainProcessLifecycleObserver::class,
)
class SafeGazeWhitelistDownloadWorkerScheduler @Inject constructor(
    private val workManager: WorkManager,
) : MainProcessLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        Timber.v("wlLog Scheduling SafeGaze whitelist download worker")

        val workerRequest = PeriodicWorkRequestBuilder<SafeGazeWhitelistDownloadWorker>(24, TimeUnit.HOURS)
            .addTag(WHITELIST_DOWNLOAD_WORKER_TAG)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.SECONDS) // Add initial delay to stagger job execution
            .build()

        workManager.cancelUniqueWork(WHITELIST_DOWNLOAD_WORKER_TAG)

        workManager.enqueueUniquePeriodicWork(
            WHITELIST_DOWNLOAD_WORKER_TAG,
            ExistingPeriodicWorkPolicy.REPLACE,
            workerRequest
        )
    }

    companion object {
        private const val WHITELIST_DOWNLOAD_WORKER_TAG = "WHITELIST_DOWNLOAD_WORKER_TAG"
    }
}
