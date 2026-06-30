package com.naarni.service.core.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.naarni.service.App
import com.naarni.service.MainActivity
import com.naarni.service.R
import com.naarni.service.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM pushes and surfaces them on the high-importance "job_cards"
 * channel (sound + vibration), and keeps the backend's device token current.
 *
 * Token + payload handling is deliberately generic so the backend can evolve its
 * notification shape without an app change: any `data` keys (title/body/route)
 * are honoured, and a `notification` block is used as a fallback.
 */
class NaarniMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        // Best-effort: register with the backend if we have a session. A 403 when
        // logged-out is fine — PushTokenRegistrar re-registers right after login.
        scope.launch { runCatching { appContainer.jobCardRepo.registerPushToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = message.notification?.title ?: data["title"] ?: "Naarni Fleet Service"
        val body = message.notification?.body ?: data["body"] ?: data["message"] ?: ""
        val route = data["route"] ?: data["click_action"] ?: data["link"]

        notify(title, body, route)
    }

    private fun notify(title: String, body: String, route: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Carry the deep-link target so MainActivity can route once navigation
            // handling is wired (naarni://jobcard/{name} | ticket/{id} | alert/{id}).
            if (!route.isNullOrBlank()) {
                this.data = if (route.startsWith("naarni://")) Uri.parse(route) else Uri.parse("naarni://$route")
                putExtra("route", route)
            }
        }
        val pi = PendingIntent.getActivity(
            this,
            route.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, App.CHANNEL_JOB_CARDS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        val id = (route ?: (title + body)).hashCode()
        // POST_NOTIFICATIONS is requested elsewhere; guard against the SecurityException.
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
            .onFailure {
                (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)?.notify(id, notification)
            }
    }
}
