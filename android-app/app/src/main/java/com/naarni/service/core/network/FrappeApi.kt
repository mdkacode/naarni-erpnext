package com.naarni.service.core.network

import com.naarni.service.data.dto.AlertEventItem
import com.naarni.service.data.dto.BeginUploadPayload
import com.naarni.service.data.dto.BusImage
import com.naarni.service.data.dto.PresencePayload
import com.naarni.service.data.dto.ReactionsPayload
import com.naarni.service.data.dto.TypingPayload
import com.naarni.service.data.dto.ChunkPayload
import com.naarni.service.data.dto.ChunkStatusPayload
import com.naarni.service.data.dto.CommitPayload
import com.naarni.service.data.dto.ChatUserDto
import com.naarni.service.data.dto.CreateRoomPayload
import com.naarni.service.data.dto.DirectRoomPayload
import com.naarni.service.data.dto.UsersPayload
import com.naarni.service.data.dto.MarkReadPayload
import com.naarni.service.data.dto.MessagesPayload
import com.naarni.service.data.dto.RoomsPayload
import com.naarni.service.data.dto.SendPayload
import com.naarni.service.data.dto.SyncPayload
import com.naarni.service.data.dto.TicketsPayload
import com.naarni.service.data.dto.CreatedJobCard
import com.naarni.service.data.dto.CustomerFeedbackData
import com.naarni.service.data.dto.CustomerHit
import com.naarni.service.data.dto.DepotHit
import com.naarni.service.data.dto.FileUploadData
import com.naarni.service.data.dto.FleetResponse
import com.naarni.service.data.dto.FormContext
import com.naarni.service.data.dto.JobCardDetail
import com.naarni.service.data.dto.TicketItem
import com.naarni.service.data.dto.JobCardListItem
import com.naarni.service.data.dto.LoginData
import com.naarni.service.data.dto.NotificationItem
import com.naarni.service.data.dto.OtpStatus
import com.naarni.service.data.dto.PartGroupItem
import com.naarni.service.data.dto.PartItem
import com.naarni.service.data.dto.ShareReport
import com.naarni.service.data.dto.SuggestionItem
import com.naarni.service.data.dto.UnreadCount
import com.naarni.service.data.dto.VehicleHit
import com.naarni.service.data.dto.VehicleLive
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * Retrofit interface for the deployed `vehicle_maintenance` whitelisted methods
 * (base https://service.naarni.com/). Every method auto-fills or feeds a
 * searchable dropdown — there is no free-text-only endpoint.
 */
interface FrappeApi {

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.auth.login_with_phone")
    suspend fun loginWithPhone(
        @Field("phone") phone: String,
        @Field("password") password: String,
    ): FrappeWrap<Envelope<LoginData>>

    // ── Unified OTP SSO (Naarni-brokered, device-bound) ──
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.auth.request_otp")
    suspend fun requestOtp(
        @Field("phone") phone: String,
        @Field("device_uuid") deviceUuid: String,
        @Field("platform") platform: String = "ANDROID",
    ): FrappeWrap<Envelope<OtpStatus>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.auth.verify_otp")
    suspend fun verifyOtp(
        @Field("phone") phone: String,
        @Field("otp") otp: String,
        @Field("device_uuid") deviceUuid: String,
        @Field("platform") platform: String = "ANDROID",
    ): FrappeWrap<Envelope<LoginData>>

    @GET("api/method/vehicle_maintenance.fleet_service.doctype.job_card.job_card.get_user_roles")
    suspend fun getUserRoles(): FrappeWrap<List<String>>

    /** App version gate — checked on launch (works pre-login). */
    @GET("api/method/vehicle_maintenance.api.app.get_app_update")
    suspend fun getAppUpdate(
        @Query("platform") platform: String = "ANDROID",
        @Query("version_code") versionCode: Int = 0,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.AppUpdateInfo>>

    /** Request deletion of the signed-in account (Play account-deletion requirement). */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.auth.request_account_deletion")
    suspend fun requestAccountDeletion(
        @Field("reason") reason: String = "",
    ): FrappeWrap<Envelope<JsonObject>>

    // ── Dynamic dropdown options (admin-editable via Customize Form) ──
    @GET("api/method/vehicle_maintenance.api.options.get_app_field_options")
    suspend fun getAppFieldOptions(): FrappeWrap<Envelope<Map<String, List<String>>>>

    // ── Auto-fill ──
    @GET("api/method/vehicle_maintenance.api.job_card.get_job_card_form_context")
    suspend fun getFormContext(
        @Query("vehicle") vehicle: String,
        @Query("job_card_type") jobCardType: String,
        @Query("odometer") odometer: Int? = null,
    ): FrappeWrap<Envelope<FormContext>>

    // ── SmartSelect sources ──
    @GET("api/method/vehicle_maintenance.api.job_card.search_vehicles")
    suspend fun searchVehicles(
        @Query("txt") txt: String,
        @Query("limit") limit: Int = 10,
    ): FrappeWrap<Envelope<List<VehicleHit>>>

    @GET("api/method/vehicle_maintenance.api.job_card.search_depots")
    suspend fun searchDepots(
        @Query("txt") txt: String,
        @Query("limit") limit: Int = 10,
    ): FrappeWrap<Envelope<List<DepotHit>>>

    @GET("api/method/vehicle_maintenance.api.tickets.get_my_depots")
    suspend fun getMyDepots(): FrappeWrap<Envelope<com.naarni.service.data.dto.MyDepots>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.tickets.set_my_depot")
    suspend fun setMyDepot(
        @Field("depot") depot: String,
    ): FrappeWrap<Envelope<JsonObject>>

    @GET("api/method/vehicle_maintenance.api.job_card.search_customers")
    suspend fun searchCustomers(
        @Query("txt") txt: String,
        @Query("limit") limit: Int = 20,
    ): FrappeWrap<Envelope<List<CustomerHit>>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.create_customer")
    suspend fun createCustomer(
        @Field("customer_name") customerName: String,
        @Field("mobile_no") mobileNo: String = "",
    ): FrappeWrap<Envelope<CustomerHit>>

    @GET("api/method/vehicle_maintenance.api.job_card.list_complaints")
    suspend fun listComplaints(
        @Query("subsystem") subsystem: String = "",
        @Query("txt") txt: String = "",
        @Query("limit") limit: Int = 20,
    ): FrappeWrap<Envelope<List<SuggestionItem>>>

    @GET("api/method/vehicle_maintenance.api.job_card.list_fault_codes")
    suspend fun listFaultCodes(
        @Query("part_group") partGroup: String = "",
        @Query("subsystem") subsystem: String = "",
        @Query("txt") txt: String = "",
        @Query("limit") limit: Int = 20,
    ): FrappeWrap<Envelope<List<SuggestionItem>>>

    @GET("api/method/vehicle_maintenance.api.job_card.list_observation_templates")
    suspend fun listObservationTemplates(
        @Query("subsystem") subsystem: String = "",
        @Query("txt") txt: String = "",
        @Query("limit") limit: Int = 20,
    ): FrappeWrap<Envelope<List<SuggestionItem>>>

    // ── Fleet (all synced vehicles, for the SE fleet list) ──
    @GET("api/method/vehicle_maintenance.integrations.naarni_vehicles.list_fleet")
    suspend fun listFleet(
        @Query("txt") txt: String = "",
        @Query("limit") limit: Int = 300,
        @Query("offset") offset: Int = 0,
    ): FrappeWrap<Envelope<FleetResponse>>

    @GET("api/method/vehicle_maintenance.integrations.naarni_vehicles.get_vehicle_live")
    suspend fun getVehicleLive(
        @Query("vehicle") vehicle: String,
    ): FrappeWrap<Envelope<VehicleLive?>>

    // ── Job cards ──
    @GET("api/method/vehicle_maintenance.api.job_card.get_my_job_cards")
    suspend fun getMyJobCards(
        @Query("status") status: String = "",
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): FrappeWrap<Envelope<List<JobCardListItem>>>

    @GET("api/method/vehicle_maintenance.api.job_card.get_job_card_summary")
    suspend fun getJobCardSummary(
        @Query("job_card_name") name: String,
    ): FrappeWrap<Envelope<JobCardDetail>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.transition_job_card")
    suspend fun transitionJobCard(
        @Field("job_card_name") name: String,
        @Field("action") action: String,
    ): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.update_job_card")
    suspend fun updateJobCard(
        @Field("job_card_name") name: String,
        @Field("updates") updates: String,
    ): FrappeWrap<Envelope<JsonObject>>

    // ── Repair / Maintenance job lists ──
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.save_repair_items")
    suspend fun saveRepairItems(
        @Field("job_card_name") name: String,
        @Field("rows") rows: String,
    ): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.save_maintenance_items")
    suspend fun saveMaintenanceItems(
        @Field("job_card_name") name: String,
        @Field("rows") rows: String,
    ): FrappeWrap<Envelope<JsonObject>>

    @GET("api/method/vehicle_maintenance.api.job_card.list_part_groups")
    suspend fun listPartGroups(): FrappeWrap<Envelope<List<PartGroupItem>>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.save_groups_impacted")
    suspend fun saveGroupsImpacted(
        @Field("job_card_name") name: String,
        @Field("part_groups") partGroups: String,
    ): FrappeWrap<Envelope<JsonObject>>

    // ── Software update + Breakdown ──
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.save_software_components")
    suspend fun saveSoftwareComponents(
        @Field("job_card_name") name: String,
        @Field("rows") rows: String,
    ): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.update_breakdown_diagnosis")
    suspend fun updateBreakdownDiagnosis(
        @Field("job_card_name") name: String,
        @Field("updates") updates: String,
    ): FrappeWrap<Envelope<JsonObject>>

    // ── Inventory request flow ──
    @GET("api/method/vehicle_maintenance.api.job_card.list_parts")
    suspend fun listParts(
        @Query("part_group") partGroup: String = "",
        @Query("txt") txt: String = "",
        @Query("limit") limit: Int = 20,
    ): FrappeWrap<Envelope<List<PartItem>>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.create_inventory_request")
    suspend fun createInventoryRequest(
        @Field("job_card_name") name: String,
        @Field("part") part: String,
        @Field("quantity") quantity: Double,
        @Field("urgency_level") urgency: String = "Medium",
        @Field("notes") notes: String = "",
    ): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.advance_inventory_status")
    suspend fun advanceInventoryStatus(
        @Field("inventory_request_name") name: String,
        @Field("next_status") nextStatus: String,
    ): FrappeWrap<Envelope<JsonObject>>

    // ── Approval / Force close / Reopen ──
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.record_customer_approval_decision")
    suspend fun recordApprovalDecision(
        @Field("job_card_name") name: String,
        @Field("approved") approved: Int,
        @Field("rejection_feedback") rejectionFeedback: String = "",
        @Field("per_part_feedback") perPartFeedback: String = "[]",
    ): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.force_close_job_card")
    suspend fun forceCloseJobCard(
        @Field("job_card_name") name: String,
        @Field("severity") severity: String,
        @Field("reason") reason: String,
    ): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.reopen_job_card")
    suspend fun reopenJobCard(
        @Field("job_card_name") name: String,
        @Field("reason") reason: String,
    ): FrappeWrap<Envelope<JsonObject>>

    // ── Closure report (PDF proof of service) ──
    @GET("api/method/vehicle_maintenance.api.reports.share_closure_report")
    suspend fun shareClosureReport(
        @Query("job_card_name") name: String,
    ): FrappeWrap<Envelope<ShareReport>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.reports.email_closure_report")
    suspend fun emailClosureReport(
        @Field("job_card_name") name: String,
        @Field("recipient") recipient: String = "",
    ): FrappeWrap<Envelope<JsonObject>>

    // ── Customer feedback ──
    @GET("api/method/vehicle_maintenance.api.job_card.get_customer_feedback")
    suspend fun getCustomerFeedback(
        @Query("job_card_name") name: String,
    ): FrappeWrap<Envelope<CustomerFeedbackData?>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.submit_customer_feedback")
    suspend fun submitCustomerFeedback(
        @Field("job_card_name") name: String,
        @Field("rating") rating: Int,
        @Field("comments") comments: String = "",
        @Field("nps_score") npsScore: Int = 0,
        @Field("would_recommend") wouldRecommend: String = "",
    ): FrappeWrap<Envelope<JsonObject>>

    // ── Notifications ──
    @GET("api/method/vehicle_maintenance.api.notifications.get_my_notifications")
    suspend fun getMyNotifications(
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): FrappeWrap<Envelope<List<NotificationItem>>>

    @GET("api/method/vehicle_maintenance.api.notifications.get_unread_count")
    suspend fun getUnreadCount(): FrappeWrap<Envelope<UnreadCount>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.notifications.mark_notification_read")
    suspend fun markNotificationRead(
        @Field("name") name: String = "",
        @Field("mark_all") markAll: Int = 0,
    ): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.notifications.register_push_token")
    suspend fun registerPushToken(
        @Field("device_token") deviceToken: String,
        @Field("platform") platform: String = "android",
    ): FrappeWrap<Envelope<JsonObject>>

    // ── Create ──
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.create_job_card_with_inspection")
    suspend fun createJobCard(
        @Field("vehicle_number") vehicleNumber: String,
        @Field("odometer_reading") odometer: Int,
        @Field("service_type") serviceType: String,
        @Field("job_card_type") jobCardType: String,
        @Field("complaint_description") complaint: String = "",
        @Field("technician_notes") technicianNotes: String = "",
        @Field("inspection_sheet_id") inspectionSheetId: String = "",
        @Field("inspection_results") inspectionResults: String = "{}",
        @Field("inspection_poc") inspectionPoc: String = "",
        @Field("subsystems") subsystems: String = "[]",
        @Field("depot") depot: String = "",
        @Field("customer") customer: String = "",
    ): FrappeWrap<Envelope<CreatedJobCard>>

    @GET("api/method/vehicle_maintenance.api.job_card.get_inspection_sheet")
    suspend fun getInspectionSheet(
        @Query("odometer") odometer: Int,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.InspectionSheet>>

    @GET("api/method/vehicle_maintenance.api.job_card.list_users_by_role")
    suspend fun listUsersByRole(
        @Query("role") role: String,
        @Query("txt") txt: String = "",
        @Query("limit") limit: Int = 50,
    ): FrappeWrap<Envelope<List<com.naarni.service.data.dto.RoleUser>>>

    @GET("api/method/vehicle_maintenance.api.job_card.list_subsystems")
    suspend fun listSubsystems(): FrappeWrap<Envelope<List<com.naarni.service.data.dto.SubsystemItem>>>

    // ── Bus image gallery (Vehicle + Job Card, multi-angle) ──
    @GET("api/method/vehicle_maintenance.api.images.get_bus_images")
    suspend fun getBusImages(
        @Query("parent_doctype") parentDoctype: String,
        @Query("parent_name") parentName: String,
    ): FrappeWrap<Envelope<List<BusImage>>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.images.upload_bus_image")
    suspend fun uploadBusImage(
        @Field("parent_doctype") parentDoctype: String,
        @Field("parent_name") parentName: String,
        @Field("angle") angle: String,
        @Field("file_url") fileUrl: String,
        @Field("is_primary") isPrimary: Int = 0,
    ): FrappeWrap<Envelope<List<BusImage>>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.images.delete_bus_image")
    suspend fun deleteBusImage(
        @Field("parent_doctype") parentDoctype: String,
        @Field("parent_name") parentName: String,
        @Field("angle") angle: String,
    ): FrappeWrap<Envelope<List<BusImage>>>

    // ── File upload (Frappe built-in; attaches to the Job Card) ──
    @Multipart
    @POST("api/method/upload_file")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Part("doctype") doctype: RequestBody,
        @Part("docname") docname: RequestBody,
        @Part("is_private") isPrivate: RequestBody,
    ): FrappeWrap<FileUploadData>

    // ── Tickets + SE-scoped alerts (Phase-0) ──
    @GET("api/method/vehicle_maintenance.api.tickets.get_my_tickets")
    suspend fun getMyTickets(
        @Query("status") status: String = "",
        @Query("limit") limit: Int = 50,
    ): FrappeWrap<Envelope<List<TicketItem>>>

    @GET("api/method/vehicle_maintenance.api.tickets.get_my_alert_events")
    suspend fun getMyAlertEvents(
        @Query("severity") severity: String = "",
        @Query("status") status: String = "",
        @Query("limit") limit: Int = 50,
    ): FrappeWrap<Envelope<List<AlertEventItem>>>

    /** Modernized Alerts feed — grouped by (bus, issue). */
    @GET("api/method/vehicle_maintenance.api.tickets.get_my_alert_groups")
    suspend fun getMyAlertGroups(
        @Query("search") search: String = "",
        @Query("severity") severity: String = "",
        @Query("status") status: String = "",
        @Query("sort") sort: String = "latest",
        @Query("limit") limit: Int = 50,
    ): FrappeWrap<Envelope<List<com.naarni.service.data.dto.AlertGroup>>>

    /** Group detail: latest reading + occurrences + ticket episodes. */
    @GET("api/method/vehicle_maintenance.api.tickets.get_alert_group")
    suspend fun getAlertGroup(
        @Query("dedup_key") dedupKey: String? = null,
        @Query("alert_event") alertEvent: String? = null,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.AlertGroupDetail>>

    /** Ranked canned resolutions for the quick-response chips. */
    @GET("api/method/vehicle_maintenance.api.tickets.get_alert_responses")
    suspend fun getAlertResponses(
        @Query("alert_type") alertType: String = "",
    ): FrappeWrap<Envelope<List<com.naarni.service.data.dto.QuickResponse>>>

    /** Full alert detail (+ linked ticket) for the detailed Alert page. */
    @GET("api/method/vehicle_maintenance.api.tickets.get_alert_event")
    suspend fun getAlertEvent(@Query("name") name: String): FrappeWrap<Envelope<com.naarni.service.data.dto.AlertDetail>>

    /** Full Service Ticket detail for the ticket page behind an alert. */
    @GET("api/method/vehicle_maintenance.api.tickets.get_ticket")
    suspend fun getTicket(@Query("name") name: String): FrappeWrap<Envelope<com.naarni.service.data.dto.TicketDetail>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.tickets.acknowledge_ticket")
    suspend fun acknowledgeTicket(@Field("name") name: String): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.tickets.resolve_ticket")
    suspend fun resolveTicket(
        @Field("name") name: String,
        @Field("response") response: String = "",
        @Field("reason") reason: String = "",
    ): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.tickets.create_job_card_from_ticket")
    suspend fun createJobCardFromTicket(
        @Field("name") name: String,
        @Field("job_card_type") jobCardType: String = "Breakdown",
    ): FrappeWrap<Envelope<JsonObject>>

    // ---------------------------------------------------------------- process engine
    //
    // Generic across every process. Nothing here mentions batteries: the app
    // renders whatever definition the server sends, so publishing a new process
    // needs no app release.

    /** Processes this user's roles allow, for the process list. */
    @GET("api/method/vehicle_maintenance.api.process.list_processes")
    suspend fun listProcesses(): FrappeWrap<Envelope<List<com.naarni.service.data.dto.ProcessSummary>>>

    /**
     * The full definition in one round trip — stages, steps, options, conditions
     * and entity types. `appCapability` tells the server which step types this
     * build can render; anything newer comes back flagged unsupported rather
     * than crashing the runner.
     */
    @GET("api/method/vehicle_maintenance.api.process.get_definition")
    suspend fun getProcessDefinition(
        @Query("process") process: String,
        @Query("app_capability") appCapability: Int = 1,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.ProcessDefinition>>

    /** Idempotent on [clientUuid] — a retry over a dead Wi-Fi zone resumes, never duplicates. */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.process.start_run")
    suspend fun startProcessRun(
        @Field("process") process: String,
        @Field("identifier") identifier: String? = null,
        @Field("subject_name") subjectName: String? = null,
        @Field("client_uuid") clientUuid: String? = null,
        @Field("station") station: String? = null,
        @Field("shift") shift: String? = null,
        @Field("latitude") latitude: Double? = null,
        @Field("longitude") longitude: Double? = null,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.ProcessRun>>

    @GET("api/method/vehicle_maintenance.api.process.get_run")
    suspend fun getProcessRun(
        @Query("name") name: String,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.ProcessRun>>

    /** Saves one answer. The server judges it and fires its actions — the client never decides pass/fail. */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.process.save_step_result")
    suspend fun saveStepResult(
        @Field("run") run: String,
        @Field("step_code") stepCode: String,
        @Field("response") response: String? = null,
        @Field("value") value: String? = null,
        @Field("remark") remark: String? = null,
        @Field("skipped") skipped: Int = 0,
        @Field("skip_reason") skipReason: String? = null,
        @Field("seconds_spent") secondsSpent: Int = 0,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.SaveResultResponse>>

    /** Records a scan. A payload no pattern matches is still stored, never rejected. */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.process.record_scan")
    suspend fun recordProcessScan(
        @Field("run") run: String,
        @Field("entity_type") entityType: String,
        @Field("payload") payload: String,
        @Field("step_code") stepCode: String? = null,
        @Field("position_index") positionIndex: Int = 0,
        @Field("is_manual_entry") isManualEntry: Int = 0,
        @Field("latitude") latitude: Double? = null,
        @Field("longitude") longitude: Double? = null,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.RecordScanResponse>>

    /** Maps an uploaded photo to a step, with the capture metadata burnt into it. */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.process.attach_photo")
    suspend fun attachProcessPhoto(
        @Field("run") run: String,
        @Field("step_code") stepCode: String,
        @Field("file_url") fileUrl: String,
        @Field("captured_at") capturedAt: String? = null,
        @Field("latitude") latitude: Double? = null,
        @Field("longitude") longitude: Double? = null,
        @Field("accuracy_m") accuracyM: Double? = null,
        @Field("location_source") locationSource: String = "Unavailable",
        @Field("is_stamped") isStamped: Int = 1,
    ): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.process.submit_stage")
    suspend fun submitProcessStage(
        @Field("run") run: String,
        @Field("stage") stage: String,
        @Field("remarks") remarks: String? = null,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.ProcessRun>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.process.verify_stage")
    suspend fun verifyProcessStage(
        @Field("run") run: String,
        @Field("stage") stage: String,
        @Field("decision") decision: String,
        @Field("remarks") remarks: String? = null,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.ProcessRun>>

    /** Runs this user started and has not finished — the resume list. */
    @GET("api/method/vehicle_maintenance.api.process.my_open_runs")
    suspend fun myOpenProcessRuns(): FrappeWrap<Envelope<List<com.naarni.service.data.dto.OpenRun>>>

    /**
     * This operator's own inspection record — counts, plus a page of runs.
     *
     * Scoped server-side to the calling user, so there is no id to pass and no
     * way to ask for anyone else's work.
     */
    @GET("api/method/vehicle_maintenance.api.process.my_history")
    suspend fun myProcessHistory(
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
        @Query("scope") scope: String = "finished",
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.ProcessHistory>>

    /** One run as it was filled in, with the photos grouped onto their steps. */
    @GET("api/method/vehicle_maintenance.api.process.run_report")
    suspend fun processRunReport(
        @Query("name") name: String,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.RunReport>>

    // ══════════════════════════════════════════════════════════════════ Chat

    @GET("api/method/vehicle_maintenance.api.chat.list_rooms")
    suspend fun chatRooms(): FrappeWrap<Envelope<RoomsPayload>>

    @GET("api/method/vehicle_maintenance.api.chat.list_messages")
    suspend fun chatMessages(
        @Query("room") room: String,
        @Query("before_seq") beforeSeq: Long? = null,
        @Query("limit") limit: Int = 50,
    ): FrappeWrap<Envelope<MessagesPayload>>

    /**
     * The correctness path. `cursors` is a JSON object of {room: highest_seq}.
     * Run on every reconnect, foreground and push — a dropped socket event is
     * then not a special case, just a cursor that is behind.
     */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.sync")
    suspend fun chatSync(@Field("cursors") cursors: String): FrappeWrap<Envelope<SyncPayload>>

    /** Idempotent on `client_id` — a retry after a lost response is safe. */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.send_message")
    suspend fun chatSend(
        @Field("room") room: String,
        @Field("client_id") clientId: String,
        @Field("body") body: String,
        @Field("kind") kind: String = "text",
        @Field("reply_to") replyTo: String? = null,
        @Field("lat") lat: Double? = null,
        @Field("lon") lon: Double? = null,
        @Field("vehicle") vehicle: String? = null,
        @Field("ticket") ticket: String? = null,
        /** JSON array of user ids named with `@`. The server drops non-members. */
        @Field("mentions") mentions: String? = null,
    ): FrappeWrap<Envelope<SendPayload>>

    /**
     * Acknowledge that this device has stored [seq] — the sender's second tick.
     *
     * Separate from [chatMarkRead] on purpose: receiving is not reading, and a
     * message that lands while the app is on another screen is delivered and
     * unread.
     */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.mark_delivered")
    suspend fun chatMarkDelivered(
        @Field("room") room: String,
        @Field("seq") seq: Long,
    ): FrappeWrap<Envelope<JsonObject>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.mark_read")
    suspend fun chatMarkRead(
        @Field("room") room: String,
        @Field("seq") seq: Long,
    ): FrappeWrap<Envelope<MarkReadPayload>>

    /**
     * Announce that this device is composing in [room], or has stopped.
     *
     * Nothing is persisted server-side; the call exists only to fan a realtime
     * event out to whoever has the thread open.
     */
    /**
     * Put a reaction on a message, or take it off — one call for both.
     *
     * `reaction` is a server-defined code (`like`, `love`, …), never the emoji
     * itself: MariaDB's collation treats every emoji as equal to every other,
     * so the glyph cannot be an identity.
     */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.toggle_reaction")
    suspend fun chatToggleReaction(
        @Field("message") message: String,
        @Field("reaction") reaction: String,
    ): FrappeWrap<Envelope<ReactionsPayload>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.set_typing")
    suspend fun chatSetTyping(
        @Field("room") room: String,
        @Field("typing") typing: Int,
    ): FrappeWrap<Envelope<TypingPayload>>

    /**
     * Report this device as online and collect who else is.
     *
     * Both halves in one call because they run on the same schedule — a client
     * asking for fresh presence is itself proof of presence.
     */
    @POST("api/method/vehicle_maintenance.api.chat.heartbeat")
    suspend fun chatHeartbeat(): FrappeWrap<Envelope<PresencePayload>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.set_muted")
    suspend fun chatSetMuted(
        @Field("room") room: String,
        @Field("muted") muted: Int,
    ): FrappeWrap<Envelope<JsonObject>>

    /** Staff directory search for starting a direct chat. */
    @GET("api/method/vehicle_maintenance.api.chat.search_users")
    suspend fun chatSearchUsers(
        @Query("query") query: String = "",
        @Query("limit") limit: Int = 25,
    ): FrappeWrap<Envelope<UsersPayload>>

    /** Idempotent: returns the existing one-to-one thread if there is one. */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.get_or_create_direct")
    suspend fun chatGetOrCreateDirect(
        @Field("user") user: String,
    ): FrappeWrap<Envelope<DirectRoomPayload>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.create_room")
    suspend fun chatCreateRoom(
        @Field("title") title: String,
        @Field("kind") kind: String = "Group",
        @Field("members") members: String = "[]",
        @Field("depot") depot: String? = null,
        @Field("vehicle") vehicle: String? = null,
        @Field("ticket") ticket: String? = null,
        @Field("job_card") jobCard: String? = null,
    ): FrappeWrap<Envelope<CreateRoomPayload>>

    /** Room members only — an `@` must name someone who can actually see the thread. */
    @GET("api/method/vehicle_maintenance.api.chat.list_members")
    suspend fun chatRoomMembers(
        @Query("room") room: String,
        @Query("query") query: String = "",
        @Query("limit") limit: Int = 30,
    ): FrappeWrap<Envelope<UsersPayload>>

    @GET("api/method/vehicle_maintenance.api.chat.search_tickets")
    suspend fun chatSearchTickets(
        @Query("query") query: String = "",
        @Query("limit") limit: Int = 20,
    ): FrappeWrap<Envelope<TicketsPayload>>

    /** Idempotent on `client_id`, like every other send. */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.share_ticket")
    suspend fun chatShareTicket(
        @Field("room") room: String,
        @Field("ticket") ticket: String,
        @Field("client_id") clientId: String,
        @Field("note") note: String = "",
    ): FrappeWrap<Envelope<SendPayload>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat.assign_ticket")
    suspend fun chatAssignTicket(
        @Field("ticket") ticket: String,
        @Field("user") user: String,
        @Field("room") room: String? = null,
    ): FrappeWrap<Envelope<JsonObject>>

    // ── Resumable chunked upload (core upload_file buffers whole files in RAM) ──

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat_upload.begin_upload")
    suspend fun chatBeginUpload(
        @Field("room") room: String,
        @Field("file_name") fileName: String,
        @Field("total_size") totalSize: Long,
        @Field("content_type") contentType: String,
        @Field("sha256") sha256: String? = null,
    ): FrappeWrap<Envelope<BeginUploadPayload>>

    @Multipart
    @POST("api/method/vehicle_maintenance.api.chat_upload.upload_chunk")
    suspend fun chatUploadChunk(
        @Part("upload_id") uploadId: RequestBody,
        @Part("offset") offset: RequestBody,
        @Part chunk: MultipartBody.Part,
    ): FrappeWrap<Envelope<ChunkPayload>>

    /** Authoritative resume point — trusted over local bookkeeping after death. */
    @GET("api/method/vehicle_maintenance.api.chat_upload.chunk_status")
    suspend fun chatChunkStatus(
        @Query("upload_id") uploadId: String,
    ): FrappeWrap<Envelope<ChunkStatusPayload>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.chat_upload.commit_upload")
    suspend fun chatCommitUpload(
        @Field("upload_id") uploadId: String,
        @Field("client_id") clientId: String,
        @Field("body") body: String = "",
        @Field("reply_to") replyTo: String? = null,
        @Field("duration_ms") durationMs: Long? = null,
        @Field("lat") lat: Double? = null,
        @Field("lon") lon: Double? = null,
    ): FrappeWrap<Envelope<CommitPayload>>

    // ------------------------------------------------------------------ duty roster

    /**
     * Everything the duty card needs, in one call. `next_action` decides whether
     * the button says Check In, Check Out, or nothing at all — the app never
     * works that out for itself.
     */
    @GET("api/method/vehicle_maintenance.api.roster.get_my_duty")
    suspend fun getMyDuty(
        @Query("on_date") onDate: String? = null,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.DutyState>>

    /**
     * Check in or out. Coordinates are optional by design: no GPS fix is recorded
     * and flagged, never refused, because an engineer blocked at the gate just
     * starts work without a record. [clientUuid] makes a retry after a timeout
     * resolve to the same punch instead of a duplicate.
     */
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.roster.punch")
    suspend fun dutyPunch(
        @Field("punch_type") punchType: String,
        @Field("latitude") latitude: Double? = null,
        @Field("longitude") longitude: Double? = null,
        @Field("accuracy_m") accuracyM: Double? = null,
        @Field("device_uuid") deviceUuid: String? = null,
        @Field("client_uuid") clientUuid: String? = null,
        @Field("note") note: String? = null,
        @Field("photo") photo: String? = null,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.PunchResult>>

    /** The engineer's own published duty days — defaults to the coming fortnight. */
    @GET("api/method/vehicle_maintenance.api.roster.get_my_roster")
    suspend fun getMyRoster(
        @Query("from_date") fromDate: String? = null,
        @Query("to_date") toDate: String? = null,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.MyRoster>>

    /** The engineer's own attendance history — defaults to the last 30 days. */
    @GET("api/method/vehicle_maintenance.api.roster.get_my_attendance")
    suspend fun getMyAttendance(
        @Query("from_date") fromDate: String? = null,
        @Query("to_date") toDate: String? = null,
    ): FrappeWrap<Envelope<com.naarni.service.data.dto.MyAttendance>>
}
