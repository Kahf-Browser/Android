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
    data object PrayerTimeOpened : AnalyticsEvent("prayer_time_opened")
    data object InactiveFor7Days : AnalyticsEvent("inactive_for_7_days")

    // Private DNS (Safe Internet)
    data object PrivateDnsDisable : AnalyticsEvent("private_dns_disable")
    data object PrivateDnsHigh : AnalyticsEvent("private_dns_high")
    data object PrivateDnsMedium : AnalyticsEvent("private_dns_medium")
    data object PrivateDnsLow : AnalyticsEvent("private_dns_low")

    // Image Filter (Decent Internet)
    data object ImageFilerEnable : AnalyticsEvent("image_filter_enable")
    data object ImageFilerDisable : AnalyticsEvent("image_filter_disable")
    data object P90ImageProcessing : AnalyticsEvent("p90_image_processing")
    data object P90DnsResolution : AnalyticsEvent("p90_dns_resolution")
    data object AvgQueueTime : AnalyticsEvent("avg_queue_time")
    data object ImageProcessingTimeout : AnalyticsEvent("image_processing_timeout")
    data object ModelInitTime : AnalyticsEvent("model_initialization_time")

    // Onboarding
    data object OnboardSetAsDefaultBrowser : AnalyticsEvent("onboard_set_as_default_browser")
    data object OnboardSkipDefaultBrowser : AnalyticsEvent("onboard_skip_default_browser")
    data object OnboardSetBookmarks : AnalyticsEvent("onboard_set_bookmarks")
    data object OnboardSkipBookmarks : AnalyticsEvent("onboard_skip_bookmarks")
    data object OnboardEnabledDecentInternet : AnalyticsEvent("onboard_enable_decent_internet")
    data object OnboardSkipDecentInternet : AnalyticsEvent("onboard_skip_decent_internet")

    // Search
    data object AddressBarSuggestionSelection : AnalyticsEvent("address_bar_suggestion_selection")
    data object SearchQueryEnter : AnalyticsEvent("search_query_enter")
    data object SearchResultLinkClick : AnalyticsEvent("search_result_link_click")
    data object ExtSearchRedirect : AnalyticsEvent("external_search_redirect")
}

sealed class AnalyticsParam(val name: String) {
    data object Url : AnalyticsParam("url")
    data object TimedOutImageUrl : AnalyticsParam("timed_out_image_url")
    data object DnsResolutionTime : AnalyticsParam("dns_resolution_time_ms")
    data object ImageProcessingTime : AnalyticsParam("image_processing_time_ms")
    data object AvgQueueTimeMs : AnalyticsParam("avg_queue_time_ms")
    data object DnsResolver : AnalyticsParam("dns_resolver")
    data object ModelInitTimeMS : AnalyticsParam("model_initialization_time_ms")

    // Search
    data object SuggestionSearchEngine : AnalyticsParam("autosuggestion_search_engine")
    data object QuerySearchEngine : AnalyticsParam("query_enter_search_engine")
    data object ExtSearchEngine : AnalyticsParam("external_search_engine")
    data object ResultClickSearchEngine : AnalyticsParam("search_result_link_click_search_engine")
}
