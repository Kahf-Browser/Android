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

package com.duckduckgo.app.global

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.duckduckgo.app.analytics.AnalyticsEvent
import com.duckduckgo.app.analytics.AnalyticsParam
import com.duckduckgo.app.analytics.AnalyticsService
import com.duckduckgo.app.browser.BuildConfig
import com.duckduckgo.app.browser.safe_gaze_and_host_blocker.WallpaperDownloadWorker
import com.duckduckgo.app.di.AppComponent
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.di.DaggerAppComponent
import com.duckduckgo.app.lifecycle.MainProcessLifecycleObserver
import com.duckduckgo.app.lifecycle.VpnProcessLifecycleObserver
import com.duckduckgo.app.referral.AppInstallationReferrerStateListener
import com.duckduckgo.app.safegaze.lifecycle.SafeGazeModelLifecycleManager
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.FALLBACK_PUBLISHER_ID
import com.duckduckgo.common.utils.plugins.PluginPoint
import com.duckduckgo.di.DaggerMap
import com.facebook.FacebookSdk
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
// import com.kahfads.sdk.GoogleAdManagerConfig
import com.kahfads.sdk.KahfAdsSdk
import com.kahfads.sdk.KahfAdsSdkConfig
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import dagger.android.AndroidInjector
import dagger.android.HasDaggerInjector
import io.kahf.kahf_segmentation.ImageProcessor
import io.kahf.video_filter.VideoFrameProcessor
import io.reactivex.exceptions.UndeliverableException
import io.reactivex.plugins.RxJavaPlugins
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val VPN_PROCESS_NAME = "vpn"

/**
 * Main application class that implements Configuration.Provider for on-demand WorkManager initialization.
 *
 * WorkManager initialization is deferred using on-demand initialization to prevent ANR during app startup.
 * The heavy NetworkStateTracker initialization happens lazily when WorkManager is first accessed,
 * rather than blocking the main thread during Application.onCreate().
 */
open class DuckDuckGoApplication : HasDaggerInjector, MultiProcessApplication(), Configuration.Provider {

    @Inject
    lateinit var uncaughtExceptionHandler: Thread.UncaughtExceptionHandler

    @Inject
    lateinit var referralStateListener: AppInstallationReferrerStateListener

    @Inject
    lateinit var primaryLifecycleObserverPluginPoint: PluginPoint<MainProcessLifecycleObserver>

    @Inject
    lateinit var vpnLifecycleObserverPluginPoint: PluginPoint<VpnProcessLifecycleObserver>

    @Inject
    lateinit var activityLifecycleCallbacks: PluginPoint<com.duckduckgo.browser.api.ActivityLifecycleCallbacks>

    @Inject
    @AppCoroutineScope
    lateinit var appCoroutineScope: CoroutineScope

    @Inject
    lateinit var injectorFactoryMap: DaggerMap<Class<*>, AndroidInjector.Factory<*, *>>

    @Inject
    lateinit var dispatchers: DispatcherProvider

    // PERFORMANCE FIX: Use dagger.Lazy for heavy ML processors to defer initialization
    // These are exposed as lazy references so consumers can defer initialization until actually needed
    @Inject
    lateinit var imageProcessorLazy: dagger.Lazy<ImageProcessor>

    @Inject
    lateinit var videoFrameProcessorLazy: dagger.Lazy<VideoFrameProcessor>

    // Track whether processors have been initialized to avoid unnecessary initialization on cleanup
    @Volatile
    private var imageProcessorInitialized = false
    @Volatile
    private var videoFrameProcessorInitialized = false

    // Accessor properties that trigger initialization on first access
    // Use these only when you need the processor immediately (not recommended for startup path)
    val imageProcessor: ImageProcessor
        get() {
            imageProcessorInitialized = true
            return imageProcessorLazy.get()
        }
    val videoFrameProcessor: VideoFrameProcessor
        get() {
            videoFrameProcessorInitialized = true
            return videoFrameProcessorLazy.get()
        }

