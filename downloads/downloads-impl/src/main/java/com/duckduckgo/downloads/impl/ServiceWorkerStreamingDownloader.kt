/*
 * Copyright (c) 2024 DuckDuckGo
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

package com.duckduckgo.downloads.impl

import android.content.Context
import android.util.Base64
import com.duckduckgo.common.utils.formatters.time.DatabaseDateFormatter
import com.duckduckgo.downloads.api.DownloadFailReason
import com.duckduckgo.downloads.api.FileDownloader.PendingFileDownload
import com.duckduckgo.downloads.api.model.DownloadItem
import com.duckduckgo.downloads.store.DownloadStatus.STARTED
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import logcat.logcat

class ServiceWorkerStreamingDownloader @Inject constructor(
    private val context: Context,
    private val downloadFileCopier: DownloadFileCopier,
) {

    /**
     * Creates a new streaming session. Immediately calls callback.onStart()
     * to register the download in the repository and show the in-progress notification.
     *
     * @param pendingDownload Download metadata including target directory
     * @param fileName Extracted filename for the download
     * @param totalSize Total blob size from JS (for progress calculation)
     * @param callback DownloadCallback for notifications and repository updates
     * @return A StreamingDownloadSession to receive chunks from the JS bridge
     */
    fun createSession(
        pendingDownload: PendingFileDownload,
        fileName: String,
        totalSize: Long,
        callback: DownloadCallback,
    ): StreamingDownloadSession {
        val downloadId = Random.nextLong()
        val cacheDir = File(context.cacheDir, "sw-downloads").also { it.mkdirs() }
        val tempFile = File(cacheDir, fileName)

        callback.onStart(
            DownloadItem(
                downloadId = downloadId,
                downloadStatus = STARTED,
                fileName = fileName,
                contentLength = totalSize,
                filePath = pendingDownload.directory.path + File.separatorChar + fileName,
                createdAt = DatabaseDateFormatter.timestamp(),
                downloadUrl = pendingDownload.url,
            ),
        )

        return StreamingDownloadSession(
            tempFile = tempFile,
            downloadId = downloadId,
            fileName = fileName,
            totalSize = totalSize,
            callback = callback,
        )
    }

    /**
     * Copies the completed temp file to the user's target directory,
     * calls callback.onSuccess(), and cleans up the temp file.
     * Must be called on IO dispatcher.
     */
    fun finalizeDownload(
        session: StreamingDownloadSession,
        pendingDownload: PendingFileDownload,
    ) {
        val tempFile = session.tempFile
        val contentLength = tempFile.length()
        val copyResult = downloadFileCopier.copyToTargetDirectory(
            cachedFile = tempFile,
            fileName = session.fileName,
            targetDir = pendingDownload.directory,
            mimeType = pendingDownload.mimeType,
        )
        tempFile.delete()

        if (copyResult != null) {
            val finalFile = when (copyResult) {
                is DownloadFileCopier.CopyResult.WithFile -> copyResult.file
                is DownloadFileCopier.CopyResult.WithUri -> copyResult.file
            }
            val contentUri = when (copyResult) {
                is DownloadFileCopier.CopyResult.WithFile -> null
                is DownloadFileCopier.CopyResult.WithUri -> copyResult.contentUri
            }
            session.callback.onSuccess(
                session.downloadId, contentLength, finalFile,
                pendingDownload.mimeType, contentUri,
            )
        } else {
            session.callback.onError(
                url = pendingDownload.url,
                downloadId = session.downloadId,
                reason = DownloadFailReason.Other,
            )
        }
    }
}

/**
 * Receives base64-encoded file chunks from the WebView JS bridge and writes them
 * to a temporary file on disk.
 *
 * Thread safety: onChunk/onComplete/onFailed are called from the WebView background thread,
 * cancel() from IO dispatcher or main thread. All terminal state transitions are guarded by
 * a single synchronized block to prevent double-close and double-complete races.
 *
 * IO offloading: Base64.decode() and disk write are offloaded to a single-thread IO executor
 * to avoid blocking the WebView thread (which would cause ANR/jank).
 */
