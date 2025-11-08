# Image Detection Performance Improvement Report

**Project:** Android Browser (DuckDuckGo Fork)
**Feature:** SafeGaze Image Detection & Processing
**Date:** 2025-11-08
**Status:** Analysis Complete

---

## Executive Summary

This report analyzes the current image detection and processing pipeline to identify performance bottlenecks and improvement opportunities. The system currently:
- Injects JavaScript to detect images on web pages
- Processes images in Kotlin using ML models (NSFW detection + body segmentation)
- Applies masks and replaces original images
- Has bidirectional communication between JavaScript and Kotlin
- Sometimes re-downloads images due to CORS issues

**Key Finding:** The current architecture has 15 major optimization opportunities that could deliver **2-3x overall performance improvement** with significantly better perceived performance.

---

## Current Architecture Overview

### High-Level Flow

```
1. JavaScript Detection → Monitors DOM for images
2. Image Download → Downloads and converts to base64 in JS (with CORS handling)
3. Message to Kotlin → Sends image data via JavaScript bridge
4. Re-download in Kotlin → Downloads image again with ImageDownloader (due to CORS)
5. NSFW Detection → First-pass filter using TensorFlow Lite model
6. Segmentation Processing → Body/pose detection if not NSFW
7. Result to JavaScript → Sends processed image back
8. Image Replacement → JavaScript replaces original with masked version
```

### Key Components

**JavaScript Layer:**
- **File:** `node_modules/@duckduckgo/privacy-dashboard/build/app/safe_gaze_v2.js` (1752 lines)
- **Responsibilities:** Image discovery (MutationObserver), canvas processing, blur effects, bridge communication

**Kotlin Bridge:**
- **File:** `app/src/main/java/com/duckduckgo/app/browser/safe_gaze/SafeGazeJsInterface.kt` (414 lines)
- **Responsibilities:** Queue management, image downloads, NSFW + segmentation coordination, caching

**Models:**
- **NSFW Detection:** TensorFlow Lite (224x224 input, 5-class output, threshold >= 0.85)
- **Segmentation:** External library (`io.kahf.kahf_segmentation.ImageProcessor`)

**Database Cache:**
- **File:** `app-store/src/main/java/com/duckduckgo/app/trackerdetection/db/KahfImageBlocked.kt`
- **Key:** MD5(URL + maskType)
- **Stores:** Result string, dimensions, isIndecent flag, timestamp

---

## Performance Bottlenecks & Improvement Opportunities

### 🔴 CRITICAL ISSUES

#### **1. Double Download Problem (HIGHEST IMPACT)**

**Location:**
- `SafeGazeJsInterface.kt:123-168`
- `safe_gaze_v2.js:1523-1547`

**Current Behavior:**
1. JavaScript downloads image via canvas (`getImageData()`)
2. Converts to base64 (JPEG 60% quality, max 400x500)
3. Sends base64 to Kotlin via bridge
4. **Kotlin receives base64 → Downloads the same image AGAIN** via `ImageDownloader`
5. Uses re-downloaded image for processing

**Why This Happens:** CORS restrictions prevent JS from accessing some images

**Impact:**
- ~2x download time
- ~2x network bandwidth usage
- ~2x memory consumption
- Users on slow networks heavily penalized

**Solution:**

```kotlin
// SafeGazeJsInterface.kt:123-140
private fun startImageDownload(input: InputImage) {
    downloadTracker[input.id] = DownloadStatus.Pending
    var acquired = false
    scope.launch {
        try {
            downloadSemaphore.acquire()
            acquired = true

            val downloadTime = measureTimeMillis {
                val bitmap = if (!input.baseImg.isNullOrEmpty()) {
                    // ✅ Use base64 from JavaScript if available
                    Timber.d("kLog Using base64 from JS for ${input.id}")
                    decodeBase64ToBitmap(input.baseImg)
                } else {
                    // ⚠️ Fallback: Download only if JS couldn't provide
                    Timber.d("kLog Re-downloading ${input.id} due to CORS")
                    withTimeout(2000) {
                        imageDownloader.downloadImageWithAspectRatio(
                            context, input.src, null, input.width, input.height
                        )
                    }
                }

                // ... rest of the logic
            }
        } catch (e: Exception) {
            // ...
        }
    }
}

private fun decodeBase64ToBitmap(base64: String): Bitmap? {
    return try {
        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (e: Exception) {
        Timber.e("Failed to decode base64: ${e.message}")
        null
    }
}
```

**Expected Gain:**
- **30-50% faster** processing for images JS can successfully download
- **50% reduction** in network bandwidth
- **40% reduction** in memory pressure

---

#### **2. Sequential Queue Processing with Polling**

**Location:** `SafeGazeJsInterface.kt:171-351`

**Current Issues:**
1. Single coroutine processes queue **sequentially** (line 173)
2. **Polls every 50ms** waiting for downloads to complete (line 182)
3. Only **ONE image** processed at a time despite 5 concurrent downloads
4. **10ms arbitrary delay** between each task (line 207)
5. Linear search through queue to find ready tasks (line 176)

