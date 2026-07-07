package com.naarni.service.core.network

import com.naarni.service.data.dto.AlertEventItem
import com.naarni.service.data.dto.BusImage
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
}