class StreamingDownloadSession internal constructor(
    internal val tempFile: File,
    val downloadId: Long,
    internal val fileName: String,
    private val totalSize: Long,
    internal val callback: DownloadCallback,
) {
    private val outputStream = BufferedOutputStream(FileOutputStream(tempFile), IO_BUFFER_SIZE)
    private val _bytesWritten = AtomicLong(0L)
    val bytesWritten: Long get() = _bytesWritten.get()

    // Single-thread executor to offload Base64.decode + disk write off the WebView thread.
    // Sequential execution preserves chunk ordering.
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sw-download-io").apply { isDaemon = true }
    }

    // Terminal state flag — only one of onComplete/onFailed/cancel can win.
    // All checks and transitions are inside synchronized(terminalLock).
    private val terminalLock = Any()
    private var terminated = false

    // Throttle progress updates so Android's notification system can display intermediate values.
    // Without throttling, rapid chunk processing floods NotificationManager and only the last
    // update (100%) is visible.
    @Volatile
    private var lastProgressUpdateMs = 0L

    @Volatile
    var isComplete = false
        private set

    @Volatile
    var isFailed = false
        private set

    /** Completes with true on success, false on failure/cancel. */
    val completionDeferred = CompletableDeferred<Boolean>()

    /**
     * Called from WebView thread for each base64-encoded chunk.
     * Decodes and writes on a background IO executor to avoid blocking the WebView thread.
     *
     * Progress is throttled to one update per [PROGRESS_THROTTLE_MS] to prevent flooding
     * Android's NotificationManager, which would cause intermediate values to be skipped.
     */
    fun onChunk(base64Chunk: String) {
        if (terminated) return
        ioExecutor.execute {
            if (terminated) return@execute
            try {
                val bytes = Base64.decode(base64Chunk, Base64.DEFAULT)
                outputStream.write(bytes)
                val written = _bytesWritten.addAndGet(bytes.size.toLong())
                // Throttle progress updates to allow notification system to display intermediate values
                val now = System.currentTimeMillis()
                if (now - lastProgressUpdateMs >= PROGRESS_THROTTLE_MS) {
                    lastProgressUpdateMs = now
                    val progress = if (totalSize > 0) {
                        ((written * 100) / totalSize).toInt().coerceIn(0, 99)
                    } else {
                        0
                    }
                    callback.onProgress(downloadId, fileName, progress)
                }
            } catch (e: Exception) {
                logcat { "Error writing SW download chunk to temp file: ${e.message}" }
                onFailed("IO error: ${e.message}")
            }
        }
    }

    /**
     * Called from WebView thread when all chunks have been sent.
     * Flushes and closes the output stream, signals success.
     * Submitted to the IO executor so it runs after all pending onChunk writes.
     */
    fun onComplete() {
        ioExecutor.execute {
            synchronized(terminalLock) {
                if (terminated) return@execute
                terminated = true
            }
            runCatching { outputStream.flush(); outputStream.close() }
            // Send final 100% progress before signaling completion
            callback.onProgress(downloadId, fileName, 100)
            ioExecutor.shutdown()
            isComplete = true
            completionDeferred.complete(true)
        }
    }

    /**
     * Called from WebView thread when JS fetch/read fails.
     * Closes the stream and cleans up the temp file.
     */
    fun onFailed(reason: String) {
        synchronized(terminalLock) {
            if (terminated) return
            terminated = true
        }
        logcat { "SW streaming download failed: $reason" }
        runCatching { outputStream.flush(); outputStream.close() }
        ioExecutor.shutdown()
        tempFile.delete()
        isFailed = true
        callback.onError(downloadId = downloadId, reason = DownloadFailReason.Other)
        completionDeferred.complete(false)
    }

    /**
     * Cancel the download. Called from the monitoring coroutine or user action.
     */
    fun cancel() {
        synchronized(terminalLock) {
            if (terminated) return
            terminated = true
        }
        runCatching { outputStream.flush(); outputStream.close() }
        ioExecutor.shutdown()
        tempFile.delete()
        callback.onCancel(downloadId)
        completionDeferred.complete(false)
    }

    companion object {
        private const val IO_BUFFER_SIZE = 8192
        private const val PROGRESS_THROTTLE_MS = 300L
    }
}
