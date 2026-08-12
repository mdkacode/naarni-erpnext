package com.naarni.service.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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

    /** Types the chat backend's allow-list accepts. */
    private val SUPPORTED = setOf(
        "image/jpeg", "image/png", "image/webp",
        "video/mp4", "video/quicktime",
        "audio/mp4", "audio/aac", "audio/mpeg", "audio/ogg", "audio/opus",
        "application/pdf",
    )

    data class Picked(val file: File, val contentType: String, val kind: String, val displayName: String)

    /**
     * Copy [uri] into `filesDir/chat_outbox` and classify it.
     *
     * Returns null when the type is not one the server will accept, so the UI
     * can say so instead of queueing an upload that is guaranteed to be
     * rejected at commit.
     */
    suspend fun copyToOutbox(context: Context, uri: Uri): Picked? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rawType = resolver.getType(uri) ?: "application/octet-stream"
        val contentType = normalise(rawType)
        if (contentType !in SUPPORTED) return@withContext null

        val displayName = queryName(context, uri) ?: "attachment"
        val outbox = File(context.filesDir, "chat_outbox").apply { mkdirs() }
        val dest = File(outbox, "${UUID.randomUUID()}_${displayName.take(60).replace('/', '_')}")

        // Streamed, so a large video never lands in memory on its way to disk.
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: return@withContext null

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

    fun kindFor(contentType: String): String = when {
        contentType.startsWith("image/") -> "image"
        contentType.startsWith("video/") -> "video"
        contentType.startsWith("audio/") -> "audio"
        else -> "image" // PDFs render as a document card
    }

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
