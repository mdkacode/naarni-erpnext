package com.naarni.service.data.repo

import android.content.Context
import android.net.Uri
import com.naarni.service.BuildConfig
import com.naarni.service.core.network.FrappeApi
import com.naarni.service.core.network.payload
import com.naarni.service.core.push.NotificationTones
import com.naarni.service.data.dto.DesignationDto
import com.naarni.service.data.dto.ProfileDto
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder

/**
 * The signed-in person's own record: name, face, designation, sounds.
 *
 * The one thing worth knowing here is that a profile picture is **not** fetched
 * from the url it was uploaded to. Avatars live in the private bucket, where
 * Frappe permission-checks a file against the document it hangs off — a User —
 * and nobody may read another person's User. So the raw path works for exactly
 * one viewer, its owner, and everyone else gets a broken circle. [avatarUrl]
 * points at an endpoint that does the check itself and hands over the bytes.
 */
class ProfileRepository(
    private val api: FrappeApi,
    private val uploads: UploadClient,
) {

    suspend fun load(): ProfileDto = api.getMyProfile().payload()

    suspend fun save(
        fullName: String? = null,
        designation: String? = null,
        about: String? = null,
    ): ProfileDto = api.updateMyProfile(fullName, designation, about).payload()

    suspend fun designations(query: String? = null): List<DesignationDto> =
        api.listDesignations(query).payload().designations

    /**
     * Upload a picked image and adopt it, in that order.
     *
     * Private, always — the server refuses a public one, and rightly: a public
     * url is a photograph of a named employee that needs no login.
     */
    suspend fun setPhoto(file: File, contentType: String = "image/jpeg"): ProfileDto {
        val url = uploads.uploadPrivate(file, contentType)
        return api.setProfilePhoto(url).payload()
    }

    suspend fun removePhoto(): ProfileDto = api.removeProfilePhoto().payload()

    /**
     * Store the choice and rebuild the channels.
     *
     * The server keeps it so a reinstall or a second handset inherits it; the
     * phone has to act on it locally because an Android channel's sound is fixed
     * at creation. Both halves, or the setting is a lie on one of them.
     */
    suspend fun setTones(
        context: Context,
        chatTone: String? = null,
        alertTone: String? = null,
        vibrate: Boolean? = null,
    ) {
        api.setNotificationTones(chatTone, alertTone, vibrate?.let { if (it) 1 else 0 })
        NotificationTones.applyTones(context, chatTone, alertTone, vibrate)
    }

    suspend fun snoozePrompt() {
        runCatching { api.snoozeProfilePrompt() }
    }

    companion object {
        /**
         * Where a face comes from. Null when the person has none, so the caller
         * draws initials rather than firing a request that will 404.
         */
        fun avatarUrl(user: String?, hasPhoto: Boolean = true): String? {
            if (user.isNullOrBlank() || !hasPhoto) return null
            val encoded = URLEncoder.encode(user, "UTF-8")
            return BuildConfig.BASE_URL.trimEnd('/') +
                "/api/method/vehicle_maintenance.api.profile.avatar?user=$encoded"
        }
    }
}

/**
 * Frappe's own `upload_file`, used for the one file this app sends outside the
 * chat's chunked path. A profile picture is small and one-shot; the resumable
 * machinery would be all cost and no benefit.
 */
class UploadClient(private val api: FrappeApi) {

    suspend fun uploadPrivate(file: File, contentType: String): String {
        val part = MultipartBody.Part.createFormData(
            "file",
            file.name,
            file.asRequestBody(contentType.toMediaTypeOrNull()),
        )
        val plain = "text/plain".toMediaTypeOrNull()
        val res = api.uploadStandaloneFile(
            file = part,
            isPrivate = "1".toRequestBody(plain),
            folder = "Home".toRequestBody(plain),
        )
        return res.message?.file_url
            ?: error("The upload did not come back with a file")
    }
}

/** Convenience for the picker, which hands back a content uri rather than a file. */
fun Uri.copyToCache(context: Context, name: String): File {
    val dest = File(context.cacheDir, name)
    context.contentResolver.openInputStream(this).use { input ->
        dest.outputStream().use { output -> requireNotNull(input).copyTo(output) }
    }
    return dest
}
