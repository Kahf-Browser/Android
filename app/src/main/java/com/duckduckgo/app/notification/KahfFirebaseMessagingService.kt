/*
 * Copyright (c) 2025 DuckDuckGo
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

package com.duckduckgo.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.duckduckgo.app.browser.BrowserActivity
import com.duckduckgo.app.browser.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

class KahfFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Send this token to your backend if you want targeted notifications
        println("FCM Token: $token")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"]
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"]
        val url = remoteMessage.data["url"]

        Timber.d("kahfLog: notificationTitle: $title, notificationBody: $body, redirectUrl: $url")

        // Fixed: Move notification creation to background thread to prevent ANR
        if (title != null && body != null) {
            serviceScope.launch {
                sendNotification(title = title, messageBody = body, redirectUrl = url)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel coroutine scope when service is destroyed
        serviceScope.cancel()
    }

    private fun sendNotification(title: String, messageBody: String, redirectUrl: String?) {
        try {
            val intent = Intent(this, BrowserActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                Timber.d("kahfLog: redirectUrl: $redirectUrl")
                putExtra("redirectUrl", redirectUrl ?: "")
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            val channelId = "default_channel_id"
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val notificationBuilder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(com.duckduckgo.mobile.android.R.drawable.kahf_browser_logo_only)
                .setContentTitle(title)
                .setContentText(messageBody)
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setContentIntent(pendingIntent)

            // Fixed: Add null check for NotificationManager
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

            if (notificationManager == null) {
                Timber.e("kahfLog: NotificationManager is null, cannot show notification")
                return
            }

            // Fixed: Create channel only once by checking if it exists
            if (notificationManager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId, "Default Channel", NotificationManager.IMPORTANCE_DEFAULT
                )
                notificationManager.createNotificationChannel(channel)
            }

            notificationManager.notify(0, notificationBuilder.build())
        } catch (e: Exception) {
            // Fixed: Catch and log any exceptions to prevent crashes
            Timber.e(e, "kahfLog: Error showing notification")
        }
    }
}
