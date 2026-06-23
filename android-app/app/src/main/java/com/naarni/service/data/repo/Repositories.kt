package com.naarni.service.data.repo

import com.naarni.service.core.auth.SessionManager
import com.naarni.service.core.network.ApiException
import com.naarni.service.core.network.FrappeApi
import com.naarni.service.core.network.payload
import com.naarni.service.data.dto.AlertEventItem
import com.naarni.service.data.dto.FormContext
import com.naarni.service.data.dto.JobCardListItem
import com.naarni.service.data.dto.NotificationItem
import com.naarni.service.data.dto.SuggestionItem
import com.naarni.service.data.dto.TicketItem
import com.naarni.service.data.dto.VehicleHit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class AuthRepository(
    private val api: FrappeApi,
    private val session: SessionManager,
) {
    /**
     * Phone login. The `sid` session cookie is captured by the OkHttp cookie jar;
     * we treat a non-blank sid as success and best-effort enrich user/roles.
     */
    suspend fun login(phone: String, password: String): Result<Unit> = runCatching {
        val resp = api.loginWithPhone(phone, password)
        resp.message?.let { env ->
            if (!env.success && session.sid.isNullOrBlank()) {
                throw ApiException(env.message ?: "Login failed")
            }
            env.data?.let { data ->
                session.user = data.user
                session.fullName = data.full_name
                if (data.roles.isNotEmpty()) session.roles = data.roles.toSet()
            }
        }
        if (session.sid.isNullOrBlank()) throw ApiException("Login failed — no session")
        if (session.roles.isEmpty()) {
            runCatching { api.getUserRoles().message }.getOrNull()
                ?.let { session.roles = it.toSet() }
        }
    }

    /** Step 1 of OTP login: ask the backend to send a code to this phone (device-bound). */
    suspend fun requestOtp(phone: String): Result<Unit> = runCatching {
        val env = api.requestOtp(phone, session.deviceUuid).message
        if (env?.success == false) throw ApiException(env.message ?: "Could not send code")
    }

    /**
     * Step 2 of OTP login: verify the code. On success the `sid` cookie is set by
     * the backend (Naarni-brokered) and we enrich display user/roles.
     */
    suspend fun verifyOtp(phone: String, otp: String): Result<Unit> = runCatching {
        val resp = api.verifyOtp(phone, otp, session.deviceUuid)
        resp.message?.let { env ->
            if (!env.success && session.sid.isNullOrBlank()) {
                throw ApiException(env.message ?: "Invalid code")
            }
            env.data?.let { data ->
                session.user = data.user
                session.fullName = data.full_name
                if (data.roles.isNotEmpty()) session.roles = data.roles.toSet()
            }
        }
        if (session.sid.isNullOrBlank()) throw ApiException("Login failed — no session")
        if (session.roles.isEmpty()) {
            runCatching { api.getUserRoles().message }.getOrNull()
                ?.let { session.roles = it.toSet() }
        }
    }

    fun logout() = session.clear()
}

class JobCardRepository(private val api: FrappeApi) {

    suspend fun formContext(vehicle: String, type: String, odometer: Int?): FormContext =
        api.getFormContext(vehicle, type, odometer).payload()

    suspend fun searchVehicles(txt: String): List<VehicleHit> =
        api.searchVehicles(txt).payload()

    suspend fun complaints(txt: String, subsystem: String = ""): List<SuggestionItem> =
        api.listComplaints(subsystem, txt).payload()

    suspend fun faultCodes(txt: String): List<SuggestionItem> =
        api.listFaultCodes(txt = txt).payload()

    suspend fun observations(txt: String): List<SuggestionItem> =
        api.listObservationTemplates(txt = txt).payload()

    suspend fun myJobCards(status: String = ""): List<JobCardListItem> =
        api.getMyJobCards(status).payload()

    suspend fun notifications(): List<NotificationItem> =
        api.getMyNotifications().payload()

    suspend fun unreadCount(): Int = api.getUnreadCount().payload().unread

    suspend fun registerPushToken(token: String) {
        api.registerPushToken(token).payload()
    }

    /** Create a Job Card; returns its name. Reuses the deployed inspection endpoint. */
    suspend fun createJobCard(
        vehicleNumber: String,
        odometer: Int,
        serviceType: String,
        jobCardType: String,
        complaint: String,
    ): String = api.createJobCard(
        vehicleNumber = vehicleNumber,
        odometer = odometer,
        serviceType = serviceType,
        jobCardType = jobCardType,
        complaint = complaint,
    ).payload().name

    /** Attach a (stamped) photo to a Job Card via Frappe's upload_file. */
    suspend fun uploadPhoto(file: File, jobCardName: String) {
        val part = MultipartBody.Part.createFormData(
            "file", file.name, file.asRequestBody("image/jpeg".toMediaType()),
        )
        fun text(v: String) = v.toRequestBody("text/plain".toMediaType())
        api.uploadFile(part, text("Job Card"), text(jobCardName), text("0"))
    }

    // ── Tickets + SE-scoped alerts ──
    suspend fun myTickets(status: String = ""): List<TicketItem> =
        api.getMyTickets(status).payload()

    suspend fun myAlertEvents(): List<AlertEventItem> =
        api.getMyAlertEvents().payload()

    suspend fun acknowledgeTicket(name: String) { api.acknowledgeTicket(name).payload() }

    suspend fun resolveTicket(name: String, reason: String) { api.resolveTicket(name, reason).payload() }

    suspend fun createJobCardFromTicket(name: String): String =
        api.createJobCardFromTicket(name).payload()["job_card"].orEmpty()
}
