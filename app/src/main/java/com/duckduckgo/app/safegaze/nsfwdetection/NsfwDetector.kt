package com.duckduckgo.app.safegaze.nsfwdetection

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType.FLOAT32
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod.BILINEAR
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import timber.log.Timber
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optimized NSFW detector using TensorFlow Lite with GPU acceleration.
 *
 * Optimizations implemented:
 * 1. GPU delegate with FP16 precision and automatic fallback to CPU
 * 2. Pre-allocated buffer reuse (no GC pressure)
 * 3. Multi-threaded CPU execution (4 threads + XNNPACK)
 * 4. Eager initialization support
 */
class NsfwDetector(val context: Context) {
    private val inputImageSize = 224
    private val numClasses = 5

    var modelInitializationTime = 0L
        private set

    var isGpuEnabled = false
        private set

    @Volatile
    var isInitialized = false
        private set

    var numThreadsConfigured = 0
        private set

    @Volatile
    private var interpreter: Interpreter? = null
    @Volatile
    private var gpuDelegate: GpuDelegate? = null

    // Pre-allocated buffers (reused across inferences)
    private val inputBuffer = TensorBuffer.createFixedSize(
        intArrayOf(1, inputImageSize, inputImageSize, 3),
        FLOAT32
    )
    private val outputBuffer = TensorBuffer.createFixedSize(
        intArrayOf(1, numClasses),
        FLOAT32
    )

