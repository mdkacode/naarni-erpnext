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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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

/** Read a string field from a tolerant JsonObject response (empty if absent). */
private fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: ""

class JobCardRepository(private val api: FrappeApi) {

    private val repoJson = kotlinx.serialization.json.Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    /** Live dropdown options keyed by option name (admin-editable via Customize Form). */
    suspend fun appFieldOptions(): Map<String, List<String>> =
        api.getAppFieldOptions().payload()

    suspend fun formContext(vehicle: String, type: String, odometer: Int?): FormContext =
        api.getFormContext(vehicle, type, odometer).payload()

    suspend fun searchVehicles(txt: String): List<VehicleHit> =
        api.searchVehicles(txt).payload()

    suspend fun searchDepots(txt: String): List<com.naarni.service.data.dto.DepotHit> =
        api.searchDepots(txt).payload()

    /** The depots the current Service Engineer is assigned to (scopes Alerts/Tickets). */
    suspend fun myDepots(): com.naarni.service.data.dto.MyDepots =
        api.getMyDepots().payload()

    /** Change the current SE's service depot — re-scopes their alerts + tickets. */
    suspend fun setMyDepot(depot: String) {
        api.setMyDepot(depot).payload()
    }

    suspend fun searchCustomers(txt: String): List<com.naarni.service.data.dto.CustomerHit> =
        api.searchCustomers(txt).payload()

    /** Create a customer on the spot; returns its name (id). */
    suspend fun createCustomer(name: String, phone: String): com.naarni.service.data.dto.CustomerHit =
        api.createCustomer(name, phone).payload()

    suspend fun complaints(txt: String, subsystem: String = ""): List<SuggestionItem> =
        api.listComplaints(subsystem, txt).payload()

    suspend fun faultCodes(txt: String): List<SuggestionItem> =
        api.listFaultCodes(txt = txt).payload()

    suspend fun observations(txt: String): List<SuggestionItem> =
        api.listObservationTemplates(txt = txt).payload()

    suspend fun myJobCards(status: String = ""): List<JobCardListItem> =
        api.getMyJobCards(status).payload()

    suspend fun jobCardDetail(name: String): com.naarni.service.data.dto.JobCardDetail =
        api.getJobCardSummary(name).payload()

    suspend fun transitionJobCard(name: String, action: String) {
        api.transitionJobCard(name, action).payload()
    }

