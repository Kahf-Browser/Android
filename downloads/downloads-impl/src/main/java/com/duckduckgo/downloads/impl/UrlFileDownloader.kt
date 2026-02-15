/*
 * Copyright (c) 2022 DuckDuckGo
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
import androidx.annotation.WorkerThread
import com.duckduckgo.common.utils.formatters.time.DatabaseDateFormatter
import com.duckduckgo.downloads.api.DownloadFailReason.ConnectionRefused
import com.duckduckgo.downloads.api.DownloadFailReason.Other
import com.duckduckgo.downloads.api.FileDownloader
import com.duckduckgo.downloads.api.model.DownloadItem
import com.duckduckgo.downloads.store.DownloadStatus.STARTED
import java.io.File
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.floor
import kotlin.random.Random
import logcat.asLog
import logcat.logcat
import okhttp3.ResponseBody
import okio.Buffer
import okio.sink

class UrlFileDownloader @Inject constructor(
    private val context: Context,
    private val downloadFileService: DownloadFileService,
    private val urlFileDownloadCallManager: UrlFileDownloadCallManager,
    private val cookieManagerWrapper: CookieManagerWrapper,
    private val downloadFileCopier: DownloadFileCopier,
) {

    @WorkerThread
    fun downloadFile(
        pendingFileDownload: FileDownloader.PendingFileDownload,
        fileName: String,
        downloadCallback: DownloadCallback,
    ) {
        val url = pendingFileDownload.url
        val directory = pendingFileDownload.directory
        val referer = try {
            val uri = android.net.Uri.parse(url)
            "${uri.scheme}://${uri.host}/"
        } catch (e: Exception) {
            ""
        }
        val call = downloadFileService.downloadFile(
            urlString = url,
            cookie = cookieManagerWrapper.getCookie(url).orEmpty(),
            referer = referer,
        )
        val downloadId = Random.nextLong()
        urlFileDownloadCallManager.add(downloadId, call)

        logcat { "Starting download $fileName / $url" }
        downloadCallback.onStart(
            DownloadItem(
                downloadId = downloadId,
                downloadStatus = STARTED,
                fileName = fileName,
                contentLength = 0,
                filePath = directory.path + File.separatorChar + fileName,
                createdAt = DatabaseDateFormatter.timestamp(),
            ),

        )

        // Use app cache as temp directory to avoid scoped storage restrictions
        val cacheDir = File(context.cacheDir, "downloads").also { it.mkdirs() }

        runCatching {
            val response = call.execute()

            if (response.isSuccessful) {
                response.body()?.let {
                    if (writeStreamingResponseBodyToDisk(downloadId, fileName, cacheDir, it, downloadCallback)) {
                        val cachedFile = File(cacheDir, fileName)
                        val contentLength = cachedFile.length()
                        val copyResult = downloadFileCopier.copyToTargetDirectory(cachedFile, fileName, directory, pendingFileDownload.mimeType)
                        cachedFile.delete()
                        if (copyResult != null) {
                            val finalFile = when (copyResult) {
                                is DownloadFileCopier.CopyResult.WithFile -> copyResult.file
                                is DownloadFileCopier.CopyResult.WithUri -> copyResult.file
                            }
                            val contentUri = when (copyResult) {
                                is DownloadFileCopier.CopyResult.WithFile -> null
                                is DownloadFileCopier.CopyResult.WithUri -> copyResult.contentUri
                            }
                            downloadCallback.onSuccess(downloadId, contentLength, finalFile, pendingFileDownload.mimeType, contentUri)
                        } else {
                            downloadCallback.onError(url = url, downloadId = downloadId, reason = Other)
                        }
                    } else {
                        if (call.isCanceled) {
                            logcat { "Download $fileName cancelled" }
                            downloadCallback.onCancel(downloadId)
                        } else {
                            logcat { "Download $fileName failed" }
                            downloadCallback.onError(url = url, downloadId = downloadId, reason = Other)
                        }
                        // clean up
                        File(cacheDir, fileName).delete()
                    }
                }
            } else {
                logcat { "Failed to download $fileName / ${response.errorBody()?.string()}" }
                downloadCallback.onError(url = url, downloadId = downloadId, reason = ConnectionRefused)
            }
        }.onFailure {
            logcat { "Failed to download $fileName: ${it.asLog()}" }
            if (call.isCanceled) {
                downloadCallback.onCancel(downloadId)
            } else {
                downloadCallback.onError(url = url, downloadId = downloadId, reason = ConnectionRefused)
            }
            // clean up
            File(cacheDir, fileName).delete()
        }
    }

    private fun writeStreamingResponseBodyToDisk(
        downloadId: Long,
        fileName: String,
        directory: File,
        body: ResponseBody,
        downloadCallback: DownloadCallback,
    ): Boolean {
        logcat { "Writing streaming response body to disk $fileName" }

        // ensure content length never 0
        val contentLength = if (body.contentLength() > 0) body.contentLength() else -1
        val file = directory.getOrCreate(fileName)
        val sink = file.sink()
        val source = body.source()

        var totalRead = 0L
        val buffer = Buffer()
        val success = try {
            var progressSteps = 0.0
            while (!source.exhausted()) {
                // Block while paused, yielding the thread
                while (urlFileDownloadCallManager.isPaused(downloadId)) {
                    Thread.sleep(PAUSE_CHECK_INTERVAL_MS)
                }
                val didRead = source.read(buffer, READ_SIZE_BYTES)
                totalRead += didRead
                sink.write(buffer, didRead)
                val fakeProgress = floor(calculateFakeProgress(progressSteps) * 100.0).toInt().also { progressSteps += 0.0001 }
                val calculatedProgress = (totalRead * 100 / contentLength)
                val progress = if (calculatedProgress < 0L) fakeProgress else calculatedProgress
                downloadCallback.onProgress(downloadId, fileName, progress.toInt())
            }
            true
        } catch (t: Throwable) {
            logcat { "Failed to write to disk $fileName: ${t.asLog()}" }
            false
        } finally {
            source.close()
            sink.close()
        }

        return success
    }

    private fun File.getOrCreate(filename: String): File {
        val file = File(this, filename)
        if (!this.exists()) this.mkdirs()
        if (!file.exists()) file.createNewFile()
        return file
    }

    /**
     * This method calculates fake progress that will be used in cases where the file content length is not known.
     * The fake progress curve follows 1-Math.exp(-step)
     */
    private fun calculateFakeProgress(step: Double): Double {
        return(1 - exp(-step))
    }

    companion object {
        const val READ_SIZE_BYTES = 1024L * 100
        private const val PAUSE_CHECK_INTERVAL_MS = 200L
    }
}
