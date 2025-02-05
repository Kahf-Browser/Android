package com.duckduckgo.app.prayers.utils

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.launch.LaunchBridgeActivity
import com.duckduckgo.app.prayers.landing.SharedPrefKey

object NotificationUtils {

    private const val CHANNEL_ID = "alarm_id"
    private const val CHANNEL_NAME = "alarm_name"

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    fun createNotification(context: Context, message: String?, notificationId: Int, triggerTime: Long) {
        val intent = Intent(context, LaunchBridgeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.appName))
            .setContentText(message)
            .setWhen(triggerTime)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(longArrayOf(1000, 300, 300, 1000))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(notificationId, notificationBuilder.build())
    }

    fun removeNotificationEntryFromSharedPref(context: Context, notificationId: Int) {
        val editor = context.getSharedPreferences("PrayersPreferences", Context.MODE_PRIVATE).edit()
        editor.remove("${SharedPrefKey.ALARM_SET_AT.value}$notificationId")
        editor.apply()
    }

    fun scheduleNotification(
        context: Context,
        notificationBody: String,
        notificationTimeInMillis: Long,
        notificationId: Int
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("EXTRA_MESSAGE", notificationBody)
            putExtra("notificationId", notificationId)
            putExtra("when", notificationTimeInMillis)
        }

        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            notificationTimeInMillis,
            PendingIntent.getBroadcast(
                context,
                notificationId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    fun cancelNotification(context: Context, notificationId: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        alarmManager.cancel(
            PendingIntent.getBroadcast(
                context,
                notificationId,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }
}
