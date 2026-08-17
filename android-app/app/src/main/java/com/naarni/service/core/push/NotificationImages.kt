package com.naarni.service.core.push

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.naarni.service.BuildConfig
import com.naarni.service.appContainer
import com.naarni.service.core.media.ImageScaler
import okhttp3.Request
import java.io.File

/**
 * Staging a photo so it can be shown inside a notification.
 *
 * Three things make this less obvious than "download and point at it":
 *
 * * **The bytes are behind a session cookie.** A chat attachment is a private
 *   Frappe file, so the URL cannot simply be handed to the system — the notif
 *   would silently show nothing, or worse, an HTML login page decoded as an
 *   image. It is fetched with the app's own authenticated client, the same one
 *   that fixed voice notes and video playback.
 * * **`file://` is not shareable.** SystemUI is a different process, so the
 *   image has to travel as a `content://` URI through the FileProvider that
 *   already exists for handing attachments to other apps.
 * * **This runs in Doze, on a phone nobody is holding.** A push arrives at
 *   three in the morning on a depot's connection, so the fetch is capped, given
 *   a short timeout, and allowed to fail — a notification with no picture is a
 *   perfectly good notification, and one that costs a technician their data
 *   allowance is not.
 */
object NotificationImages {

    /**
     * Refuse anything larger than this without reading it.
     *
     * Photos leave the app at roughly 250 KB after [ImageScaler]; two megabytes
     * is generous headroom for one taken before that existed, while still
     * ruling out someone's 40 MB scan of a service manual.
     */
    private const val MAX_BYTES = 2L * 1024 * 1024

    /** Plenty for a shade preview; the full image is one tap away in the thread. */
    private const val PREVIEW_EDGE = 1024

    /**
     * Must sit under the directory the FileProvider publishes, or the grant
     * fails at post time with an IllegalArgumentException.
     */
    private const val DIR = "chat_downloads"

    private const val KEEP_FILES = 12

    /**
     * Fetch [path] and return a `content://` URI, or null.
     *
     * Null is an ordinary outcome — no network, too big, not an image, the
     * fetch timed out — and every caller must treat it as "show the text".
     */
    fun stage(context: Context, path: String?): Uri? {
        if (path.isNullOrBlank()) return null
        return runCatching {
            val url = if (path.startsWith("http")) path
            else BuildConfig.BASE_URL.trimEnd('/') + path

            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val target = File(dir, "notif_${url.hashCode().toUInt()}.jpg")

            // Already staged by an earlier push for the same photo.
            if (!target.exists() || target.length() == 0L) {
                val request = Request.Builder().url(url).build()
                context.appContainer.httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return null
                    val body = response.body ?: return null
                    if (body.contentLength() > MAX_BYTES) return null
                    val type = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
                    // A login page redirect answers 200 with HTML. Decoding that
                    // as an image is how you get an empty grey box in the shade.
                    if (type.isNotEmpty() && !type.startsWith("image/")) return null

                    val bytes = body.bytes()
                    if (bytes.size > MAX_BYTES) return null
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                    val small = ImageScaler.fit(decoded, PREVIEW_EDGE)
                    target.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    small.recycle()
                }
            }

            sweep(dir)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }.getOrNull()
    }

    /**
     * Keep the staging directory small.
     *
     * These are copies of images the app already holds; letting them accumulate
     * would mean a busy depot thread quietly parking tens of megabytes in the
     * cache to redraw notifications nobody will look at again.
     */
    private fun sweep(dir: File) {
        runCatching {
            val staged = dir.listFiles { f -> f.name.startsWith("notif_") } ?: return
            if (staged.size <= KEEP_FILES) return
            staged.sortedBy { it.lastModified() }
                .dropLast(KEEP_FILES)
                .forEach { it.delete() }
        }
    }
}
