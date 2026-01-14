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
    data object SocialMediaMenuOpened : AnalyticsEvent("social_media_menu_opened")
    data object InactiveFor7Days : AnalyticsEvent("inactive_for_7_days")

    // Private DNS (Safe Internet)
    data object PrivateDnsDisable : AnalyticsEvent("private_dns_disable")
    data object PrivateDnsHigh : AnalyticsEvent("private_dns_high")
    data object PrivateDnsMedium : AnalyticsEvent("private_dns_medium")
    data object PrivateDnsLow : AnalyticsEvent("private_dns_low")
    data object AvgDnsResolutionTime : AnalyticsEvent("avg_dns_resolution_time")
    data object DnsLookupTime : AnalyticsEvent("bottleneck_dns_lookup_time")
    data object AvgKahfGuardResponseTime : AnalyticsEvent("avg_kahf_guard_response_time")
    data object DnsTimeoutError : AnalyticsEvent("dns_timeout_error")
    data object DnsErrorLog : AnalyticsEvent("dns_error_log")
    data object DefaultBrowserCheck : AnalyticsEvent("browser_default_still_active")

    // Image Filter (Decent Internet)
    data object ImageFilerEnable : AnalyticsEvent("image_filter_enable")
    data object ImageFilerDisable : AnalyticsEvent("image_filter_disable")
    data object P90ImageProcessing : AnalyticsEvent("p90_image_processing")
    data object P90DnsResolution : AnalyticsEvent("p90_dns_resolution")
    data object AvgQueueTime : AnalyticsEvent("avg_queue_time")
    data object ImageProcessingTimeout : AnalyticsEvent("image_processing_timeout")
    data object ModelInitTime : AnalyticsEvent("model_initialization_time")

    data object P90NSFWProcessing: AnalyticsEvent("p90_nsfw_processing")
    data object P90CoreInference: AnalyticsEvent("p90_core_inference")
    data object P90ApplySolidMask: AnalyticsEvent("p90_apply_solid_mask")
    data object P90ApplyPixelationMask: AnalyticsEvent("p90_apply_pixelation_mask")
    data object ColdStartToModelInitTime: AnalyticsEvent("cold_start_to_model_init_time")
    data object VideoFrameProcessorInitTime: AnalyticsEvent("video_frame_processor_init_time")

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

    data object NewTabBannerAdImpression : AnalyticsEvent("new_tab_kahf_banner_ad_impression")
    data object NewTabBannerAdClicked : AnalyticsEvent("new_tab_kahf_banner_ad_clicked")
    // Kafh Ad
    data object SearchAutocompleteBannerAdClicked : AnalyticsEvent("search_auto_complete_kahf_banner_ad_clicked")
    data object SearchAutoCompleteBannerAdImpression : AnalyticsEvent("search_auto_complete_kahf_banner_ad_impression")
    data object QuickAccessPageBannerAdImpression : AnalyticsEvent("quick_access_page_kahf_banner_ad_impression")
    data object QuickAccessPageBannerAdClicked : AnalyticsEvent("quick_access_page_kahf_banner_ad_clicked")
    data object BannerAdLoadFailed : AnalyticsEvent("kahf_banner_ad_failed_to_load")
    data object AdNotFound : AnalyticsEvent("kahf_ad_not_found")
    data object AdServerError : AnalyticsEvent("kahf_ad_server_error")
    data object AdTimeout : AnalyticsEvent("kahf_ad_timeout")

    //bookmark
    data object BookmarkAdded : AnalyticsEvent("bookmark_added")
    data object BookmarkOpened : AnalyticsEvent("bookmark_opened")

    //tab
    data object TabClosed : AnalyticsEvent("tab_closed")
    data object MultipleTabsOpened : AnalyticsEvent("multiple_tabs_opened")
    data object TabSwitched : AnalyticsEvent("tab_switch")

    data object LightTheme : AnalyticsEvent("light_theme_enabled")
    data object DarkTheme : AnalyticsEvent("dark_theme_enabled")
    data object SystemDefaultTheme : AnalyticsEvent("system_theme_enabled")
}

sealed class AnalyticsParam(val name: String) {
    data object Url : AnalyticsParam("url")
    data object Error : AnalyticsParam("error")
    data object TimedOutImageUrl : AnalyticsParam("timed_out_image_url")
    data object DnsResolutionTime : AnalyticsParam("dns_resolution_time_ms")
    data object ImageProcessingTime : AnalyticsParam("image_processing_time_ms")
    data object AvgQueueTimeMs : AnalyticsParam("avg_queue_time_ms")
    data object DnsResolver : AnalyticsParam("dns_resolver")
    data object ModelInitTimeMS : AnalyticsParam("model_initialization_time_ms")
    data object AvgResolutionTimeMs : AnalyticsParam("avg_resolution_time_ms")
    data object DnsLookupTimeMs : AnalyticsParam("bottleneck_dns_lookup_time_ms")
    data object AvgKahfGuardTimeMs : AnalyticsParam("avg_kahf_guard_time_ms")

    data object NSFWProcessingTime : AnalyticsParam("nsfw_processing_time_ms")
    data object CoreInferenceTime : AnalyticsParam("core_inference_time_ms")
    data object ApplySolidMaskTime : AnalyticsParam("apply_solid_mask_time_ms")
    data object ApplyPixelationMaskTime : AnalyticsParam("apply_pixelation_mask_time_ms")
    data object ColdStartToModelInitTimeMs : AnalyticsParam("cold_start_to_model_init_time_ms")
    data object VideoFrameProcessorInitTimeMs : AnalyticsParam("video_frame_processor_init_time_ms")
    data object LaunchType : AnalyticsParam("launch_type")

    // Search
    data object SuggestionSearchEngine : AnalyticsParam("autosuggestion_search_engine")
    data object QuerySearchEngine : AnalyticsParam("query_enter_search_engine")
    data object ExtSearchEngine : AnalyticsParam("external_search_engine")
    data object ResultClickSearchEngine : AnalyticsParam("search_result_link_click_search_engine")
}
