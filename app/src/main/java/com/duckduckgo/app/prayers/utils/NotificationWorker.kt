package com.duckduckgo.app.prayers.utils

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.duckduckgo.app.prayers.landing.SharedPrefKey

class NotificationWorker(private val context: Context, workerParams: WorkerParameters): Worker(context, workerParams) {
    override fun doWork(): Result {
        val prayerTime = inputData.getString("prayerTime")
        val notificationId = inputData.getInt("notificationId", 0)

        // Logic to show the notification for prayer time
        // This could include setting up the notification channel, building the notification, etc.
        showPrayerNotification(prayerTime, notificationId)
        return Result.success()
    }

    private fun showPrayerNotification(prayerTime: String?, notificationId: Int) {
        NotificationUtils.createNotification(context, prayerTime, notificationId)
        val editor = context.getSharedPreferences("PrayersPreferences", Context.MODE_PRIVATE).edit()
        editor.remove("${SharedPrefKey.ALARM_SET_AT.value}$notificationId")
        editor.apply()
    }
}
