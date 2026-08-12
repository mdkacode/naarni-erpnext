package com.naarni.service.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.naarni.service.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * Opening a document somebody sent you.
 *
 * Chat attachments live under Frappe's `/private/files/`, so they are not
 * fetchable by handing the URL to a browser — the request has to carry the
 * session cookie, which means it has to go through the app's own OkHttp client.
 * The bytes are staged in a cache directory and handed on as a `content://`
 * grant: a `file://` Uri throws FileUriExposedException on anything modern, and
 * exposing the whole cache would put the session store one path traversal away.
 *
 * Already-downloaded files are reused, so tapping a spreadsheet twice does not
 * pull it down a depot's link twice.
 */
object FileOpener {

    sealed interface Result {
        data object Opened : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun open(context: Context, message: com.naarni.service.data.chat.ChatMessageEntity): Result {
        // Still in the outbox: it is our own file and never left the device.
        message.localPath?.let { path ->
            val local = File(path)
            if (local.exists()) return hand(context, local, message)
        }

        val url = message.fileUrl?.let(::absoluteUrl)
            ?: return Result.Failed("This attachment is still uploading.")

        val staged = runCatching { download(context, url, message.fileName ?: "attachment") }
            .getOrElse { return Result.Failed(it.message ?: "Couldn't download that file.") }

        return hand(context, staged, message)
    }

    private suspend fun download(context: Context, url: String, name: String): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "chat_downloads").apply { mkdirs() }
            // Named from the server path, so the same attachment resolves to the
            // same cache entry across sessions.
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "attachment" }
            val dest = File(dir, "${url.substringAfterLast('/').take(40)}_$safe")
            if (dest.exists() && dest.length() > 0) return@withContext dest

            val response = context.appContainer.httpClient
                .newCall(Request.Builder().url(url).build())
                .execute()
            response.use {
                val body = it.body ?: error("Empty response")
                if (!it.isSuccessful) error("Server returned ${it.code}")
                // Streamed to a partial file and renamed only once complete, so an
                // interrupted download can never be mistaken for a cached one.
                val partial = File(dest.absolutePath + ".part")
                body.byteStream().use { input ->
                    partial.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
                partial.renameTo(dest)
            }
            dest
        }

    private fun hand(
        context: Context,
        file: File,
        message: com.naarni.service.data.chat.ChatMessageEntity,
    ): Result {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrElse { return Result.Failed("Couldn't share that file with another app.") }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(message))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Result.Opened
        } catch (_: ActivityNotFoundException) {
            Result.Failed("No app on this phone can open ${message.fileName ?: "that file"}.")
        }
    }

    /**
     * The Room row does not carry a content type, so it is inferred from the
     * name. Getting this wrong only costs the chooser a hint — the wildcard
     * fallback still lets the user pick an app.
     */
    private fun mimeOf(message: com.naarni.service.data.chat.ChatMessageEntity): String {
        val ext = (message.fileName ?: "").substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "csv" -> "text/csv"
            "txt", "log" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "*/*"
        }
    }
}

/** Bytes as something a person reads at a glance. */
fun humanSize(bytes: Long?): String {
    val b = bytes ?: return ""
    return when {
        b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
        b >= 1024L * 1024 -> "%.1f MB".format(b / (1024.0 * 1024))
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }
}