    // Split into separate processors for [PERF] timing (resize vs preprocess)
    private val resizeProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputImageSize, inputImageSize, BILINEAR))
        .build()

    private val normalizeProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(0f, 255f))
        .build()

    // Thread-safe initialization flag
    private val initializationLock = Object()
    private val isInitializing = AtomicBoolean(false)

    /**
     * Initialize the model eagerly.
     * Call this from Application.onCreate() to avoid first-inference delay.
     */
    fun initializeEagerly() {
        if (isInitialized || isInitializing.get()) {
            Timber.d("kLog NSFW model already initialized or initializing")
            return
        }

        try {
            isInitializing.set(true)
            initializeModel()
        } catch (e: Exception) {
            Timber.e("kLog Failed to eagerly initialize NSFW model: ${e.message}")
            isInitializing.set(false)
        }
    }

    private fun initializeModel() {
        synchronized(initializationLock) {
            if (isInitialized) {
                return
            }

            val t1 = System.currentTimeMillis()

            try {
                val options = createOptimizedInterpreterOptions()
                val modelFile = loadModelFile(context, "nsfw.tflite")
                interpreter = Interpreter(modelFile, options)

                modelInitializationTime = System.currentTimeMillis() - t1
                isInitialized = true
                isInitializing.set(false)

                Timber.d(
                    "kLog NSFW model initialized in ${modelInitializationTime}ms " +
                        "(GPU: $isGpuEnabled, CPU threads: $numThreadsConfigured)"
                )
                Timber.tag("NsfwDetector").i(
                    "[PERF] Model loaded: time=${modelInitializationTime}ms, delegate=${if (isGpuEnabled) "GPU" else "CPU"}, threads=$numThreadsConfigured"
                )
            } catch (e: Exception) {
                isInitializing.set(false)
                Timber.e("kLog Failed to initialize NSFW model: ${e.message}", e)
                throw e
            }
        }
    }


    private fun createOptimizedInterpreterOptions(): Interpreter.Options {
        return Interpreter.Options().apply {
            try {
                val compatList = CompatibilityList()
                if (compatList.isDelegateSupportedOnThisDevice) {
                    // Use bestOptionsForThisDevice to auto-tune GPU delegate for the device's
                    // GPU (Adreno, Mali, PowerVR, etc.) instead of generic defaults.
                    val gpuDelegateOptions = try {
                        compatList.bestOptionsForThisDevice
                    } catch (e: Exception) {
                        Timber.w("bestOptionsForThisDevice failed, using manual options: ${e.message}")
                        GpuDelegateFactory.Options()
                    }.apply {
                        setPrecisionLossAllowed(true)
                        setInferencePreference(
                            GpuDelegateFactory.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER
                        )
                    }
                    val delegate = GpuDelegate(gpuDelegateOptions)
                    addDelegate(delegate)
                    gpuDelegate = delegate
                    isGpuEnabled = true
                    Timber.d("kLog GPU delegate enabled for NSFW model (bestOptionsForThisDevice + FP16)")
                    return@apply
                } else {
                    Timber.d("kLog GPU delegate not supported on this device")
                }
            } catch (e: Exception) {
                Timber.w("kLog GPU delegate initialization failed: ${e.message}")
            }

            // CPU fallback with multi-threading
            try {
                val numCores = Runtime.getRuntime().availableProcessors()
                val optimalThreads = maxOf(1, (numCores * 0.25).toInt())

                setNumThreads(optimalThreads)
                numThreadsConfigured = optimalThreads
                Timber.d("kLog Using CPU with $optimalThreads threads (25% of $numCores cores) for NSFW model")

                // Enable XNNPACK delegate for optimized CPU operations
                setUseXNNPACK(true)
                Timber.d("kLog XNNPACK delegate enabled")
            } catch (e: Exception) {
                Timber.w("kLog Failed to configure CPU optimizations: ${e.message}")
                // Fallback to default single-threaded CPU
                setNumThreads(1)
                numThreadsConfigured = 1
            }
        }
    }

    /**
     * Load model file from assets.
     */
    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Detect NSFW content in the provided bitmap.
     *
     * @param bitmap Input image to classify
     * @return NsfwPrediction with classification results, or null on error
     */
    fun isNsfw(bitmap: Bitmap): NsfwPrediction? {
        // Hold initializationLock during entire inference to prevent use-after-close race
        // with dispose(). Also protects shared pre-allocated buffers from concurrent corruption.
        // Java synchronized is reentrant, so nested initializeModel() call is safe.
        synchronized(initializationLock) {
            if (!isInitialized && !isInitializing.get()) {
                Timber.d("kLog NSFW model not initialized, initializing now (lazy)")
                initializeModel()
            }

            val currentInterpreter = interpreter
            if (currentInterpreter == null) {
                Timber.e("kLog NSFW model not initialized")
                return null
            }

            return try {
                val totalStart = System.currentTimeMillis()
                val inputW = bitmap.width
                val inputH = bitmap.height

                // Phase 1: decode (N/A in native browser - bitmap already decoded from network)
                val decodeMs = 0L

                // Phase 2: Resize to model input size
                val resizeStart = System.currentTimeMillis()
                val tensorImage = TensorImage(FLOAT32)
                tensorImage.load(bitmap)
                val resizedImage = resizeProcessor.process(tensorImage)
                val resizeMs = System.currentTimeMillis() - resizeStart

                // Phase 3: Preprocess (normalize pixels to 0-1 range)
                val preprocessStart = System.currentTimeMillis()
                val processedImage = normalizeProcessor.process(resizedImage)
                inputBuffer.loadBuffer(processedImage.tensorBuffer.buffer)
                val preprocessMs = System.currentTimeMillis() - preprocessStart

                // Phase 4: Run inference under lock — prevents use-after-close race with dispose()
                val inferenceStart = System.currentTimeMillis()
                currentInterpreter.run(inputBuffer.buffer, outputBuffer.buffer)
                val inferenceMs = System.currentTimeMillis() - inferenceStart

                val totalMs = System.currentTimeMillis() - totalStart
                val delegate = if (isGpuEnabled) "GPU" else "CPU"
                Timber.tag("NsfwDetector").i(
                    "[PERF] NSFW classify: total=${totalMs}ms | decode=${decodeMs}ms, resize=${resizeMs}ms, preprocess=${preprocessMs}ms, inference=${inferenceMs}ms | input=${inputW}x${inputH}, delegate=$delegate"
                )

                val prediction = NsfwPrediction(outputBuffer.floatArray.clone())
                prediction
            } catch (e: Exception) {
                Timber.e("kLog NSFW inference error: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Dispose resources when detector is no longer needed.
     */
    fun dispose() {
        synchronized(initializationLock) {
            try {
                interpreter?.close()
                interpreter = null

                gpuDelegate?.close()
                gpuDelegate = null

                isInitialized = false
                isGpuEnabled = false
                // Reset isInitializing so initializeEagerly() can reinitialize after dispose
                isInitializing.set(false)

                Timber.d("kLog NSFW model disposed")
            } catch (e: Exception) {
                Timber.e("kLog Error disposing NSFW model: ${e.message}")
            }
        }
    }

    /**
     * Get current configuration info for debugging/analytics.
     */
    fun getConfigInfo(): Map<String, Any> {
        val numCores = Runtime.getRuntime().availableProcessors()
        return mapOf(
            "initialized" to isInitialized,
            "gpuEnabled" to isGpuEnabled,
            "initializationTimeMs" to modelInitializationTime,
            "numThreads" to numThreadsConfigured,
            "cpuCores" to numCores,
            "inputSize" to inputImageSize,
            "numClasses" to numClasses
        )
    }
}
