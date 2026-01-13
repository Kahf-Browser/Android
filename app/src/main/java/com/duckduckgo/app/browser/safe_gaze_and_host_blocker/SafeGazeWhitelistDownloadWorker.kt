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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.net.URL
import java.net.UnknownHostException
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

    // OkHttp client with sane timeouts
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(dispatchers.io()) {
        try {
            downloadAndSaveWhitelist()
            Timber.i("wlLog Whitelist downloaded successfully.")
            Result.success()
        } catch (e: UnknownHostException) {
            Timber.e(e, "wlLog DNS resolution failed. Possibly no internet or host blocked.")
            Result.retry() // Retry instead of permanent failure
        } catch (e: IOException) {
            Timber.e(e, "wlLog Network IO error while downloading whitelist")
            Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "wlLog Unexpected error downloading SafeGaze whitelist")
            Result.failure()
        }
    }

    private fun downloadAndSaveWhitelist() {
        Timber.i("wlLog Downloading SafeGaze whitelist from $SAFE_GAZE_WHITELIST")

        val request = Request.Builder()
            .url(SAFE_GAZE_WHITELIST)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected HTTP ${response.code}")

            val body = response.body?.string() ?: throw IOException("Empty response body")

            whitelistDao.deleteAll() // Optional — keep whitelist fresh
            body.lineSequence()
                .filter { it.isNotBlank() }
                .forEach { host ->
                    whitelistDao.insert(SafeGazeWhitelistEntity(host = "www.$host"))
                }
        }
    }
}


/**
 * Schedules periodic SafeGaze whitelist downloads using WorkManager.
 *
 * PERFORMANCE FIX: Uses Lazy<WorkManager> to defer WorkManager initialization until after
 * DI is complete. This prevents ANR during app startup caused by NetworkStateTracker
 * initialization on the main thread.
 *
 * The following annotation automatically includes SafeGazeWhitelistDownloadWorkerScheduler in the lifecycle observers.
 * [DuckDuckGoApplication::onMainProcessCreate] handles all lifecycle observers with
 * ProcessLifecycleOwner.get().lifecycle.primaryLifecycleObserverPluginPoint.addObservers()
 */
@ContributesMultibinding(
    scope = AppScope::class,
    boundType = MainProcessLifecycleObserver::class,
)
class SafeGazeWhitelistDownloadWorkerScheduler @Inject constructor(
    private val workManagerLazy: dagger.Lazy<WorkManager>,
) : MainProcessLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        Timber.v("wlLog Scheduling SafeGaze whitelist download worker")

        val workerRequest = PeriodicWorkRequestBuilder<SafeGazeWhitelistDownloadWorker>(24, TimeUnit.HOURS)
            .addTag(WHITELIST_DOWNLOAD_WORKER_TAG)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
            .setInitialDelay(15, TimeUnit.SECONDS) // Add initial delay to stagger job execution
            .setConstraints(constraints)
            .build()

        val workManager = workManagerLazy.get()
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
