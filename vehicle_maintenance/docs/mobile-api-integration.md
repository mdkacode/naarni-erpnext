# Mobile API Integration Guide — Kotlin / Android

> For the native Android app (Kotlin + Retrofit + Moshi + Coroutines) consuming the Vehicle Maintenance backend deployed at `https://service.naarni.com`.

This guide is the single source of truth for every wire-level contract the mobile app depends on. It tracks the real deployed state of `vehicle_maintenance.api.*` on `service.naarni.com`, not aspirational endpoints — any `NEW` badge means the backend change is still pending.

---

## Table of Contents

1. [Environments](#1-environments)
2. [Authentication](#2-authentication)
3. [Retrofit + OkHttp Setup](#3-retrofit--okhttp-setup)
4. [Request / Response Envelope](#4-request--response-envelope)
5. [Error Handling](#5-error-handling)
6. [Endpoint Reference](#6-endpoint-reference)
7. [Workflow Transitions](#7-workflow-transitions)
8. [File Uploads (Photos)](#8-file-uploads-photos)
9. [Pagination](#9-pagination)
10. [Realtime (Socket.IO)](#10-realtime-socketio)
11. [Push Notifications (FCM)](#11-push-notifications-fcm)
12. [Offline & Sync](#12-offline--sync)
13. [Security Checklist](#13-security-checklist)

---

## 1. Environments

| Env | Base URL | Notes |
|---|---|---|
| Production | `https://service.naarni.com` | DNS pending; once live, use this. HTTPS via Let's Encrypt |
| Staging/interim | `http://20.219.136.19` | Works today with `Host: service.naarni.com` header; swap for production once TLS cert issued |
| Dev (local bench) | `http://<bench-host>:8000` | Pass `X-Frappe-Site-Name: <site>` header |

Put these in `BuildConfig`:

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        debug { buildConfigField("String", "API_BASE", "\"http://10.0.2.2:8000\"") }
        release { buildConfigField("String", "API_BASE", "\"https://service.naarni.com\"") }
    }
}
```

---

## 2. Authentication

The site keys users by phone number. Two auth flows are supported, picked based on device/session needs.

### 2.1 Phone + password login (interactive)

**Endpoint** (already deployed):

```
POST /api/method/vehicle_maintenance.api.auth.login_with_phone
Content-Type: application/x-www-form-urlencoded

phone=9876543210&password=YourPass123!
```

Phone accepts `9876543210`, `+919876543210`, `+91 98765 43210`, `98765-43210`. Server normalizes to the last 10 digits.

**Success (200):**

```json
{
  "message": {
    "success": true,
    "data": {
      "user": "mayank.dwivedi@naarni.com",
      "full_name": "Mayank Dwivedi",
      "user_type": "System User",
      "roles": ["System Manager", "Service Engineer", "All", "Guest", "Desk User"]
    },
    "message": "Logged in."
  },
  "home_page": "/app/home",
  "full_name": "Mayank Dwivedi"
}
```

Two session cookies land on `Set-Cookie`: `sid` and `system_user`. Persist them in `CookieJar`.

**Errors:**

- `401 AuthenticationError` — `Invalid login credentials` (wrong password)
- `401 AuthenticationError` — `No account found for that phone number.`
- `417 ValidationError` — `Phone number must be at least 10 digits.`

### 2.2 API Key + Secret (recommended for mobile, **NEW** endpoint pending)

Session cookies are fine for web but awkward on mobile: CSRF token for every POST, renewal on idle expiry, interference with multi-user test devices. The app should:

1. Call `login_with_phone` once at onboarding to verify credentials
2. Immediately call **`vehicle_maintenance.api.auth.issue_api_key`** (**NEW — pending backend change**) to receive `{api_key, api_secret}`
3. Store the pair in EncryptedSharedPreferences (Keystore-backed)
4. On every subsequent request, attach:

```
Authorization: token <api_key>:<api_secret>
```

No cookies, no CSRF required.

> **Until `issue_api_key` ships:** use Desk UI (Administrator → User → open a user → API Access → Generate Keys) and hand the pair to the dev via a secure channel. This is acceptable for internal dogfood builds. Production launch requires the endpoint.

### 2.3 CSRF (only if you stick with cookie auth)

```
GET /api/method/vehicle_maintenance.api.auth.get_csrf_token
Cookie: sid=...; system_user=...
```

Response: `{ "message": "<token>" }`. Attach as `X-Frappe-CSRF-Token` header on every non-GET after login.

### 2.4 Logout

```
POST /api/method/logout
Cookie: sid=...
X-Frappe-CSRF-Token: <token>
```

Also wipes `api_key`/`api_secret` if your session was token-auth — call **`vehicle_maintenance.api.auth.revoke_api_key`** (**NEW — pending**) explicitly before clearing local storage.

---

## 3. Retrofit + OkHttp Setup

Hilt modules for DI, Moshi for JSON, coroutine adapters. This is the wiring the Android app uses.

### 3.1 Dependencies (Gradle KTS, Version Catalog)

```toml
# gradle/libs.versions.toml
[versions]
retrofit = "2.11.0"
okhttp = "4.12.0"
moshi = "1.15.1"
hilt = "2.52"

[libraries]
retrofit = { module = "com.squareup.retrofit2:retrofit", version.ref = "retrofit" }
retrofit-moshi = { module = "com.squareup.retrofit2:converter-moshi", version.ref = "retrofit" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-logging = { module = "com.squareup.okhttp3:logging-interceptor", version.ref = "okhttp" }
moshi = { module = "com.squareup.moshi:moshi", version.ref = "moshi" }
moshi-kotlin = { module = "com.squareup.moshi:moshi-kotlin", version.ref = "moshi" }
moshi-codegen = { module = "com.squareup.moshi:moshi-kotlin-codegen", version.ref = "moshi" }
```

### 3.2 Auth interceptor (token mode)

```kotlin
// core/network/AuthInterceptor.kt
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenStore: TokenStore,          // reads api_key:api_secret from EncryptedSharedPreferences
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenStore.currentToken()      // null before login
        val request = chain.request().newBuilder().apply {
            if (token != null) {
                header("Authorization", "token ${token.key}:${token.secret}")
            }
            header("Accept", "application/json")
            header("X-Frappe-Site-Name", "service.naarni.com")
        }.build()
        return chain.proceed(request)
    }
}
```

### 3.3 NetworkModule (Hilt)

```kotlin
// di/NetworkModule.kt
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides @Singleton
    fun provideOkHttp(
        auth: AuthInterceptor,
        logger: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(auth)
        .addInterceptor(logger)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun provideRetrofit(okHttp: OkHttpClient, moshi: Moshi): Retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE)
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    @Provides @Singleton
    fun provideLogger(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        }
}
```

### 3.4 Retrofit interface — one per Frappe module

```kotlin
// data/api/AuthApi.kt
interface AuthApi {
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.auth.login_with_phone")
    suspend fun loginWithPhone(
        @Field("phone") phone: String,
        @Field("password") password: String,
    ): FrappeEnvelope<LoginData>

    @POST("api/method/vehicle_maintenance.api.auth.issue_api_key")
    suspend fun issueApiKey(): FrappeEnvelope<ApiKeyData>    // NEW — pending

    @POST("api/method/vehicle_maintenance.api.auth.revoke_api_key")
    suspend fun revokeApiKey(): FrappeEnvelope<Unit>          // NEW — pending

    @GET("api/method/frappe.auth.get_logged_user")
    suspend fun getLoggedUser(): FrappeSingleMessage<String>
}

// data/api/JobCardApi.kt
interface JobCardApi {
    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.get_my_job_cards")
    suspend fun myJobCards(
        @Field("status") status: String? = null,
        @Field("limit") limit: Int = 20,
        @Field("offset") offset: Int = 0,
    ): FrappeEnvelope<List<JobCardSummary>>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.get_job_card_summary")
    suspend fun summary(@Field("job_card_name") name: String): FrappeEnvelope<JobCardSummary>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.transition_job_card")
    suspend fun transition(
        @Field("job_card_name") name: String,
        @Field("action") action: String,
    ): FrappeEnvelope<TransitionResult>

    @FormUrlEncoded
    @POST("api/method/vehicle_maintenance.api.job_card.search_vehicles")
    suspend fun searchVehicles(@Field("query") query: String): FrappeEnvelope<List<VehicleLite>>

    // …one method per whitelisted backend function listed in §6
}
```

### 3.5 Repository pattern

```kotlin
// data/repo/AuthRepository.kt
@Singleton
class AuthRepository @Inject constructor(
    private val api: AuthApi,
    private val tokenStore: TokenStore,
) {
    suspend fun signIn(phone: String, password: String): Result<LoginData> = runCatching {
        val login = api.loginWithPhone(phone, password).data
        // Flip to token auth immediately once issue_api_key is live
        // val keys = api.issueApiKey().data
        // tokenStore.save(ApiToken(keys.apiKey, keys.apiSecret))
        login
    }.onFailure { Timber.e(it, "signIn failed") }
}
```

---

## 4. Request / Response Envelope

### 4.1 Frappe wraps every whitelisted method's return value

```json
{
  "message": <your_function_return_value>
}
```

Our app's `@frappe.whitelist()` functions return a consistent envelope inside that:

```json
{
  "success": true,
  "data": { ... },
  "message": "Human-readable status"
}
```

So the wire response is always:

```json
{
  "message": {
    "success": true,
    "data": { ... },
    "message": "..."
  }
}
```

### 4.2 Kotlin model

```kotlin
// data/models/FrappeEnvelope.kt
@JsonClass(generateAdapter = true)
data class FrappeEnvelope<T>(
    @Json(name = "message") val payload: AppEnvelope<T>,
) {
    val data: T get() = payload.data
    val ok: Boolean get() = payload.success
    val userMessage: String? get() = payload.message
}

@JsonClass(generateAdapter = true)
data class AppEnvelope<T>(
    val success: Boolean,
    val data: T,
    val message: String? = null,
)
```

For Frappe-built-ins that return a scalar:

```kotlin
@JsonClass(generateAdapter = true)
data class FrappeSingleMessage<T>(
    @Json(name = "message") val message: T,
)
```

### 4.3 Sending complex params

Most endpoints accept JSON for list/dict params. Pass them as JSON strings in form-urlencoded fields:

```kotlin
@POST("api/method/vehicle_maintenance.api.inventory.submit_inventory_request")
@FormUrlEncoded
suspend fun submitInventoryRequest(
    @Field("job_card_name") jc: String,
    @Field("items") itemsJson: String,           // JSON array, pre-serialized
): FrappeEnvelope<InventoryRequestData>

// Usage
val items = listOf(
    InventoryItem("Oil Filter", "Part", 1, 350.0),
    InventoryItem("Brake Pad Set", "Part", 2, 800.0),
)
val json = moshi.adapter<List<InventoryItem>>(Types.newParameterizedType(List::class.java, InventoryItem::class.java))
    .toJson(items)
api.submitInventoryRequest(jc = "JC-2026-00042", itemsJson = json)
```

---

## 5. Error Handling

Frappe returns non-2xx with a JSON body containing `exc_type` and `_server_messages`. Normalize into a typed exception.

### 5.1 Exception hierarchy

```kotlin
sealed class ApiException(message: String) : Exception(message) {
    class Auth(message: String) : ApiException(message)                // 401
    class Validation(message: String) : ApiException(message)          // 417
    class Permission(message: String) : ApiException(message)          // 403
    class NotFound(message: String) : ApiException(message)            // 404
    class Conflict(message: String) : ApiException(message)            // 409
    class Server(message: String) : ApiException(message)              // 5xx
    class Network(cause: Throwable) : ApiException(cause.message ?: "Network error")
    class Unknown(message: String) : ApiException(message)
}
```

### 5.2 Error-mapping interceptor

```kotlin
// core/network/ErrorInterceptor.kt
class ErrorInterceptor @Inject constructor(private val moshi: Moshi) : Interceptor {
    private val adapter = moshi.adapter(FrappeError::class.java)

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.isSuccessful) return response

        // Read body once into memory (Retrofit will re-read via peekBody copy)
        val raw = response.peekBody(64 * 1024).string()
        val err = runCatching { adapter.fromJson(raw) }.getOrNull()
        val msg = err?.userFacingMessage() ?: "Request failed (${response.code})"

        throw when (response.code) {
            401 -> ApiException.Auth(msg)
            403 -> ApiException.Permission(msg)
            404 -> ApiException.NotFound(msg)
            409 -> ApiException.Conflict(msg)
            417 -> ApiException.Validation(msg)
            in 500..599 -> ApiException.Server(msg)
            else -> ApiException.Unknown(msg)
        }
    }
}

@JsonClass(generateAdapter = true)
data class FrappeError(
    @Json(name = "exc_type") val excType: String? = null,
    @Json(name = "exception") val exception: String? = null,
    @Json(name = "_server_messages") val serverMessagesRaw: String? = null,
    val message: String? = null,
) {
    fun userFacingMessage(): String {
        // _server_messages is a JSON-encoded array of JSON-encoded objects — double-parse
        serverMessagesRaw?.let {
            runCatching {
                val arr = JSONArray(it)
                if (arr.length() > 0) {
                    val inner = JSONObject(arr.getString(0))
                    return inner.optString("message", message ?: "Error")
                }
            }
        }
        return message ?: exception?.substringAfter(": ") ?: "Error"
    }
}
```

### 5.3 Viewmodel usage

```kotlin
class LoginViewModel @Inject constructor(
    private val repo: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state = _state.asStateFlow()

    fun login(phone: String, password: String) = viewModelScope.launch {
        _state.value = LoginState.Submitting
        repo.signIn(phone, password)
            .onSuccess { _state.value = LoginState.Success(it) }
            .onFailure { e ->
                _state.value = when (e) {
                    is ApiException.Auth -> LoginState.Error("Invalid phone or password.")
                    is ApiException.Validation -> LoginState.Error(e.message ?: "Check input.")
                    is ApiException.Network -> LoginState.Error("No internet. Try again.")
                    else -> LoginState.Error("Something went wrong.")
                }
            }
    }
}
```

---

## 6. Endpoint Reference

> Legend: ✅ deployed · 🆕 pending backend work (tracked in [`MOBILE_APP_FEATURE_DOC.md`](../MOBILE_APP_FEATURE_DOC.md#14-backend-additions-needed-for-mobile))

All paths below are relative to `{baseUrl}/api/method/`.

### 6.1 Auth & Identity

| Method | Path | Purpose | Status |
|---|---|---|---|
| POST | `vehicle_maintenance.api.auth.login_with_phone` | Sign in with phone + password | ✅ |
| GET  | `vehicle_maintenance.api.auth.get_csrf_token` | CSRF token for cookie sessions | ✅ |
| POST | `logout` | End session | ✅ |
| POST | `vehicle_maintenance.api.auth.issue_api_key` | Mint long-lived `(api_key, api_secret)` pair | 🆕 |
| POST | `vehicle_maintenance.api.auth.revoke_api_key` | Rotate out the current pair | 🆕 |
| POST | `vehicle_maintenance.api.auth.register_fcm_device` | `{token, device_id, platform}` | 🆕 |
| POST | `vehicle_maintenance.api.auth.unregister_fcm_device` | `{device_id}` | 🆕 |
| GET  | `frappe.auth.get_logged_user` | Sanity check for stored token | ✅ (Frappe built-in) |
| GET  | `vehicle_maintenance.fleet_service.doctype.job_card.job_card.get_user_roles` | Primary + additional roles | ✅ |

### 6.2 Job Card lifecycle

Base path: `vehicle_maintenance.api.job_card.*`

| Method | Function | Params | Purpose |
|---|---|---|---|
| POST | `search_vehicles` | `query` | Autocomplete vehicle by number/VIN |
| POST | `search_depots` | `query` | Autocomplete depots |
| POST | `list_users_by_role` | `role` | Staff picker (e.g., next-level engineer) |
| POST | `list_part_groups` | `query?` | Part Group autocomplete |
| POST | `list_subsystems` | `query?` | Subsystem multi-select |
| POST | `get_customer_name` | `vehicle` | Derive customer from vehicle |
| POST | `get_last_pms_info` | `vehicle` | Breakdown context banner |
| POST | `get_my_job_cards` | `status?`, `limit`, `offset` | Role-scoped paginated list |
| POST | `get_job_card_summary` | `job_card_name` | Full card, role-filtered fields |
| POST | `get_available_actions` | `job_card_name` | UI chip computation |
| POST | `transition_job_card` | `job_card_name`, `action` | Workflow step |
| POST | `update_job_card` | `job_card_name`, fields… | Partial update |
| POST | `save_repair_items` | `job_card_name`, `items` (JSON) | Repair lines |
| POST | `save_maintenance_items` | `job_card_name`, `items` (JSON) | PMS fluids/filters |
| POST | `save_software_components` | `job_card_name`, `items` (JSON) | SW Update rows |
| POST | `save_subsystems` | `job_card_name`, `subsystems` (JSON) | Affected subsystems |
| POST | `save_groups_impacted` | `job_card_name`, `groups` (JSON) | Breakdown impact chips |
| POST | `update_breakdown_diagnosis` | `job_card_name`, **kwargs | Progressive Breakdown state |
| POST | `force_close_job_card` | `job_card_name`, `severity`, `reason` | Force-close with severity matrix |
| POST | `reopen_job_card` | `job_card_name`, `reason` | Customer-initiated reopen |

### 6.3 Customer flows

| Method | Function | Purpose |
|---|---|---|
| POST | `vehicle_maintenance.api.job_card.record_customer_approval_decision` | In-person approval/rejection |
| POST | `vehicle_maintenance.api.job_card.get_approval_link_payload` | Guest call w/ token (for deep-link into customer portal) |
| POST | `vehicle_maintenance.api.job_card.submit_approval_via_link` | Guest submit w/ token |
| POST | `vehicle_maintenance.api.job_card.submit_customer_feedback` | NPS + rating + comments |
| POST | `vehicle_maintenance.api.job_card.get_customer_feedback` | Read-back for viewer |

### 6.4 Inventory

| Method | Function | Purpose |
|---|---|---|
| POST | `vehicle_maintenance.fleet_service.doctype.part.part.search_parts` | Part autocomplete |
| POST | `vehicle_maintenance.api.job_card.create_inventory_request` | Raise request from JC detail |
| POST | `vehicle_maintenance.api.job_card.advance_inventory_status` | Allocated → Issued → Received |
| POST | `vehicle_maintenance.fleet_service.doctype.inventory_request.inventory_request.list_for_job_card` | All requests for a JC |

### 6.5 Reports

| Method | Function | Purpose |
|---|---|---|
| POST | `vehicle_maintenance.api.job_card.tat_adherence_report` | Ops dashboard |
| POST | `vehicle_maintenance.api.job_card.repeated_issues_for_vehicle` | History analysis |

### 6.6 Health Card / Closures

| Method | Function | Purpose |
|---|---|---|
| POST | `vehicle_maintenance.fleet_service.doctype.vehicle_health_card.vehicle_health_card.get_for_job_card` | Render post-PMS health card |
| POST | `vehicle_maintenance.fleet_service.doctype.job_card_closure_record.job_card_closure_record.list_for_job_card` | Reopen chain history |

### 6.7 Photos & media

| Method | Function | Purpose | Status |
|---|---|---|---|
| POST | `upload_file` | Frappe native multipart upload | ✅ |
| POST | `vehicle_maintenance.api.media.register_photo_metadata` | Tamper evidence: hash, lat/lng, server time | 🆕 |
| POST | `vehicle_maintenance.api.breakdown.upload_travel_polyline` | Encoded travel polyline | 🆕 |

### 6.8 Notifications

| Method | Function | Purpose |
|---|---|---|
| POST | `frappe.client.get_list` | Query `Notification Log` by `for_user` |
| POST | `frappe.client.set_value` | Mark notification read |

---

## 7. Workflow Transitions

Transitions are gated server-side by role + current state + workflow fixture. Don't hardcode the state machine on the client; always consume `get_available_actions` before rendering action chips.

```kotlin
val actions = api.getAvailableActions(jcName).data    // List<ActionDescriptor>
actions.forEach { a ->
    Chip(
        text = a.label,
        enabled = a.enabled,
        onClick = { vm.transition(jcName, a.action) },
    )
}
```

| Current State | Action | Next State | Role Gate |
|---|---|---|---|
| Open | Start Work | WIP | SE, Technician |
| WIP | Send Estimate | Awaiting Customer Approval | SE |
| WIP | Request Parts | Awaiting Parts | Technician |
| WIP | Submit for Verification | Verification Pending | Technician |
| Awaiting Customer Approval | Customer Approves | Awaiting Parts | Customer |
| Awaiting Customer Approval | Customer Rejects | WIP | Customer |
| Awaiting Parts | Parts Received & Fitted | Parts Fitted | Technician, DM |
| Parts Fitted | Continue Work | WIP | Technician |
| Parts Fitted | Submit for Verification | Verification Pending | Technician |
| Verification Pending | Close Job Card | Closed | SE |
| Verification Pending | Verification Failed | WIP | SE |
| any non-terminal | Force Close (Minor/Major/Critical) | Closed (force_closed=1) | severity-gated role |
| Closed | Reopen | Reopened | Customer (within 48h) |

**Retrofit call:**

```kotlin
@FormUrlEncoded
@POST("api/method/vehicle_maintenance.api.job_card.transition_job_card")
suspend fun transition(
    @Field("job_card_name") name: String,
    @Field("action") action: String,
): FrappeEnvelope<TransitionResult>

@JsonClass(generateAdapter = true)
data class TransitionResult(
    @Json(name = "workflow_state") val newState: String,
    val name: String,
)
```

---

## 8. File Uploads (Photos)

Frappe's built-in upload endpoint attaches a File record to a DocType row.

### 8.1 Retrofit interface

```kotlin
interface FileApi {
    @Multipart
    @POST("api/method/upload_file")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Part("doctype") doctype: RequestBody,
        @Part("docname") docname: RequestBody,
        @Part("is_private") isPrivate: RequestBody,
        @Part("fieldname") fieldname: RequestBody? = null,
    ): FrappeSingleMessage<UploadedFile>
}

@JsonClass(generateAdapter = true)
data class UploadedFile(
    @Json(name = "file_url") val fileUrl: String,
    @Json(name = "name") val name: String,
    @Json(name = "is_private") val isPrivate: Int,
)
```

### 8.2 Kotlin uploader with watermark + metadata

```kotlin
class PhotoUploader @Inject constructor(
    private val fileApi: FileApi,
    private val mediaApi: MediaApi,          // register_photo_metadata
    private val watermarker: WatermarkRenderer,
    private val locationFix: LocationFix,
    private val serverClock: ServerClock,
) {
    suspend fun uploadPhoto(
        raw: Bitmap,
        jcName: String,
        childField: String,
        context: CaptureContext,
    ): UploadedFile = withContext(Dispatchers.IO) {
        val loc = locationFix.getFix(timeout = 5.seconds)
            ?: throw ApiException.Validation("Location required for photo.")
        val serverTime = serverClock.now()
        val watermarked = watermarker.compose(raw, loc, serverTime, context)

        val part = MultipartBody.Part.createFormData(
            name = "file",
            filename = "${jcName}-${UUID.randomUUID()}.jpg",
            body = watermarked.bytes.toRequestBody("image/jpeg".toMediaType()),
        )
        val uploaded = fileApi.uploadFile(
            file = part,
            doctype = "Job Card".toPlainTextRequestBody(),
            docname = jcName.toPlainTextRequestBody(),
            isPrivate = "1".toPlainTextRequestBody(),
            fieldname = childField.toPlainTextRequestBody(),
        ).message

        // Tamper-evidence metadata — NEW endpoint
        mediaApi.registerPhotoMetadata(
            fileUrl = uploaded.fileUrl,
            sha256 = watermarked.sha256,
            lat = loc.latitude,
            lng = loc.longitude,
            accuracy = loc.accuracy,
            capturedAtIso = serverTime.toString(),
            deviceId = DeviceId.current(),
            jcName = jcName,
            childField = childField,
        )

        uploaded
    }
}

private fun String.toPlainTextRequestBody(): RequestBody =
    toRequestBody("text/plain".toMediaType())
```

Photo capture spec (permissions, watermark layout, EXIF embedding, tamper resistance) lives in [`MOBILE_APP_FEATURE_DOC.md §7`](../MOBILE_APP_FEATURE_DOC.md#7-camera--gps-watermark-module-deep-dive).

---

## 9. Pagination

All list endpoints take `limit` (max 100) and `offset` (0-based).

```kotlin
class JobCardPagingSource(
    private val api: JobCardApi,
    private val status: String?,
) : PagingSource<Int, JobCardSummary>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, JobCardSummary> = try {
        val offset = params.key ?: 0
        val list = api.myJobCards(status = status, limit = PAGE_SIZE, offset = offset).data
        LoadResult.Page(
            data = list,
            prevKey = null,
            nextKey = if (list.size < PAGE_SIZE) null else offset + PAGE_SIZE,
        )
    } catch (e: Exception) {
        LoadResult.Error(e)
    }

    override fun getRefreshKey(state: PagingState<Int, JobCardSummary>) =
        state.anchorPosition?.let { state.closestPageToPosition(it)?.prevKey?.plus(PAGE_SIZE) }

    companion object { const val PAGE_SIZE = 20 }
}
```

---

## 10. Realtime (Socket.IO)

Frappe fires realtime events over Socket.IO on port `9000` on the VM. Use Kotlin's official client.

### 10.1 Events we emit

| Event | Payload | Roles that receive |
|---|---|---|
| `sla_breach` | `{ job_card, service_type, breached_at }` | Ops, DM, SE of that JC |
| `job_card_update` | `{ name, workflow_state, updater }` | Viewers of the JC |
| `inventory_update` | `{ request_name, status, jc_name }` | DM, SE, Tech of the JC |

### 10.2 Kotlin client

```kotlin
// core/network/RealtimeClient.kt
@Singleton
class RealtimeClient @Inject constructor(
    private val tokenStore: TokenStore,
) {
    private val socket: Socket = IO.socket(
        BuildConfig.API_BASE.replace(":8000", ":9000"),
        IO.Options().apply {
            extraHeaders = mapOf("Authorization" to listOf("token ${tokenStore.currentToken()?.bearerPair}"))
            reconnection = true
        },
    )

    val slaBreaches: Flow<SlaBreach> = callbackFlow {
        val listener = Emitter.Listener { args ->
            val payload = args.firstOrNull() as? JSONObject ?: return@Listener
            trySend(
                SlaBreach(
                    jobCard = payload.optString("job_card"),
                    serviceType = payload.optString("service_type"),
                ),
            )
        }
        socket.on("sla_breach", listener)
        socket.connect()
        awaitClose {
            socket.off("sla_breach", listener)
            socket.disconnect()
        }
    }
}
```

Usage in Viewmodel:

```kotlin
class DashboardViewModel @Inject constructor(private val rt: RealtimeClient) : ViewModel() {
    val breaches = rt.slaBreaches
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
}
```

---

## 11. Push Notifications (FCM)

Full design in [`MOBILE_APP_FEATURE_DOC.md §10`](../MOBILE_APP_FEATURE_DOC.md#10-push-notifications-fcm). Mobile contract:

1. On first app-open with granted permission → register token via `register_fcm_device` (🆕 pending).
2. Re-register on token refresh (`onNewToken`) and on login.
3. Call `unregister_fcm_device` on logout.
4. Handle `data`-only payloads — app controls channel/icon/sound.
5. Deep-link from `data.deep_link` (`naarni://jc/<name>`, `naarni://approve/<name>?token=<t>`).

```kotlin
// core/push/ServicePushService.kt
class ServicePushService : FirebaseMessagingService() {
    @Inject lateinit var repo: DeviceRepository

    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch { repo.registerDevice(token) }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val data = msg.data
        NotificationDispatcher.show(
            context = this,
            type = data["type"],
            subject = data["subject"],
            body = data["body"],
            deepLink = data["deep_link"],
            priority = data["priority"],
        )
    }
}
```

---

## 12. Offline & Sync

Full spec in [`MOBILE_APP_FEATURE_DOC.md §9`](../MOBILE_APP_FEATURE_DOC.md#9-offline-mode--sync-queue). Wire-level rules:

1. **Idempotency keys** — every mutating request carries `intent_id` (UUID) in form field; server rejects duplicates for 48h. *(Server-side support is part of the NEW auth/metadata work.)*
2. **Photo uploads** are idempotent by `sha256` — `register_photo_metadata` returns `{existed: true}` for duplicates.
3. **State transitions** queued offline → flushed in order via WorkManager; conflicts surface as 409 `ApiException.Conflict` and are routed to the Sync Conflicts screen.
4. **Cache staleness** — every Room row carries `lastSyncedAt`. Viewmodel triggers re-fetch if older than TTL (5 min for JC summary, 24h for masters).

---

## 13. Security Checklist

- [ ] Pin TLS cert (`CertificatePinner`) before Play Store release
- [ ] Store `api_key:api_secret` in EncryptedSharedPreferences (Keystore-backed)
- [ ] Clear token + Room on logout
- [ ] Clear clipboard of any sensitive tokens after 30s
- [ ] Disable WebView for any content we render (no XSS surface)
- [ ] `android:allowBackup="false"` on release flavor
- [ ] ProGuard rules for Moshi / Retrofit / Hilt (templates in `proguard-rules.pro`)
- [ ] `android:usesCleartextTraffic="false"` — HTTPS-only
- [ ] Verify every whitelisted method has a `frappe.only_for()` or `frappe.has_permission()` check (audit list in [`CLAUDE.md §6`](../../.claude/CLAUDE.md#6-api-layer-design))

---

_Last updated: 2026-04-24 · Maintained by the mobile + backend teams. Any change to a function signature on the server must update §6 in the same PR._
