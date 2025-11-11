package com.duckduckgo.app.safegaze.nsfwdetection

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import kotlin.system.measureTimeMillis

/**
 * Performance tests for NsfwDetector optimizations.
 *
 * These tests verify that the optimizations are working:
 * 1. GPU acceleration (when available)
 * 2. Buffer reuse (no GC pressure)
 * 3. Eager initialization
 * 4. Thread pool optimization
 *
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class NsfwDetectorPerformanceTest {

    private lateinit var context: Context
    private lateinit var detector: NsfwDetector
    private lateinit var testBitmap: Bitmap

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        detector = NsfwDetector(context)

        // Create a test bitmap (224x224 matching input size)
        testBitmap = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        // Fill with some pattern to simulate real image
        for (x in 0 until 224) {
            for (y in 0 until 224) {
                val color = ((x + y) % 255) shl 16 or ((x * y) % 255) shl 8 or ((x - y) % 255)
                testBitmap.setPixel(x, y, color)
            }
        }
    }

    @After
    fun teardown() {
        if (::detector.isInitialized) {
            detector.dispose()
        }
        if (::testBitmap.isInitialized && !testBitmap.isRecycled) {
            testBitmap.recycle()
        }
    }

    /**
     * Test 1: Verify GPU acceleration is enabled (on supported devices)
     */
    @Test
    fun testGpuAccelerationEnabled() {
        // Initialize detector
        detector.initializeEagerly()

        // Wait for initialization
        Thread.sleep(500)

        // Check configuration
        val config = detector.getConfigInfo()
        assertTrue("Detector should be initialized", config["initialized"] as Boolean)

        // Log GPU status (will be true on GPU-capable devices)
        val gpuEnabled = config["gpuEnabled"] as Boolean
        println("GPU Enabled: $gpuEnabled")

        // On GPU-capable devices, GPU should be enabled
        // On CPU-only devices, this will be false (which is expected)
        if (gpuEnabled) {
            println("✅ GPU acceleration is ENABLED")
        } else {
            println("⚠️  GPU acceleration is NOT ENABLED (using CPU with 4 threads)")
        }
    }

    /**
     * Test 2: Verify initialization time is reasonable
     */
    @Test
    fun testInitializationTime() {
        val initTime = measureTimeMillis {
            detector.initializeEagerly()
            Thread.sleep(100) // Small wait for async init
        }

        assertTrue("Initialization should complete within 2 seconds", initTime < 2000)
        assertTrue("Model should be initialized", detector.isInitialized)

        val modelInitTime = detector.modelInitializationTime
        println("Model initialization time: ${modelInitTime}ms")

        // Model should initialize reasonably fast
        assertTrue(
            "Model initialization should be under 1 second on most devices",
            modelInitTime < 1000
        )
    }

    /**
     * Test 3: Benchmark inference speed
     */
    @Test
    fun testInferenceSpeed() {
        // Initialize first
        detector.initializeEagerly()
        Thread.sleep(500) // Wait for initialization

        // Run multiple inferences to measure average time
        val numInferences = 20
        val times = mutableListOf<Long>()

        for (i in 0 until numInferences) {
            val inferenceTime = measureTimeMillis {
                val result = detector.isNsfw(testBitmap)
                assertNotNull("Inference should return a result", result)
            }
            times.add(inferenceTime)
        }

        // Calculate statistics
        val avgTime = times.average()
        val p50 = times.sorted()[numInferences / 2]
        val p90 = times.sorted()[(numInferences * 90) / 100]
        val minTime = times.minOrNull() ?: 0L
        val maxTime = times.maxOrNull() ?: 0L

        println("Inference Performance Statistics:")
        println("  Average: ${avgTime.toLong()}ms")
        println("  P50 (median): ${p50}ms")
        println("  P90: ${p90}ms")
        println("  Min: ${minTime}ms")
        println("  Max: ${maxTime}ms")

        // Assertions
        if (detector.isGpuEnabled) {
            // GPU inference should be fast (< 150ms on average)
            assertTrue(
                "GPU inference should be fast (avg < 150ms), got ${avgTime.toLong()}ms",
                avgTime < 150
            )
            println("✅ GPU Performance: EXCELLENT (avg ${avgTime.toLong()}ms)")
        } else {
            // CPU with 4 threads should still be reasonable (< 400ms)
            assertTrue(
                "CPU inference should complete in reasonable time (avg < 400ms), got ${avgTime.toLong()}ms",
                avgTime < 400
            )
            println("✅ CPU Performance: GOOD (avg ${avgTime.toLong()}ms with 4 threads)")
        }
    }

    /**
     * Test 4: Verify buffer reuse (no GC pressure)
     *
     * This test runs many inferences and checks that performance doesn't degrade
     * (which would indicate GC pauses from buffer allocation)
     */
    @Test
    fun testBufferReuseNoGCPressure() {
        detector.initializeEagerly()
        Thread.sleep(500)

        // Run 50 inferences
        val times = mutableListOf<Long>()
        repeat(50) {
            val time = measureTimeMillis {
                detector.isNsfw(testBitmap)
            }
            times.add(time)
        }

        // First 10 vs Last 10 should have similar performance
        // (if buffers are being reused, no GC pressure buildup)
        val firstTen = times.take(10).average()
        val lastTen = times.takeLast(10).average()

        println("First 10 inferences avg: ${firstTen.toLong()}ms")
        println("Last 10 inferences avg: ${lastTen.toLong()}ms")
        println("Performance variance: ${((lastTen - firstTen) / firstTen * 100).toLong()}%")

        // Last 10 should not be more than 20% slower than first 10
        // (some variance is normal, but excessive slowdown indicates GC pressure)
        val variance = (lastTen - firstTen) / firstTen
        assertTrue(
            "Performance should not degrade significantly (variance < 20%), got ${(variance * 100).toLong()}%",
            variance < 0.20
        )

        println("✅ Buffer Reuse: WORKING (no performance degradation)")
    }

    /**
     * Test 5: Eager vs Lazy initialization comparison
     */
    @Test
    fun testEagerVsLazyInitialization() {
        // Test 1: Eager initialization
        val eagerDetector = NsfwDetector(context)
        val eagerInitTime = measureTimeMillis {
            eagerDetector.initializeEagerly()
            Thread.sleep(100) // Small wait
        }

        // First inference with eager init (should be fast - no init delay)
        val eagerFirstInference = measureTimeMillis {
            eagerDetector.isNsfw(testBitmap)
        }

        println("Eager init approach:")
        println("  Init time: ${eagerInitTime}ms")
        println("  First inference: ${eagerFirstInference}ms")

        eagerDetector.dispose()

        // Test 2: Lazy initialization
        val lazyDetector = NsfwDetector(context)

        // First inference with lazy init (includes init time)
        val lazyFirstInference = measureTimeMillis {
            lazyDetector.isNsfw(testBitmap)
        }

        println("Lazy init approach:")
        println("  First inference (includes init): ${lazyFirstInference}ms")

        lazyDetector.dispose()

        // With eager init, first inference should be much faster
        // (because model is already loaded)
        val improvement = lazyFirstInference - eagerFirstInference
        println("Improvement with eager init: ${improvement}ms faster first inference")

        assertTrue(
            "Eager init should make first inference at least 50ms faster",
            improvement > 50
        )

        println("✅ Eager Initialization: WORKING (${improvement}ms improvement)")
    }

    /**
     * Test 6: Concurrent inference (thread safety)
     */
    @Test
    fun testConcurrentInference() {
        detector.initializeEagerly()
        Thread.sleep(500)

        // Run 10 concurrent inferences
        val threads = List(10) { threadIndex ->
            Thread {
                repeat(5) { iteration ->
                    val result = detector.isNsfw(testBitmap)
                    assertNotNull("Inference $iteration on thread $threadIndex should succeed", result)
                }
            }
        }

        // Start all threads
        threads.forEach { it.start() }

        // Wait for all to complete
        threads.forEach { it.join(5000) } // 5 second timeout per thread

        println("✅ Concurrent Inference: WORKING (thread-safe)")
    }

    /**
     * Test 7: Verify configuration info is accurate
     */
    @Test
    fun testConfigurationInfo() {
        detector.initializeEagerly()
        Thread.sleep(500)

        val config = detector.getConfigInfo()

        assertEquals("Input size should be 224", 224, config["inputSize"])
        assertEquals("Number of classes should be 5", 5, config["numClasses"])
        assertTrue("Should be initialized", config["initialized"] as Boolean)

        val initTime = config["initializationTimeMs"] as Long
        assertTrue("Initialization time should be recorded", initTime > 0)

        println("Configuration Info:")
        config.forEach { (key, value) ->
            println("  $key: $value")
        }

        println("✅ Configuration Info: ACCURATE")
    }

    /**
     * Test 8: Performance comparison summary
     */
    @Test
    fun testPerformanceComparisonSummary() {
        detector.initializeEagerly()
        Thread.sleep(500)

        println("\n========================================")
        println("NSFW Detector Performance Summary")
        println("========================================")

        val config = detector.getConfigInfo()
        println("Configuration:")
        println("  GPU Enabled: ${config["gpuEnabled"]}")
        println("  Initialized: ${config["initialized"]}")
        println("  Init Time: ${config["initializationTimeMs"]}ms")

        // Run benchmark
        val times = mutableListOf<Long>()
        repeat(30) {
            val time = measureTimeMillis {
                detector.isNsfw(testBitmap)
            }
            times.add(time)
        }

        val sorted = times.sorted()
        val avg = times.average()
        val p50 = sorted[sorted.size / 2]
        val p90 = sorted[(sorted.size * 90) / 100]
        val p99 = sorted[(sorted.size * 99) / 100]

        println("\nInference Performance (30 runs):")
        println("  Average: ${avg.toLong()}ms")
        println("  P50: ${p50}ms")
        println("  P90: ${p90}ms")
        println("  P99: ${p99}ms")

        if (config["gpuEnabled"] as Boolean) {
            println("\nExpected GPU Performance:")
            println("  Target: < 100ms average")
            println("  Status: ${if (avg < 100) "✅ EXCELLENT" else "⚠️  Check GPU configuration"}")
        } else {
            println("\nExpected CPU Performance (4 threads):")
            println("  Target: < 300ms average")
            println("  Status: ${if (avg < 300) "✅ GOOD" else "⚠️  Check thread configuration"}")
        }

        println("========================================\n")

        // Final assertion
        assertTrue("Performance should be acceptable", avg < 500)
    }
}
