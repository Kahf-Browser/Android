# Google Safe Browsing Implementation Plan

> **📋 Progress Tracking:** See [SAFE_BROWSING_CHECKLIST.md](./SAFE_BROWSING_CHECKLIST.md) for detailed task checklist
> **🔑 API Key:** AIzaSyDCFzAK32Gx8b08iSU4FFt7LLxisb9FTrE (configured in `local.properties`)
> **📅 Start Date:** 2025-11-27

## Overview
Implement Google Safe Browsing API (SafetyNet) to protect users from malicious websites. Pages will load normally while Safe Browsing checks happen in the background. If a threat is detected, a warning banner will appear below the address bar.

**Implementation Status:** Ready to begin - API key configured, plan approved ✅

## Architecture

### Module Structure
Following the existing project pattern (e.g., fingerprint-protection, ad-click):

```
safe-browsing/
├── safe-browsing-api/          # Public interfaces and contracts
├── safe-browsing-impl/         # Implementation and business logic
└── safe-browsing-store/        # Database entities, DAOs, and repositories
```

### Core Components

#### 1. **safe-browsing-api**
- `SafeBrowsingManager` interface
  - `suspend fun checkUrl(url: String): SafeBrowsingResult`
  - `fun initialize()`
  - `fun shutdown()`
  - `fun isEnabled(): Boolean`
  - `fun setEnabled(enabled: Boolean)`

- Data classes:
  - `SafeBrowsingResult` (sealed class: Safe, Threat, Error, Cached)
  - `ThreatType` (enum: MALWARE, PHISHING, UNWANTED_SOFTWARE)

#### 2. **safe-browsing-store**
- Room Database: `SafeBrowsingDatabase`
- Entities:
  - `SafeBrowsingCacheEntity` (url, threatType, lastChecked, expiresAt)
  - `SafeBrowsingSettingsEntity` (enabled, statisticsEnabled)
  - `SafeBrowsingStatisticsEntity` (threatsBlocked, lastBlockedUrl, timestamp)

- DAOs:
  - `SafeBrowsingCacheDao`
  - `SafeBrowsingSettingsDao`
  - `SafeBrowsingStatisticsDao`

- Repositories:
  - `SafeBrowsingRepository`

#### 3. **safe-browsing-impl**
- `RealSafeBrowsingManager` implements `SafeBrowsingManager`
  - Integrates with Google Play Services SafetyNet API
  - Handles lifecycle (init/shutdown)
  - Manages caching strategy
  - Handles error cases gracefully

- `SafeBrowsingLifecycleObserver`
  - Observes app lifecycle to manage SafetyNet sessions
  - Calls `initSafeBrowsing()` on resume
  - Calls `shutdownSafeBrowsing()` on pause

- `SafeBrowsingApiKeyProvider`
  - Provides API key from BuildConfig or secure storage

#### 4. **UI Components (in app module)**
- `SafeBrowsingWarningBanner` (custom view)
  - Material3 design with prominent warning styling
  - Shows threat type and suggested action
  - Actions: "Go Back" and "Proceed Anyway"
  - Positioned below omnibar in `fragment_browser_tab.xml`

- `SafeBrowsingViewState` (in BrowserViewState)
  - `data class SafeBrowsingBannerState(val visible: Boolean, val threatType: ThreatType?, val url: String?)`

#### 5. **Integration Points**
- **BrowserWebViewClient.onPageStarted()**
  - Extract URL
  - Filter scheme (only HTTP/HTTPS)
  - Launch coroutine to check URL asynchronously
  - Update view state based on result

- **BrowserTabViewModel**
  - Add LiveData for SafeBrowsingViewState
  - Handle banner show/hide logic
  - Track user actions (proceed/go back)

- **Settings**
  - Add toggle to enable/disable Safe Browsing
  - Show statistics (threats blocked count)
  - Privacy notice about data collection

## Implementation Details

### 1. Google Play Services Integration
```kotlin
// Add to app/build.gradle dependencies
implementation "com.google.android.gms:play-services-safetynet:18.0.1"
```

### 2. API Key Management
- Obtain API key from Google Safe Browsing Console
- Store in `local.properties`: `SAFE_BROWSING_API_KEY=your_key_here`
- Add to BuildConfig:
```gradle
buildConfigField "String", "SAFE_BROWSING_API_KEY", getLocalProperty("SAFE_BROWSING_API_KEY", "")
```