**Current Code:**
```kotlin
private fun processQueue() {
    if (!paused.get() && processingJob?.isActive != true) {
        processingJob = scope.launch {
            while (urlQueue.isNotEmpty()) {
                // 🔴 LINEAR SEARCH for ready task
                val readyTask = urlQueue.find { task ->
                    val status = downloadTracker[task.id]
                    status is DownloadStatus.Success || status is DownloadStatus.Failed
                }

                if (readyTask == null) {
                    delay(50) // 🔴 POLLING - wastes CPU
                    continue
                }

                urlQueue.remove(readyTask)

                // ... process ONE task at a time
                delay(10) // 🔴 ARBITRARY DELAY
            }
        }
    }
}
```

**Impact:**
- CPU cycles wasted on polling
- Underutilized: 5 images downloading, but only 1 processing
- Poor throughput on pages with many images

**Solution:**

```kotlin
// Replace polling with event-driven approach
private val readyTasksChannel = Channel<InputImage>(Channel.UNLIMITED)
private val processingConcurrencyLimit = 2 // Process 2 images concurrently

private fun startImageDownload(input: InputImage) {
    // ... existing download logic ...

    if (bitmap != null) {
        downloadTracker[input.id] = DownloadStatus.Success(bitmap)
        // ✅ NOTIFY instead of polling
        readyTasksChannel.trySend(input)
    } else {
        downloadTracker[input.id] = DownloadStatus.Failed
        readyTasksChannel.trySend(input) // Notify failure too
    }
}

private fun processQueue() {
    if (!paused.get() && processingJob?.isActive != true) {
        processingJob = scope.launch {
            // ✅ Process multiple images concurrently
            repeat(processingConcurrencyLimit) {
                launch {
                    for (task in readyTasksChannel) {
                        processTask(task) // No delays, no polling
                    }
                }
            }
        }
    }
}

private suspend fun processTask(readyTask: InputImage) {
    when (val downloadStatus = downloadTracker[readyTask.id]) {
        is DownloadStatus.Failed -> {
            downloadTracker.remove(readyTask.id)
            onImageClassified("detectionResult", OutputImage(
                result = "null",
                id = readyTask.id,
                width = readyTask.width,
                height = readyTask.height,
                from = "statusFailed"
            ))
        }
        is DownloadStatus.Success -> {
            // ✅ Process immediately - no delays
            processImageWithNSFWAndSegmentation(readyTask, downloadStatus.bitmap)
        }
        else -> {
            // Should not happen with event-driven approach
            Timber.w("Unexpected download status for ${readyTask.id}")
        }
    }
}
```

**Expected Gain:**
- **40-60% faster** queue throughput
- **No CPU waste** on polling
- **Better parallelism** - process while downloading

---

#### **3. Cache Check Happens TOO LATE**

**Location:** `SafeGazeJsInterface.kt:212-228`

**Current Flow:**
```
1. Image added to queue
2. Download starts IMMEDIATELY
3. Wait for download to complete
4. Remove from queue
5. ❌ THEN check cache (line 212)
6. If cached → Wasted download!
```

**Current Code:**
```kotlin
private fun addTaskToQueue(input: InputImage?) {
    // ... validation ...

    // 🔴 Start download WITHOUT checking cache first
    startImageDownload(input)
    urlQueue.add(input)
    processQueue()
}

// Later in processQueue (line 212):
private fun processQueue() {
    // ... after download completes ...

    val maskType = getMaskType()
    val cachedResult = kahfImageBlockedDao.findByUrl(
        "${it.src?.md5()}_$maskType"
    )

    if (cachedResult != null) {
        // 🔴 Found in cache AFTER downloading!
        onImageClassified("detectionResult", ...)
        return
    }

    // Only now process...
}
```

**Impact:**
- **Wastes 100% of download time** for cached images
- **Wastes bandwidth** re-downloading known images
- **Delays results** - could return immediately from cache

**Solution:**

```kotlin
private fun addTaskToQueue(input: InputImage?) {
    if (input == null || isInvalidImageUrl(input.src)) {
        onImageClassified("detectionResult", OutputImage(
            result = "null",
            id = input?.id ?: "",
            width = input?.width ?: 0,
            height = input?.height ?: 0,
            from = "addTaskToQueue"
        ))
        return
    }

    // ✅ CHECK CACHE FIRST - before any downloads!
    val maskType = getMaskType()
    val cacheKey = "${input.src?.md5()}_$maskType"

    scope.launch {
        val cachedResult = withContext(Dispatchers.IO) {
            kahfImageBlockedDao.findByUrl(cacheKey)
        }

        if (cachedResult != null) {
            // ✅ IMMEDIATE return - no download needed!
            Timber.d("kLog Cache hit BEFORE download: ${input.id}")
            onImageClassified("detectionResult", OutputImage(
                result = cachedResult.responseStr,
                id = input.id,
                width = input.width,
                height = input.height,
                from = "cache_early"
            ))
            return@launch
        }

        // ✅ Only download if NOT cached
        if (urlQueue.any { it.id == input.id } || downloadTracker.containsKey(input.id)) {
            return@launch
        }

        startImageDownload(input)
        urlQueue.add(input)
        processQueue()
    }
}
```

