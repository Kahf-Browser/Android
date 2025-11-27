# Safe Browsing Implementation Checklist

**Project:** kahf-browser
**Feature:** Google Safe Browsing Protection
**API Key:** AIzaSyDCFzAK32Gx8b08iSU4FFt7LLxisb9FTrE
**Start Date:** 2025-11-27

---

## Phase 1: Module Setup

- [ ] Create `safe-browsing/safe-browsing-api` module directory
- [ ] Create `safe-browsing/safe-browsing-impl` module directory
- [ ] Create `safe-browsing/safe-browsing-store` module directory
- [ ] Create `build.gradle` for safe-browsing-api module
- [ ] Create `build.gradle` for safe-browsing-impl module
- [ ] Create `build.gradle` for safe-browsing-store module
- [ ] Add modules to `settings.gradle`
- [ ] Add Google Play Services SafetyNet dependency to safe-browsing-impl
- [ ] Add Room dependencies to safe-browsing-store
- [ ] Add Dagger/Anvil dependencies to modules

---

## Phase 2: API Key Configuration

- [ ] Add API key to `local.properties` file
- [ ] Add `getLocalProperty()` function to app/build.gradle if not exists
- [ ] Add BuildConfig field for SAFE_BROWSING_API_KEY in app/build.gradle
- [ ] Verify API key is accessible via BuildConfig
- [ ] Test build with API key configuration

---

## Phase 3: API Layer (safe-browsing-api)

- [ ] Define `SafeBrowsingResult` sealed class (Safe, Threat, Error, Cached)
- [ ] Define `ThreatType` enum (MALWARE, PHISHING, UNWANTED_SOFTWARE)
- [ ] Define `SafeBrowsingManager` interface
  - [ ] `suspend fun checkUrl(url: String): SafeBrowsingResult`
  - [ ] `suspend fun initialize()`
  - [ ] `suspend fun shutdown()`
  - [ ] `fun isEnabled(): Boolean`
  - [ ] `fun setEnabled(enabled: Boolean)`
- [ ] Define `SafeBrowsingSettings` data class
- [ ] Define `SafeBrowsingStatistics` data class
- [ ] Create package structure and organize files

---

## Phase 4: Database Layer (safe-browsing-store)

- [ ] Create `SafeBrowsingCacheEntity`
  - [ ] url: String (primary key)
  - [ ] threatType: String?
  - [ ] isSafe: Boolean
  - [ ] lastChecked: Long
  - [ ] expiresAt: Long
- [ ] Create `SafeBrowsingSettingsEntity`
  - [ ] id: Int (primary key, always 1)
  - [ ] enabled: Boolean
  - [ ] statisticsEnabled: Boolean
- [ ] Create `SafeBrowsingStatisticsEntity`
  - [ ] id: Int (primary key, auto-increment)
  - [ ] threatsBlocked: Int
  - [ ] lastBlockedUrl: String?
  - [ ] timestamp: Long
- [ ] Create `SafeBrowsingCacheDao`
  - [ ] `suspend fun getCachedResult(url: String): SafeBrowsingCacheEntity?`
  - [ ] `suspend fun insertCacheResult(entity: SafeBrowsingCacheEntity)`
  - [ ] `suspend fun deleteExpiredEntries(currentTime: Long)`
  - [ ] `suspend fun clearCache()`
- [ ] Create `SafeBrowsingSettingsDao`
  - [ ] `suspend fun getSettings(): SafeBrowsingSettingsEntity?`
  - [ ] `suspend fun updateSettings(entity: SafeBrowsingSettingsEntity)`
- [ ] Create `SafeBrowsingStatisticsDao`
  - [ ] `suspend fun incrementThreatsBlocked()`
  - [ ] `suspend fun getStatistics(): SafeBrowsingStatisticsEntity?`
  - [ ] `suspend fun updateLastBlockedUrl(url: String, timestamp: Long)`
- [ ] Create `SafeBrowsingDatabase` (Room Database)
  - [ ] Add all entities
  - [ ] Add all DAOs
  - [ ] Define version and export schema
- [ ] Create `SafeBrowsingRepository` interface
- [ ] Create `RealSafeBrowsingRepository` implementation
  - [ ] Implement cache management
  - [ ] Implement settings management
  - [ ] Implement statistics tracking
