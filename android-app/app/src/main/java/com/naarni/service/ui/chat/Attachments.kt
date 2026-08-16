package com.naarni.service.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.naarni.service.core.media.ImageScaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Getting a picked file into the chat outbox.
 *
 * Everything is copied into app-private storage before it is queued. A 400 MB
 * video can sit waiting for wifi for hours, and in that time a content URI's
 * permission grant can be revoked and a cache directory can be purged under
 * storage pressure — either of which would orphan the upload halfway through.
 * Owning the bytes removes scoped storage from the upload path entirely.
 */
object Attachments {

    /**
     * The only things chat refuses.
     *
     * Mirrors the server's deny-list so the app can say no immediately rather
     * than after copying 400 MB to disk. Everything else — spreadsheets, CAD
     * exports, diagnostic logs, zips — goes, because an allow-list means
     * someone hits "cannot be sent" for an ordinary work document and goes back
     * to WhatsApp.
     */
    private val BLOCKED_TYPES = setOf(
        "application/vnd.android.package-archive",
        "application/x-msdownload",
        "application/x-msdos-program",
        "application/x-executable",
        "application/x-sh",
        "application/x-shellscript",
        "text/x-shellscript",
        "application/x-dosexec",
    )

    private val BLOCKED_EXTENSIONS = setOf(
        "apk", "apex", "dex", "exe", "msi", "bat", "cmd", "com", "scr", "sh", "bash",
    )

    data class Picked(val file: File, val contentType: String, val kind: String, val displayName: String)

    /**
     * Copy [uri] into `filesDir/chat_outbox` and classify it.
     *
     * Returns null only for something the server would refuse anyway, so the UI
     * can say so up front instead of copying the bytes and then failing at
     * commit.
     */
    suspend fun copyToOutbox(context: Context, uri: Uri): Picked? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rawType = resolver.getType(uri) ?: "application/octet-stream"
        val contentType = normalise(rawType)
        val displayName = queryName(context, uri) ?: "attachment"
        if (isBlocked(contentType, displayName)) return@withContext null

        val outbox = File(context.filesDir, "chat_outbox").apply { mkdirs() }
        val dest = File(outbox, "${UUID.randomUUID()}_${displayName.take(60).replace('/', '_')}")

        // Streamed, so a large video never lands in memory on its way to disk.
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: return@withContext null

        // Shrunk here, before it is queued, rather than in the upload worker. A
        // photo waiting on wifi should already be its final size, so that what
        // the outbox is holding is what will actually go over the wire — and so
        // a retry never re-does the work.
        ImageScaler.scaleInPlace(dest, contentType)

        Picked(
            file = dest,
            contentType = contentType,
            kind = kindFor(contentType),
            displayName = displayName,
        )
    }

    /** A camera capture is already a file we own; just classify it. */
    fun fromCapture(file: File): Picked =
        Picked(file = file, contentType = "image/jpeg", kind = "image", displayName = file.name)

    /** Mirrors the server's `_kind_for`, so the optimistic row matches the acked one. */
    fun kindFor(contentType: String): String = when {
        contentType.startsWith("image/") -> "image"
        contentType.startsWith("video/") -> "video"
        contentType.startsWith("audio/") -> "audio"
        else -> "file"
    }

    private fun isBlocked(contentType: String, displayName: String): Boolean =
        contentType in BLOCKED_TYPES || displayName.substringAfterLast('.', "").lowercase() in BLOCKED_EXTENSIONS

    /** Strip any `;charset=` suffix and normalise the couple of aliases we see. */
    private fun normalise(raw: String): String = when (val t = raw.substringBefore(';').trim().lowercase()) {
        "image/jpg" -> "image/jpeg"
        "audio/x-m4a", "audio/m4a" -> "audio/mp4"
        else -> t
    }

    private fun queryName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

    /** Human-readable size for the "too large" message. */
    fun megabytes(bytes: Long): Long = bytes / (1024 * 1024)
}