**Expected Gain:**
- **100% faster** for cached images (instant return vs. full download + process)
- **Zero bandwidth** for repeat visits
- **Better UX** - instant unblur for known-safe images

---

### 🟡 HIGH PRIORITY ISSUES

#### **4. Unnecessary JavaScript Canvas Processing**

**Location:** `safe_gaze_v2.js:1523-1547`

**Current:** JS creates canvas, resizes, and converts to base64 **before** knowing if cached

```javascript
const processImage = async (htmlElement, src, type = "image", srcChanged = false) => {
    // ... validation ...

    blurImage(htmlElement, true); // Apply blur immediately

    const uid = Math.random().toString(36).substr(2, 9);

    try {
        // 🔴 EXPENSIVE canvas processing BEFORE cache check
        const { imgData, canvas, ctx, ratio } = await getImageData(srcEdited);
        htmlElement.ourCanvas = canvas;
        htmlElement.ourContext = ctx;
        htmlElement.ourRatio = ratio;

        sendMessage("coreML/-/" + srcEdited + "/-/" + uid + "/-/" + imgData);
    } catch (e) {
        sendMessage("coreML/-/" + srcEdited + "/-/" + uid);
    }
};
```

**Problem:**
- Downloads image in JS
- Creates canvas and processes pixels
- All wasted work if image is cached in Kotlin

**Solution:**

```javascript
const processImage = async (htmlElement, src, type = "image", srcChanged = false) => {
    // ... validation ...

    blurImage(htmlElement, true);

    const uid = Math.random().toString(36).substr(2, 9);

    // ✅ Send lightweight metadata FIRST
    sendMessage("checkCache/-/" + srcEdited + "/-/" + uid);

    // Store callback for when Kotlin responds
    onProcessImageMap.set(uid, {
        element: htmlElement,
        src: srcEdited,
        type: type,
        processCanvas: async () => {
            // Only do expensive work if Kotlin requests it
            try {
                const { imgData, canvas, ctx, ratio } = await getImageData(srcEdited);
                htmlElement.ourCanvas = canvas;
                htmlElement.ourContext = ctx;
                htmlElement.ourRatio = ratio;
                return imgData;
            } catch (e) {
                return null;
            }
        }
    });
};

// New function called by Kotlin if image NOT cached
window.requestImageData = async function(uid) {
    const pending = onProcessImageMap.get(uid);
    if (!pending) return;

    const imgData = await pending.processCanvas();
    sendMessage("coreML/-/" + pending.src + "/-/" + uid + "/-/" + imgData);
};
```

**Expected Gain:**
- **20-30% less JS overhead** for cached images
- **Faster UI responsiveness** (no blocking canvas operations)

---

#### **5. Limited Concurrency for Model Inference**

**Location:** `SafeGazeJsInterface.kt:242-289`

**Current:**
- Downloads: **5 concurrent** ✅ (good)
- Processing: **1 at a time** ❌ (bottleneck)
- NSFW + Segmentation: **Sequential per image** ❌

**Problem:** Modern phones have multi-core CPUs + GPU, but only 1 image processed at a time

**Solution:**

```kotlin
private val nsfwSemaphore = Semaphore(2)  // 2 concurrent NSFW checks
private val segmentationSemaphore = Semaphore(1)  // 1 segmentation (GPU-heavy)

private suspend fun processImageWithNSFWAndSegmentation(
    input: InputImage,
    bitmap: Bitmap
) {
    try {
        // ✅ NSFW can run concurrently (CPU-bound, lightweight)
        nsfwSemaphore.acquire()
        val nsfwResult: NsfwPrediction?
        val nsfwInference = measureTimeMillis {
            nsfwResult = nsfwDetector.isNsfw(bitmap)
        }
        nsfwSemaphore.release()

        nsfwProcessingTimes.add(nsfwInference)
        Timber.d("kLog NSFW - ${nsfwResult?.isSafe()?.not()}. Time: $nsfwInference ms")

        if (nsfwResult?.isSafe() == false) {
            onImageClassified("detectionResult", OutputImage(
                result = "nsfw",
                id = input.id,
                width = input.width,
                height = input.height,
                isManipulated = true,
                from = "nsfw"
            ))
        } else {
            // ✅ Segmentation limited to 1 concurrent (GPU-heavy)
            segmentationSemaphore.acquire()
            try {
                val segmentationInf = measureTimeMillis {
                    imageDetector.downloadAndStore(input.copy(imgBitmap = bitmap)) { result ->
                        onImageClassified("detectionResult", result)
                        // ... cache logic ...
                    }
                }
                Timber.d("kLog Segmentation time: $segmentationInf ms")
            } finally {
                segmentationSemaphore.release()
            }
        }
    } catch (e: Exception) {
        Timber.e("kLog Error processing image: ${e.message}")
    } finally {
        downloadTracker.remove(input.id)
        bitmap.recycle() // ✅ Free memory immediately
    }
}
```

**Expected Gain:**
- **30-50% better throughput** on multi-image pages
- **Better CPU/GPU utilization**

