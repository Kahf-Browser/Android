/*
 * Copyright (c) 2017 DuckDuckGo
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

package com.duckduckgo.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.net.http.SslError.SSL_DATE_INVALID
import android.net.http.SslError.SSL_EXPIRED
import android.net.http.SslError.SSL_IDMISMATCH
import android.net.http.SslError.SSL_UNTRUSTED
import android.util.Log
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.StringRes
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.duckduckgo.adclick.api.AdClickManager
import com.duckduckgo.anrs.api.CrashLogger
import com.duckduckgo.app.analytics.AnalyticsEvent
import com.duckduckgo.app.analytics.AnalyticsParam
import com.duckduckgo.app.analytics.PostHogAnalyticsService
import com.duckduckgo.app.browser.R.string
import com.duckduckgo.app.browser.SSLErrorType.EXPIRED
import com.duckduckgo.app.browser.SSLErrorType.GENERIC
import com.duckduckgo.app.browser.SSLErrorType.UNTRUSTED_HOST
import com.duckduckgo.app.browser.SSLErrorType.WRONG_HOST
import com.duckduckgo.app.browser.WebViewErrorResponse.BAD_URL
import com.duckduckgo.app.browser.WebViewErrorResponse.CONNECTION
import com.duckduckgo.app.browser.WebViewErrorResponse.OMITTED
import com.duckduckgo.app.browser.WebViewPixelName.WEB_RENDERER_GONE_CRASH
import com.duckduckgo.app.browser.WebViewPixelName.WEB_RENDERER_GONE_KILLED
import com.duckduckgo.app.browser.certificates.rootstore.CertificateValidationState
import com.duckduckgo.app.browser.certificates.rootstore.TrustedCertificateStore
import com.duckduckgo.app.browser.cookies.ThirdPartyCookieManager
import com.duckduckgo.app.browser.httpauth.WebViewHttpAuthStore
import com.duckduckgo.app.browser.logindetection.DOMLoginDetector
import com.duckduckgo.app.browser.logindetection.WebNavigationEvent
import com.duckduckgo.app.browser.mediaplayback.MediaPlayback
import com.duckduckgo.app.browser.model.BasicAuthenticationRequest
import com.duckduckgo.app.browser.navigation.safeCopyBackForwardList
import com.duckduckgo.app.browser.pageloadpixel.PageLoadedHandler
import com.duckduckgo.app.browser.pageloadpixel.firstpaint.PagePaintedHandler
import com.duckduckgo.app.browser.print.PrintInjector
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.dns.CustomDnsResolver
import com.duckduckgo.app.isAutoPlayVideoEnabled
import com.duckduckgo.app.isZikrTab
import com.duckduckgo.app.safegaze.enums.PrivateDnsLevel
import com.duckduckgo.app.safegaze.enums.SafeGazeLevel
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.app.trackerdetection.db.SafeGazeWhitelistDao
import com.duckduckgo.autoconsent.api.Autoconsent
import com.duckduckgo.autofill.api.BrowserAutofill
import com.duckduckgo.autofill.api.InternalTestUserChecker
import com.duckduckgo.browser.api.JsInjectorPlugin
import com.duckduckgo.common.utils.CurrentTimeProvider
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.KAHF_GUARD_BLOCKED_URL
import com.duckduckgo.common.utils.extensions.isDataUri
import com.duckduckgo.common.utils.plugins.PluginPoint
import com.duckduckgo.cookies.api.CookieManagerProvider
import com.duckduckgo.data.store.api.SharedPreferencesProvider
import com.duckduckgo.history.api.NavigationHistory
import com.duckduckgo.privacy.config.api.AmpLinks
import com.duckduckgo.subscriptions.api.Subscriptions
import com.duckduckgo.user.agent.api.ClientBrandHintProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import javax.inject.Inject
import kotlin.system.measureTimeMillis

private const val ABOUT_BLANK = "about:blank"

class BrowserWebViewClient @Inject constructor(
    private val webViewHttpAuthStore: WebViewHttpAuthStore,
    private val trustedCertificateStore: TrustedCertificateStore,
    private val requestRewriter: RequestRewriter,
    private val specialUrlDetector: SpecialUrlDetector,
    private val requestInterceptor: RequestInterceptor,
    private val cookieManagerProvider: CookieManagerProvider,
    private val loginDetector: DOMLoginDetector,
    private val dosDetector: DosDetector,
    private val thirdPartyCookieManager: ThirdPartyCookieManager,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val browserAutofillConfigurator: BrowserAutofill.Configurator,
    private val ampLinks: AmpLinks,
    private val printInjector: PrintInjector,
    private val internalTestUserChecker: InternalTestUserChecker,
    private val adClickManager: AdClickManager,
    private val autoconsent: Autoconsent,
    private val pixel: Pixel,
    private val crashLogger: CrashLogger,
    private val jsPlugins: PluginPoint<JsInjectorPlugin>,
    private val context: Context,
    private val currentTimeProvider: CurrentTimeProvider,
    private val pageLoadedHandler: PageLoadedHandler,
    private val shouldSendPagePaintedPixel: PagePaintedHandler,
    private val navigationHistory: NavigationHistory,
    private val mediaPlayback: MediaPlayback,
    private val subscriptions: Subscriptions,
    private val dnsResolver: CustomDnsResolver,
    private val sgWhitelistDao: SafeGazeWhitelistDao,
    private val ampDetector: AmpDetector,
    private val safeBrowsingManager: com.duckduckgo.safebrowsing.api.SafeBrowsingManager,
    private val youtubeAdBlocker: com.duckduckgo.app.browser.youtube.YouTubeAdBlocker,
    private val postHogAnalyticsService: PostHogAnalyticsService,
    spProvider: SharedPreferencesProvider
) : WebViewClient() {

    var webViewClientListener: WebViewClientListener? = null
    var clientProvider: ClientBrandHintProvider? = null
    private var lastPageStarted: String? = null
    lateinit var activity: FragmentActivity
    private var start: Long? = null
    private var sharedPreferences = spProvider.getKahfSharedPreferences()
    private val isZikrTablet = isZikrTab()
    private var privateDnsMode = PrivateDnsLevel.getCurrentLevel(sharedPreferences)

    /**
     * This is the method of url overriding available from API 24 onwards
     */
    @UiThread
    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        val url = request.url
        return shouldOverride(view, url, request.isForMainFrame)
    }

    /**
     * API-agnostic implementation of deciding whether to override url or not
     */
    private fun shouldOverride(
        webView: WebView,
        url: Uri,
        isForMainFrame: Boolean,
    ): Boolean {
        try {
            Timber.v("shouldOverride webViewUrl: ${webView.url} URL: $url")
            webViewClientListener?.onShouldOverride()
            if (isForMainFrame && dosDetector.isUrlGeneratingDos(url)) {
                if (!(isZikrTablet && url.host == "iom.edu.bd")) {
                    webView.loadUrl("about:blank")
                    webViewClientListener?.dosAttackDetected()
                    return false
                }
            }

            return when (val urlType = specialUrlDetector.determineType(initiatingUrl = webView.originalUrl, uri = url)) {
                is SpecialUrlDetector.UrlType.ShouldLaunchPrivacyProLink -> {
                    subscriptions.launchPrivacyPro(webView.context, url)
                    true
                }
                is SpecialUrlDetector.UrlType.Email -> {
                    webViewClientListener?.sendEmailRequested(urlType.emailAddress)
                    true
                }

                is SpecialUrlDetector.UrlType.Telephone -> {
                    webViewClientListener?.dialTelephoneNumberRequested(urlType.telephoneNumber)
                    true
                }

                is SpecialUrlDetector.UrlType.Sms -> {
                    webViewClientListener?.sendSmsRequested(urlType.telephoneNumber)
                    true
                }

                is SpecialUrlDetector.UrlType.AppLink -> {
                    Timber.i("Found app link for ${urlType.uriString}")
                    webViewClientListener?.let { listener ->
                        return listener.handleAppLink(urlType, isForMainFrame)
                    }
                    false
                }

                is SpecialUrlDetector.UrlType.NonHttpAppLink -> {
                    Timber.i("Found non-http app link for ${urlType.uriString}")
                    if (isForMainFrame) {
                        webViewClientListener?.let { listener ->
                            return listener.handleNonHttpAppLink(urlType)
                        }
                    }
                    true
                }

                is SpecialUrlDetector.UrlType.Unknown -> {
                    Timber.w("Unable to process link type for ${urlType.uriString}")
                    webView.originalUrl?.let {
                        webView.loadUrl(it)
                    }
                    false
                }

                is SpecialUrlDetector.UrlType.SearchQuery -> false

                is SpecialUrlDetector.UrlType.Web -> {
                    if (requestRewriter.shouldRewriteRequest(url)) {
                        webViewClientListener?.let { listener ->
                            val newUri = requestRewriter.rewriteRequestWithCustomQueryParams(url)
                            loadUrl(listener, webView, newUri.toString())
                            return true
                        }
                    }
                    if (isForMainFrame) {
                        webViewClientListener?.let { listener ->
                            listener.willOverrideUrl(url.toString())
                            clientProvider?.let { provider ->
                                if (provider.shouldChangeBranding(url.toString())) {
                                    provider.setOn(webView.settings, url.toString())
                                    loadUrl(listener, webView, url.toString())
                                    return true
                                } else {
                                    return false
                                }
                            }
                            return false
                        }
                    }
                    false
                }

                is SpecialUrlDetector.UrlType.ExtractedAmpLink -> {
                    if (isForMainFrame) {
                        webViewClientListener?.let { listener ->
                            listener.startProcessingTrackingLink()
                            Timber.d("AMP link detection: Loading extracted URL: ${urlType.extractedUrl}")
                            loadUrl(listener, webView, urlType.extractedUrl)
                            return true
                        }
                    }
                    false
                }

                is SpecialUrlDetector.UrlType.CloakedAmpLink -> {
                    val lastAmpLinkInfo = ampLinks.lastAmpLinkInfo
                    if (isForMainFrame && (lastAmpLinkInfo == null || lastPageStarted != lastAmpLinkInfo.destinationUrl)) {
                        webViewClientListener?.let { listener ->
                            listener.handleCloakedAmpLink(urlType.ampUrl)
                            return true
                        }
                    }
                    false
                }

                is SpecialUrlDetector.UrlType.TrackingParameterLink -> {
                    if (isForMainFrame) {
                        webViewClientListener?.let { listener ->
                            listener.startProcessingTrackingLink()
                            Timber.d("Loading parameter cleaned URL: ${urlType.cleanedUrl}")

                            return when (
                                val parameterStrippedType =
                                    specialUrlDetector.processUrl(initiatingUrl = webView.originalUrl, uriString = urlType.cleanedUrl)
                            ) {
                                is SpecialUrlDetector.UrlType.AppLink -> {
                                    loadUrl(listener, webView, urlType.cleanedUrl)
                                    listener.handleAppLink(parameterStrippedType, isForMainFrame)
                                }

                                is SpecialUrlDetector.UrlType.ExtractedAmpLink -> {
                                    Timber.d("AMP link detection: Loading extracted URL: ${parameterStrippedType.extractedUrl}")
                                    loadUrl(listener, webView, parameterStrippedType.extractedUrl)
                                    true
                                }

                                else -> {
                                    loadUrl(listener, webView, urlType.cleanedUrl)
                                    true
                                }
                            }
                        }
                    }
                    false
                }
                else -> false
            }
        } catch (e: Throwable) {
            crashLogger.logCrash(CrashLogger.Crash(shortName = "m_webview_should_override", t = e))
            return false
        }
    }

    @UiThread
    override fun onPageCommitVisible(webView: WebView, url: String) {
        Timber.v("onPageCommitVisible webViewUrl: ${webView.url} URL: $url progress: ${webView.progress}")
        // Show only when the commit matches the tab state
        if (webView.url == url) {
            val navigationList = webView.safeCopyBackForwardList() ?: return
            webViewClientListener?.navigationStateChanged(WebViewNavigationState(navigationList))
            webViewClientListener?.onPageContentStart(url)
        }
    }

    @WorkerThread
    private suspend fun loadLocalJs(webView: WebView) {
        val jsCode = readAssetFile(context.assets, "safe_gaze_v2.js")
        withContext(dispatcherProvider.main()) {
            webView.evaluateJavascript("javascript:(function() { $jsCode })()", null)
        }
    }

    private fun loadPordaJs(
        webView: WebView,
        url: String?
    ) {
        appCoroutineScope.launch(dispatcherProvider.io()) {
            var blurMode = SafeGazeLevel.getImageBlurLevel(sharedPreferences)
            if (blurMode == SafeGazeLevel.Off) {
                blurMode = SafeGazeLevel.getVideoBlurLevel(sharedPreferences, "loadPordaJs")
            }
            Log.d("kLog", "imgLog Received blurMode: $blurMode")
            val isUrlWhiteListed = sgWhitelistDao.isHostWhitelisted(url?.toUri()?.host ?: "")

            val currentPrivateDns = PrivateDnsLevel.getCurrentLevel(sharedPreferences)
            val saferInternetStatus = if (currentPrivateDns != PrivateDnsLevel.Off) "ON" else "OFF"
            val decentInternetStatus = if (SafeGazeLevel.isEnabled(blurMode.name)) "ON" else "OFF"

            Log.d("LoadTimeLog", "SAFE_GAZE_CHECK | URL: $url | SaferInternet: $saferInternetStatus | DecentInternet: $decentInternetStatus | BlurMode: $blurMode | Whitelisted: $isUrlWhiteListed")

            if (SafeGazeLevel.isEnabled(blurMode.name) && !isUrlWhiteListed) {
                Log.d("LoadTimeLog", "SAFE_GAZE_INJECT | URL: $url | SaferInternet: $saferInternetStatus | DecentInternet: $decentInternetStatus | Injecting video_filter.js")
                val contentJs = readAssetFile(context.assets, "video_filter.js")
                withContext(dispatcherProvider.main()) {
                    webView.evaluateJavascript("Android = true;", null)
                    webView.evaluateJavascript(contentJs, null)
                }
            }
        }
    }

    private fun loadAutoplayBlockerJs(webView: WebView) {
        val currentUrl = webView.url
        val isYoutube = currentUrl?.contains("youtube.com", ignoreCase = true) == true
        val isFacebook = currentUrl?.contains("facebook.com", ignoreCase = true) == true

        if (isYoutube || isFacebook) {
            Timber.v("apLog Loading Autoplay Blocker JS")

            appCoroutineScope.launch(dispatcherProvider.io()) {
                val script = readAssetFile(context.assets, "autoplay_blocker.js")
                withContext(dispatcherProvider.main()) {
                    webView.evaluateJavascript(script, null)
                }
            }
        }
    }

    private fun loadYouTubeAdBlockerJs(webView: WebView, url: String?) {
        if (youtubeAdBlocker.isYouTubeUrl(url)) {
            Timber.v("YouTubeAdBlocker: Injecting ad blocker script for $url")

            appCoroutineScope.launch(dispatcherProvider.io()) {
                val script = youtubeAdBlocker.getAdBlockerScript()
                withContext(dispatcherProvider.main()) {
                    webView.evaluateJavascript(script, null)
                }
            }
        }
    }

    private fun loadUrl(
        listener: WebViewClientListener,
        webView: WebView,
        url: String,
    ) {
        if (listener.linkOpenedInNewTab()) {
            webView.post {
                webView.loadUrl(url)
            }
        } else {
            webView.loadUrl(url)
        }
    }

    private suspend fun resolveDnsAndBlock(uri: Uri) = withContext(dispatcherProvider.io()) {
        var result: Pair<String, String>?
        val exeTime = measureTimeMillis {
            result = dnsResolver.resolveDomain(uri)
        }
        Timber.d("asLog ipLog $result || lookup time ${System.currentTimeMillis() - exeTime}ms || ${uri.host}")

        val currentPrivateDns = PrivateDnsLevel.getCurrentLevel(sharedPreferences)
        val currentSafeGaze = SafeGazeLevel.getImageBlurLevel(sharedPreferences)
        val saferInternetStatus = if (currentPrivateDns != PrivateDnsLevel.Off) "ON" else "OFF"
        val decentInternetStatus = if (currentSafeGaze != SafeGazeLevel.Off) "ON" else "OFF"

        Log.d("LoadTimeLog", "DNS_RESOLVE | URL: $uri | SaferInternet: $saferInternetStatus | DecentInternet: $decentInternetStatus | Result: $result | LookupTime: ${exeTime}ms")
        postHogAnalyticsService.logEvent(AnalyticsEvent.PageBlocked)
        postHogAnalyticsService.logEvent(
            AnalyticsEvent.DnsLookupTime,
            mapOf(
                AnalyticsParam.DnsLookupTimeMs to exeTime.toString()
            )
        )

        if (result?.second == KAHF_GUARD_BLOCKED_URL) {
            Timber.d("asLog Blocking URL: $uri")
            Log.d("LoadTimeLog", "DNS_BLOCKED | URL: $uri | SaferInternet: $saferInternetStatus | DecentInternet: $decentInternetStatus")
            withContext(dispatcherProvider.main()) {
                webViewClientListener?.onUrlBlocked(uri.toString())
            }
        }
    }

    @UiThread
    override fun onPageStarted(
        webView: WebView,
        url: String?,
        favicon: Bitmap?,
    ) {
        Timber.v("onPageStarted webViewUrl: ${webView.url} URL: $url progress: ${webView.progress}")

        url?.let {
            requestRewriter.enforceSafeSearch(it.toUri(), privateDnsMode)?.let { safeSearchEnforcedUrl ->
                webView.stopLoading()
                webView.loadUrl(safeSearchEnforcedUrl.toString())
                return
            }

            // Safe Browsing check
            performSafeBrowsingCheck(it)

            loadPordaJs(webView, it)

            // Layer 1: Inject YouTube ad blocker script at page start
            loadYouTubeAdBlockerJs(webView, it)

            // See https://app.asana.com/0/0/1206159443951489/f (WebView limitations)
            if (it != "about:blank" && start == null) {
                start = currentTimeProvider.elapsedRealtime()

                // Log page load start with current settings
                val currentPrivateDns = PrivateDnsLevel.getCurrentLevel(sharedPreferences)
                val currentSafeGaze = SafeGazeLevel.getImageBlurLevel(sharedPreferences)
                val saferInternetStatus = if (currentPrivateDns != PrivateDnsLevel.Off) "ON" else "OFF"
                val decentInternetStatus = if (currentSafeGaze != SafeGazeLevel.Off) "ON" else "OFF"
                Log.d("LoadTimeLog", "PAGE_LOAD_START | URL: $it | SaferInternet: $saferInternetStatus | DecentInternet: $decentInternetStatus | Timestamp: $start")
            }
            handleMediaPlayback(webView, it)
            autoconsent.injectAutoconsent(webView, url)
            adClickManager.detectAdDomain(url)
            requestInterceptor.onPageStarted(url)
            appCoroutineScope.launch(dispatcherProvider.io()) {
                thirdPartyCookieManager.processUriForThirdPartyCookies(webView, url.toUri())
            }
        }
        val navigationList = webView.safeCopyBackForwardList() ?: return
        webViewClientListener?.navigationStateChanged(WebViewNavigationState(navigationList))
        if (url != null && url == lastPageStarted) {
            webViewClientListener?.pageRefreshed(url)
        }
        lastPageStarted = url
        browserAutofillConfigurator.configureAutofillForCurrentPage(webView, url)
        jsPlugins.getPlugins().forEach {
            it.onPageStarted(webView, url, webViewClientListener?.getSite())
        }
        loginDetector.onEvent(WebNavigationEvent.OnPageStarted(webView))
    }

    private fun handleMediaPlayback(
        webView: WebView,
        url: String,
    ) {
        // The default value for this flag is `true`.
        webView.settings.mediaPlaybackRequiresUserGesture = mediaPlayback.doesMediaPlaybackRequireUserGestureForUrl(url)
    }

    @UiThread
    override fun onPageFinished(
        webView: WebView,
        url: String?,
    ) {
        Timber.v("onPageFinished webViewUrl: ${webView.url} URL: $url progress: ${webView.progress}")

        // See https://app.asana.com/0/0/1206159443951489/f (WebView limitations)
        if (webView.progress == 100) {
            if (!sharedPreferences.isAutoPlayVideoEnabled()) {
                loadAutoplayBlockerJs(webView)
            }

            // Layer 3: Re-inject YouTube ad blocker script on page finish (backup for SPA navigation)
            loadYouTubeAdBlockerJs(webView, url)

            jsPlugins.getPlugins().forEach {
                it.onPageFinished(webView, url, webViewClientListener?.getSite())
            }
            url?.let {
                // We call this for any url but it will only be processed for an internal tester verification url
                internalTestUserChecker.verifyVerificationCompleted(it)
            }
            val navigationList = webView.safeCopyBackForwardList() ?: return
            webViewClientListener?.run {
                navigationStateChanged(WebViewNavigationState(navigationList))
                url?.let { prefetchFavicon(url) }
            }
            flushCookies()
            printInjector.injectPrint(webView)

            url?.let {
                if (url != ABOUT_BLANK) {
                    start?.let { safeStart ->
                        // CRITICAL FIX: Use site.title from webViewClientListener instead of navigationList.currentItem?.title
                        // The title is properly captured in BrowserChromeClient.onReceivedTitle() and stored in site.title
                        // navigationList.currentItem?.title may be null/outdated, especially for sites that set title dynamically via JavaScript
                        val pageTitle = webViewClientListener?.getSite()?.title

                        // Calculate load time and log with current settings
                        val endTime = currentTimeProvider.elapsedRealtime()
                        val loadTimeMs = endTime - safeStart

                        val currentPrivateDns = PrivateDnsLevel.getCurrentLevel(sharedPreferences)
                        val currentSafeGaze = SafeGazeLevel.getImageBlurLevel(sharedPreferences)
                        val saferInternetStatus = if (currentPrivateDns != PrivateDnsLevel.Off) "ON" else "OFF"
                        val decentInternetStatus = if (currentSafeGaze != SafeGazeLevel.Off) "ON" else "OFF"

                        Log.d("LoadTimeLog", "PAGE_LOAD_FINISHED | URL: $it | SaferInternet: $saferInternetStatus | DecentInternet: $decentInternetStatus | LoadTime: ${loadTimeMs}ms | StartTime: $safeStart | EndTime: $endTime")

                        // TODO (cbarreiro - 22/05/2024): Extract to plugins
                        pageLoadedHandler.onPageLoaded(it, pageTitle, safeStart, currentTimeProvider.elapsedRealtime())
                        shouldSendPagePaintedPixel(webView = webView, url = it)
                        appCoroutineScope.launch(dispatcherProvider.io()) {
                            navigationHistory.saveToHistory(url, pageTitle)
                        }
                        start = null
                    }
                }
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun readAssetFile(assetManager: AssetManager, fileName: String): String {
        val stringBuilder = StringBuilder()
        try {
            val inputStream = assetManager.open(fileName)
            val bufferedReader = BufferedReader(InputStreamReader(inputStream))

            var line: String?
            while (bufferedReader.readLine().also { line = it } != null) {
                stringBuilder.append(line).append('\n')
            }
        } catch (e: IOException) {
            Timber.d("Read Asset File Exception: ${e.localizedMessage}")
            e.printStackTrace()
        }
        return stringBuilder.toString()
    }

    private fun flushCookies() {
        appCoroutineScope.launch(dispatcherProvider.io()) {
            cookieManagerProvider.get()?.flush()
        }
    }

    @WorkerThread
    override fun shouldInterceptRequest(
        webView: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        var url = request.url.toString()
        if (request.url.host == KAHF_GUARD_BLOCKED_URL || url.isDataUri()) return null

        // Layer 2: Block YouTube ad requests at network level
        if (youtubeAdBlocker.shouldBlockRequest(url)) {
            Timber.v("YouTubeAdBlocker: Blocked request to $url")
            // Return empty response to block the request
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                java.io.ByteArrayInputStream(ByteArray(0))
            )
        }
        privateDnsMode = PrivateDnsLevel.getCurrentLevel(sharedPreferences)
        val privateDnsEnabled = privateDnsMode != PrivateDnsLevel.Off
        val isAmpUrl = ampDetector.isAmpUrl(url)

        if (privateDnsEnabled && (request.isForMainFrame || isAmpUrl)) {
            // Don't allow IP URLs to be loaded
            val isIpUrl = specialUrlDetector.isIpUrl(url) // Url falls in this pattern: https://1.1.1.1
            if (isIpUrl) {
                return null
            }

            if (isAmpUrl) {
                ampDetector.extractOriginalUrlFromAmp(url).also {
                    Timber.d("amLog AMP URL: $it")
                    url = it
                }
                return WebResourceResponse(null, null, null)
            } else {
                appCoroutineScope.launch { resolveDnsAndBlock(url.toUri()) }
            }
        }

        return runBlocking {
            withContext(dispatcherProvider.io()) {
                try {

                    val documentUrl = withContext(dispatcherProvider.main()) { webView.url }
                    withContext(dispatcherProvider.main()) {
                        loginDetector.onEvent(WebNavigationEvent.ShouldInterceptRequest(webView, request))
                    }
                    Timber.v("Intercepting resource ${request.url} type:${request.method} on page $documentUrl")
                    requestInterceptor.shouldIntercept(request, webView, documentUrl?.toUri(), webViewClientListener, privateDnsEnabled)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?,
    ): Boolean {
        Timber.w("onRenderProcessGone. Did it crash? ${detail?.didCrash()}")
        if (detail?.didCrash() == true) {
            pixel.fire(WEB_RENDERER_GONE_CRASH)
        } else {
            pixel.fire(WEB_RENDERER_GONE_KILLED)
        }
        webViewClientListener?.recoverFromRenderProcessGone()
        return true
    }

    @UiThread
    override fun onReceivedHttpAuthRequest(
        view: WebView?,
        handler: HttpAuthHandler?,
        host: String?,
        realm: String?,
    ) {
        Timber.v("onReceivedHttpAuthRequest ${view?.url} $realm, $host")
        if (handler != null) {
            Timber.v("onReceivedHttpAuthRequest - useHttpAuthUsernamePassword [${handler.useHttpAuthUsernamePassword()}]")
            if (handler.useHttpAuthUsernamePassword()) {
                val credentials = view?.let {
                    webViewHttpAuthStore.getHttpAuthUsernamePassword(it, host.orEmpty(), realm.orEmpty())
                }

                if (credentials != null) {
                    handler.proceed(credentials.username, credentials.password)
                } else {
                    requestAuthentication(view, handler, host, realm)
                }
            } else {
                requestAuthentication(view, handler, host, realm)
            }
        } else {
            super.onReceivedHttpAuthRequest(view, handler, host, realm)
        }
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler,
        error: SslError,
    ) {
        var trusted: CertificateValidationState = CertificateValidationState.UntrustedChain

        when (error.primaryError) {
            SSL_UNTRUSTED -> {
                Timber.d("The certificate authority ${error.certificate.issuedBy.dName} is not trusted")
                trusted = trustedCertificateStore.validateSslCertificateChain(error.certificate)
            }

            else -> Timber.d("SSL error ${error.primaryError}")
        }

        Timber.d("The certificate authority validation result is $trusted")
        if (trusted is CertificateValidationState.TrustedChain) {
            handler.proceed()
        } else {
            webViewClientListener?.onReceivedSslError(handler, parseSSlErrorResponse(error))
        }
    }

    private fun parseSSlErrorResponse(sslError: SslError): SslErrorResponse {
        Timber.d("SSL Certificate: parseSSlErrorResponse ${sslError.primaryError}")
        val sslErrorType = when (sslError.primaryError) {
            SSL_UNTRUSTED -> UNTRUSTED_HOST
            SSL_EXPIRED -> EXPIRED
            SSL_DATE_INVALID -> EXPIRED
            SSL_IDMISMATCH -> WRONG_HOST
            else -> GENERIC
        }
        return SslErrorResponse(sslError, sslErrorType, sslError.url)
    }

    private fun requestAuthentication(
        view: WebView?,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?,
    ) {
        webViewClientListener?.let {
            Timber.v("showAuthenticationDialog - $host, $realm")

            val siteURL = if (view?.url != null) "${URI(view.url).scheme}://$host" else host.orEmpty()

            val request = BasicAuthenticationRequest(
                handler = handler,
                host = host.orEmpty(),
                realm = realm.orEmpty(),
                site = siteURL,
            )

            it.requiresAuthentication(request)
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        error?.let {
            val parsedError = parseErrorResponse(it)
            if (parsedError != OMITTED && request?.isForMainFrame == true) {
                start = null
                webViewClientListener?.onReceivedError(parsedError, request.url.toString())
            }
            if (request?.isForMainFrame == true) {
                Timber.d("recordErrorCode for ${request.url}")
                webViewClientListener?.recordErrorCode(
                    "${it.errorCode.asStringErrorCode()} - ${it.description}",
                    request.url.toString(),
                )
            }
        }
        super.onReceivedError(view, request, error)
    }

    private fun parseErrorResponse(error: WebResourceError): WebViewErrorResponse {
        return if (error.errorCode == ERROR_HOST_LOOKUP) {
            when (error.description) {
                "net::ERR_NAME_NOT_RESOLVED" -> BAD_URL
                "net::ERR_INTERNET_DISCONNECTED" -> CONNECTION
                else -> OMITTED
            }
        } else if (error.errorCode == ERROR_FAILED_SSL_HANDSHAKE && error.description == "net::ERR_SSL_PROTOCOL_ERROR") {
            WebViewErrorResponse.SSL_PROTOCOL_ERROR
        } else {
            OMITTED
        }
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        view?.url?.let {
            // We call this for any url but it will only be processed for an internal tester verification url
            internalTestUserChecker.verifyVerificationErrorReceived(it)
        }
        if (request?.isForMainFrame == true) {
            errorResponse?.let {
                Timber.d("recordHttpErrorCode for ${request.url}")
                webViewClientListener?.recordHttpErrorCode(it.statusCode, request.url.toString())
            }
        }
    }

    private fun Int.asStringErrorCode(): String {
        return when (this) {
            ERROR_AUTHENTICATION -> "ERROR_AUTHENTICATION"
            ERROR_BAD_URL -> "ERROR_BAD_URL"
            ERROR_CONNECT -> "ERROR_CONNECT"
            ERROR_FAILED_SSL_HANDSHAKE -> "ERROR_FAILED_SSL_HANDSHAKE"
            ERROR_FILE -> "ERROR_FILE"
            ERROR_FILE_NOT_FOUND -> "ERROR_FILE_NOT_FOUND"
            ERROR_HOST_LOOKUP -> "ERROR_HOST_LOOKUP"
            ERROR_IO -> "ERROR_IO"
            ERROR_PROXY_AUTHENTICATION -> "ERROR_PROXY_AUTHENTICATION"
            ERROR_REDIRECT_LOOP -> "ERROR_REDIRECT_LOOP"
            ERROR_TIMEOUT -> "ERROR_TIMEOUT"
            ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            ERROR_UNKNOWN -> "ERROR_UNKNOWN"
            ERROR_UNSAFE_RESOURCE -> "ERROR_UNSAFE_RESOURCE"
            ERROR_UNSUPPORTED_AUTH_SCHEME -> "ERROR_UNSUPPORTED_AUTH_SCHEME"
            ERROR_UNSUPPORTED_SCHEME -> "ERROR_UNSUPPORTED_SCHEME"
            SAFE_BROWSING_THREAT_BILLING -> "SAFE_BROWSING_THREAT_BILLING"
            SAFE_BROWSING_THREAT_MALWARE -> "SAFE_BROWSING_THREAT_MALWARE"
            SAFE_BROWSING_THREAT_PHISHING -> "SAFE_BROWSING_THREAT_PHISHING"
            SAFE_BROWSING_THREAT_UNKNOWN -> "SAFE_BROWSING_THREAT_UNKNOWN"
            SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE -> "SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE"
            else -> "ERROR_OTHER"
        }
    }

    /**
     * Perform Safe Browsing check on the URL
     */
    private fun performSafeBrowsingCheck(url: String) {
        appCoroutineScope.launch(dispatcherProvider.io()) {
            try {
                if (!safeBrowsingManager.isEnabled()) {
                    return@launch
                }

                val result = safeBrowsingManager.checkUrl(url)
                when (result) {
                    is com.duckduckgo.safebrowsing.api.SafeBrowsingResult.Threat -> {
                        withContext(dispatcherProvider.main()) {
                            webViewClientListener?.showSafeBrowsingThreatWarning(result.threatType, url)
                        }
                    }
                    is com.duckduckgo.safebrowsing.api.SafeBrowsingResult.Safe,
                    is com.duckduckgo.safebrowsing.api.SafeBrowsingResult.Error -> {
                        withContext(dispatcherProvider.main()) {
                            webViewClientListener?.hideSafeBrowsingWarning()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error performing Safe Browsing check for $url")
            }
        }
    }
}

enum class WebViewPixelName(override val pixelName: String) : Pixel.PixelName {
    WEB_RENDERER_GONE_CRASH("m_web_view_renderer_gone_crash"),
    WEB_RENDERER_GONE_KILLED("m_web_view_renderer_gone_killed"),
    WEB_PAGE_LOADED("m_web_view_page_loaded"),
    WEB_PAGE_PAINTED("m_web_view_page_painted"),
}

enum class WebViewErrorResponse(@StringRes val errorId: Int) {
    BAD_URL(string.webViewErrorBadUrl),
    CONNECTION(string.webViewErrorNoConnection),
    OMITTED(string.webViewErrorNoConnection),
    LOADING(string.webViewErrorNoConnection),
    SSL_PROTOCOL_ERROR(string.webViewErrorSslProtocol),
}

data class SslErrorResponse(val error: SslError, val errorType: SSLErrorType, val url: String)
enum class SSLErrorType(@StringRes val errorId: Int) {
    EXPIRED(R.string.sslErrorExpiredMessage),
    WRONG_HOST(R.string.sslErrorWrongHostMessage),
    UNTRUSTED_HOST(R.string.sslErrorUntrustedMessage),
    GENERIC(R.string.sslErrorUntrustedMessage),
    NONE(R.string.sslErrorUntrustedMessage),
}
