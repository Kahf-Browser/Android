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

package com.duckduckgo.app.analytics

import com.posthog.PostHog

class PostHogAnalyticsService(): AnalyticsService {

    override fun logEvent(
        event: AnalyticsEvent,
        params: Map<AnalyticsParam, String>?
    ) {
        PostHog.capture(
            event = event.name,
            properties = params?.mapKeys { it.key.name }?.mapValues { it.value }
        )
    }

    override fun setUserProperty(
        propertyName: String,
        value: String
    ) {
        // PostHog does not support setting user properties directly
    }

    override fun setUserId(userId: String) {
        PostHog.identify(distinctId = userId)
    }
}