    // PERFORMANCE FIX: Expose lazy references for deferred initialization
    // Use these in SafeGazeJsInterface to avoid triggering ML model load during fragment creation
    fun createImageProcessorLazyWrapper(): dagger.Lazy<ImageProcessor> {
        return object : dagger.Lazy<ImageProcessor> {
            override fun get(): ImageProcessor {
                imageProcessorInitialized = true
                safeGazeModelLifecycleManager.markImageProcessorAccessed()
                return imageProcessorLazy.get()
            }
        }
    }

    fun createVideoFrameProcessorLazyWrapper(): dagger.Lazy<VideoFrameProcessor> {
        return object : dagger.Lazy<VideoFrameProcessor> {
            override fun get(): VideoFrameProcessor {
                videoFrameProcessorInitialized = true
                safeGazeModelLifecycleManager.markVideoFrameProcessorAccessed()
                return videoFrameProcessorLazy.get()
            }
        }
    }

    // Safe cleanup methods that only close if initialized
    fun closeImageProcessorIfInitialized() {
        if (imageProcessorInitialized) {
            imageProcessorLazy.get().closePordaSegment()
        }
    }

    fun closeVideoFrameProcessorIfInitialized() {
        if (videoFrameProcessorInitialized) {
            videoFrameProcessorLazy.get().close()
        }
    }

    @Inject
    lateinit var nsfwDetector: NsfwDetector

    @Inject
    lateinit var safeGazeModelLifecycleManager: SafeGazeModelLifecycleManager

    @Inject
    lateinit var youtubeAdblockUpdateManager: com.duckduckgo.app.browser.youtube.YoutubeAdblockUpdateManager

    @Inject
    lateinit var analyticsService: AnalyticsService

    private val applicationCoroutineScope = CoroutineScope(SupervisorJob())

    // INTENT-AWARE MODEL LOADING: Track cold start timing and launch type
    @Volatile
    private var appStartTime: Long = 0L

    // Track whether this is a link launch (aggressive loading) or icon launch (conservative loading)
    private val isLinkLaunch = AtomicBoolean(false)
    private val hasDetectedLaunchType = AtomicBoolean(false)

    // Track model initialization completion for ColdStartToModelInitTime analytics
    @Volatile
    private var nsfwModelInitCompleteTime: Long = 0L
    @Volatile
    private var imageProcessorInitCompleteTime: Long = 0L
    @Volatile
    private var videoFrameProcessorInitCompleteTime: Long = 0L
    private val nsfwModelInitialized = AtomicBoolean(false)
    private val imageProcessorModelInitialized = AtomicBoolean(false)
    private val videoFrameProcessorModelInitialized = AtomicBoolean(false)
    private val coldStartAnalyticsLogged = AtomicBoolean(false)

    open lateinit var daggerAppComponent: AppComponent

    override fun onMainProcessCreate() {
        // INTENT-AWARE MODEL LOADING: Record app start time for cold start analytics
        appStartTime = System.currentTimeMillis()

        configureLogging()
        Timber.d("onMainProcessCreate $currentProcessName with pid=${android.os.Process.myPid()}")

        configureStrictMode()
        configureDependencyInjection()
        setupActivityLifecycleCallbacks()
        configureUncaughtExceptionHandler()

        // INTENT-AWARE MODEL LOADING: Register callback to detect link launches early
        registerLaunchTypeDetectionCallback()

        // PERFORMANCE FIX: Stagger lifecycle observer registration to reduce main thread blocking
        // Critical observers (TrackerDataLoader, AppConfigurationSyncer) are registered first
        // Non-critical observers are registered in batches with small delays
        registerLifecycleObserversStaggered()

        appCoroutineScope.launch(dispatchers.io()) {
            referralStateListener.initialiseReferralRetrieval()
        }

        // PERFORMANCE FIX: Stagger all heavy background work to prevent CPU/GPU resource contention
        // Each operation is delayed to avoid all of them starting at once
        initializeBackgroundServicesStaggered()
    }

