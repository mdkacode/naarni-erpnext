# Notifications + Push + Pull-to-Refresh — Implementation Plan

## Audit (what exists today)

**Backend — ready ✅**
- `api/notifications.py`: `get_my_notifications(limit, offset, unread_only)` (newest-first, paginated), `get_unread_count`, `mark_notification_read(name | mark_all)`, `register_push_token(device_token, platform)`.
- `Push Token` doctype (user, device_token, platform, is_active).
- `fleet_service/notifications.py`: `_dispatch_in_app` writes a **Notification Log** (what the list API reads), `_dispatch_realtime` (socket `vm_notification`), `_dispatch_push` → `_dispatch_push_job` (FCM). ~15 `notify_*` events fire on job-card / inventory transitions; `notify_job_card_created` targets `assigned_service_engineer`.

**App — gaps ❌**
- No Firebase/FCM (no `google-services.json`, no SDK, no `FirebaseMessagingService`).
- `registerPushToken` defined but **never called**.
- **No Notifications screen**; no `markNotificationRead` API method.
- **No pull-to-refresh** on any screen.

**Backend issue 🐛**
- `_dispatch_push_job` posts to the **legacy** `fcm/send` server-key endpoint, which Google **shut down (2024)**. Must move to **FCM HTTP v1** (OAuth via service account).

---

## Phase 1 — In-app notifications + PTR (no Firebase; verifiable now)

1. **App API/repo**: add `markNotificationRead(name)` / `markAllRead()`; `notifications(limit, offset)`, `unreadCount()`.
2. **NotificationsScreen**: list **newest → oldest**, unread highlighted, tap → mark-read (+ deeplink to job card later), "Mark all read", infinite scroll, **pull-to-refresh**.
3. **Bell + unread badge** on the Home top bar → opens Notifications.
4. **Reusable `Refreshable` wrapper** (`PullToRefreshBox`) applied to **Jobs, Fleet, Alerts, Tickets, Notifications, Home**.
5. **E2E**: create a Job Card → `notify_job_card_created` writes a Notification Log for the SE → pull-to-refresh Notifications → it appears.

## Phase 2 — FCM push (needs your Firebase project)

6. **Firebase**: you add `app/google-services.json` (from a Firebase project) + Google-services Gradle plugin + `firebase-messaging` dep.
7. **`NaarniMessagingService`**: `onNewToken` → `registerPushToken`; `onMessageReceived` → post a system notification (channel + deeplink).
8. **Runtime POST_NOTIFICATIONS** permission; register token **on login** and on refresh.
9. **Backend → FCM HTTP v1**: `_dispatch_push_job` rewritten to mint an OAuth token from a **service-account JSON** (`notifications_fcm_service_account` in site_config) and POST to `/v1/projects/<id>/messages:send`. No-op until configured.
10. **Config**: `notifications_push_enabled=1` + the service account.

## Phase 3 — Realtime (optional, later)
- Kotlin socket.io client subscribing to `vm_notification` for instant in-app (today: polling + PTR + FCM).

---

## Verification matrix
| Item | How verified | Phase |
|---|---|---|
| Notification list newest→old | Create job card → see it in Notifications | 1 ✅ now |
| Pull-to-refresh all screens | Pull on each list → reloads | 1 ✅ now |
| Mark read / unread badge | Tap → badge decrements | 1 ✅ now |
| FCM token saved | Push Token row on login | 2 (needs Firebase) |
| Push delivered to device | System notification on event | 2 (needs Firebase + service account) |

## What you must provide for Phase 2
- `google-services.json` from a Firebase project (Android app `com.naarni.service`).
- A Firebase **service-account JSON** → `bench set-config notifications_fcm_service_account '<json>'` + `notifications_push_enabled 1`.
