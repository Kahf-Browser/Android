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

package com.duckduckgo.app.analytics

sealed class AnalyticsEvent(val name: String) {
    data object NewTabOpened : AnalyticsEvent("new_tab_opened")
    data object PageBlocked : AnalyticsEvent("page_blocked")
    data object SettingsClicked : AnalyticsEvent("settings_clicked")
    data object SafeInternetToggled : AnalyticsEvent("safe_internet_toggled")
    data object DecentInternetToggled : AnalyticsEvent("decent_internet_toggled")
    data object PrayerTimeOpened : AnalyticsEvent("prayer_time_opened")
    data object SetAsDefaultBrowser : AnalyticsEvent("set_as_default_browser")
    data object GoogleSearchPerformed : AnalyticsEvent("google_search_performed")
}

sealed class AnalyticsParam(val name: String) {
    data object Url : AnalyticsParam("url")
    data object IsEnabled : AnalyticsParam("enabled")
    data object Mode : AnalyticsParam("safe_internet_mode")
    data object SearchQuery : AnalyticsParam("search_query")
}
