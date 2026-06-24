package com.naarni.service.core.network

import com.naarni.service.data.dto.AlertEventItem
import com.naarni.service.data.dto.CreatedJobCard
import com.naarni.service.data.dto.FileUploadData
import com.naarni.service.data.dto.FleetResponse
import com.naarni.service.data.dto.FormContext
import com.naarni.service.data.dto.TicketItem
import com.naarni.service.data.dto.JobCardListItem
import com.naarni.service.data.dto.LoginData
import com.naarni.service.data.dto.NotificationItem
import com.naarni.service.data.dto.OtpStatus
import com.naarni.service.data.dto.SuggestionItem
import com.naarni.service.data.dto.UnreadCount
import com.naarni.service.data.dto.VehicleHit
import com.naarni.service.data.dto.VehicleLive
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

    // ── Notifications ──
    @GET("api/method/vehicle_maintenance.api.notifications.get_my_notifications")
    suspend fun getMyNotifications(
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): FrappeWrap<Envelope<List<NotificationItem>>>

    @GET("api/method/vehicle_maintenance.api.notifications.get_unread_count")
    suspend fun getUnreadCount(): FrappeWrap<Envelope<UnreadCount>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.notifications.register_push_token")
    suspend fun registerPushToken(
        @Field("device_token") deviceToken: String,
        @Field("platform") platform: String = "android",
    ): FrappeWrap<Envelope<Map<String, String>>>

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
    ): FrappeWrap<Envelope<CreatedJobCard>>

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

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.tickets.acknowledge_ticket")
    suspend fun acknowledgeTicket(@Field("name") name: String): FrappeWrap<Envelope<Map<String, String>>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.tickets.resolve_ticket")
    suspend fun resolveTicket(
        @Field("name") name: String,
        @Field("reason") reason: String = "",
    ): FrappeWrap<Envelope<Map<String, String>>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.tickets.create_job_card_from_ticket")
    suspend fun createJobCardFromTicket(
        @Field("name") name: String,
        @Field("job_card_type") jobCardType: String = "Breakdown",
    ): FrappeWrap<Envelope<Map<String, String>>>
}