- [ ] Create database migration tests

---

## Phase 5: Implementation Layer (safe-browsing-impl)

- [ ] Create `SafeBrowsingApiKeyProvider` class
  - [ ] Provide API key from BuildConfig
  - [ ] Add fallback/error handling
- [ ] Create `RealSafeBrowsingManager` implementation
  - [ ] Inject SafetyNetClient
  - [ ] Inject SafeBrowsingRepository
  - [ ] Inject CoroutineScope
  - [ ] Inject API key provider
- [ ] Implement `initialize()` method
  - [ ] Call SafetyNet.getClient().initSafeBrowsing()
  - [ ] Handle initialization errors
  - [ ] Add retry logic
- [ ] Implement `shutdown()` method
  - [ ] Call SafetyNet.getClient().shutdownSafeBrowsing()
- [ ] Implement `checkUrl(url: String)` method
  - [ ] Validate URL (HTTP/HTTPS only)
  - [ ] Check cache first
  - [ ] If cached and not expired, return cached result
  - [ ] If not cached, call SafetyNet API
  - [ ] Parse threat response
  - [ ] Cache result
  - [ ] Update statistics if threat found
  - [ ] Return SafeBrowsingResult
- [ ] Implement threat type mapping
  - [ ] Map SafeBrowsingThreat.TYPE_SOCIAL_ENGINEERING to ThreatType.PHISHING
  - [ ] Map SafeBrowsingThreat.TYPE_POTENTIALLY_HARMFUL_APPLICATION to ThreatType.MALWARE
- [ ] Implement error handling
  - [ ] Handle API not initialized error
  - [ ] Handle network errors
  - [ ] Handle Google Play Services unavailable
  - [ ] Handle API quota exceeded
  - [ ] Never block page loading on errors
- [ ] Implement `isEnabled()` method
  - [ ] Check settings from repository
- [ ] Implement `setEnabled(enabled: Boolean)` method
  - [ ] Update settings in repository
- [ ] Create `SafeBrowsingLifecycleObserver`
  - [ ] Implement LifecycleObserver interface
  - [ ] Call initialize() on ON_RESUME
  - [ ] Call shutdown() on ON_PAUSE
- [ ] Create Dagger/Anvil module for dependency injection
  - [ ] Provide SafeBrowsingManager binding
  - [ ] Provide SafetyNetClient
  - [ ] Provide repository bindings
- [ ] Add comprehensive logging with Timber

---

## Phase 6: UI Components

- [ ] Create `SafeBrowsingWarningBanner` custom view
  - [ ] Extend ConstraintLayout or MaterialCardView
  - [ ] Add warning icon (shield with exclamation)
  - [ ] Add warning text
  - [ ] Add threat type description text
  - [ ] Add "Go Back" button (primary)
  - [ ] Add "Proceed Anyway" button (secondary)
  - [ ] Add close/dismiss button
