/*
 * Copyright (c) 2018 DuckDuckGo
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

package com.duckduckgo.app.browser.di

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.work.WorkManager
import com.duckduckgo.adclick.api.AdClickManager
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.analytics.FirebaseAnalyticsService
import com.duckduckgo.app.browser.DuckDuckGoRequestRewriter
import com.duckduckgo.app.browser.DuckDuckGoUrlDetector
import com.duckduckgo.app.browser.LongPressHandler
import com.duckduckgo.app.browser.RequestInterceptor
import com.duckduckgo.app.browser.RequestRewriter
import com.duckduckgo.app.browser.SpecialUrlDetector
import com.duckduckgo.app.browser.SpecialUrlDetectorImpl
import com.duckduckgo.app.browser.WebDataManager
import com.duckduckgo.app.browser.WebViewDataManager
import com.duckduckgo.app.browser.WebViewLongPressHandler
import com.duckduckgo.app.browser.WebViewRequestInterceptor
import com.duckduckgo.app.browser.addtohome.AddToHomeCapabilityDetector
import com.duckduckgo.app.browser.addtohome.AddToHomeSystemCapabilityDetector
import com.duckduckgo.app.browser.applinks.ExternalAppIntentFlagsFeature
import com.duckduckgo.app.browser.certificates.rootstore.TrustedCertificateStore
import com.duckduckgo.app.browser.cookies.AppThirdPartyCookieManager
import com.duckduckgo.app.browser.cookies.ThirdPartyCookieManager
import com.duckduckgo.app.browser.cookies.db.AuthCookiesAllowedDomainsRepository
import com.duckduckgo.app.browser.defaultbrowsing.DefaultBrowserDetector
import com.duckduckgo.app.browser.defaultbrowsing.DefaultBrowserObserver
import com.duckduckgo.app.browser.downloader.BlobConverterInjector
import com.duckduckgo.app.browser.downloader.BlobConverterInjectorJs
import com.duckduckgo.app.browser.favicon.FaviconPersister
import com.duckduckgo.app.browser.favicon.FileBasedFaviconPersister
import com.duckduckgo.app.browser.httpauth.WebViewHttpAuthStore
import com.duckduckgo.app.browser.logindetection.BrowserTabFireproofDialogsEventHandler
import com.duckduckgo.app.browser.logindetection.DOMLoginDetector
import com.duckduckgo.app.browser.logindetection.FireproofDialogsEventHandler
import com.duckduckgo.app.browser.logindetection.JsLoginDetector
import com.duckduckgo.app.browser.logindetection.NavigationAwareLoginDetector
import com.duckduckgo.app.browser.logindetection.NextPageLoginDetection
import com.duckduckgo.app.browser.mediaplayback.store.ALL_MIGRATIONS
import com.duckduckgo.app.browser.mediaplayback.store.MediaPlaybackDao
import com.duckduckgo.app.browser.mediaplayback.store.MediaPlaybackDatabase
import com.duckduckgo.app.browser.pageloadpixel.PageLoadedPixelDao
import com.duckduckgo.app.browser.pageloadpixel.firstpaint.PagePaintedPixelDao
import com.duckduckgo.app.browser.session.WebViewSessionInMemoryStorage
import com.duckduckgo.app.browser.session.WebViewSessionStorage
import com.duckduckgo.app.browser.tabpreview.FileBasedWebViewPreviewGenerator
import com.duckduckgo.app.browser.tabpreview.FileBasedWebViewPreviewPersister
import com.duckduckgo.app.browser.tabpreview.WebViewPreviewGenerator
import com.duckduckgo.app.browser.tabpreview.WebViewPreviewPersister
import com.duckduckgo.app.browser.urlextraction.DOMUrlExtractor
import com.duckduckgo.app.browser.urlextraction.JsUrlExtractor
import com.duckduckgo.app.browser.urlextraction.UrlExtractingWebViewClient
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.dns.CustomDnsResolver
import com.duckduckgo.app.fire.AuthDatabaseLocator
import com.duckduckgo.app.fire.DatabaseCleaner
import com.duckduckgo.app.fire.DatabaseCleanerHelper
import com.duckduckgo.app.fire.DatabaseLocator
import com.duckduckgo.app.fire.WebViewDatabaseLocator
import com.duckduckgo.app.fire.fireproofwebsite.data.FireproofWebsiteRepository
import com.duckduckgo.app.global.db.AppDatabase
import com.duckduckgo.app.global.events.db.UserEventsStore
import com.duckduckgo.app.global.file.FileDeleter
import com.duckduckgo.app.global.install.AppInstallStore
import com.duckduckgo.app.lifecycle.MainProcessLifecycleObserver
import com.duckduckgo.app.privacy.db.PrivacyProtectionCountDao
import com.duckduckgo.app.referral.AppReferrerDataStore
import com.duckduckgo.app.safegaze.genderdetection.GenderDetector
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
import com.duckduckgo.app.safegaze.poseDetection.MoveNetMultiPose
import com.duckduckgo.app.safegaze.poseDetection.TrackerType.BOUNDING_BOX
import com.duckduckgo.app.safegaze.poseDetection.Type
import com.duckduckgo.app.settings.db.SettingsDataStore
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.app.statistics.store.StatisticsDataStore
import com.duckduckgo.app.surrogates.ResourceSurrogates
import com.duckduckgo.app.tabs.ui.GridViewColumnCalculator
import com.duckduckgo.app.trackerdetection.CloakedCnameDetector
import com.duckduckgo.app.trackerdetection.TrackerDetector
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.SAFE_GAZE_PREFERENCES
import com.duckduckgo.cookies.api.CookieManagerProvider
import com.duckduckgo.cookies.api.DuckDuckGoCookieManager
import com.duckduckgo.cookies.api.ThirdPartyCookieNames
import com.duckduckgo.customtabs.api.CustomTabDetector
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.downloads.api.FileDownloader
import com.duckduckgo.downloads.impl.AndroidFileDownloader
import com.duckduckgo.downloads.impl.DataUriDownloader
import com.duckduckgo.downloads.impl.FileDownloadCallback
import com.duckduckgo.experiments.api.VariantManager
import com.duckduckgo.httpsupgrade.api.HttpsUpgrader
import com.duckduckgo.privacy.config.api.AmpLinks
import com.duckduckgo.privacy.config.api.Gpc
import com.duckduckgo.privacy.config.api.TrackingParameters
import com.duckduckgo.request.filterer.api.RequestFilterer
import com.duckduckgo.subscriptions.api.Subscriptions
import com.duckduckgo.user.agent.api.UserAgentProvider
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.Module
import dagger.Provides
import dagger.SingleInstanceIn
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import javax.inject.Named

@Module
class BrowserModule {

    @Provides
    fun duckDuckGoRequestRewriter(
        urlDetector: DuckDuckGoUrlDetector,
        statisticsStore: StatisticsDataStore,
        variantManager: VariantManager,
        appReferrerDataStore: AppReferrerDataStore,
    ): RequestRewriter {
        return DuckDuckGoRequestRewriter(urlDetector, statisticsStore, variantManager, appReferrerDataStore)
    }

    @Provides
    fun urlExtractingWebViewClient(
        webViewHttpAuthStore: WebViewHttpAuthStore,
        trustedCertificateStore: TrustedCertificateStore,
        requestInterceptor: RequestInterceptor,
        cookieManagerProvider: CookieManagerProvider,
        thirdPartyCookieManager: ThirdPartyCookieManager,
        @AppCoroutineScope appCoroutineScope: CoroutineScope,
        dispatcherProvider: DispatcherProvider,
        urlExtractor: DOMUrlExtractor,
    ): UrlExtractingWebViewClient {
        return UrlExtractingWebViewClient(
            webViewHttpAuthStore,
            trustedCertificateStore,
            requestInterceptor,
            cookieManagerProvider,
            thirdPartyCookieManager,
            appCoroutineScope,
            dispatcherProvider,
            urlExtractor,
        )
    }

    @Provides
    fun webViewLongPressHandler(
        context: Context,
        pixel: Pixel,
        customTabDetector: CustomTabDetector,
    ): LongPressHandler {
        return WebViewLongPressHandler(context, pixel, customTabDetector)
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    @IntoSet
    fun defaultBrowserObserver(
        defaultBrowserDetector: DefaultBrowserDetector,
        appInstallStore: AppInstallStore,
        pixel: Pixel,
    ): MainProcessLifecycleObserver {
        return DefaultBrowserObserver(defaultBrowserDetector, appInstallStore, pixel)
    }

    @SingleInstanceIn(AppScope::class)
    @Provides
    fun webViewSessionStorage(): WebViewSessionStorage = WebViewSessionInMemoryStorage()

    @SingleInstanceIn(AppScope::class)
    @Provides
    fun webDataManager(
        context: Context,
        webViewSessionStorage: WebViewSessionStorage,
        cookieManager: DuckDuckGoCookieManager,
        fileDeleter: FileDeleter,
        webViewHttpAuthStore: WebViewHttpAuthStore,
    ): WebDataManager =
        WebViewDataManager(context, webViewSessionStorage, cookieManager, fileDeleter, webViewHttpAuthStore)

    @Provides
    fun clipboardManager(context: Context): ClipboardManager {
        return context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    @Provides
    fun addToHomeCapabilityDetector(context: Context): AddToHomeCapabilityDetector {
        return AddToHomeSystemCapabilityDetector(context)
    }

    @Provides
    fun specialUrlDetector(
        packageManager: PackageManager,
        ampLinks: AmpLinks,
        trackingParameters: TrackingParameters,
        subscriptions: Subscriptions,
        externalAppIntentFlagsFeature: ExternalAppIntentFlagsFeature,
    ): SpecialUrlDetector = SpecialUrlDetectorImpl(packageManager, ampLinks, trackingParameters, subscriptions, externalAppIntentFlagsFeature)

    @Provides
    fun webViewRequestInterceptor(
        resourceSurrogates: ResourceSurrogates,
        trackerDetector: TrackerDetector,
        httpsUpgrader: HttpsUpgrader,
        privacyProtectionCountDao: PrivacyProtectionCountDao,
        gpc: Gpc,
        userAgentProvider: UserAgentProvider,
        adClickManager: AdClickManager,
        cloakedCnameDetector: CloakedCnameDetector,
        requestFilterer: RequestFilterer,
        customDnsResolver: CustomDnsResolver
    ): RequestInterceptor =
        WebViewRequestInterceptor(
            resourceSurrogates,
            trackerDetector,
            httpsUpgrader,
            privacyProtectionCountDao,
            gpc,
            userAgentProvider,
            adClickManager,
            cloakedCnameDetector,
            requestFilterer,
            dnsResolver = customDnsResolver
        )

    @Provides
    @Named("webViewDbLocator")
    fun webViewDatabaseLocator(context: Context): DatabaseLocator = WebViewDatabaseLocator(context)

    @Provides
    @Named("authDbLocator")
    fun authDatabaseLocator(context: Context): DatabaseLocator = AuthDatabaseLocator(context)

    @Provides
    fun databaseCleanerHelper(): DatabaseCleaner = DatabaseCleanerHelper()

    @SingleInstanceIn(AppScope::class)
    @Provides
    fun gridViewColumnCalculator(context: Context): GridViewColumnCalculator {
        return GridViewColumnCalculator(context)
    }

    @SingleInstanceIn(AppScope::class)
    @Provides
    fun webViewPreviewPersister(
        context: Context,
        fileDeleter: FileDeleter,
        dispatchers: DispatcherProvider,
    ): WebViewPreviewPersister {
        return FileBasedWebViewPreviewPersister(context, fileDeleter, dispatchers)
    }

    @SingleInstanceIn(AppScope::class)
    @Provides
    fun faviconPersister(
        context: Context,
        fileDeleter: FileDeleter,
        dispatcherProvider: DispatcherProvider,
    ): FaviconPersister {
        return FileBasedFaviconPersister(context, fileDeleter, dispatcherProvider)
    }

    @Provides
    fun webViewPreviewGenerator(dispatchers: DispatcherProvider): WebViewPreviewGenerator {
        return FileBasedWebViewPreviewGenerator(dispatchers = dispatchers)
    }

    @Provides
    fun domLoginDetector(settingsDataStore: SettingsDataStore): DOMLoginDetector {
        return JsLoginDetector(settingsDataStore)
    }

    @Provides
    fun domUrlExtractor(): DOMUrlExtractor {
        return JsUrlExtractor()
    }

    @Provides
    fun blobConverterInjector(): BlobConverterInjector {
        return BlobConverterInjectorJs()
    }

    @Provides
    fun navigationAwareLoginDetector(
        settingsDataStore: SettingsDataStore,
        @AppCoroutineScope appCoroutineScope: CoroutineScope,
    ): NavigationAwareLoginDetector {
        return NextPageLoginDetection(settingsDataStore, appCoroutineScope)
    }

    @Provides
    fun fileDownloader(
        dataUriDownloader: DataUriDownloader,
        callback: FileDownloadCallback,
        workManager: WorkManager,
        @AppCoroutineScope appCoroutineScope: CoroutineScope,
        dispatcherProvider: DispatcherProvider,
    ): FileDownloader {
        return AndroidFileDownloader(dataUriDownloader, callback, workManager, appCoroutineScope, dispatcherProvider)
    }

    @Provides
    fun fireproofLoginDialogEventHandler(
        userEventsStore: UserEventsStore,
        pixel: Pixel,
        fireproofWebsiteRepository: FireproofWebsiteRepository,
        appSettingsPreferencesStore: SettingsDataStore,
        dispatchers: DispatcherProvider,
    ): FireproofDialogsEventHandler {
        return BrowserTabFireproofDialogsEventHandler(
            userEventsStore,
            pixel,
            fireproofWebsiteRepository,
            appSettingsPreferencesStore,
            dispatchers,
        )
    }

    @SingleInstanceIn(AppScope::class)
    @Provides
    fun thirdPartyCookieManager(
        cookieManagerProvider: CookieManagerProvider,
        authCookiesAllowedDomainsRepository: AuthCookiesAllowedDomainsRepository,
        thirdPartyCookieNames: ThirdPartyCookieNames,
    ): ThirdPartyCookieManager {
        return AppThirdPartyCookieManager(cookieManagerProvider, authCookiesAllowedDomainsRepository, thirdPartyCookieNames)
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun providePageLoadedPixelDao(appDatabase: AppDatabase): PageLoadedPixelDao {
        return appDatabase.pageLoadedPixelDao()
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun providePagePaintedPixelDao(appDatabase: AppDatabase): PagePaintedPixelDao {
        return appDatabase.pagePaintedPixelDao()
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun provideMediaPlaybackDatabase(context: Context): MediaPlaybackDatabase {
        return Room.databaseBuilder(context, MediaPlaybackDatabase::class.java, "media_playback.db")
            .enableMultiInstanceInvalidation()
            .fallbackToDestructiveMigration()
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun providesMediaPlaybackDao(mediaPlaybackDatabase: MediaPlaybackDatabase): MediaPlaybackDao {
        return mediaPlaybackDatabase.mediaPlaybackDao()
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun providesDnsResolver(dispatcherProvider: DispatcherProvider, sharedPreferences: SharedPreferences): CustomDnsResolver {
        return CustomDnsResolver(dispatcherProvider, sharedPreferences)
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun providesNsfwDetector(context: Context): NsfwDetector {
        return NsfwDetector(context)
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun providesGenderDetector(context: Context): GenderDetector {
        return GenderDetector(context)
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun providePoseDetector(context: Context): MoveNetMultiPose {
        return MoveNetMultiPose.create(context, Type.Dynamic).apply {
            setTracker(BOUNDING_BOX)
        }
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun providesKahfSharedPreference(context: Context): SharedPreferences {
        return context.getSharedPreferences(SAFE_GAZE_PREFERENCES, Context.MODE_PRIVATE)
    }

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun provideAnalyticsService(context: Context): AnalyticsService {
        val f = FirebaseAnalytics.getInstance(context.applicationContext)
        return FirebaseAnalyticsService(f)
    }
}
