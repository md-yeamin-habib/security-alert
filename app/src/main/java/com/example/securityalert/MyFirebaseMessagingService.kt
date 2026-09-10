package com.example.securityalert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {

        Log.d(
            "FCM",
            "Message received from Firebase"
        )

        val title =
            message.notification?.title
                ?: "Security Alert"

        val body =
            message.notification?.body
                ?: "A security event has been detected."

        showNotification(
            title,
            body
        )
    }

    override fun onNewToken(token: String) {

        super.onNewToken(token)

        Log.d(
            "FCM",
            "FCM token refreshed: $token"
        )
    }

    private fun showNotification(
        title: String,
        body: String
    ) {

        val channelId = "security_alerts"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                channelId,
                "Security Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )

            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 500, 200, 500)

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE)
                        as NotificationManager

            notificationManager.createNotificationChannel(
                channel
            )
        }

        val notification =
            NotificationCompat.Builder(
                this,
                channelId
            )
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setAutoCancel(true)
                .build()

        NotificationManagerCompat
            .from(this)
            .notify(
                System.currentTimeMillis().toInt(),
                notification
            )
    }
}