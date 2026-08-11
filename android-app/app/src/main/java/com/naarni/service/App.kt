package com.naarni.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import com.naarni.service.core.auth.SessionManager
import com.naarni.service.core.chat.FrappeSocket
import com.naarni.service.core.network.Network
import com.naarni.service.data.chat.ChatDatabase
import com.naarni.service.data.repo.AuthRepository
import com.naarni.service.data.repo.ChatRepository
import com.naarni.service.data.repo.JobCardRepository
import com.naarni.service.data.repo.ProcessRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
        createChatChannels()
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

    /**
     * Chat gets its own channels so a technician can silence depot banter without
     * also silencing SLA and breakdown alerts. Importance is fixed at creation —
     * Android will not let it be raised later — so messages start HIGH.
     */
    private fun createChatChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_CHAT, "Chat Messages", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "New messages in your depot and vehicle threads"
                    enableVibration(true)
                }
        )
        // Upload progress is a persistent, silent notification — LOW keeps it out
        // of the shade's alerting section and off the lock screen.
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CHAT_UPLOADS,
                "Attachment Uploads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress for photos and videos being sent"
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_JOB_CARDS = "job_cards"
        const val CHANNEL_CHAT = "chat_messages"
        const val CHANNEL_CHAT_UPLOADS = "chat_uploads"
    }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val session = SessionManager(context)
    val api by lazy { Network.api(session) }

    /** Shared so the socket, upload worker and Coil all carry the `sid` cookie. */
    val httpClient by lazy { Network.client(session) }

    val authRepo by lazy { AuthRepository(api, session) }
    val jobCardRepo by lazy { JobCardRepository(api) }
    val processRepo by lazy { ProcessRepository(api) }

    // ---- Chat ----
    val chatDb by lazy { ChatDatabase.build(appContext) }
    val chatDao by lazy { chatDb.chatDao() }
    val chatRepo by lazy { ChatRepository(api, chatDao, appContext) }

    /**
     * One socket for the process, bound to the app lifecycle rather than to any
     * screen — see ChatLifecycleObserver.
     */
    val chatSocket by lazy {
        FrappeSocket(
            client = httpClient,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}

/** Reach the DI container from any Context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as App).container
