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

package com.duckduckgo.app.downloads

import com.duckduckgo.app.analytics.AnalyticsEvent
import com.duckduckgo.app.analytics.AnalyticsParam
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.downloads.api.DownloadAnalyticsListener
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import javax.inject.Inject

@ContributesBinding(AppScope::class)
@SingleInstanceIn(AppScope::class)
class RealDownloadAnalyticsListener @Inject constructor(
    private val analyticsService: AnalyticsService,
) : DownloadAnalyticsListener {

    override fun onDownloadStarted(fileName: String) {
        analyticsService.logEvent(
            AnalyticsEvent.DownloadStarted,
            mapOf(AnalyticsParam.DownloadFileName to fileName),
        )
    }

    override fun onDownloadCompleted(fileName: String) {
        analyticsService.logEvent(
            AnalyticsEvent.DownloadCompleted,
            mapOf(AnalyticsParam.DownloadFileName to fileName),
        )
    }

    override fun onDownloadFailed(reason: String) {
        analyticsService.logEvent(
            AnalyticsEvent.DownloadFailed,
            mapOf(AnalyticsParam.DownloadFailReason to reason),
        )
    }

    override fun onDownloadPaused() {
        analyticsService.logEvent(AnalyticsEvent.DownloadPaused)
    }

    override fun onDownloadResumed() {
        analyticsService.logEvent(AnalyticsEvent.DownloadResumed)
    }

    override fun onDownloadCancelledFromNotification() {
        analyticsService.logEvent(AnalyticsEvent.DownloadCancelledFromNotification)
    }

    override fun onDownloadRetriedFromNotification() {
        analyticsService.logEvent(AnalyticsEvent.DownloadRetriedFromNotification)
    }

    override fun onDownloadConfirmationShown() {
        analyticsService.logEvent(AnalyticsEvent.DownloadConfirmationShown)
    }

    override fun onDownloadConfirmationAccepted() {
        analyticsService.logEvent(AnalyticsEvent.DownloadConfirmationAccepted)
    }

    override fun onDownloadConfirmationCancelled() {
        analyticsService.logEvent(AnalyticsEvent.DownloadConfirmationCancelled)
    }

    override fun onDownloadLocationChanged() {
        analyticsService.logEvent(AnalyticsEvent.DownloadLocationChanged)
    }
}
