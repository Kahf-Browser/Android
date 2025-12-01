package com.duckduckgo.app.browser.socialmedia

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Manages social media platform navigation and tab tracking
 * Ensures we don't open new tabs if one already exists for the social media platform
 */
class SocialMediaManager {

    // Map of social media platform to tab ID
    private val socialMediaTabMap = mutableMapOf<SocialMediaPlatform, String>()

    private val _navigateToTab = MutableLiveData<NavigateToTabEvent>()
    val navigateToTab: LiveData<NavigateToTabEvent> = _navigateToTab

    enum class SocialMediaPlatform(val url: String, val domain: String) {
        FACEBOOK("https://www.facebook.com", "facebook.com"),
        TWITTER("https://twitter.com", "twitter.com"),
        INSTAGRAM("https://www.instagram.com", "instagram.com"),
        YOUTUBE("https://www.youtube.com", "youtube.com"),
        LINKEDIN("https://www.linkedin.com", "linkedin.com")
    }

    data class NavigateToTabEvent(
        val tabId: String?,
        val url: String,
        val createNewTab: Boolean
    )

    /**
     * Register a tab as containing a social media platform
     */
    fun registerSocialMediaTab(url: String, tabId: String) {
        val platform = detectPlatform(url)
        if (platform != null) {
            socialMediaTabMap[platform] = tabId
        }
    }

    /**
     * Unregister a tab (when it's closed or navigates away from social media)
     */
    fun unregisterSocialMediaTab(tabId: String) {
        val platformToRemove = socialMediaTabMap.entries.find { it.value == tabId }?.key
        if (platformToRemove != null) {
            socialMediaTabMap.remove(platformToRemove)
        }
    }

    /**
     * Check if URL contains a social media platform
     */
    fun isSocialMediaUrl(url: String): Boolean {
        return detectPlatform(url) != null
    }

    /**
     * Detect which social media platform a URL belongs to
     */
    private fun detectPlatform(url: String): SocialMediaPlatform? {
        val lowerUrl = url.lowercase()
        return SocialMediaPlatform.values().find { platform ->
            lowerUrl.contains(platform.domain)
        }
    }

    /**
     * Navigate to a social media platform
     * Returns the tab ID if one exists, or null if a new tab should be created
     */
    fun navigateToSocialMedia(platform: SocialMediaPlatform) {
        val existingTabId = socialMediaTabMap[platform]

        _navigateToTab.value = NavigateToTabEvent(
            tabId = existingTabId,
            url = platform.url,
            createNewTab = existingTabId == null
        )
    }

    /**
     * Clear all tracked social media tabs (e.g., when clearing all tabs)
     */
    fun clearAll() {
        socialMediaTabMap.clear()
    }

    /**
     * Get the current tab ID for a platform, if any
     */
    fun getTabForPlatform(platform: SocialMediaPlatform): String? {
        return socialMediaTabMap[platform]
    }

    /**
     * Get all currently tracked social media tabs
     */
    fun getAllSocialMediaTabs(): Map<SocialMediaPlatform, String> {
        return socialMediaTabMap.toMap()
    }
}