---

#### **6. No Image Prefetching / Viewport Awareness**

**Location:** `safe_gaze_v2.js:1691-1733`

**Current:** Processes ALL detected images in DOM order, regardless of visibility

**Problem:**
- User sees first 3 images, but system processes image #15 first (DOM order)
- Poor perceived performance

**Solution:**

```javascript
// Use Intersection Observer for viewport detection
const imageObserver = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
        const img = entry.target;
        if (entry.isIntersecting) {
            // ✅ HIGH PRIORITY - visible in viewport
            img.dataset.sgPriority = "high";
            processImage(img, img.src, "image", false);
        } else {
            // ✅ LOW PRIORITY - offscreen
            img.dataset.sgPriority = "low";
            scheduleBackgroundProcessing(img);
        }
    });
}, {
    rootMargin: '50px' // Pre-load 50px before entering viewport
});

const scheduleBackgroundProcessing = debouncer((img) => {
    // Process offscreen images with delay
    setTimeout(() => {
        if (!img.dataset.sgIsSent) {
            processImage(img, img.src, "image", false);
        }
    }, 1000);
}, 500);

// Update observeElement
const observeElement = (el, srcChanged = false) => {
    // ... existing logic ...

    // ✅ Observe for viewport intersection
    if (el.tagName === "IMG") {
        imageObserver.observe(el);
    }
};
```

**In Kotlin - Priority Queue:**
```kotlin
// Update priority comparator
data class InputImage(
    val id: String,
    val src: String,
    val width: Int,
    val height: Int,
    val order: Int,
    val priority: String = "normal", // "high", "normal", "low"
    // ...
)

private val urlQueue: PriorityBlockingQueue<InputImage> = PriorityBlockingQueue(
    20,
    compareBy<InputImage> {
        when(it.priority) {
            "high" -> 0
            "normal" -> 1
            "low" -> 2
            else -> 1
        }
    }.thenBy { it.order }
)
```

**Expected Gain:**
- **40-60% better perceived performance** (visible images processed first)
- **Can cancel low-priority work** if user navigates away

---

### 🟢 MEDIUM PRIORITY ISSUES

#### **7. Base64 Encoding Overhead**

**Location:** `safe_gaze_v2.js:1546`, multiple Kotlin locations

**Current Data Flow:**
```
Bitmap → Canvas → JPEG → Base64 String → Bridge → Parse String → Decode Base64 → Bitmap
```

**Overhead:**
- Base64 encoding: ~33% size increase
- String allocation and GC pressure
- Encoding/decoding CPU time

**Solution (Advanced):**

```javascript
// Use Blob + ArrayBuffer (more efficient)
async function getImageDataAsBlob(src) {
    const corsImage = new Image();
    corsImage.crossOrigin = "anonymous";
    await new Promise((resolve, reject) => {
        corsImage.onload = resolve;
        corsImage.onerror = reject;
        corsImage.src = src;
    });

    const { width, height } = calculateResizeDimensions(
        corsImage.width,
        corsImage.height,
        400,
        500
    );

    const canvas = createCanvas(width, height);
    const ctx = canvas.getContext("2d");
    ctx.drawImage(corsImage, 0, 0, width, height);

    // ✅ Return Blob instead of base64
    return new Promise((resolve) => {
        canvas.toBlob((blob) => {
            const reader = new FileReader();
            reader.onloadend = () => {
                // ArrayBuffer - more efficient than base64
                resolve(new Uint8Array(reader.result));
            };
            reader.readAsArrayBuffer(blob);
        }, 'image/jpeg', 0.6);
    });
}
```

**In Kotlin - Update Bridge:**
```kotlin
@JavascriptInterface
fun sendImageBytes(imageId: String, bytes: ByteArray) {
    // ✅ Direct byte array - no base64 decode needed
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    // ... process bitmap ...
}
```

**Note:** Requires updating JavaScript bridge to support byte arrays

**Expected Gain:**
- **15-25% less encoding/decoding overhead**
- **20% less memory** (no base64 string allocation)

---

#### **8. No Batch Processing**

**Current:** Each image sends individual message to Kotlin bridge

**Problem:**
- Bridge call overhead for each image
- No cache locality

**Solution:**

```javascript
let pendingBatch = [];
let batchTimeout = null;

const processImage = async (htmlElement, src, type = "image") => {
    // ... existing logic ...

    // ✅ Add to batch instead of immediate send
    pendingBatch.push({
        uid: uid,
        src: srcEdited,
        imgData: imgData,
        width: htmlElement.width,
        height: htmlElement.height
    });

    // Debounce: send batch after 50ms of no new images
    clearTimeout(batchTimeout);
    batchTimeout = setTimeout(() => {
        if (pendingBatch.length > 0) {
            sendMessage("batchML/-/" + JSON.stringify(pendingBatch));
            pendingBatch = [];
        }
    }, 50);
};
```

```kotlin
@JavascriptInterface
fun sendBatchMessageFromWebView(messageType: String, data: String) {
    when (messageType) {
        "batchML" -> {
            val batch = gson.fromJson(data, Array<InputImage>::class.java)
            batch.forEach { addTaskToQueue(it) }
        }
    }
}
```