    /** Update editable job-card fields (complaint, observations, priority, …). */
    suspend fun updateJobCard(name: String, updates: Map<String, String>) {
        val json = updates.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
        }
        api.updateJobCard(name, json).payload()
    }

    // ── Repair / Maintenance job lists ──
    suspend fun partGroups(): List<com.naarni.service.data.dto.PartGroupItem> =
        api.listPartGroups().payload()

    /** Part groups as picker options (for the breakdown "groups impacted" multiselect). */
    suspend fun partGroupOptions(txt: String = ""): List<SuggestionItem> =
        api.listPartGroups().payload()
            .map { SuggestionItem(value = it.name, label = it.part_group_name ?: it.name) }
            .filter { txt.isBlank() || it.label.contains(txt, ignoreCase = true) }

    /** Save the breakdown's impacted part-groups (Table MultiSelect). */
    suspend fun saveGroupsImpacted(name: String, groups: List<String>) {
        val json = groups.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" }
        api.saveGroupsImpacted(name, json).payload()
    }

    suspend fun saveRepairItems(name: String, rows: List<com.naarni.service.data.dto.RepairItem>) {
        api.saveRepairItems(name, repoJson.encodeToString(rows)).payload()
    }

    suspend fun saveMaintenanceItems(name: String, rows: List<com.naarni.service.data.dto.MaintenanceItem>) {
        api.saveMaintenanceItems(name, repoJson.encodeToString(rows)).payload()
    }

    // ── Inventory request flow ──
    suspend fun parts(txt: String, partGroup: String = ""): List<com.naarni.service.data.dto.PartItem> =
        api.listParts(partGroup, txt).payload()

    suspend fun createInventoryRequest(jobCard: String, part: String, quantity: Double, urgency: String, notes: String = "") {
        api.createInventoryRequest(jobCard, part, quantity, urgency, notes).payload()
    }

    suspend fun advanceInventoryStatus(name: String, nextStatus: String) {
        api.advanceInventoryStatus(name, nextStatus).payload()
    }

    // ── Approval / Force close / Reopen ──
    suspend fun recordApprovalDecision(
        name: String,
        approved: Boolean,
        rejectionFeedback: String = "",
        perPartFeedback: List<Triple<String, String, String>> = emptyList(),
    ) {
        // [{part_group, description, feedback}, …]
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val json = perPartFeedback.joinToString(",", "[", "]") { (pg, desc, fb) ->
            "{\"part_group\":\"${esc(pg)}\",\"description\":\"${esc(desc)}\",\"feedback\":\"${esc(fb)}\"}"
        }
        api.recordApprovalDecision(name, if (approved) 1 else 0, rejectionFeedback, json).payload()
    }

    suspend fun forceCloseJobCard(name: String, severity: String, reason: String) {
        api.forceCloseJobCard(name, severity, reason).payload()
    }

    suspend fun reopenJobCard(name: String, reason: String) {
        api.reopenJobCard(name, reason).payload()
    }

    // ── Software update + Breakdown ──
    suspend fun saveSoftwareComponents(name: String, rows: List<com.naarni.service.data.dto.SoftwareComponent>) {
        api.saveSoftwareComponents(name, repoJson.encodeToString(rows)).payload()
    }

    suspend fun updateBreakdownDiagnosis(name: String, updates: Map<String, String>) {
        val json = updates.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
        }
        api.updateBreakdownDiagnosis(name, json).payload()
    }

    // ── Closure report (PDF proof of service) ──
    suspend fun shareClosureReport(name: String): com.naarni.service.data.dto.ShareReport =
        api.shareClosureReport(name).payload()

    suspend fun emailClosureReport(name: String): String =
        api.emailClosureReport(name).payload().str("sent_to")

    // ── Customer feedback ──
    suspend fun customerFeedback(name: String): com.naarni.service.data.dto.CustomerFeedbackData? =
        api.getCustomerFeedback(name).message?.data

    suspend fun submitCustomerFeedback(
        name: String,
        rating: Int,
        comments: String = "",
        npsScore: Int = 0,
        wouldRecommend: String = "",
    ) {
        api.submitCustomerFeedback(name, rating, comments, npsScore, wouldRecommend).payload()
    }

    /** All synced vehicles for the fleet list (optionally filtered). */
    suspend fun fleet(txt: String = ""): com.naarni.service.data.dto.FleetResponse =
        api.listFleet(txt).payload()

    /** Live Naarni telemetry for one vehicle (IST); null when not linked/unreachable. */
    suspend fun vehicleLive(name: String): com.naarni.service.data.dto.VehicleLive? =
        api.getVehicleLive(name).message?.data

    suspend fun notifications(limit: Int = 30, offset: Int = 0): List<NotificationItem> =
        api.getMyNotifications(limit, offset).payload()

    suspend fun unreadCount(): Int = api.getUnreadCount().payload().unread

    suspend fun markNotificationRead(name: String) {
        api.markNotificationRead(name = name).payload()
    }

    suspend fun markAllNotificationsRead() {
        api.markNotificationRead(markAll = 1).payload()
    }

    suspend fun registerPushToken(token: String, platform: String = "android") {
        api.registerPushToken(token, platform).payload()
    }

    /** Create a Job Card; returns its name. Reuses the deployed inspection endpoint. */
    suspend fun createJobCard(
        vehicleNumber: String,
        odometer: Int,
        serviceType: String,
        jobCardType: String,
        complaint: String,
        depot: String = "",
        customer: String = "",
        inspectionSheetId: String = "",
        inspectionResults: String = "{}",
        inspectionPoc: String = "",
        subsystems: List<String> = emptyList(),
    ): String = api.createJobCard(
        vehicleNumber = vehicleNumber,
        odometer = odometer,
        serviceType = serviceType,
        jobCardType = jobCardType,
        complaint = complaint,
        inspectionSheetId = inspectionSheetId,
        inspectionResults = inspectionResults,
        inspectionPoc = inspectionPoc,
        subsystems = subsystems.joinToString(",", "[", "]") { "\"${it.replace("\"", "\\\"")}\"" },
        depot = depot,
        customer = customer,
    ).payload().name

    /** PMS inspection check sheet (component grid) for the given odometer. */
    suspend fun inspectionSheet(odometer: Int): com.naarni.service.data.dto.InspectionSheet =
        api.getInspectionSheet(odometer).payload()

    /** Active users holding [role] (e.g. "Technician") — for the inspection-POC picker. */
    suspend fun usersByRole(role: String, txt: String = ""): List<SuggestionItem> =
        api.listUsersByRole(role, txt).payload().map {
            SuggestionItem(value = it.user, label = it.full_name ?: it.user)
        }

    /** Subsystem master for the Repair / Software / Breakdown multiselect. */
    suspend fun subsystems(txt: String = ""): List<SuggestionItem> =
        api.listSubsystems().payload()
            .filter { txt.isBlank() || (it.subsystem_name ?: it.name).contains(txt, ignoreCase = true) }
            .map { SuggestionItem(value = it.name, label = it.subsystem_name ?: it.name, sublabel = it.category) }

    /** Attach a (stamped) photo to a Job Card via Frappe's upload_file. */
    suspend fun uploadPhoto(file: File, jobCardName: String) {
        val part = MultipartBody.Part.createFormData(
            "file", file.name, file.asRequestBody("image/jpeg".toMediaType()),
        )
        fun text(v: String) = v.toRequestBody("text/plain".toMediaType())
        api.uploadFile(part, text("Job Card"), text(jobCardName), text("0"))
    }

    /**
     * Upload a (stamped) per-item photo and return its File URL so it can be
     * stored on a repair / maintenance / software row (pre/post photo fields).
     */
    suspend fun uploadItemPhoto(file: File, jobCardName: String): String {
        val part = MultipartBody.Part.createFormData(
            "file", file.name, file.asRequestBody("image/jpeg".toMediaType()),
        )
        fun text(v: String) = v.toRequestBody("text/plain".toMediaType())
        return api.uploadFile(part, text("Job Card"), text(jobCardName), text("0"))
            .message?.file_url ?: throw ApiException("Photo upload failed")
    }

    // ── Bus image gallery (Vehicle + Job Card) ──
    suspend fun busImages(parentDoctype: String, parentName: String): List<com.naarni.service.data.dto.BusImage> =
        api.getBusImages(parentDoctype, parentName).payload()

    /**
     * Upload a stamped photo and map it to [angle] on the parent (Vehicle / Job Card):
     * uploads the file (public), then records the angle. Returns the refreshed gallery.
     */
    suspend fun uploadBusImage(
        parentDoctype: String,
        parentName: String,
        angle: String,
        file: File,
    ): List<com.naarni.service.data.dto.BusImage> {
        val part = MultipartBody.Part.createFormData(
            "file", file.name, file.asRequestBody("image/jpeg".toMediaType()),
        )
        fun text(v: String) = v.toRequestBody("text/plain".toMediaType())
        val fileUrl = api.uploadFile(part, text(parentDoctype), text(parentName), text("0"))
            .message?.file_url ?: throw ApiException("Upload failed")
        return api.uploadBusImage(parentDoctype, parentName, angle, fileUrl).payload()
    }

    suspend fun deleteBusImage(parentDoctype: String, parentName: String, angle: String): List<com.naarni.service.data.dto.BusImage> =
        api.deleteBusImage(parentDoctype, parentName, angle).payload()

    // ── Tickets + SE-scoped alerts ──
    suspend fun myTickets(status: String = ""): List<TicketItem> =
        api.getMyTickets(status).payload()

    suspend fun myAlertEvents(severity: String = "", status: String = ""): List<AlertEventItem> =
        api.getMyAlertEvents(severity, status).payload()

    suspend fun acknowledgeTicket(name: String) { api.acknowledgeTicket(name).payload() }

    suspend fun resolveTicket(name: String, reason: String) { api.resolveTicket(name, reason).payload() }

    suspend fun createJobCardFromTicket(name: String): String =
        api.createJobCardFromTicket(name).payload().str("job_card")
}
