package com.naarni.service.core.chat

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.naarni.service.App
import com.naarni.service.R
import com.naarni.service.appContainer
import com.naarni.service.core.network.Envelope
import com.naarni.service.core.network.FrappeWrap
import com.naarni.service.core.network.payload
import com.naarni.service.data.chat.SendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

private const val KEY_CLIENT_ID = "client_id"
private const val TAG = "ChatWorkers"

/** Files above this go on wifi only — field staff are on metered data. */
private const val UNMETERED_THRESHOLD = 20L * 1024 * 1024

/**
 * Delivers one queued text message.
 *
 * Trivial on purpose: the message already exists in Room, so this worker only
 * has to reach the server. The CONNECTED constraint is what implements offline
 * queueing — WorkManager holds the job until there is a network, which is TC02.
 */
class ChatSendWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val clientId = inputData.getString(KEY_CLIENT_ID) ?: return@withContext Result.failure()
        val repo = applicationContext.appContainer.chatRepo
        try {
            repo.deliverText(clientId)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "send failed for $clientId: ${e.message}")
            // Retry is safe: send_message is idempotent on client_id, so a send
            // that actually succeeded but lost its response resolves to the same
            // row rather than duplicating.
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                applicationContext.appContainer.chatDao
                    .markStatus(clientId, SendStatus.FAILED, e.message)
                Result.failure()
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 8
    }
}

/**
 * Uploads one attachment in resumable chunks.
 *
 * Holds no state worth losing: after process death it asks the server where it
 * got to and continues from there, so a killed 400 MB upload costs one chunk
 * rather than the file. Runs as a `dataSync` foreground service, which is what
 * buys it Doze and battery-saver exemption for the duration.
 */
class ChunkUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0)

    private fun foregroundInfo(pct: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, App.CHANNEL_CHAT_UPLOADS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("Sending attachment")
            .setContentText(if (pct > 0) "$pct%" else "Preparing…")
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val clientId = inputData.getString(KEY_CLIENT_ID) ?: return@withContext Result.failure()
        val container = applicationContext.appContainer
        val dao = container.chatDao
        val api = container.api

        val row = dao.upload(clientId) ?: return@withContext Result.failure()
        val file = File(row.path)
        if (!file.exists()) {
            dao.markStatus(clientId, SendStatus.FAILED, "file missing")
            return@withContext Result.failure()
        }

        try {
            setForeground(foregroundInfo(row.pct))

            // 1 — Reserve staging server-side, once.
            val uploadId = row.uploadId ?: api.chatBeginUpload(
                room = row.room,
                fileName = row.fileName,
                totalSize = row.totalSize,
                contentType = row.contentType,
                sha256 = row.sha256,
            ).let { unwrap(it) }.upload_id.also { dao.setUploadId(clientId, it) }

            // 2 — The server, not our bookkeeping, decides where we resume. This
            //     is the line that makes process death survivable.
            var offset = unwrap(api.chatChunkStatus(uploadId)).bytes_received
            val total = file.length()
            dao.markProgress(clientId, ((offset * 100) / total.coerceAtLeast(1)).toInt())

            // 3 — Stream. Never read the whole file into memory.
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(CHUNK)
                while (offset < total) {
                    if (isStopped) return@withContext Result.retry()
                    raf.seek(offset)
                    val n = raf.read(buf)
                    if (n <= 0) break

                    unwrap(
                        api.chatUploadChunk(
                            uploadId = uploadId.toPlainPart(),
                            offset = offset.toString().toPlainPart(),
                            chunk = MultipartBody.Part.createFormData(
                                "chunk",
                                row.fileName,
                                buf.toRequestBody(OCTET, 0, n),
                            ),
                        )
                    )

                    offset += n
                    val pct = ((offset * 100) / total).toInt()
                    // Durable — survives process death and outlives the worker.
                    dao.setUploadProgress(clientId, offset, pct)
                    dao.markProgress(clientId, pct)
                    // Ephemeral — only for a screen that is currently open.
                    setProgress(workDataOf(KEY_PROGRESS to pct))
                    setForeground(foregroundInfo(pct))
                }
            }

            // 4 — Commit: verifies size + digest, publishes the File, posts the message.
            val committed = unwrap(
                api.chatCommitUpload(
                    uploadId = uploadId,
                    clientId = clientId,
                    body = row.caption,
                    replyTo = row.replyTo,
                    durationMs = row.durationMs,
                    lat = row.lat,
                    lon = row.lon,
                )
            )
            val msg = committed.message
            dao.markSent(clientId, msg.name, msg.seq, msg.file_url)
            dao.advanceReadCursor(row.room, msg.seq)
            dao.deleteUpload(clientId)
            runCatching { file.delete() }

            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "upload failed for $clientId: ${e.message}")
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                dao.markStatus(clientId, SendStatus.FAILED, e.message)
                Result.failure()
            }
        }
    }

    private fun <T> unwrap(wrap: FrappeWrap<Envelope<T>>): T = wrap.payload()

    private fun String.toPlainPart() = toRequestBody(PLAIN)

    private companion object {
        const val NOTIF_ID = 4801
        const val CHUNK = 4 * 1024 * 1024
        const val MAX_ATTEMPTS = 10
        val OCTET = "application/octet-stream".toMediaType()
        val PLAIN = "text/plain".toMediaType()
    }
}

const val KEY_PROGRESS = "progress"

/** Enqueue helpers. Unique work per client_id, so a double-tap cannot double-send. */
object ChatWork {

    fun enqueueText(context: Context, clientId: String) {
        val request = OneTimeWorkRequestBuilder<ChatSendWorker>()
            .setInputData(workDataOf(KEY_CLIENT_ID to clientId))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("chat-send-$clientId", ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueUpload(context: Context, clientId: String, bytes: Long) {
        val request = OneTimeWorkRequestBuilder<ChunkUploadWorker>()
            // Never the bytes: Data is capped at 10 KB. Only the id travels.
            .setInputData(workDataOf(KEY_CLIENT_ID to clientId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (bytes > UNMETERED_THRESHOLD) NetworkType.UNMETERED else NetworkType.CONNECTED
                    )
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // KEEP, not REPLACE — restarting a transfer that is 80% done would be a
        // brutal thing to do on a metered link.
        WorkManager.getInstance(context)
            .enqueueUniqueWork("chat-upload-$clientId", ExistingWorkPolicy.KEEP, request)
    }

    /** Observe an upload's live state (queued / running / blocked on constraint). */
    fun uploadInfo(context: Context, clientId: String) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("chat-upload-$clientId")

    fun cancel(context: Context, clientId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("chat-upload-$clientId")
        WorkManager.getInstance(context).cancelUniqueWork("chat-send-$clientId")
    }
}

/** Cancels the upload notification if the process is torn down mid-flight. */
internal fun Context.clearUploadNotification(id: Int) {
    (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(id)
}