**Expected Gain:**
- **10-20% less bridge overhead**
- **Better cache locality** in processing

---

#### **9. Memory Management Issues**

**Current:**
- Downloads 5 images concurrently → 5 bitmaps in memory
- Queue holds 20 images → potential 20+ bitmaps
- **No explicit bitmap recycling**

**Solution:**

```kotlin
// Add bitmap pool
private val bitmapPool = object : LruCache<String, Bitmap>(10 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int {
        return value.byteCount
    }

    override fun entryRemoved(
        evicted: Boolean,
        key: String,
        oldValue: Bitmap,
        newValue: Bitmap?
    ) {
        if (evicted && !oldValue.isRecycled) {
            oldValue.recycle()
        }
    }
}

private suspend fun processImageWithNSFWAndSegmentation(
    input: InputImage,
    bitmap: Bitmap
) {
    try {
        // ... processing logic ...
    } finally {
        // ✅ ALWAYS recycle bitmap when done
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
        downloadTracker.remove(input.id)
    }
}

// Update download to check pool first
private fun startImageDownload(input: InputImage) {
    // ✅ Check bitmap pool first
    val pooledBitmap = bitmapPool.get(input.src)
    if (pooledBitmap != null && !pooledBitmap.isRecycled) {
        Timber.d("kLog Reusing bitmap from pool: ${input.id}")
        downloadTracker[input.id] = DownloadStatus.Success(pooledBitmap)
        readyTasksChannel.trySend(input)
        return
    }

    // ... existing download logic ...
}
```

**Expected Gain:**
- **30-50% less memory pressure**
- **Fewer GC pauses** (smoother UI)
- **Faster for duplicate images** (bitmap pool)

---

#### **10. No Request Deduplication at JS Level**

**Current:** Same image URL appears multiple times → processed multiple times

**Example:** Product page with same thumbnail in different locations

**Solution:**

```javascript
const pendingImages = new Map(); // URL -> Promise<result>
const processedResults = new Map(); // URL -> result

const processImage = async (htmlElement, src, type = "image") => {
    const srcEdited = src?.startsWith("://") ? "https:" + src : src;

    // ✅ Check if already processed
    if (processedResults.has(srcEdited)) {
        const result = processedResults.get(srcEdited);
        applyResult(htmlElement, result);
        return;
    }

    // ✅ Check if currently processing
    if (pendingImages.has(srcEdited)) {
        const result = await pendingImages.get(srcEdited);
        applyResult(htmlElement, result);
        return;
    }

    // ✅ Create new processing promise
    const processingPromise = actuallyProcessImage(htmlElement, srcEdited, type);
    pendingImages.set(srcEdited, processingPromise);

    const result = await processingPromise;
    processedResults.set(srcEdited, result);
    pendingImages.delete(srcEdited);

    applyResult(htmlElement, result);
};

function applyResult(element, result) {
    if (result === "nsfw") {
        blurImage(element);
    } else if (result && result.length > 0) {
        replaceWithMaskedImage(element, result);
    } else {
        unblurImage(element);
    }
}
```

**Expected Gain:**
- **50%+ faster** for pages with duplicate images
- **Less network and processing** for duplicates

---

### 🔵 LOW PRIORITY / ADVANCED

#### **11. Synchronous Cache Access**

**Location:** `SafeGazeJsInterface.kt:212`

**Current:** `kahfImageBlockedDao.findByUrl()` might block coroutine

**Solution:**

```kotlin
// Ensure DAO uses suspend functions
@Dao
interface KahfImageBlockedDao {
    @Query("SELECT * FROM kahf_image_blocked WHERE imageUrl = :url LIMIT 1")
    suspend fun findByUrl(url: String): KahfImageBlocked?  // ✅ suspend
}

// Use with proper async
scope.launch {
    val cachedResult = withContext(Dispatchers.IO) {
        kahfImageBlockedDao.findByUrl(cacheKey)
    }
}
```

**Expected Gain:**
- **5-10% better queue responsiveness**

---

#### **12. Progressive Rendering / Early Results**

**Current:** Image stays blurred until fully processed (NSFW + Segmentation)

**Problem:** Even if NSFW check passes in 50ms, user waits 300ms for segmentation

**Solution:**

```kotlin
private suspend fun processImageWithNSFWAndSegmentation(
    input: InputImage,
    bitmap: Bitmap
) {
    try {
        // Step 1: NSFW check (FAST)
        val nsfwResult = nsfwDetector.isNsfw(bitmap)

        if (nsfwResult?.isSafe() == false) {
            // ✅ IMMEDIATELY show NSFW result (blur entire image)
            onImageClassified("detectionResult", OutputImage(
                result = "nsfw",
                id = input.id,
                isManipulated = true,
                from = "nsfw"
            ))
            return // ✅ DONE - no need for segmentation
        } else {
            // ✅ Show "safe" result immediately (unblur)
            onImageClassified("detectionResult", OutputImage(
                result = "safe_preliminary",
                id = input.id,
                isManipulated = false,
                from = "nsfw_passed"
            ))

            // Step 2: Segmentation (SLOW) - runs in background
            val segResult = imageDetector.downloadAndStore(
                input.copy(imgBitmap = bitmap)
            ) { finalResult ->
                // ✅ UPDATE with precise mask when ready
                onImageClassified("detectionResult", finalResult)
            }
        }
    } catch (e: Exception) {
        Timber.e("kLog Error: ${e.message}")
    }
}
```

