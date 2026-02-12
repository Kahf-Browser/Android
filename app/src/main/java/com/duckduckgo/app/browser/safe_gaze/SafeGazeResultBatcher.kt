package com.duckduckgo.app.browser.safe_gaze

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * C1 FIX: Batches evaluateJavascript calls to prevent ANR from rapid-fire
 * main-thread WebView IPC with large base64 payloads.
 *
 * Two problems solved:
 * 1. JSON serialization of large base64 data URIs (100KB+) was happening on the main thread
 *    inside webView.post{}. Callers now pre-serialize on IO threads before calling [enqueue].
 * 2. Each processed image triggered an individual evaluateJavascript call. On image-heavy
 *    pages (50-100+ images), this floods the main-thread Looper with posts, starving touch
 *    events. This batcher uses a throttle (not debounce) to coalesce results within a 16ms
 *    window into a single evaluateJavascript invocation.
 *
 * Throttle vs Debounce:
 * - Debounce resets the timer on each new result — a burst of 20 results over 300ms would
 *   delay ALL of them until 316ms. Bad for perceived speed.
 * - Throttle schedules a flush on the first result, then accumulates. Max added latency is
 *   always [BATCH_INTERVAL_MS] regardless of burst length. Results appear in batches every
 *   16ms during sustained load.
 *
 * Usage:
 * ```
 * // On IO thread — serialization happens here, not on main thread
 * val jsonData = gson.toJson(data)
 * batcher.enqueue(type, jsonData)
 * ```
 */
class SafeGazeResultBatcher(private val webViewProvider: () -> WebView?) {

    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<String>()
    private val lock = Any()
    // Throttle guard: true when a flush is already scheduled on the Handler
    private val flushScheduled = AtomicBoolean(false)

    private val flushRunnable = Runnable { flush() }

    /**
     * Enqueue a pre-serialized result for batched delivery to WebView.
     * Thread-safe — call from any thread. JSON serialization must be done by the caller.
     *
     * Uses throttle pattern: the first enqueue schedules a flush after [BATCH_INTERVAL_MS].
     * Subsequent enqueues within that window just accumulate — the timer is NOT reset.
     * This guarantees max [BATCH_INTERVAL_MS] added latency regardless of burst length.
     */
    fun enqueue(type: String, jsonData: String) {
        val jsCall = "receiveMessageFromKotlin('$type', '$jsonData')"
        synchronized(lock) {
            pending.add(jsCall)
        }
        // Throttle: only schedule a flush if one isn't already pending.
        // compareAndSet is atomic — exactly one enqueue wins and schedules the flush.
        // All others just add to the pending list and piggyback on the scheduled flush.
        if (flushScheduled.compareAndSet(false, true)) {
            handler.postDelayed(flushRunnable, BATCH_INTERVAL_MS)
        }
    }

    private fun flush() {
        // Reset the throttle gate BEFORE draining, so new enqueues that arrive
        // during flush processing will schedule a fresh flush.
        flushScheduled.set(false)

        val batch: List<String>
        synchronized(lock) {
            if (pending.isEmpty()) return
            batch = ArrayList(pending)
            pending.clear()
        }

        val webView = webViewProvider() ?: return

        // Combine all pending JS calls into a single evaluateJavascript invocation.
        // Each call is separated by ';' so the JS engine executes them sequentially.
        val combinedJs = batch.joinToString(";")
        webView.evaluateJavascript(combinedJs, null)

        if (batch.size > 1) {
            Timber.d("kLog C1: Batched ${batch.size} SafeGaze results into single evaluateJavascript call")
        }
    }

    /**
     * Cancel pending flushes and discard accumulated results.
     * Call when the tab is destroyed or WebView is being torn down.
     */
    fun clear() {
        handler.removeCallbacks(flushRunnable)
        flushScheduled.set(false)
        synchronized(lock) {
            pending.clear()
        }
    }

    companion object {
        // One frame at 60fps. Keeps added latency imperceptible while coalescing
        // rapid-fire results from concurrent image processing on IO threads.
        // For context: image processing takes 50-500ms per image, so 16ms is ~3-10% overhead.
        private const val BATCH_INTERVAL_MS = 16L
    }
}