### 3. Safe Browsing Check Flow
```
User navigates to URL
    ↓
BrowserWebViewClient.onPageStarted()
    ↓
Extract & validate URL (HTTP/HTTPS only)
    ↓
Check local cache (SafeBrowsingRepository)
    ↓
If cached & not expired → Return cached result
    ↓
If not cached → Call SafetyNet API
    ↓
SafetyNet.lookupUri(url, apiKey, TYPE_PHISHING, TYPE_MALWARE)
    ↓
Parse response (detectedThreats)
    ↓
Cache result in database
    ↓
Update UI (show warning banner if threat detected)
```

### 4. Caching Strategy
- Cache duration: 24 hours
- Cache safe URLs to reduce API calls
- Cache threat URLs with threat type
- Invalidate cache on app update or manual clear

### 5. Threat Types
Check for:
- `SafeBrowsingThreat.TYPE_SOCIAL_ENGINEERING` (Phishing)
- `SafeBrowsingThreat.TYPE_POTENTIALLY_HARMFUL_APPLICATION` (Malware)

### 6. Error Handling
- **API not initialized**: Retry initialization once, fail silently if fails again
- **Network error**: Use cached result if available, otherwise fail silently
- **Google Play Services unavailable**: Graceful degradation (disable feature)
- **API quota exceeded**: Use cache only, log for monitoring
- **Never block page loading** on API errors

### 7. Privacy Considerations
- SafetyNet uses privacy-preserving hash prefix matching
- Only hash prefixes are sent to Google (not full URLs)
- Add to privacy policy: "We use Google Safe Browsing to protect you from malicious websites"
- Make feature opt-out-able in settings
- Consider whitelist for trusted domains

