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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import androidx.lifecycle.LifecycleOwner
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.app.lifecycle.MainProcessLifecycleObserver
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.common.utils.extensions.registerNotExportedReceiver
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.downloads.api.*
import com.duckduckgo.downloads.api.FileDownloader.PendingFileDownload
import com.duckduckgo.downloads.impl.pixels.DownloadsPixelName.DOWNLOAD_REQUEST_CANCELLED_BY_USER
import com.duckduckgo.downloads.impl.pixels.DownloadsPixelName.DOWNLOAD_REQUEST_RETRIED
import com.duckduckgo.downloads.store.DownloadStatus.STARTED
import com.squareup.anvil.annotations.ContributesMultibinding
import dagger.SingleInstanceIn
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import logcat.logcat

@ContributesMultibinding(
    scope = AppScope::class,
    boundType = MainProcessLifecycleObserver::class,
)
@SingleInstanceIn(AppScope::class)
class FileDownloadNotificationActionReceiver @Inject constructor(
    private val context: Context,
    private val fileDownloader: FileDownloader,
    private val fileDownloadNotificationManager: FileDownloadNotificationManager,
    private val downloadsRepository: DownloadsRepository,
    private val urlFileDownloadCallManager: UrlFileDownloadCallManager,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val pixel: Pixel,
) : BroadcastReceiver(), MainProcessLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        logcat { "Registering file download notification action receiver" }
        val filter = IntentFilter().apply {
            addAction(ACTION_CANCEL)
            addAction(ACTION_RETRY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_RESUME)
        }
        context.registerNotExportedReceiver(this, filter)

        // Clean up pending downloads and partial files from a previous killed process
        coroutineScope.launch(dispatcherProvider.io()) {
            purgePendingDownloads()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        context.unregisterReceiver(this)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val downloadId = intent.getLongExtra(DOWNLOAD_ID_EXTRA, -1)
        if (downloadId == -1L) return

        when (intent.action) {
            ACTION_CANCEL -> handleCancel(downloadId)
            ACTION_RETRY -> handleRetry(intent, downloadId)
            ACTION_PAUSE -> handlePause(downloadId)
            ACTION_RESUME -> handleResume(downloadId)
        }
    }

    private fun handleCancel(downloadId: Long) {
        logcat { "Received cancel download intent for download id $downloadId" }
        pixel.fire(DOWNLOAD_REQUEST_CANCELLED_BY_USER)

        // Mark cancelled FIRST so in-flight progress callbacks are suppressed
        (fileDownloadNotificationManager as? DefaultFileDownloadNotificationManager)?.markDownloadCancelled(downloadId)
        urlFileDownloadCallManager.remove(downloadId)
        fileDownloadNotificationManager.cancelDownloadFileNotification(downloadId)

        coroutineScope.launch(dispatcherProvider.io()) {
            val item = downloadsRepository.getDownloadItem(downloadId)
            item?.let {
                val file = File(it.filePath)
                if (file.exists()) {
                    file.delete()
                    logcat { "Deleted partial file: ${it.filePath}" }
                }
            }
            downloadsRepository.delete(downloadId)
        }
    }

    private fun handleRetry(intent: Intent, downloadId: Long) {
        logcat { "Received retry download intent for download id $downloadId" }
        pixel.fire(DOWNLOAD_REQUEST_RETRIED)

        val url = intent.getStringExtra(URL_EXTRA) ?: return
        PendingFileDownload(
            url = url,
            subfolder = Environment.DIRECTORY_DOWNLOADS,
        ).run {
            logcat { "Retrying download for $url" }
            coroutineScope.launch(dispatcherProvider.io()) {
                downloadsRepository.delete(downloadId)
                fileDownloader.enqueueDownload(this@run)
            }
        }
    }

    private fun handlePause(downloadId: Long) {
        logcat { "Received pause download intent for download id $downloadId" }
        // Mark paused FIRST so any in-flight progress callback sees paused state
        // markDownloadPaused also immediately refreshes the notification with stored progress
        (fileDownloadNotificationManager as? DefaultFileDownloadNotificationManager)?.markDownloadPaused(downloadId)
        urlFileDownloadCallManager.pause(downloadId)
    }

    private fun handleResume(downloadId: Long) {
        logcat { "Received resume download intent for download id $downloadId" }
        // markDownloadResumed also immediately refreshes the notification
        (fileDownloadNotificationManager as? DefaultFileDownloadNotificationManager)?.markDownloadResumed(downloadId)
        urlFileDownloadCallManager.resume(downloadId)
    }

    private suspend fun purgePendingDownloads() {
        downloadsRepository.getDownloads().filter { it.downloadStatus == STARTED }.run {
            map { it.downloadId }.let { ids ->
                downloadsRepository.delete(ids)
                ids.forEach { fileDownloadNotificationManager.cancelDownloadFileNotification(it) }
            }
            // Delete partial files from killed downloads
            forEach { item ->
                val file = File(item.filePath)
                if (file.exists()) {
                    file.delete()
                    logcat { "Cleaned up partial download file: ${item.filePath}" }
                }
            }
        }
    }

    companion object {
        private const val ACTION_CANCEL = "com.duckduckgo.downloads.ACTION_CANCEL"
        private const val ACTION_RETRY = "com.duckduckgo.downloads.ACTION_RETRY"
        private const val ACTION_PAUSE = "com.duckduckgo.downloads.ACTION_PAUSE"
        private const val ACTION_RESUME = "com.duckduckgo.downloads.ACTION_RESUME"
        private const val DOWNLOAD_ID_EXTRA = "downloadId"
        private const val URL_EXTRA = "URL"

        fun cancelDownloadIntent(downloadId: Long): Intent {
            return Intent(ACTION_CANCEL).apply {
                putExtra(DOWNLOAD_ID_EXTRA, downloadId)
            }
        }

        fun retryDownloadIntent(downloadId: Long, url: String): Intent {
            return Intent(ACTION_RETRY).apply {
                putExtra(DOWNLOAD_ID_EXTRA, downloadId)
                putExtra(URL_EXTRA, url)
            }
        }

        fun pauseDownloadIntent(downloadId: Long): Intent {
            return Intent(ACTION_PAUSE).apply {
                putExtra(DOWNLOAD_ID_EXTRA, downloadId)
            }
        }

        fun resumeDownloadIntent(downloadId: Long): Intent {
            return Intent(ACTION_RESUME).apply {
                putExtra(DOWNLOAD_ID_EXTRA, downloadId)
            }
        }
    }
}
