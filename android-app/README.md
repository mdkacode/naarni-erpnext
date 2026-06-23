# Naarni Service — Android app (Kotlin)

Native Android app for **Service Engineers**, talking to the deployed Frappe
backend at `https://service.naarni.com`. Built per [`../KOTLIN_APP_PLAN.md`](../KOTLIN_APP_PLAN.md):
fast, low-end-friendly, **auto-fill + searchable dropdowns everywhere**, with
**geo/time/user-stamped photos**.

## Status: Phase-1 foundation (this scaffold)

Implemented and wired to the **live backend**:
- **Auth** — phone login (`login_with_phone`), session cookie persisted in
  EncryptedSharedPreferences (`core/auth`, `core/network/Network.kt`).
- **Networking** — Retrofit + OkHttp + kotlinx-serialization; unwraps Frappe's
  `{"message": {success, data}}` envelope (`core/network`).
- **Auto-fill create flow** — `CreateJobCardScreen` calls
  `get_job_card_form_context` after vehicle pick; everything else is pre-filled
  and read-only (`ui/screens/CreateJobCardScreen.kt`).
- **SmartSelect** — one searchable bottom-sheet dropdown, opens with suggestions
  before typing, debounced server search (`ui/components/SmartSelect.kt`). Backed
  by `search_vehicles` / `list_complaints` / `list_fault_codes` /
  `list_observation_templates`.
- **Stamped camera** — CameraX capture that burns **date-time + lat/long (+accuracy)
  + the capturing user's name** into the bottom-left of every photo
  (`core/camera/PhotoStamper.kt`, `ui/components/StampingCamera.kt`). Location is a
  soft gate (stamps "Location unavailable" if denied).
- **Shell** — bottom-nav: Home · Jobs · Alerts · Tickets · Profile
  (`ui/navigation/MainShell.kt`). Job Cards list loads `get_my_job_cards`.
- **Deeplinks** declared: `naarni://ticket|jobcard|alert/{id}` (manifest).

Stubbed / pending (need backend or later phases):
- **Alerts** & **Tickets** tabs are informative placeholders — they activate when
  the **Phase-0 backend gate** ships `get_my_alert_events`, the **Service Ticket**
  DocType + `get_my_tickets`, and `ingest_alert_event` ticket automation
  (KOTLIN_APP_PLAN §9).
- **Create submit** posts nothing yet (`onDone` is a TODO) — next step wires
  `create_job_card_with_inspection` + photo upload (`upload_file`).
- **Push (FCM)** — `register_push_token` endpoint is wired in the API layer;
  Firebase Messaging service + token registration is a follow-up.
- **Offline queue** (Room + WorkManager) — Phase-1.5.

## Enabling Push (FCM) + Deeplinks — requires your Firebase config

Push is wired on the backend (`register_push_token` + FCM dispatch) and the app
already calls `registerPushToken`. To turn it on in the app you must add Firebase
(can't be committed blind — the google-services plugin fails without the file):

1. Create a Firebase project, add an Android app with package `com.naarni.service`.
2. Download `google-services.json` → `android-app/app/google-services.json`.
3. Add to `gradle/libs.versions.toml` + `build.gradle.kts`: the
   `com.google.gms.google-services` plugin and `firebase-messaging` (BoM).
4. Add a `FirebaseMessagingService` that:
   - on new token → calls `appContainer.jobCardRepo.registerPushToken(token)`,
   - on message → builds a notification whose tap `PendingIntent` carries a
     `naarni://{type}/{id}` deeplink (the manifest already registers the scheme).
5. On the backend, set the FCM server key:
   `bench --site service.naarni.com set-config notifications_fcm_server_key "<KEY>"`
   and `set-config notifications_push_enabled 1`.

Deeplinks (`naarni://ticket|jobcard|alert/{id}`) are declared in the manifest; the
in-app routing for them lands with the Tickets/Alerts screens (Phase-0 backend).

## Build & run

Requires Android Studio (Koala+) / Android SDK — there is no SDK in this repo, so
the project is not built here.

```bash
# In Android Studio: Open  android-app/  → let it sync → Run on a device/emulator.
# Or CLI (after generating the wrapper once):
cd android-app
gradle wrapper          # creates gradlew + wrapper jar (not committed)
./gradlew :app:assembleDebug
```

Create a `local.properties` with `sdk.dir=/path/to/Android/sdk` (Android Studio
does this automatically).

## Architecture

MVVM + manual DI (`App.kt` → `AppContainer`), single-Activity Compose. See
`KOTLIN_APP_PLAN.md §2` for the full module map and `§3` for the performance budget
(APK < 12 MB, cold start < 1.5s on Android 7 / 2 GB RAM).

```
core/network   Retrofit + Frappe envelope + cookie session
core/auth      SessionManager (encrypted)
core/location  FusedLocation wrapper (for photo stamping)
core/camera    PhotoStamper (geo/time/user burn-in)
data/dto,repo  DTOs + repositories over the whitelisted endpoints
ui/components  SmartSelect, StampingCamera
ui/screens     Login, Home, Create, JobCards, Alerts, Tickets, Profile
ui/navigation  MainShell (bottom nav + NavHost + deeplinks)
```

## Config

`BASE_URL` is a `buildConfigField` in `app/build.gradle.kts`
(`https://service.naarni.com/`). Override per build type as needed.