    /**
     * INTENT-AWARE MODEL LOADING: Register a callback to detect whether the app was launched
     * via a link click (ACTION_VIEW with data) or via icon click.
     *
     * For link launches, we use aggressive model loading (no delays) because:
     * - User is about to browse external content immediately
     * - SafeGaze needs to be ready ASAP to process images on the target page
     *
     * For icon launches, we use conservative loading (with delays) because:
     * - User sees the home screen first and may not browse immediately
     * - We prioritize smooth UI rendering over model readiness
     */
    private fun registerLaunchTypeDetectionCallback() {
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // Only detect on the first activity launch
                if (hasDetectedLaunchType.compareAndSet(false, true)) {
                    val intent = activity.intent
                    val hasData = intent?.data != null
                    val isViewAction = intent?.action == Intent.ACTION_VIEW

                    // Warm restore: savedInstanceState != null means Android killed the process
                    // and is restoring the activity. The user had content open, so load aggressively.
                    val isWarmRestore = savedInstanceState != null

                    val detectedAsLinkLaunch = hasData || isViewAction || isWarmRestore

                    isLinkLaunch.set(detectedAsLinkLaunch)

                    val launchType = when {
                        isWarmRestore -> "warm-restore"
                        hasData || isViewAction -> "link"
                        else -> "icon"
                    }
                    Timber.d("kLog Launch type detected: $launchType (data=${intent?.data}, action=${intent?.action}, savedState=${savedInstanceState != null})")

                    // If link launch or warm restore detected, trigger immediate model loading
                    if (detectedAsLinkLaunch) {
                        triggerAggressiveModelLoading()
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * INTENT-AWARE MODEL LOADING: For link launches, start model loading immediately
     * without any delays. This is called when a link launch is detected.
     */
    private fun triggerAggressiveModelLoading() {
        Timber.d("kLog Aggressive model loading triggered for link launch")

        // Start NSFW detector immediately (no 1200ms delay)
        appCoroutineScope.launch(dispatchers.io()) {
            try {
                Timber.d("kLog [AGGRESSIVE] Starting immediate NSFW model initialization")
                nsfwDetector.initializeEagerly()
                onNsfwModelInitialized()
                Timber.d("kLog [AGGRESSIVE] NSFW model initialization completed")
            } catch (e: Exception) {
                Timber.e("kLog [AGGRESSIVE] Failed to initialize NSFW model: ${e.message}")
            }
        }

        // Start ImageProcessor immediately (no 2000ms delay)
        appCoroutineScope.launch(dispatchers.io()) {
            try {
                Timber.d("kLog [AGGRESSIVE] Starting immediate ImageProcessor initialization")
                imageProcessor.let {
                    onImageProcessorInitialized()
                    Timber.d("kLog [AGGRESSIVE] ImageProcessor initialization completed")
                }
            } catch (e: Exception) {
                Timber.e("kLog [AGGRESSIVE] Failed to initialize ImageProcessor: ${e.message}")
            }
        }
    }

    /**
     * Called when NSFW model initialization completes.
     * Tracks completion time and logs analytics if both models are ready.
     */
    private fun onNsfwModelInitialized() {
        if (nsfwModelInitialized.compareAndSet(false, true)) {
            nsfwModelInitCompleteTime = System.currentTimeMillis()
            Timber.d("kLog NSFW model initialized at ${nsfwModelInitCompleteTime - appStartTime}ms after app start")
            logColdStartToModelInitTimeIfReady()
        }
    }

    /**
     * Called when ImageProcessor initialization completes.
     * Tracks completion time and logs analytics if all models are ready.
     */
    private fun onImageProcessorInitialized() {
        if (imageProcessorModelInitialized.compareAndSet(false, true)) {
            imageProcessorInitCompleteTime = System.currentTimeMillis()
            Timber.d("kLog ImageProcessor initialized at ${imageProcessorInitCompleteTime - appStartTime}ms after app start")
            logColdStartToModelInitTimeIfReady()
        }
    }

    /**
     * Called when VideoFrameProcessor initialization completes.
     * Tracks completion time and logs VideoFrameProcessor-specific analytics.
     * Note: VideoFrameProcessor is tracked separately from NSFW/ImageProcessor.
     */
    private fun onVideoFrameProcessorInitialized() {
        if (videoFrameProcessorModelInitialized.compareAndSet(false, true)) {
            videoFrameProcessorInitCompleteTime = System.currentTimeMillis()
            val initTimeMs = videoFrameProcessorInitCompleteTime - appStartTime
            val launchType = if (isLinkLaunch.get()) "link" else "icon"

            Timber.d("kLog VideoFrameProcessor initialized at ${initTimeMs}ms after app start (launchType: $launchType)")

            // Log VideoFrameProcessor-specific analytics
            try {
                analyticsService.logEvent(
                    AnalyticsEvent.VideoFrameProcessorInitTime,
                    mapOf(
                        AnalyticsParam.VideoFrameProcessorInitTimeMs to initTimeMs.toString(),
                        AnalyticsParam.LaunchType to launchType
                    )
                )
            } catch (e: Exception) {
                Timber.e("kLog Failed to log VideoFrameProcessorInitTime analytics: ${e.message}")
            }
        }
    }

    /**
     * Logs the ColdStartToModelInitTime analytics event when both NSFW and ImageProcessor
     * models have completed initialization.
     * Note: VideoFrameProcessor is tracked separately via VideoFrameProcessorInitTime event.
     */
    private fun logColdStartToModelInitTimeIfReady() {
        if (nsfwModelInitialized.get() && imageProcessorModelInitialized.get()) {
            if (coldStartAnalyticsLogged.compareAndSet(false, true)) {
                // Use the later of the two completion times as the "all models ready" time
                val allModelsReadyTime = maxOf(nsfwModelInitCompleteTime, imageProcessorInitCompleteTime)
                val coldStartToModelInitTime = allModelsReadyTime - appStartTime
                val launchType = if (isLinkLaunch.get()) "link" else "icon"

                Timber.d("kLog ColdStartToModelInitTime: ${coldStartToModelInitTime}ms (launchType: $launchType)")

                try {
                    analyticsService.logEvent(
                        AnalyticsEvent.ColdStartToModelInitTime,
                        mapOf(
                            AnalyticsParam.ColdStartToModelInitTimeMs to coldStartToModelInitTime.toString(),
                            AnalyticsParam.LaunchType to launchType
                        )
                    )
                } catch (e: Exception) {
                    Timber.e("kLog Failed to log ColdStartToModelInitTime analytics: ${e.message}")
                }
            }
        }
    }

    /**
     * PERFORMANCE FIX: Initialize background services with staggered delays.
     *
     * Problem: When all background tasks start at once, they compete for:
     * - CPU cores (context switching overhead)
     * - GPU resources (ML model loading)
     * - Memory bandwidth (loading large files)
     *
     * Solution: Stagger the initialization with delays to reduce contention.
     * Priority order:
     * 1. Firebase (needed for remote config, messaging) - immediate
     * 2. NSFW detector (critical for SafeGaze) - 100ms delay
     * 3. Image/Video processors (SafeGaze ML) - 500ms delay
     * 4. Other SDKs and services - 1000ms+ delay
     */
    private fun initializeBackgroundServicesStaggered() {
        // Priority 0: Pre-warm OkHttp's PublicSuffixDatabase (immediate)
        // This prevents ANR/crashes when topPrivateDomain() is called on the main thread.
        // The database is a gzipped file that OkHttp reads synchronously on first access.
        // By pre-warming it on a background thread, we ensure it's cached before the UI needs it.
        appCoroutineScope.launch(dispatchers.io()) {
            try {
                Timber.d("PublicSuffixDatabase: Pre-warming on background thread")
                // Trigger PublicSuffixDatabase initialization by calling topPrivateDomain()
                // This reads the gzipped public suffix list and caches it for future calls
                "https://example.com".toHttpUrlOrNull()?.topPrivateDomain()
                Timber.d("PublicSuffixDatabase: Pre-warming completed successfully")
            } catch (e: Exception) {
                // Log but don't crash - the URL detector has fallback handling
                Timber.w(e, "PublicSuffixDatabase: Pre-warming failed, URL detection may be slower")
            }
        }

        // Priority 1: Firebase initialization (immediate, needed by other services)
        // PERFORMANCE FIX: Manually initialize Firebase since we disabled auto-init to save ~276ms
        appCoroutineScope.launch(dispatchers.io()) {
            try {
                Timber.d("Firebase: Starting manual initialization")
                FirebaseApp.initializeApp(this@DuckDuckGoApplication)
                Timber.d("Firebase: Manual initialization completed")
                // Now safe to initialize Remote Config
                configRemoteConfig()
                Timber.d("Firebase Remote Config initialized")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize Firebase")
            }
        }

        // INTENT-AWARE MODEL LOADING: Priority 2: NSFW detector
        // For icon launches (conservative): delay 1200ms to balance UI smoothness with SafeGaze readiness
        // For link launches (aggressive): this is skipped - triggerAggressiveModelLoading() handles it immediately
        // The TensorFlow Lite model takes ~4.5 seconds to load and was competing for CPU/memory
        // resources during the critical UI rendering window.
        appCoroutineScope.launch(dispatchers.io()) {
            delay(1200)
            // Skip if already initialized by aggressive loading (link launch)
            if (nsfwModelInitialized.get()) {
                Timber.d("kLog [CONSERVATIVE] NSFW model already initialized by aggressive loading, skipping")
                return@launch
            }
            try {
                Timber.d("kLog [CONSERVATIVE] Starting eager NSFW model initialization")
                nsfwDetector.initializeEagerly()
                onNsfwModelInitialized()
                Timber.d("kLog [CONSERVATIVE] NSFW model eager initialization completed")
            } catch (e: Exception) {
                Timber.e("kLog [CONSERVATIVE] Failed to eagerly initialize NSFW model: ${e.message}")
            }
        }

        // INTENT-AWARE MODEL LOADING: Priority 3: ML Image/Video processors
        // For icon launches (conservative): delay 2000ms to let NSFW start first
        // For link launches (aggressive): this is skipped - triggerAggressiveModelLoading() handles it immediately
        appCoroutineScope.launch(dispatchers.io()) {
            delay(2000)
            // Skip if already initialized by aggressive loading (link launch)
            if (imageProcessorModelInitialized.get()) {
                Timber.d("kLog [CONSERVATIVE] ImageProcessor already initialized by aggressive loading, skipping")
                return@launch
            }
            try {
                Timber.d("kLog [CONSERVATIVE] Starting eager ImageProcessor initialization")
                imageProcessor.let {
                    onImageProcessorInitialized()
                    Timber.d("kLog [CONSERVATIVE] ImageProcessor eager initialization completed")
                }
            } catch (e: Exception) {
                Timber.e("kLog [CONSERVATIVE] Failed to eagerly initialize ImageProcessor: ${e.message}")
            }
        }

        appCoroutineScope.launch(dispatchers.io()) {
            delay(2200) // Stagger video processor after image processor
            // Skip if already initialized by aggressive loading (link launch)
            if (videoFrameProcessorModelInitialized.get()) {
                Timber.d("kLog [CONSERVATIVE] VideoFrameProcessor already initialized by aggressive loading, skipping")
                return@launch
            }
            try {
                Timber.d("kLog [CONSERVATIVE] Starting eager VideoFrameProcessor initialization")
                videoFrameProcessor.let {
                    onVideoFrameProcessorInitialized()
                    Timber.d("kLog [CONSERVATIVE] VideoFrameProcessor eager initialization completed")
                }
            } catch (e: Exception) {
                Timber.e("kLog [CONSERVATIVE] Failed to eagerly initialize VideoFrameProcessor: ${e.message}")
            }
        }

        // Priority 4: Non-critical services (3000ms+ delay)
        appCoroutineScope.launch(dispatchers.io()) {
            delay(3000)
            try {
                Timber.d("YouTubeAdblock: Starting update check on app startup")
                youtubeAdblockUpdateManager.checkForUpdates()
                Timber.d("YouTubeAdblock: Update check completed")
            } catch (e: Exception) {
                Timber.e(e, "YouTubeAdblock: Failed to check for updates on startup")
            }
        }

        // WorkManager prune (3200ms delay)
        appCoroutineScope.launch(dispatchers.io()) {
            delay(3200)
            try {
                Timber.d("WorkManager: Starting async prune of completed work")
                val workManager = WorkManager.getInstance(this@DuckDuckGoApplication)
                workManager.pruneWork()
                Timber.d("WorkManager: Successfully pruned completed work")
            } catch (e: Exception) {
                Timber.w(e, "WorkManager: Failed to prune work, but continuing")
            }
        }

        // Schedule tasks after a small delay to not block lifecycle observer registration
        appCoroutineScope.launch(dispatchers.io()) {
            delay(200)
            scheduleTasks()
        }

        // PostHog SDK (1500ms delay - analytics can wait)
        appCoroutineScope.launch(dispatchers.io()) {
            delay(1500)
            try {
                with(
                    PostHogAndroidConfig(
                        apiKey = BuildConfig.POSTHOG_API_KEY,
                        host = BuildConfig.POSTHOG_HOST,
                    ),
                ) {
                    PostHogAndroid.setup(this@DuckDuckGoApplication, this)
                }
                Timber.d("PostHog SDK initialized on background thread")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize PostHog SDK")
            }
        }

        // KahfAdsSdk (2000ms delay - ads can wait until UI is fully ready)
        appCoroutineScope.launch(dispatchers.io()) {
            delay(2000)
            try {
                setupKahfAdsSDK()
                Timber.d("KahfAds SDK initialized on background thread")
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize KahfAds SDK")
            }
        }
    }

    override fun onSecondaryProcessCreate(shortProcessName: String) {
        runInSecondaryProcessNamed(VPN_PROCESS_NAME) {
            configureLogging()
            configureStrictMode()
            Timber.d("Init for secondary process $shortProcessName with pid=${android.os.Process.myPid()}")
            configureDependencyInjection()
            configureUncaughtExceptionHandler()

            // ProcessLifecycleOwner doesn't know about secondary processes, so the callbacks are our own callbacks and limited to onCreate which
            // is good enough.
            // See https://developer.android.com/reference/android/arch/lifecycle/ProcessLifecycleOwner#get
            ProcessLifecycleOwner.get().lifecycle.apply {
                vpnLifecycleObserverPluginPoint.getPlugins().forEach {
                    it.onVpnProcessCreated()
                }
            }
        }
    }

    private fun setupActivityLifecycleCallbacks() {
        activityLifecycleCallbacks.getPlugins().forEach { registerActivityLifecycleCallbacks(it) }
    }

    /**
     * PERFORMANCE FIX: Register lifecycle observers with deferred non-critical registration.
     *
     * Problem: Registering 68+ lifecycle observers during onCreate() blocks the main thread
     * and causes frame skips. Small delays between batches actually make it WORSE because
     * the registration then overlaps with UI rendering.
     *
     * Solution:
     * 1. Register CRITICAL observers immediately (these must run before UI shows)
     * 2. Defer ALL non-critical observers until 2 seconds AFTER app start
     *    This ensures the first UI frame renders smoothly, then we do the heavy work
     *
     * The 2-second delay ensures:
     * - First activity is fully rendered
     * - Splash screen animation completes
     * - User sees responsive UI immediately
     * - Non-critical observers run after UI is stable
     */
    private fun registerLifecycleObserversStaggered() {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val allObservers = primaryLifecycleObserverPluginPoint.getPlugins().toList()

        // Critical observer class names that must be registered immediately
        // These are needed for core browser functionality before any page loads
        val criticalObserverNames = setOf(
            "TrackerDataLoader",        // Must have tracker data ready
            "AppConfigurationSyncer",   // Must sync config
            "ResourceSurrogateLoader",  // Must have surrogates ready
            "MigrationLifecycleObserver" // Must run migrations first
        )

        // Partition observers into critical and non-critical
        val (critical, nonCritical) = allObservers.partition { observer ->
            criticalObserverNames.any { name ->
                observer.javaClass.simpleName.contains(name)
            }
        }

        // Register critical observers immediately (synchronously)
        critical.forEach { observer ->
            Timber.d("Registering CRITICAL lifecycle observer: ${observer.javaClass.canonicalName}")
            lifecycle.addObserver(observer)
        }

        // Defer ALL non-critical observers until AFTER first UI frame renders
        // Using 2000ms delay to ensure UI is completely stable before heavy work
        if (nonCritical.isNotEmpty()) {
            appCoroutineScope.launch(dispatchers.main()) {
                // Wait for UI to be fully rendered and interactive
                delay(2000)

                Timber.d("Starting deferred registration of ${nonCritical.size} non-critical observers")

                // Register all non-critical observers quickly in one batch
                // The UI is already stable, so we can do this without frame drops
                nonCritical.forEach { observer ->
                    lifecycle.addObserver(observer)
                }

                Timber.d("All ${allObservers.size} lifecycle observers registered")
            }
        }
    }

    private fun configureUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler)
        RxJavaPlugins.setErrorHandler { throwable ->
            if (throwable is UndeliverableException) {
                Timber.w(throwable, "An exception happened inside RxJava code but no subscriber was still around to handle it")
            } else {
                uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), throwable)
            }
        }
    }

    private fun scheduleTasks() {
        try {
            val workManager = WorkManager.getInstance(this)

            // Cancel old work to prevent accumulation of network callbacks
            workManager.cancelAllWorkByTag("jsDownloader")
            workManager.cancelAllWorkByTag("com.duckduckgo.app.browser.safe_gaze_and_host_blocker.SafeGazeBlockListAndWallpaperWorker")

            /*val jsDownloadWorkReq = OneTimeWorkRequest.Builder(JsDownloadWorker::class.java)
                .addTag("jsDownloader")
                .build()

            workManager.enqueueUniqueWork(
                "jsDownloadWork",
                androidx.work.ExistingWorkPolicy.REPLACE,
                jsDownloadWorkReq,
            )*/

            val wallpaperDownloadWorkReq = OneTimeWorkRequest.Builder(WallpaperDownloadWorker::class.java)
                .addTag("com.duckduckgo.app.browser.safe_gaze_and_host_blocker.SafeGazeBlockListAndWallpaperWorker")
                .setInitialDelay(15, TimeUnit.SECONDS)
                .build()

            workManager.enqueueUniqueWork(
                "com.duckduckgo.app.browser.safe_gaze_and_host_blocker.SafeGazeBlockListAndWallpaperWorker",
                androidx.work.ExistingWorkPolicy.REPLACE,
                wallpaperDownloadWorkReq
            )
        } catch (e: Exception) {
            // Catch any WorkManager exceptions to prevent app crash during initialization
            Timber.e(e, "Failed to schedule tasks - WorkManager may have connectivity issues")
        }
    }

    private fun configRemoteConfig() {
        val remoteConfig: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
        remoteConfig.setConfigSettingsAsync(
            FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(60 * 60) // 1 hour
                .build(),
        )
    }

    private fun configureLogging() {
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }

    private fun configureDependencyInjection() {
        daggerAppComponent = DaggerAppComponent.builder()
            .application(this)
            .applicationCoroutineScope(applicationCoroutineScope)
            .build()
        daggerAppComponent.inject(this)
    }

    private fun configureStrictMode() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .penaltyDropBox()
                    .build(),
            )
        }
    }

    private fun setupKahfAdsSDK() {
        // PERFORMANCE FIX: Manually initialize Facebook SDK on background thread
        // This is required because we disabled auto-initialization to prevent ANR during app startup
        // Facebook SDK is used by KahfAds SDK and must be initialized before KahfAds
        FacebookSdk.setAutoInitEnabled(false)
        FacebookSdk.fullyInitialize()
        Timber.d("Facebook SDK manually initialized on background thread")

        KahfAdsSdk.initialize(
            config = KahfAdsSdkConfig(
                fallbackPublisherId = FALLBACK_PUBLISHER_ID,
                /*googleAdManagerConfig = GoogleAdManagerConfig(
                    adUnitId = "/23318618088/Kahf_Browser_Android/Native"
                )*/
            ),
            context = this
        )
    }

    // vtodo - Work around for https://crbug.com/558377
    // AndroidInjection.inject(this) creates a new instance of the DuckDuckGoApplication (because we are in a new process)
    // This has several disadvantages:
    //   1. our app is of massive size, because we are duplicating our Dagger graph
    //   2. we are hitting this bug in https://crbug.com/558377, because some of the injected dependencies may eventually
    //      depend in something webview-related
    //
    // We need to override getDir and getCacheDir so that the webview does not share the same data dir across processes
    // This is hacky hacky but should be OK for now as we don't use the webview in the VPN, it is just an issue with
    // injecting/creating dependencies
    //
    // A proper fix should be to create a VpnServiceComponent that just provide the dependencies needed by the VPN, which would
    // also help with memory
    override fun getDir(
        name: String?,
        mode: Int,
    ): File {
        val dir = super.getDir(name, mode)
        runInSecondaryProcessNamed(VPN_PROCESS_NAME) {
            if (name == "webview") {
                return File("${dir.absolutePath}/vpn").apply {
                    Timber.d(":vpn process getDir = $absolutePath")
                    if (!exists()) {
                        mkdirs()
                    }
                }
            }
        }
        return dir
    }

    override fun getCacheDir(): File {
        val dir = super.getCacheDir()
        runInSecondaryProcessNamed(VPN_PROCESS_NAME) {
            return File("${dir.absolutePath}/vpn").apply {
                Timber.d(":vpn process getCacheDir = $absolutePath")
                if (!exists()) {
                    mkdirs()
                }
            }
        }
        return dir
    }

    /**
     * Forward memory pressure signals to SafeGazeModelLifecycleManager.
     * On TRIM_MEMORY_MODERATE or higher, ML models are released immediately
     * to reduce the probability of the process being killed by Android's LMK.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::safeGazeModelLifecycleManager.isInitialized) {
            safeGazeModelLifecycleManager.onTrimMemory(level)
        }
    }

    /**
     * Implementation of [HasDaggerInjector.daggerFactoryFor].
     * Similar to what dagger-android does, The [DuckDuckGoApplication] gets the [DuckDuckGoApplication.injectorFactoryMap]
     * from DI. This holds all the Dagger factories for Android types, like Activities that we create. See [BookmarksActivityComponent.Factory]
     * as an example.
     *
     * This method will return the [AndroidInjector.Factory] for the given key passed in as parameter.
     */
    override fun daggerFactoryFor(key: Class<*>): AndroidInjector.Factory<*, *> {
        return injectorFactoryMap[key]
            ?: throw RuntimeException(
                """
                Could not find the dagger component for ${key.simpleName}.
                You probably forgot to create the ${key.simpleName}Component
                """.trimIndent(),
            )
    }

    /**
     * Implementation of [Configuration.Provider] for on-demand WorkManager initialization.
     *
     * This method is called by WorkManager when getInstance() is called and WorkManager
     * hasn't been initialized yet. By implementing this interface, we enable on-demand
     * initialization which defers the heavy NetworkStateTracker initialization until
     * WorkManager is actually needed, preventing ANR during app startup.
     *
     * IMPORTANT: We access the configuration directly from daggerAppComponent instead of
     * using @Inject because this method may be called DURING Dagger injection (before
     * @Inject fields are populated). The daggerAppComponent is created before inject()
     * is called, so it's safe to access here.
     *
     * The configuration includes:
     * - Custom WorkerFactory for dependency injection in Workers
     * - MaxSchedulerLimit of 20 to prevent TooManyRequestsException
     * - Appropriate logging level based on debug/release build
     */
    override val workManagerConfiguration: Configuration
        get() {
            Timber.d("WorkManager: On-demand initialization triggered via Configuration.Provider")
            return daggerAppComponent.workManagerConfiguration()
        }
}
