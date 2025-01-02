package com.duckduckgo.app.prayers.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.duckduckgo.app.browser.R
import java.util.concurrent.TimeUnit

object NotificationUtils {

    private const val CHANNEL_ID = "PrayerChannelId"
    private const val CHANNEL_NAME = "Prayer-Time-Reminder"

    fun createNotificationChannel(context: Context) {
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    fun createNotification(context: Context, prayerTime: String?, notificationId: Int) {
        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Prayer Time")
            .setContentText(prayerTime)
            .setSmallIcon(R.drawable.ic_kahf_light)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(notificationId, notificationBuilder.build())
    }

    fun scheduleNotification(
        context: Context,
        prayerTime: String,
        notificationTimeInMillis: Long,
        notificationId: Int
    ) {
        val inputData = Data.Builder()
            .putString("prayerTime", prayerTime)
            .putInt("notificationId", notificationId)
            .build()

        val prayerWork = OneTimeWorkRequestBuilder<NotificationWorker>()
            .setInputData(inputData)
            .setInitialDelay(notificationTimeInMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork("unique_work_$notificationId", ExistingWorkPolicy.REPLACE, prayerWork)
    }

    fun cancelNotification(context: Context, notificationId: Int) {
        WorkManager.getInstance(context).cancelUniqueWork("unique_work_$notificationId")
    }
}
