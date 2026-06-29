package com.naarni.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.naarni.service.core.auth.SessionManager
import com.naarni.service.core.network.Network
import com.naarni.service.data.repo.AuthRepository
import com.naarni.service.data.repo.JobCardRepository

/**
 * Application + manual DI container. We avoid an annotation-processor DI framework
 * to keep the APK small and the build fast (plan §2) — the graph is tiny.
 */
class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
    }

    /** High-importance channel (custom sound + vibration) used by FCM push. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val sound = Uri.parse("android.resource://$packageName/${R.raw.notify}")
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(
            CHANNEL_JOB_CARDS,
            "Job Card Updates",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Assignments, approvals, SLA alerts and status changes"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 200, 100, 200)
            setSound(sound, attrs)
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_JOB_CARDS = "job_cards"
    }
}

class AppContainer(context: Context) {
    val session = SessionManager(context)
    val api by lazy { Network.api(session) }
    val authRepo by lazy { AuthRepository(api, session) }
    val jobCardRepo by lazy { JobCardRepository(api) }
}

/** Reach the DI container from any Context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as App).container
