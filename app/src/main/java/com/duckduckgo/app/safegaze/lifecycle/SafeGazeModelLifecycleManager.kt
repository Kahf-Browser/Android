package com.duckduckgo.app.safegaze.lifecycle

import android.content.ComponentCallbacks2
import androidx.lifecycle.LifecycleOwner
import com.duckduckgo.app.lifecycle.MainProcessLifecycleObserver
import com.duckduckgo.app.safegaze.nsfwdetection.NsfwDetector
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesTo
import dagger.Module
import dagger.Provides
import dagger.SingleInstanceIn
import dagger.multibindings.IntoSet
import io.kahf.kahf_segmentation.ImageProcessor
import io.kahf.video_filter.VideoFrameProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages ML model lifecycle based on app foreground/background state and memory pressure.
 *
 * Strategy:
 * - App backgrounded (onStop): Start 60s timer, then release models to free ~50-100MB native memory
 * - Memory pressure (onTrimMemory MODERATE+): Release models immediately
 * - App foregrounded (onStart): Cancel timer or aggressively reload models with zero delays
 * - Quick app switches (<60s): No model teardown/reload at all
 *
 * All heavy work (model dispose/reload) runs on IO dispatcher, never on the main thread.
 */
class SafeGazeModelLifecycleManager(
    private val nsfwDetector: NsfwDetector,
    private val imageProcessorLazy: dagger.Lazy<ImageProcessor>,
    private val videoFrameProcessorLazy: dagger.Lazy<VideoFrameProcessor>,
    private val dispatchers: DispatcherProvider,
) : MainProcessLifecycleObserver {

    private val scope = CoroutineScope(dispatchers.io() + SupervisorJob())

    /**
     * Whether the app is currently in the background.
     * SafeGazeJsInterface checks this to skip image downloads while backgrounded.
     */
    val isAppInBackground = AtomicBoolean(false)

    /**
     * Whether models have been released and need reloading on next foreground.
     */
    private val modelsReleased = AtomicBoolean(false)

    /**
     * Track whether ImageProcessor was ever accessed (to avoid triggering lazy init just to close).
     */
    private val imageProcessorAccessed = AtomicBoolean(false)

    /**
     * Track whether VideoFrameProcessor was ever accessed.
     */
    private val videoFrameProcessorAccessed = AtomicBoolean(false)

    /**
     * The pending delayed release job. Cancelled if user returns within the delay window.
     */
    private val releaseJob = AtomicReference<Job?>(null)

    /**
     * App went to background. Start a delayed model release.
     * Called on main thread by ProcessLifecycleOwner — we only set a flag and launch a coroutine.
     */
    override fun onStop(owner: LifecycleOwner) {
        Timber.d("kLog SafeGazeLifecycle: App backgrounded, starting ${MODEL_RELEASE_DELAY_MS}ms release timer")
        isAppInBackground.set(true)

        val job = scope.launch {
            delay(MODEL_RELEASE_DELAY_MS)

            // Double-check we're still in background (user didn't return during delay)
            if (isAppInBackground.get()) {
                releaseModels()
            }
        }

        releaseJob.set(job)
    }

    /**
     * App came to foreground. Cancel pending release or reload models if already released.
     * Called on main thread by ProcessLifecycleOwner — we only set flags and launch coroutines.
     */
    override fun onStart(owner: LifecycleOwner) {
        isAppInBackground.set(false)

        // Cancel the pending release timer if it hasn't fired yet
        val pendingJob = releaseJob.getAndSet(null)
        if (pendingJob != null) {
            pendingJob.cancel()
            Timber.d("kLog SafeGazeLifecycle: Release timer cancelled")
        }

        // Always check if models were released (handles race condition where
        // releaseModels() executed between delay completing and cancel() being called)
        if (modelsReleased.compareAndSet(true, false)) {
            Timber.d("kLog SafeGazeLifecycle: Models were released, triggering aggressive reload")
            reloadModels()
        }
    }

    /**
     * Called by DuckDuckGoApplication.onTrimMemory() when system reports memory pressure.
     * Releases models immediately on TRIM_MEMORY_MODERATE or higher.
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            Timber.d("kLog SafeGazeLifecycle: Memory pressure (level=$level), releasing models immediately")

            // Cancel any pending delayed release
            releaseJob.getAndSet(null)?.cancel()

            scope.launch {
                releaseModels()
            }
        }
    }

    /**
     * Release all ML models to free native memory (~50-100MB).
     * Runs on IO dispatcher — never blocks the main thread.
     */
    private fun releaseModels() {
        if (modelsReleased.getAndSet(true)) {
            Timber.d("kLog SafeGazeLifecycle: Models already released, skipping")
            return
        }

        Timber.d("kLog SafeGazeLifecycle: Releasing ML models")

        // NsfwDetector: always initialized eagerly, so always safe to dispose
        try {
            nsfwDetector.dispose()
            Timber.d("kLog SafeGazeLifecycle: NsfwDetector disposed")
        } catch (e: Exception) {
            Timber.e("kLog SafeGazeLifecycle: Error disposing NsfwDetector: ${e.message}")
        }

        // ImageProcessor: only close if it was accessed (avoid triggering lazy init)
        if (imageProcessorAccessed.get()) {
            try {
                imageProcessorLazy.get().closePordaSegment()
                Timber.d("kLog SafeGazeLifecycle: ImageProcessor closed")
            } catch (e: Exception) {
                Timber.e("kLog SafeGazeLifecycle: Error closing ImageProcessor: ${e.message}")
            }
        }

        // VideoFrameProcessor: only close if it was accessed
        if (videoFrameProcessorAccessed.get()) {
            try {
                videoFrameProcessorLazy.get().close()
                Timber.d("kLog SafeGazeLifecycle: VideoFrameProcessor closed")
            } catch (e: Exception) {
                Timber.e("kLog SafeGazeLifecycle: Error closing VideoFrameProcessor: ${e.message}")
            }
        }

        Timber.d("kLog SafeGazeLifecycle: All models released")
    }

    /**
     * Reload all ML models aggressively with zero delays.
     * Called when user returns to the app after models were released.
     * Runs on IO dispatcher — never blocks the main thread.
     */
    private fun reloadModels() {
        // NsfwDetector: reinitialize eagerly (no delay)
        scope.launch {
            try {
                Timber.d("kLog SafeGazeLifecycle: Reloading NsfwDetector")
                nsfwDetector.initializeEagerly()
                Timber.d("kLog SafeGazeLifecycle: NsfwDetector reloaded")
            } catch (e: Exception) {
                Timber.e("kLog SafeGazeLifecycle: Error reloading NsfwDetector: ${e.message}")
            }
        }

        // ImageProcessor: explicitly trigger PordaSegment reinitialization
        if (imageProcessorAccessed.get()) {
            scope.launch {
                try {
                    Timber.d("kLog SafeGazeLifecycle: Reloading ImageProcessor/PordaSegment")
                    imageProcessorLazy.get().reinitializeIfNeeded()
                    Timber.d("kLog SafeGazeLifecycle: ImageProcessor reload triggered")
                } catch (e: Exception) {
                    Timber.e("kLog SafeGazeLifecycle: Error reloading ImageProcessor: ${e.message}")
                }
            }
        }

        // VideoFrameProcessor: reinitialize
        if (videoFrameProcessorAccessed.get()) {
            scope.launch {
                try {
                    Timber.d("kLog SafeGazeLifecycle: Reloading VideoFrameProcessor")
                    videoFrameProcessorLazy.get().reinitialize()
                    Timber.d("kLog SafeGazeLifecycle: VideoFrameProcessor reloaded")
                } catch (e: Exception) {
                    Timber.e("kLog SafeGazeLifecycle: Error reloading VideoFrameProcessor: ${e.message}")
                }
            }
        }
    }

    /**
     * Mark ImageProcessor as accessed. Called by DuckDuckGoApplication when
     * the lazy wrapper is first accessed.
     */
    fun markImageProcessorAccessed() {
        imageProcessorAccessed.set(true)
    }

    /**
     * Mark VideoFrameProcessor as accessed. Called by DuckDuckGoApplication when
     * the lazy wrapper is first accessed.
     */
    fun markVideoFrameProcessorAccessed() {
        videoFrameProcessorAccessed.set(true)
    }

    companion object {
        /**
         * Delay before releasing models after app goes to background.
         * 60 seconds balances memory savings vs. re-initialization cost.
         * Quick app switches (<60s) never trigger model teardown.
         */
        private const val MODEL_RELEASE_DELAY_MS = 60_000L
    }
}

@Module
@ContributesTo(AppScope::class)
class SafeGazeModelLifecycleModule {

    @Provides
    @SingleInstanceIn(AppScope::class)
    fun provideSafeGazeModelLifecycleManager(
        nsfwDetector: NsfwDetector,
        imageProcessorLazy: dagger.Lazy<ImageProcessor>,
        videoFrameProcessorLazy: dagger.Lazy<VideoFrameProcessor>,
        dispatchers: DispatcherProvider,
    ): SafeGazeModelLifecycleManager {
        return SafeGazeModelLifecycleManager(
            nsfwDetector,
            imageProcessorLazy,
            videoFrameProcessorLazy,
            dispatchers,
        )
    }

    @Provides
    @IntoSet
    fun provideAsLifecycleObserver(
        manager: SafeGazeModelLifecycleManager,
    ): MainProcessLifecycleObserver {
        return manager
    }
}