**In JavaScript:**
```javascript
window.receiveMessageFromKotlin = function(type, data) {
    const result = JSON.parse(data);
    const element = onProcessImageMap.get(result.id);

    if (result.from === "nsfw") {
        // NSFW - blur immediately
        blurImage(element);
    } else if (result.from === "nsfw_passed") {
        // ✅ Safe (preliminary) - unblur immediately
        unblurImage(element);
        // Wait for final segmentation result...
    } else {
        // Final segmentation result - apply precise mask
        if (result.result && result.result.length > 100) {
            replaceWithMaskedImage(element, result.result);
        }
    }
};
```

**Expected Gain:**
- **2-3x faster perceived time** to first result
- **Better UX** - images unblur faster if safe

---

#### **13. Inefficient Download Tracking**

**Location:** `SafeGazeJsInterface.kt:60-61, 113-169`

**Current:**
- `ConcurrentHashMap<String, DownloadStatus>` for tracking
- Linear search in queue for ready tasks (line 176)

**Problem:** O(n) search through queue every 50ms

**Solution:** Already covered in **#2 (Event-Driven Queue)**

---

#### **14. No Adaptive Quality / Smart Filtering**

**Current:** Processes ALL images with same quality, regardless of:
- Image size (tiny icon vs. large photo)
- Image type (photo vs. graphic/logo)
- Content (clearly not a person)

**Solution:**

```javascript
const shouldProcessImage = (element, src) => {
    const width = element.width || element.clientWidth;
    const height = element.height || element.clientHeight;

    // ✅ Skip tiny images
    if (width < 100 || height < 100) {
        return false;
    }

    // ✅ Skip very small file size (likely icon/logo)
    if (src.includes('icon') || src.includes('logo') || src.includes('sprite')) {
        return false;
    }

    // ✅ Skip square images under 200px (likely avatars/thumbnails)
    if (Math.abs(width - height) < 20 && width < 200) {
        return false;
    }

    // ✅ Detect if image is likely a graphic (check color palette complexity)
    // This would require analyzing the canvas - skip for now

    return true;
};

const processImage = async (htmlElement, src, type = "image") => {
    if (!shouldProcessImage(htmlElement, src)) {
        unblurImage(htmlElement);
        return;
    }

    // ... existing processing logic ...
};
```

**Adaptive Quality:**
```javascript
function calculateQualitySettings(width, height) {
    const pixels = width * height;

    if (pixels < 50000) { // < 223x223
        return { maxWidth: 224, maxHeight: 224, quality: 0.5 };
    } else if (pixels < 200000) { // < 447x447
        return { maxWidth: 300, maxHeight: 300, quality: 0.6 };
    } else {
        return { maxWidth: 400, maxHeight: 500, quality: 0.6 };
    }
}
```

**Expected Gain:**
- **25-40% less work** (skip non-photo content)
- **Better performance** on icon-heavy pages

---

#### **15. Network Timeout Too Aggressive**

**Location:** `SafeGazeJsInterface.kt:133`

**Current:** Fixed 2-second timeout

**Problem:**
- Slow networks → many timeouts → images not processed
- Fast networks → waiting unnecessarily

**Solution:**

```kotlin
private var averageDownloadTime = 1000L // Start with 1s estimate
private val downloadTimes = mutableListOf<Long>()

private fun calculateAdaptiveTimeout(estimatedSize: Long): Long {
    val networkType = getNetworkType()

    val baseTimeout = when (networkType) {
        "wifi" -> 2000L
        "4g" -> 4000L
        "3g" -> 8000L
        else -> 10000L
    }

    // Use P90 of recent download times
    val recentP90 = if (downloadTimes.size >= 10) {
        downloadTimes.sorted()[downloadTimes.size * 90 / 100]
    } else {
        baseTimeout
    }

    return (recentP90 * 1.5).toLong().coerceIn(2000L, 15000L)
}

private fun startImageDownload(input: InputImage) {
    val adaptiveTimeout = calculateAdaptiveTimeout(0L)

    val downloadTime = measureTimeMillis {
        val bitmap = withTimeout(adaptiveTimeout) { // ✅ Adaptive
            imageDownloader.downloadImageWithAspectRatio(...)
        }
    }

    // Track download time
    downloadTimes.add(downloadTime)
    if (downloadTimes.size > 30) {
        downloadTimes.removeAt(0)
    }
}

private fun getNetworkType(): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return "unknown"
    val capabilities = cm.getNetworkCapabilities(network) ?: return "unknown"

    return when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
            // Could check cellular generation if needed
            "4g"
        }
        else -> "unknown"
    }
}
```