- [ ] Create `layout/view_safe_browsing_warning_banner.xml`
  - [ ] Use Material3 design components
  - [ ] Red/Orange gradient background (#FF6B6B to #FFB74D)
  - [ ] Proper padding and margins
  - [ ] Responsive layout for different screen sizes
- [ ] Implement banner animations
  - [ ] Slide down animation on show
  - [ ] Slide up animation on dismiss
  - [ ] Smooth transitions
- [ ] Add banner to `fragment_browser_tab.xml`
  - [ ] Position below omnibar
  - [ ] Above WebView
  - [ ] Initially hidden (visibility: gone)
- [ ] Create `SafeBrowsingBannerState` data class
  - [ ] visible: Boolean
  - [ ] threatType: ThreatType?
  - [ ] url: String?
  - [ ] canGoBack: Boolean
- [ ] Implement banner click listeners
  - [ ] "Go Back" button handler
  - [ ] "Proceed Anyway" button handler
  - [ ] Dismiss button handler
- [ ] Add string resources
  - [ ] Warning title
  - [ ] Phishing description
  - [ ] Malware description
  - [ ] Button texts
  - [ ] Accessibility labels
- [ ] Add drawable resources
  - [ ] Warning icon
  - [ ] Shield icon
  - [ ] Button backgrounds

---

## Phase 7: Browser Integration

- [ ] Add `SafeBrowsingManager` injection to `BrowserTabFragment`
- [ ] Add `SafeBrowsingManager` injection to `BrowserTabViewModel`
- [ ] Add `SafeBrowsingBannerState` to `BrowserViewState`
- [ ] Add LiveData for banner state in `BrowserTabViewModel`
- [ ] Modify `BrowserWebViewClient.onPageStarted()`
  - [ ] Extract URL from WebResourceRequest
  - [ ] Validate URL scheme (HTTP/HTTPS)
  - [ ] Skip data:, javascript:, file: URLs
  - [ ] Launch coroutine to check URL
  - [ ] Handle coroutine result
  - [ ] Update ViewModel state
- [ ] Implement banner state observation in `BrowserTabFragment`
  - [ ] Observe banner state LiveData
  - [ ] Show/hide banner based on state
  - [ ] Update banner content (threat type, URL)
- [ ] Implement "Go Back" action
  - [ ] Call WebView.goBack()
  - [ ] Hide banner
  - [ ] Log user action
- [ ] Implement "Proceed Anyway" action
  - [ ] Add URL to whitelist (temporary or permanent)
  - [ ] Hide banner
  - [ ] Allow navigation to continue
  - [ ] Log user action with warning acknowledgment
- [ ] Handle page navigation changes
  - [ ] Auto-dismiss banner on new page load
  - [ ] Clear banner state on tab switch
- [ ] Add debouncing for rapid URL changes
  - [ ] Avoid multiple API calls for redirects
  - [ ] Use delay or distinct operators
- [ ] Add lifecycle management
  - [ ] Attach SafeBrowsingLifecycleObserver to fragment lifecycle
  - [ ] Ensure proper cleanup on fragment destroy

---

## Phase 8: Settings Integration

- [ ] Locate Settings fragment/activity
- [ ] Add "Safe Browsing Protection" section
- [ ] Add toggle switch for enable/disable
  - [ ] Bind to SafeBrowsingManager.isEnabled()
  - [ ] Handle toggle changes
- [ ] Add description text
  - [ ] "Warn about dangerous sites"
  - [ ] Privacy notice
- [ ] Add statistics display
  - [ ] "Threats blocked: X sites"
  - [ ] Last blocked URL (optional)
  - [ ] Last blocked timestamp
- [ ] Add "Learn More" link
  - [ ] Link to privacy documentation
  - [ ] Explain how Safe Browsing works
- [ ] Add "Clear Cache" option
  - [ ] Clear Safe Browsing cache
  - [ ] Confirmation dialog
- [ ] Add string resources for settings
- [ ] Test settings persistence
  - [ ] Toggle should persist across app restarts
  - [ ] Settings should sync with manager

---

## Phase 9: Testing

### Unit Tests
- [ ] Test `RealSafeBrowsingManager.checkUrl()`
  - [ ] Test safe URL returns Safe result
  - [ ] Test malicious URL returns Threat result
  - [ ] Test cached result is returned when available
  - [ ] Test cache expiry logic
  - [ ] Test error handling (network errors)
  - [ ] Test error handling (API not initialized)
- [ ] Test `SafeBrowsingRepository`
  - [ ] Test cache insertion
  - [ ] Test cache retrieval
  - [ ] Test cache expiry deletion
  - [ ] Test settings persistence
  - [ ] Test statistics tracking
- [ ] Test threat type mapping
  - [ ] Test SafetyNet threat to ThreatType conversion
- [ ] Test URL validation
  - [ ] Test HTTP/HTTPS URLs are accepted
  - [ ] Test data:, javascript:, file: URLs are rejected

### Integration Tests
- [ ] Test BrowserWebViewClient integration
  - [ ] Test checkUrl is called on page start
  - [ ] Test view state is updated on threat detection
  - [ ] Test banner is shown for threats
  - [ ] Test banner is hidden for safe URLs
- [ ] Test database operations
  - [ ] Test end-to-end cache flow
  - [ ] Test settings persistence
  - [ ] Test statistics updates
- [ ] Test ViewModel state updates
  - [ ] Test banner state LiveData emissions
  - [ ] Test user action handling

### UI Tests
- [ ] Test warning banner display
  - [ ] Test banner appears on threat detection
  - [ ] Test banner shows correct threat type
  - [ ] Test banner shows correct URL
- [ ] Test "Go Back" button
  - [ ] Test WebView goes back
  - [ ] Test banner is dismissed
- [ ] Test "Proceed Anyway" button
  - [ ] Test navigation continues
  - [ ] Test banner is dismissed
  - [ ] Test URL is whitelisted
- [ ] Test settings toggle
  - [ ] Test toggle enables/disables feature
  - [ ] Test no checks when disabled
- [ ] Test banner animations
  - [ ] Test slide down animation
  - [ ] Test slide up animation

### Manual Testing
- [ ] Test with Google Safe Browsing test URLs
  - [ ] Phishing: `http://testsafebrowsing.appspot.com/s/phishing.html`
  - [ ] Malware: `http://testsafebrowsing.appspot.com/s/malware.html`
  - [ ] Unwanted: `http://testsafebrowsing.appspot.com/s/unwanted.html`
  - [ ] Safe: `https://www.google.com`
- [ ] Test cache behavior
  - [ ] Verify first check calls API
  - [ ] Verify second check uses cache
  - [ ] Verify cache expires after 24 hours
- [ ] Test offline behavior
  - [ ] Test graceful degradation without network
  - [ ] Test cached results work offline
- [ ] Test Google Play Services scenarios
  - [ ] Test on device with Play Services
  - [ ] Test on device without Play Services (if applicable)
- [ ] Test performance
  - [ ] Verify no UI blocking
  - [ ] Verify async operations
  - [ ] Test with rapid navigation (redirects)
- [ ] Test different screen sizes
  - [ ] Phone (portrait/landscape)
  - [ ] Tablet (portrait/landscape)
- [ ] Test accessibility
  - [ ] Test with TalkBack
  - [ ] Test keyboard navigation
  - [ ] Verify content descriptions

---

## Phase 10: Documentation & Polish

- [ ] Update privacy policy
  - [ ] Add Safe Browsing disclosure
  - [ ] Mention Google Safe Browsing usage
  - [ ] Explain data collection (hash prefixes)
- [ ] Add inline code documentation
  - [ ] Add KDoc comments to public APIs
  - [ ] Add method-level documentation
  - [ ] Add class-level documentation
- [ ] Add README for safe-browsing module
  - [ ] Architecture overview
  - [ ] Usage examples
  - [ ] Configuration guide
- [ ] Update CHANGELOG.md
  - [ ] Document new feature
  - [ ] List key changes
- [ ] Create user-facing help documentation
  - [ ] How Safe Browsing works
  - [ ] How to enable/disable
  - [ ] Privacy information
- [ ] Code review checklist
  - [ ] Remove debug logs
  - [ ] Remove commented code
  - [ ] Verify no hardcoded strings
  - [ ] Check code formatting
  - [ ] Verify Proguard rules if needed

---

## Phase 11: Final Verification

- [ ] Build debug variant successfully
- [ ] Build release variant successfully
- [ ] Run all unit tests (passing)
- [ ] Run all integration tests (passing)
- [ ] Run all UI tests (passing)
- [ ] Manual end-to-end test flow
- [ ] Verify API key is not exposed in logs
- [ ] Verify API key is obfuscated in APK
- [ ] Test on minimum SDK version device
- [ ] Test on latest Android version
- [ ] Performance profiling
  - [ ] CPU usage acceptable
  - [ ] Memory usage acceptable
  - [ ] Battery impact minimal
- [ ] Verify analytics/logging (if implemented)
- [ ] Final code review
- [ ] Create pull request
- [ ] Update issue/ticket status

---

## Notes & Blockers

### Completed:
- ✅ API key obtained: AIzaSyDCFzAK32Gx8b08iSU4FFt7LLxisb9FTrE
- ✅ Implementation plan created
- ✅ Checklist created

### In Progress:
- None yet

### Blockers:
- None yet

### Questions/Decisions:
- None yet

---

## Progress Summary

- **Total Tasks:** 150+
- **Completed:** 0
- **In Progress:** 0
- **Remaining:** 150+
- **Progress:** 0%

---

**Last Updated:** 2025-11-27
**Updated By:** Claude Code Assistant
