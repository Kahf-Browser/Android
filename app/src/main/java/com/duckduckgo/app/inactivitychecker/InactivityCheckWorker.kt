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

package com.duckduckgo.app.inactivitychecker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.duckduckgo.anvil.annotations.ContributesWorker
import com.duckduckgo.di.scopes.AppScope
import timber.log.Timber
import javax.inject.Inject
import androidx.lifecycle.LifecycleOwner
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.duckduckgo.app.analytics.AnalyticsEvent
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.lifecycle.MainProcessLifecycleObserver
import com.squareup.anvil.annotations.ContributesMultibinding
import java.util.concurrent.TimeUnit

@ContributesWorker(AppScope::class)
class InactivityCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @Inject
    lateinit var userActivityRepository: UserActivityRepository

    @Inject
    lateinit var notificationHelper: InactivityNotificationHelper

    @Inject
    lateinit var analyticsService: AnalyticsService

    override suspend fun doWork(): Result {
        Timber.d("iuLog Checking user inactivity")

        if (userActivityRepository.isInactiveForDays(7)
            && userActivityRepository.shownInactivityNotificationsCount() < 3) {

            notificationHelper.showInactivityNotification()
            userActivityRepository.increaseNotificationCount()

            // log to GA
            analyticsService.logEvent(AnalyticsEvent.InactiveFor7Days)
        }

        return Result.success()
    }
}

@ContributesMultibinding(
    scope = AppScope::class,
    boundType = MainProcessLifecycleObserver::class
)
class InactivityCheckScheduler @Inject constructor(
    private val workManager: WorkManager
) : MainProcessLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        scheduleInactivityCheck()
    }

    private fun scheduleInactivityCheck() {
        Timber.d("iuLog Scheduling inactivity check worker")

        workManager.cancelUniqueWork(WORK_NAME)

        val workRequest = PeriodicWorkRequestBuilder<InactivityCheckWorker>(
            1, TimeUnit.DAYS,
        )
            .addTag("inactivityChecker")
            .setInitialDelay(1, TimeUnit.HOURS) // Add some initial delay to spread out job execution
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE, // Use REPLACE instead of KEEP
            workRequest
        )
    }

    companion object {
        private const val WORK_NAME = "inactivity_check_worker"
    }
}
