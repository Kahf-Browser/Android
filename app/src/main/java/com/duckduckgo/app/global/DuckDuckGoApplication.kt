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

import android.os.StrictMode
import android.os.StrictMode.ThreadPolicy
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.duckduckgo.app.browser.BuildConfig
import com.duckduckgo.app.browser.safe_gaze_and_host_blocker.WallpaperDownloadWorker
import com.duckduckgo.app.di.AppComponent
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.di.DaggerAppComponent
import com.duckduckgo.app.lifecycle.MainProcessLifecycleObserver
import com.duckduckgo.app.lifecycle.VpnProcessLifecycleObserver
import com.duckduckgo.app.referral.AppInstallationReferrerStateListener
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.FALLBACK_PUBLISHER_ID
import com.duckduckgo.common.utils.plugins.PluginPoint
import com.duckduckgo.di.DaggerMap
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
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
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
                return imageProcessorLazy.get()
            }
        }
    }

    fun createVideoFrameProcessorLazyWrapper(): dagger.Lazy<VideoFrameProcessor> {
        return object : dagger.Lazy<VideoFrameProcessor> {
            override fun get(): VideoFrameProcessor {
                videoFrameProcessorInitialized = true
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
    lateinit var youtubeAdblockUpdateManager: com.duckduckgo.app.browser.youtube.YoutubeAdblockUpdateManager

    private val applicationCoroutineScope = CoroutineScope(SupervisorJob())

    open lateinit var daggerAppComponent: AppComponent

    override fun onMainProcessCreate() {
        configureLogging()
        Timber.d("onMainProcessCreate $currentProcessName with pid=${android.os.Process.myPid()}")

        configureStrictMode()
        configureDependencyInjection()
        setupActivityLifecycleCallbacks()
        configureUncaughtExceptionHandler()

        // Deprecated, we need to move all these into AppLifecycleEventObserver
        ProcessLifecycleOwner.get().lifecycle.apply {
            primaryLifecycleObserverPluginPoint.getPlugins().forEach {
                Timber.d("Registering application lifecycle observer: ${it.javaClass.canonicalName}")
                addObserver(it)
            }
        }

        appCoroutineScope.launch(dispatchers.io()) {
            referralStateListener.initialiseReferralRetrieval()
        }

        // ✅ OPTIMIZATION 3: Eagerly initialize NSFW model in background
        // This eliminates the 100-300ms delay on first image detection
        appCoroutineScope.launch(dispatchers.io()) {
            try {
                Timber.d("kLog Starting eager NSFW model initialization")
                nsfwDetector.initializeEagerly()
                Timber.d("kLog NSFW model eager initialization completed")
            } catch (e: Exception) {
                Timber.e("kLog Failed to eagerly initialize NSFW model: ${e.message}")
            }
        }

        // ✅ OPTIMIZATION: Eagerly initialize ML image/video processors in background
        // This preloads the heavy ML models so they're ready when SafeGaze needs them
        // Without blocking app startup (runs on IO thread)
        appCoroutineScope.launch(dispatchers.io()) {
            try {
                Timber.d("kLog Starting eager ImageProcessor initialization")
                // Accessing the property triggers lazy initialization
                imageProcessor.let {
                    Timber.d("kLog ImageProcessor eager initialization completed")
                }
            } catch (e: Exception) {
                Timber.e("kLog Failed to eagerly initialize ImageProcessor: ${e.message}")
            }
        }

        appCoroutineScope.launch(dispatchers.io()) {
            try {
                Timber.d("kLog Starting eager VideoFrameProcessor initialization")
                // Accessing the property triggers lazy initialization
                videoFrameProcessor.let {
                    Timber.d("kLog VideoFrameProcessor eager initialization completed")
                }
            } catch (e: Exception) {
                Timber.e("kLog Failed to eagerly initialize VideoFrameProcessor: ${e.message}")
            }
        }

        // Check for YouTube ad-blocker script updates on startup
        // Only checks once every 12 hours, downloads on first run
        appCoroutineScope.launch(dispatchers.io()) {
            try {
                Timber.d("YouTubeAdblock: Starting update check on app startup")
                youtubeAdblockUpdateManager.checkForUpdates()
                Timber.d("YouTubeAdblock: Update check completed")
            } catch (e: Exception) {
                Timber.e(e, "YouTubeAdblock: Failed to check for updates on startup")
            }
        }

        // Prune completed WorkManager work asynchronously to prevent ANR
        // This prevents accumulation of completed work items and network callbacks
        appCoroutineScope.launch(dispatchers.io()) {
            try {
                Timber.d("WorkManager: Starting async prune of completed work")
                val workManager = WorkManager.getInstance(this@DuckDuckGoApplication)
                workManager.pruneWork()
                Timber.d("WorkManager: Successfully pruned completed work")
            } catch (e: Exception) {
                Timber.w(e, "WorkManager: Failed to prune work, but continuing")
            }
        }

        scheduleTasks()
        configRemoteConfig()

        // PERFORMANCE FIX: Initialize SDKs on background thread to avoid blocking app startup
        appCoroutineScope.launch(dispatchers.io()) {
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

        // KahfAdsSdk initialization moved to background (currently unused - GoogleAdManagerConfig commented out)
        appCoroutineScope.launch(dispatchers.io()) {
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
