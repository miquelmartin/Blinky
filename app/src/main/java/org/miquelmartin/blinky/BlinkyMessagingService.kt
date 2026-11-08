package org.miquelmartin.blinky

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID

class BlinkyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        BlinkyState.preferences(this).edit().putString(BlinkyState.TOKEN, token).apply()
    }

    @SuppressLint("MissingPermission") // checked by enabled(), with a catch for permission revocation
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.title
            ?: getString(R.string.app_name)
        val body = message.data["body"]?.takeIf { it.isNotBlank() }
            ?: message.notification?.body
            ?: getString(R.string.message_body_missing)
        val preferences = BlinkyState.preferences(this)
        preferences.edit()
            .putString(BlinkyState.LAST_MESSAGE, "$title: $body")
            .putString(BlinkyState.LAST_DISPLAY, BlinkyState.SUPPRESSED)
            .apply()

        BlinkyNotifications.createChannel(this)
        if (!BlinkyNotifications.enabled(this)) {
            Log.w(TAG, "Message received, but notification permission or channel is disabled.")
            return
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, BlinkyNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.blinky_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        try {
            getSystemService(NotificationManager::class.java)
                .notify(message.messageId ?: UUID.randomUUID().toString(), 0, notification)
            preferences.edit().putString(BlinkyState.LAST_DISPLAY, BlinkyState.DISPLAYED).apply()
        } catch (error: SecurityException) {
            Log.e(TAG, "Notification permission changed while displaying a message.", error)
            preferences.edit().putString(BlinkyState.LAST_DISPLAY, BlinkyState.FAILED).apply()
        }
    }

    companion object {
        private const val TAG = "BlinkyMessaging"
    }
}