**Expected Gain:**
- **20-30% fewer false failures** on slow networks
- **Better adaptation** to network conditions

---

## Implementation Priority

### 🚀 Phase 1 - Quick Wins (1-2 days)

**Recommended order:**

1. **#3 - Check cache BEFORE downloading**
   - **Impact:** Highest immediate impact
   - **Effort:** Low (1-2 hours)
   - **Risk:** Very low
   - **Gain:** 100% faster for cached images

2. **#1 - Use base64 from JavaScript**
   - **Impact:** Eliminates double download
   - **Effort:** Low (2-3 hours)
   - **Risk:** Low (has fallback)
   - **Gain:** 30-50% faster, 50% less bandwidth

3. **#2 - Replace polling with event-driven queue**
   - **Impact:** Better throughput
   - **Effort:** Medium (4-6 hours)
   - **Risk:** Medium (core architecture change)
   - **Gain:** 40-60% better queue performance

4. **#11 - JavaScript deduplication**
   - **Impact:** Huge for duplicate images
   - **Effort:** Low (2-3 hours)
   - **Risk:** Very low
   - **Gain:** 50%+ for duplicate images

**Phase 1 Expected Results:**
- **60-80% faster** for cached images
- **30-50% faster** for new images
- **50% less** network bandwidth
- **40% less** memory usage

---

### 🎯 Phase 2 - Medium Effort (3-5 days)

5. **#12 - Progressive rendering (NSFW early results)**
   - **Impact:** Much better UX
   - **Effort:** Medium (4-6 hours)
   - **Gain:** 2-3x faster perceived performance

6. **#6 - Viewport-based prioritization**
   - **Impact:** Better perceived performance
   - **Effort:** Medium (6-8 hours)
   - **Risk:** Low
   - **Gain:** 40-60% better UX

7. **#5 - Parallel processing (2-3 concurrent)**
   - **Impact:** Better throughput
   - **Effort:** Medium (4-6 hours)
   - **Risk:** Medium (test GPU handling)
   - **Gain:** 30-50% better throughput

8. **#9 - Bitmap memory management**
   - **Impact:** Stability + performance
   - **Effort:** Medium (6-8 hours)
   - **Risk:** Low
   - **Gain:** 30-50% less memory, smoother UI

**Phase 2 Expected Results:**
- **2-3x better perceived performance**
- **More stable** (better memory management)
- **Better multi-image handling**

---

### 🔬 Phase 3 - Advanced Optimizations (1-2 weeks)

9. **#8 - Batch processing**
   - **Effort:** Medium (8-10 hours)
   - **Gain:** 10-20% less overhead

10. **#14 - Adaptive quality / smart filtering**
    - **Effort:** High (2-3 days)
    - **Gain:** 25-40% less work

11. **#7 - Use Blob/ArrayBuffer instead of base64**
    - **Effort:** High (2-3 days)
    - **Risk:** High (bridge changes)
    - **Gain:** 15-25% less encoding overhead

12. **#15 - Adaptive network timeout**
    - **Effort:** Medium (4-6 hours)
    - **Gain:** 20-30% fewer timeouts

---

## Expected Overall Impact

### Phase 1 Only
- **Processing Speed:** 60-80% faster for cached, 30-50% for new
- **Network:** 50% reduction in bandwidth
- **Memory:** 40% less memory pressure
- **User Experience:** Noticeably faster

### All Phases Combined
- **Processing Speed:** 2-3x overall improvement
- **Perceived Performance:** 3-4x faster (progressive rendering + viewport priority)
- **Network:** 60-70% bandwidth reduction
- **Memory:** 50-60% less memory usage
- **Stability:** Fewer crashes, smoother scrolling

---

## Monitoring & Metrics

### Current Metrics (Keep)
- ✅ P90 NSFW processing time
- ✅ P90 core inference time
- ✅ P90 masking/pixelation time
- ✅ Average queue waiting time

### Additional Metrics Needed
- **Cache hit rate** - % of images served from cache
- **Download success rate** - % of successful vs failed downloads
- **Time to first result** - User perception metric
- **Memory usage** - Peak and average bitmap memory
- **JS processing time** - Canvas operations duration
- **Duplicate detection rate** - % of images deduplicated

### Success Criteria
- Cache hit rate > 40% on repeat visits
- Time to first result < 100ms for cached images
- Time to first result < 500ms for new images (NSFW check)
- Download success rate > 95%
- Peak memory < 50MB for 20 concurrent images

---

## Testing Strategy

### Unit Tests
- Cache retrieval logic
- Base64 decoding
- Priority queue ordering
- Bitmap recycling

### Integration Tests
- End-to-end: JS detection → Kotlin processing → JS rendering
- Cache persistence across sessions
- Network failure handling
- Memory leak detection

### Performance Tests
- Load page with 50 images, measure:
  - Time to process all images
  - Memory peak
  - Network bandwidth
  - UI frame rate
- Compare before/after each phase

### Real-World Testing
- Test on various devices:
  - Low-end (2GB RAM)
  - Mid-range (4GB RAM)
  - High-end (8GB+ RAM)
- Test on various networks:
  - WiFi
  - 4G
  - Slow 3G
