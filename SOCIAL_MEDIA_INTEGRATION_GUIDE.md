# Social Media Integration Guide

## Overview
This document describes the social media feature integration that adds quick access to social media platforms with intelligent tab management.

## Features Implemented

### 1. **Top Social Media Bar with Statistics**
- **Location**: New Browser Tab screen
- **Layout**: `include_social_media_bar.xml`
- **Design**: Translucent rounded rectangle containing:
  - **Left half**: Social media icons (Facebook, Twitter, Instagram, YouTube, WhatsApp)
  - **Right half**: Statistics (Sites, Images, Trackers) - 50% smaller than original

### 2. **Bottom Navigation Social Media Button**
- **Location**: Bottom navigation bar
- **Changes**: Replaced `timeMenuItem` (masjid/prayer icon) with `socialMediaMenuItem`
- **Icon**: `ic_social_media_24.xml` - Combined social media icon
- **Action**: Opens social media platform selector dialog

### 3. **Social Media Selector Dialog**
- **Layout**: `dialog_social_media_selector.xml`
- **Design**: Translucent popup with grid of social media icons
- **Platforms**: Facebook, Twitter, Instagram, YouTube, WhatsApp

### 4. **Intelligent Tab Management**
- **Class**: `SocialMediaManager.kt`
- **Functionality**:
  - Tracks which tabs contain social media platforms
  - When clicking a social media icon, switches to existing tab if available
  - Only creates new tab if platform not already open
  - Automatically registers/unregisters tabs as they navigate

## Files Created

### Drawables
1. `ic_social_media_24.xml` - Combined social media icon for bottom nav
2. `ic_facebook_24.xml` - Facebook icon
3. `ic_twitter_24.xml` - Twitter/X icon
4. `ic_instagram_24.xml` - Instagram icon
5. `ic_youtube_24.xml` - YouTube icon
6. `ic_whatsapp_24.xml` - WhatsApp icon
7. `social_media_bar_bg.xml` - Translucent rounded background

### Layouts
1. `include_social_media_bar.xml` - Top bar with social media icons + stats
2. `dialog_social_media_selector.xml` - Social media platform selector dialog

### Kotlin Classes
1. `SocialMediaManager.kt` - Tab tracking and navigation logic
2. `SocialMediaDialog.kt` - Dialog helper class

### Modified Files
1. `include_new_browser_tab.xml` - Replaced old statistics with new bar
2. `include_browser_bottom_nav.xml` - Replaced timeMenuItem with socialMediaMenuItem
3. `strings.xml` - Added social media platform names and short stat labels

## Integration in BrowserTabFragment.kt

### Required Changes:

1. **Add SocialMediaManager instance**
```kotlin
private val socialMediaManager = SocialMediaManager()
private var socialMediaDialog: SocialMediaDialog? = null
```

2. **Update bottom navigation setup** (around line 2720-2744)
Replace `timeMenuItem` references with `socialMediaMenuItem`:

```kotlin
bottomNav.apply {
    if (viewState.browserShowing) {
        backMenuItem.visibility = VISIBLE
        forwardMenuItem.visibility = VISIBLE
        homeMenuItem.visibility = GONE
        socialMediaMenuItem.visibility = GONE

        // ... existing code ...
    } else {
        backMenuItem.visibility = GONE
        forwardMenuItem.visibility = GONE
        homeMenuItem.visibility = VISIBLE
        socialMediaMenuItem.visibility = VISIBLE

        socialMediaMenuItem.setOnClickListener {
            showSocialMediaDialog()
        }
    }
}
```

3. **Add social media dialog method**
```kotlin
private fun showSocialMediaDialog() {
    if (socialMediaDialog?.isShowing() == true) return

    socialMediaDialog = SocialMediaDialog(requireContext()) { platform ->
        handleSocialMediaNavigation(platform)
    }
    socialMediaDialog?.show()
}

private fun handleSocialMediaNavigation(platform: SocialMediaManager.SocialMediaPlatform) {
    socialMediaManager.navigateToSocialMedia(platform)
}
```