### 8. UI/UX Design
**Warning Banner:**
- Background: Red/Orange gradient (#FF6B6B to #FFB74D)
- Icon: Shield with exclamation mark
- Text:
  - "⚠️ Warning: This site may be dangerous"
  - Subtext: "This site has been flagged as [Phishing/Malware]"
- Buttons:
  - Primary: "Go Back" (recommended)
  - Secondary: "Proceed Anyway" (risk acknowledgment)
- Position: Below omnibar, above WebView
- Animation: Slide down from top
- Dismissal: Auto-dismiss on navigation or user action

### 9. Settings Integration
Add to Settings screen:
- Section: "Privacy & Security"
- Toggle: "Safe Browsing Protection"
  - Description: "Warn about dangerous sites"
- Statistics: "Threats blocked: X sites"
- Learn more link to privacy documentation

### 10. Testing Strategy
**Unit Tests:**
- SafeBrowsingManager logic
- Caching behavior
- Threat type parsing
- Error handling

**Integration Tests:**
- BrowserWebViewClient integration
- Database operations
- ViewModel state updates

**UI Tests:**
- Warning banner display
- User interactions (proceed/go back)
- Settings toggle

**Manual Testing:**
- Test URLs (from testsafebrowsing.appspot.com):
  - Phishing: `http://testsafebrowsing.appspot.com/s/phishing.html`
  - Malware: `http://testsafebrowsing.appspot.com/s/malware.html`
  - Safe: `https://www.google.com`

## Implementation Steps

### Phase 1: Module Setup (2-3 hours)
1. Create module directories (safe-browsing-api, -impl, -store)
2. Create build.gradle files for each module
3. Add dependencies (SafetyNet, Room, Coroutines, Dagger/Anvil)
4. Setup module registration in settings.gradle

### Phase 2: API Layer (1-2 hours)
5. Define SafeBrowsingManager interface
6. Define data classes (SafeBrowsingResult, ThreatType)
7. Define feature flags and settings interfaces

### Phase 3: Database Layer (2-3 hours)
8. Create Room database and entities
9. Create DAOs with queries
10. Implement repositories
11. Write database migration tests

### Phase 4: Implementation Layer (4-5 hours)
12. Implement RealSafeBrowsingManager
13. Setup API key provider
14. Implement SafetyNet API integration
15. Implement caching logic
16. Implement lifecycle observer
17. Add error handling and logging

### Phase 5: UI Components (2-3 hours)
18. Create SafeBrowsingWarningBanner custom view
19. Design banner layout XML
20. Add animations (slide in/out)
21. Add to fragment_browser_tab.xml

### Phase 6: Browser Integration (3-4 hours)
22. Modify BrowserWebViewClient.onPageStarted()
23. Add SafeBrowsingViewState to BrowserViewModel
24. Connect ViewModel to UI (observe state changes)
25. Handle user actions (proceed/go back)

### Phase 7: Settings Integration (1-2 hours)
26. Add Safe Browsing toggle to settings
27. Add statistics display
28. Implement privacy notice

### Phase 8: Testing (3-4 hours)
29. Write unit tests for manager and repository
30. Write integration tests for WebViewClient
31. Write UI tests for warning banner
32. Manual testing with test URLs
33. Performance testing (caching effectiveness)

### Phase 9: Documentation & Polish (1-2 hours)
34. Update privacy policy
35. Add inline code documentation
36. Create user-facing help documentation
37. Update changelog

## Total Estimated Time: 20-30 hours

## Dependencies Required

```gradle
// safe-browsing-impl/build.gradle
dependencies {
    implementation "com.google.android.gms:play-services-safetynet:18.0.1"
    implementation project(':safe-browsing-api')
    implementation project(':safe-browsing-store')

    // Coroutines
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android"
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-play-services"

    // Dagger/Anvil
    implementation "com.google.dagger:dagger"
    kapt "com.google.dagger:dagger-compiler"
}

// safe-browsing-store/build.gradle
dependencies {
    implementation "androidx.room:room-runtime"
    implementation "androidx.room:room-ktx"
    kapt "androidx.room:room-compiler"

    implementation project(':safe-browsing-api')
}
```

## API Key Setup

**API Key Provided:** `AIzaSyDCFzAK32Gx8b08iSU4FFt7LLxisb9FTrE`

### Implementation Steps:
1. Add to `local.properties`:
   ```properties
   SAFE_BROWSING_API_KEY=AIzaSyDCFzAK32Gx8b08iSU4FFt7LLxisb9FTrE
   ```

2. Add to `app/build.gradle` BuildConfig:
   ```gradle
   android {
       defaultConfig {
           buildConfigField "String", "SAFE_BROWSING_API_KEY", "\"${getLocalProperty('SAFE_BROWSING_API_KEY', '')}\""
       }
   }
   ```

3. Usage in code:
   ```kotlin
   import com.duckduckgo.app.browser.BuildConfig

   val apiKey = BuildConfig.SAFE_BROWSING_API_KEY
   SafetyNet.getClient(context).lookupUri(url, apiKey, threatTypes)
   ```

### Security Notes:
- API key is stored in `local.properties` (git-ignored)
- Never commit API key to version control
- BuildConfig provides compile-time constant
- Key is obfuscated in release builds

## Privacy & Compliance

### Data Collection (per Google documentation):
- Hash prefixes of URLs (after local match)
- No full URLs sent to Google
- Minimal battery and bandwidth usage

### User Transparency:
- Feature toggle in settings (default: enabled)
- Privacy policy update
- First-run notification (optional)

### Whitelisting:
- Allow users to whitelist trusted domains
- Bypass checks for whitelisted URLs
- Store whitelist in database

## Performance Considerations

1. **Async Operations**: All API calls in coroutines (never block UI)
2. **Debouncing**: Avoid rapid successive checks for redirects
3. **Caching**: 24-hour cache to reduce API calls
4. **Rate Limiting**: Respect Google's rate limits (handle 429 errors)
5. **Battery Impact**: Use SafetyNet's optimized protocol (v4)
6. **Memory**: Clear old cache entries (keep only 1000 most recent)

## Monitoring & Analytics

Track (with user consent):
- Threats detected count
- Threat types distribution
- False positive reports
- User proceeded anyway count
- API error rates
- Cache hit rate

## Future Enhancements

1. **Local Blocklist**: Supplement with local threat database
2. **Reporting**: Allow users to report false positives
3. **Custom Messages**: Localized warning messages
4. **Whitelist Sync**: Sync whitelist across devices
5. **Advanced Stats**: Detailed threat analytics in settings

## References

- [Android SafetyNet Documentation](https://developer.android.com/privacy-and-security/safetynet/safebrowsing)
- [Safe Browsing API v4](https://developers.google.com/safe-browsing/v4)
- [Test URLs](https://testsafebrowsing.appspot.com/)
- [Terms of Service](https://developers.google.com/safe-browsing/terms)