- Test various content:
  - E-commerce (many duplicate thumbnails)
  - News sites (large photos)
  - Social media (mixed content)

---

## Risk Assessment

### Low Risk Changes
- ✅ Cache check before download (#3)
- ✅ Use JS base64 with fallback (#1)
- ✅ JavaScript deduplication (#11)
- ✅ Progressive rendering (#12)

### Medium Risk Changes
- ⚠️ Event-driven queue (#2) - Core architecture
- ⚠️ Parallel processing (#5) - GPU contention
- ⚠️ Bitmap pooling (#9) - Memory management

### High Risk Changes
- ❌ Bridge changes for byte arrays (#7) - API change
- ❌ Adaptive filtering (#14) - Might miss images

### Mitigation Strategies
1. **Feature flags** - Roll out changes gradually
2. **A/B testing** - Compare old vs new implementation
3. **Comprehensive logging** - Track failures
4. **Graceful degradation** - Fallback to old behavior on errors
5. **Memory monitoring** - Alert on high memory usage

---

## Appendix: Architecture Diagrams

### Current Flow
```
┌─────────────┐
│  WebPage    │
└──────┬──────┘
       │
       ▼
┌─────────────────────────┐
│ JavaScript Detection    │
│ - MutationObserver      │
│ - Canvas download       │ ❌ Expensive
│ - Base64 encoding       │ ❌ Overhead
└──────┬──────────────────┘
       │
       ▼ (via Bridge)
┌─────────────────────────┐
│ Kotlin SafeGazeInterface│
│ - Download AGAIN        │ ❌ Duplicate
│ - Add to queue          │
└──────┬──────────────────┘
       │
       ▼ (50ms polling)
┌─────────────────────────┐
│ Process Queue           │
│ - Check cache (late!)   │ ❌ Too late
│ - ONE at a time         │ ❌ Sequential
└──────┬──────────────────┘
       │
       ▼
┌─────────────────────────┐
│ NSFW Detection          │
│ - TensorFlow Lite       │
└──────┬──────────────────┘
       │
       ▼
┌─────────────────────────┐
│ Segmentation (if safe)  │
│ - Body detection        │
│ - Masking               │
└──────┬──────────────────┘
       │
       ▼ (via Bridge)
┌─────────────────────────┐
│ JavaScript Rendering    │
│ - Replace image         │
└─────────────────────────┘
```

### Optimized Flow (After Phase 1)
```
┌─────────────┐
│  WebPage    │
└──────┬──────┘
       │
       ▼
┌─────────────────────────┐
│ JavaScript Detection    │
│ - MutationObserver      │
│ - Send metadata only    │ ✅ Lightweight
└──────┬──────────────────┘
       │
       ▼ (via Bridge)
┌─────────────────────────┐
│ Kotlin SafeGazeInterface│
│ - Check cache FIRST     │ ✅ Early exit
│ - Use JS base64 if avail│ ✅ No redownload
└──────┬──────────────────┘
       │
       ├─ CACHED ──────────┐
       │                   ▼
       │            ┌──────────────┐
       │            │ Return cache │
       │            │ (instant!)   │
       │            └──────────────┘
       │
       ▼ NOT CACHED
┌─────────────────────────┐
│ Download (if needed)    │
│ - Event-driven          │ ✅ No polling
│ - Notify when ready     │
└──────┬──────────────────┘
       │
       ▼ (channel)
┌─────────────────────────┐
│ Process (2-3 parallel)  │ ✅ Concurrent
│ - NSFW                  │
│ - Segmentation          │
└──────┬──────────────────┘
       │
       ├─ NSFW result ─────┐
       │                   ▼
       │            ┌──────────────┐
       │            │ Send early   │ ✅ Progressive
       │            │ (blur now)   │
       │            └──────────────┘
       │
       ▼ SAFE
┌─────────────────────────┐
│ Segmentation            │
│ - Background process    │
└──────┬──────────────────┘
       │
       ▼ (via Bridge)
┌─────────────────────────┐
│ JavaScript Rendering    │
│ - Apply final mask      │
└─────────────────────────┘
```

---

## Conclusion

The current SafeGaze implementation has a solid foundation with caching, queue management, and analytics. However, there are **15 significant optimization opportunities** that can deliver:

- **2-3x overall performance improvement**
- **60-80% faster** for cached images (Phase 1)
- **50% reduction** in network bandwidth
- **Better user experience** with progressive rendering

**Recommended Approach:**
1. Start with **Phase 1** (1-2 days) for immediate 60-80% gains
2. Measure and validate improvements
3. Proceed to **Phase 2** based on results
4. Consider **Phase 3** for advanced optimization

The highest-impact, lowest-risk changes are:
- ✅ **#3** - Check cache before download
- ✅ **#1** - Use JavaScript base64
- ✅ **#11** - JavaScript deduplication
- ✅ **#2** - Event-driven queue

These four changes alone could deliver **60-80% performance improvement** with minimal risk.

---

**Report Generated:** 2025-11-08
**Analysis Tool:** Claude Code
**Codebase:** Kahf Browser (Android)