4. **Observe social media navigation events**
Add in `onViewCreated()` or similar:

```kotlin
socialMediaManager.navigateToTab.observe(viewLifecycleOwner) { event ->
    if (event.tabId != null) {
        // Switch to existing tab
        tabsButton?.callOnClick() // Open tab switcher
        // Then programmatically select the tab with event.tabId
    } else if (event.createNewTab) {
        // Create new tab and load URL
        viewModel.onNewTabRequested()
        webView?.loadUrl(event.url)
    }
}
```

5. **Track social media tabs**
In the WebView navigation handling code (where URLs are loaded):

```kotlin
webView?.let { webView ->
    webView.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)

            // Register social media tab if navigating to social media
            url?.let { currentUrl ->
                val tabId = getCurrentTabId() // Get current tab ID
                if (socialMediaManager.isSocialMediaUrl(currentUrl)) {
                    socialMediaManager.registerSocialMediaTab(currentUrl, tabId)
                }
            }
        }
    }
}
```

6. **Unregister tabs when closed**
In tab closing logic:

```kotlin
fun onTabClosed(tabId: String) {
    socialMediaManager.unregisterSocialMediaTab(tabId)
    // ... existing tab closing code ...
}
```

7. **Setup social media icons in new tab screen**
In the code that sets up the new browser tab view:

```kotlin
private fun setupSocialMediaBar() {
    val socialMediaBar = includeNewBrowserTab.socialMediaAndStatsBar

    // Set click listeners for top bar icons
    socialMediaBar.facebookIcon.setOnClickListener {
        handleSocialMediaNavigation(SocialMediaManager.SocialMediaPlatform.FACEBOOK)
    }

    socialMediaBar.twitterIcon.setOnClickListener {
        handleSocialMediaNavigation(SocialMediaManager.SocialMediaPlatform.TWITTER)
    }

    socialMediaBar.instagramIcon.setOnClickListener {
        handleSocialMediaNavigation(SocialMediaManager.SocialMediaPlatform.INSTAGRAM)
    }

    socialMediaBar.youtubeIcon.setOnClickListener {
        handleSocialMediaNavigation(SocialMediaManager.SocialMediaPlatform.YOUTUBE)
    }

    socialMediaBar.whatsappIcon.setOnClickListener {
        handleSocialMediaNavigation(SocialMediaManager.SocialMediaPlatform.WHATSAPP)
    }

    // Update statistics (reuse existing code that was updating appStat)
    updateStatistics(socialMediaBar)
}
```

## Benefits

1. **No Duplicate Tabs**: Clicking social media icon switches to existing tab if already open
2. **Quick Access**: One-click access to 5 major social media platforms
3. **Space Efficient**: Statistics reduced to 50% size, making room for social media
4. **Consistent UX**: Both top bar and bottom nav provide same functionality
5. **Smart Tracking**: Automatically tracks and manages social media tabs

## Testing Checklist

- [ ] Top social media bar displays correctly on new tab screen
- [ ] Statistics are smaller (50% of original size) and still readable
- [ ] Social media icons in top bar are clickable
- [ ] Bottom nav shows social media icon instead of masjid icon
- [ ] Clicking bottom social media icon shows dialog
- [ ] Dialog displays all 5 social media platforms
- [ ] Clicking a platform opens it in a new tab (first time)
- [ ] Clicking the same platform again switches to existing tab (not creating new one)
- [ ] Closing a social media tab allows creating new one for that platform
- [ ] All icons display correctly and are properly tinted

## URLs Used

- Facebook: https://www.facebook.com
- Twitter: https://twitter.com
- Instagram: https://www.instagram.com
- YouTube: https://www.youtube.com
- WhatsApp: https://web.whatsapp.com

## Notes

- The `socialMediaMenuItem` replaces `timeMenuItem` (prayer times) in the bottom navigation
- If prayer time functionality is still needed, consider adding it to the overflow menu or creating a separate feature
- The translucent background uses 50% alpha black (#80000000)
- All social media icons use white tint for consistency
