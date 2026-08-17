package com.naarni.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.naarni.service.core.auth.SessionManager
import com.naarni.service.core.chat.FrappeSocket
import com.naarni.service.core.network.Network
import com.naarni.service.data.chat.ChatDatabase
import com.naarni.service.data.repo.AuthRepository
import com.naarni.service.data.repo.ChatRepository
import com.naarni.service.data.repo.JobCardRepository
import com.naarni.service.data.repo.ProcessRepository
import com.naarni.service.data.repo.RosterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application + manual DI container. We avoid an annotation-processor DI framework
 * to keep the APK small and the build fast (plan §2) — the graph is tiny.
 */
class App : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    /**
     * Coil must use the app's OkHttp client, not its own.
     *
     * Chat attachments are private Frappe files (`/private/files/...`) and the
     * session `sid` cookie lives in [SessionCookieJar] on that client. With a
     * default loader every photo in every thread renders as a 403 placeholder.
     *
     * The memory cache is capped rather than left to Coil's default so a long
     * image-heavy thread has a fixed ceiling on a 2 GB field handset, and
     * thumbnails decode as RGB_565 — half the bytes of ARGB_8888, and the
     * difference is invisible on a photo of a battery terminal.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { container.httpClient }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("chat_images"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(true)
            // No crossfade. It puts a 100ms fade in front of every thumbnail,
            // which on a grid of twenty reads as the whole screen hesitating.
            // A photo that simply appears feels faster than one that arrives.
            .crossfade(false)
            .build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Chat and alert channels are owned by NotificationTones now: a channel's
        // sound is fixed when it is created, so the chosen tone has to be part of
        // the id. Everything else — the upload progress channel — stays here.
        createUploadChannel()
        com.naarni.service.core.push.NotificationTones.ensureChannels(this)
        retireLegacyChannels()
    }

    /**
     * Upload progress: persistent, silent, and nothing to do with tones.
     *
     * LOW keeps it out of the shade's alerting section and off the lock screen.
     */
    private fun createUploadChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CHAT_UPLOADS,
                "Attachment Uploads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress for photos and videos being sent"
                setShowBadge(false)
            },
        )
    }

    /**
     * Remove the channels this app used to create for chat and alerts.
     *
     * Left behind they stay in the person's notification settings for ever as
     * dead rows they can toggle and get nothing from — and worse, two entries
     * called "Chat Messages", only one of which does anything.
     */
    private fun retireLegacyChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        listOf("chat_messages", CHANNEL_CHAT, CHANNEL_JOB_CARDS).forEach {
            runCatching { mgr.deleteNotificationChannel(it) }
        }
    }

    companion object {
        const val CHANNEL_JOB_CARDS = "job_cards"

        /**
         * Suffixed because a channel's sound and vibration are fixed at creation
         * — Android ignores every later edit to an id it already knows. Giving
         * chat its own tone therefore requires a new id, and anyone upgrading
         * keeps the silent original until this one replaces it.
         */
        const val CHANNEL_CHAT = "chat_messages_v2"
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
    val rosterRepo by lazy { RosterRepository(api, appContext) }
    val profileRepo by lazy {
        com.naarni.service.data.repo.ProfileRepository(
            api,
            com.naarni.service.data.repo.UploadClient(api),
        )
    }

    // ---- Chat ----
    val chatDb by lazy { ChatDatabase.build(appContext) }
    val chatDao by lazy { chatDb.chatDao() }
    val chatRepo by lazy { ChatRepository(api, chatDao, appContext, session) }

    /**
     * One socket for the process, bound to the app lifecycle rather than to any
     * screen — see ChatLifecycleObserver.
     */
    val chatSocket by lazy {
        FrappeSocket(
            client = httpClient,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            session = session,
        )
    }
}

/** Reach the DI container from any Context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as App).container
