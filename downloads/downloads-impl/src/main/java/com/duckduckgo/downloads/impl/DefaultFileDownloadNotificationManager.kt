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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.AnyThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.browser.api.BrowserLifecycleObserver
import com.duckduckgo.common.utils.notification.checkPermissionAndNotify
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.downloads.api.FileDownloadNotificationManager
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesMultibinding
import dagger.SingleInstanceIn
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

private const val DOWNLOAD_IN_PROGRESS_GROUP = "com.duckduckgo.downloads.IN_PROGRESS"
private const val SUMMARY_ID = 0

@AnyThread
@ContributesBinding(
    scope = AppScope::class,
    boundType = FileDownloadNotificationManager::class,
)
@ContributesMultibinding(
    scope = AppScope::class,
    boundType = BrowserLifecycleObserver::class,
)
@SingleInstanceIn(AppScope::class)
class DefaultFileDownloadNotificationManager @Inject constructor(
    private val notificationManager: NotificationManagerCompat,
    private val applicationContext: Context,
    private val appBuildConfig: AppBuildConfig,
) : FileDownloadNotificationManager, BrowserLifecycleObserver {

    private val groupNotificationsCounter = AtomicReference<Map<Long, String>>(mapOf())
    private val applicationClosing = AtomicBoolean(false)
    private val pausedDownloads = AtomicReference<Set<Long>>(setOf())
    private val cancelledDownloads = AtomicReference<Set<Long>>(setOf())
    private val lastKnownProgress = AtomicReference<Map<Long, Int>>(mapOf())
    private val notificationLocks = ConcurrentHashMap<Long, Any>()

    @AnyThread
    override fun showDownloadInProgressNotification(downloadId: Long, filename: String, progress: Int) {
        // If download was cancelled, don't post/re-post notification
        if (cancelledDownloads.get().contains(downloadId)) return

        // Per-download lock ensures state-read + notify is atomic,
        // preventing a stale progress callback from overwriting a pause/resume refresh.
        val lock = notificationLocks.getOrPut(downloadId) { Any() }
        synchronized(lock) {
            val isPaused = pausedDownloads.get().contains(downloadId)

            // Track last known progress; when paused and caller passes default 0, use stored value
            val effectiveProgress = if (progress > 0) {
                lastKnownProgress.atomicUpdateAndGet { it.plus(downloadId to progress) }
                progress
            } else {
                lastKnownProgress.get()[downloadId] ?: 0
            }

            val pauseResumeIntent = if (isPaused) {
                FileDownloadNotificationActionReceiver.resumeDownloadIntent(downloadId)
            } else {
                FileDownloadNotificationActionReceiver.pauseDownloadIntent(downloadId)
            }
            pauseResumeIntent.`package` = applicationContext.packageName

            val pauseResumePendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                downloadId.toInt(),
                pauseResumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val cancelIntent = FileDownloadNotificationActionReceiver.cancelDownloadIntent(downloadId)
            cancelIntent.`package` = applicationContext.packageName
            val cancelPendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                downloadId.toInt(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val pauseResumeLabel = if (isPaused) {
                applicationContext.getString(R.string.downloadsResume)
            } else {
                applicationContext.getString(R.string.downloadsPause)
            }
            val pauseResumeIcon = if (isPaused) R.drawable.ic_play_24 else R.drawable.ic_pause_24

            val contentTitle = if (isPaused) {
                applicationContext.getString(R.string.downloadsPaused)
            } else {
                applicationContext.getString(R.string.downloadInProgress)
            }

            val notification = NotificationCompat.Builder(applicationContext, FileDownloadNotificationChannelType.FILE_DOWNLOADING.id)
                .setPriority(FileDownloadNotificationChannelType.FILE_DOWNLOADING.priority)
                .setContentTitle(contentTitle)
                .setContentText("$filename ($effectiveProgress%).")
                .setShowWhen(false)
                .setSmallIcon(R.drawable.ic_file_download_white_24dp)
                .setProgress(100, effectiveProgress, effectiveProgress == 0 && !isPaused)
                .setOngoing(true)
                .setGroup(DOWNLOAD_IN_PROGRESS_GROUP)
                .addAction(pauseResumeIcon, pauseResumeLabel, pauseResumePendingIntent)
                .addAction(R.drawable.ic_file_download_white_24dp, applicationContext.getString(R.string.downloadsCancel), cancelPendingIntent)
                .build()

            val summary = NotificationCompat.Builder(applicationContext, FileDownloadNotificationChannelType.FILE_DOWNLOADING.id)
                .setPriority(FileDownloadNotificationChannelType.FILE_DOWNLOADING.priority)
                .setShowWhen(false)
                .setSmallIcon(R.drawable.ic_file_download_white_24dp)
                .setGroup(DOWNLOAD_IN_PROGRESS_GROUP)
                .setGroupSummary(true)
                .build()

            if (applicationClosing.get()) {
                cancelDownloadFileNotification(downloadId)
                return
            }

            notificationManager.apply {
                checkPermissionAndNotify(applicationContext, downloadId.toInt(), notification)
                checkPermissionAndNotify(applicationContext, SUMMARY_ID, summary)
                groupNotificationsCounter.atomicUpdateAndGet { it.plus(downloadId to filename) }
            }
        }
    }

    @AnyThread
    override fun showDownloadFinishedNotification(downloadId: Long, file: File, mimeType: String?) {
        val filename = file.name
        val intent = createIntentToOpenFile(applicationContext, file)
        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        // Completed notification: no pause/resume/cancel actions
        val notification = NotificationCompat.Builder(applicationContext, FileDownloadNotificationChannelType.FILE_DOWNLOADED.id)
            .setPriority(FileDownloadNotificationChannelType.FILE_DOWNLOADING.priority)
            .setShowWhen(false)
            .setContentTitle(filename)
            .setContentText(applicationContext.getString(R.string.notificationDownloadComplete))
            .setContentIntent(PendingIntent.getActivity(applicationContext, downloadId.toInt(), intent, pendingIntentFlags))
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_file_download_white_24dp)
            .build()

        cancelDownloadFileNotification(downloadId)
        pausedDownloads.atomicUpdateAndGet { it - downloadId }
        cancelledDownloads.atomicUpdateAndGet { it - downloadId }
        lastKnownProgress.atomicUpdateAndGet { it - downloadId }
        notificationLocks.remove(downloadId)

        if (applicationClosing.get()) return
        notificationManager.checkPermissionAndNotify(applicationContext, downloadId.toInt(), notification)
    }

    @AnyThread
    override fun showDownloadFailedNotification(downloadId: Long, url: String?) {
        val notification = NotificationCompat.Builder(applicationContext, FileDownloadNotificationChannelType.FILE_DOWNLOADED.id)
            .setPriority(FileDownloadNotificationChannelType.FILE_DOWNLOADING.priority)
            .setShowWhen(false)
            .setContentTitle(applicationContext.getString(R.string.notificationDownloadFailed))
            .setSmallIcon(R.drawable.ic_file_download_white_24dp)
            .apply {
                url?.let { fileUrl ->
                    val retryIntent = FileDownloadNotificationActionReceiver.retryDownloadIntent(downloadId, fileUrl)
                    retryIntent.`package` = applicationContext.packageName
                    val pendingIntent = PendingIntent.getBroadcast(
                        applicationContext,
                        downloadId.toInt(),
                        retryIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(R.drawable.ic_file_download_white_24dp, applicationContext.getString(R.string.downloadsRetry), pendingIntent)
                }
                groupNotificationsCounter.get()[downloadId]?.let { fileName ->
                    setContentText(fileName)
                }
            }
            .build()

        cancelDownloadFileNotification(downloadId)
        pausedDownloads.atomicUpdateAndGet { it - downloadId }
        cancelledDownloads.atomicUpdateAndGet { it - downloadId }
        lastKnownProgress.atomicUpdateAndGet { it - downloadId }
        notificationLocks.remove(downloadId)

        if (applicationClosing.get()) return
        notificationManager.checkPermissionAndNotify(applicationContext, downloadId.toInt(), notification)
    }

    @AnyThread
    override fun cancelDownloadFileNotification(downloadId: Long) {
        groupNotificationsCounter.atomicUpdateAndGet { it - downloadId }.run {
            if (isEmpty()) {
                notificationManager.cancel(SUMMARY_ID)
            }
        }
        notificationManager.cancel(downloadId.toInt())
    }

    fun markDownloadPaused(downloadId: Long) {
        val lock = notificationLocks.getOrPut(downloadId) { Any() }
        synchronized(lock) {
            pausedDownloads.atomicUpdateAndGet { it + downloadId }
            // Cancel first to bypass Android notification rate-limiting,
            // forcing the system to render the updated paused state immediately.
            notificationManager.cancel(downloadId.toInt())
            val filename = groupNotificationsCounter.get()[downloadId] ?: return
            showDownloadInProgressNotification(downloadId, filename)
        }
    }

    fun markDownloadResumed(downloadId: Long) {
        val lock = notificationLocks.getOrPut(downloadId) { Any() }
        synchronized(lock) {
            pausedDownloads.atomicUpdateAndGet { it - downloadId }
            notificationManager.cancel(downloadId.toInt())
            val filename = groupNotificationsCounter.get()[downloadId] ?: return
            showDownloadInProgressNotification(downloadId, filename)
        }
    }

    fun markDownloadCancelled(downloadId: Long) {
        cancelledDownloads.atomicUpdateAndGet { it + downloadId }
    }

    override fun onOpen(isFreshLaunch: Boolean) {
        applicationClosing.set(false)
    }

    override fun onClose() {
        synchronized(groupNotificationsCounter) {
            applicationClosing.set(true)
            val downloadIds = groupNotificationsCounter.get().keys
            downloadIds.forEach { cancelDownloadFileNotification(it) }
            groupNotificationsCounter.set(mapOf())
        }
        Handler(Looper.getMainLooper()).postDelayed({ applicationClosing.set(false) }, 250)
    }

    private fun <T> AtomicReference<T>.atomicUpdateAndGet(updateFunction: (T) -> T): T {
        var prev: T
        var next: T
        do {
            prev = get()
            next = updateFunction(prev)
        } while (!compareAndSet(prev, next))
        return next
    }

    private fun createIntentToOpenFile(applicationContext: Context, file: File): Intent {
        val fileUri = getFilePathUri(applicationContext, file)
        return Intent().apply {
            setDataAndType(fileUri, applicationContext.contentResolver?.getType(fileUri))
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }

    private fun getFilePathUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, "${appBuildConfig.applicationId}.provider", file)
    }
}
