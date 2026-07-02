# Play Store Release Guide — Naarni Service

Everything needed to publish `com.naarni.service` to Google Play. Code/config
readiness is **done** (see "What's already handled"); the rest are Play Console
steps that can't be done from code.

---

## 1. Build the upload artifact

```bash
cd android-app
./gradlew bundleRelease        # -> app/build/outputs/bundle/release/app-release.aab
```

The AAB is signed with the **upload key** (`upload-keystore.jks`, via
`keystore.properties`). With **Play App Signing** (recommended, selected), Google
re-signs it with the real app-signing key it holds. The upload key is
**resettable** if ever lost.

> ⚠️ Back up `upload-keystore.jks` and `keystore.properties` somewhere safe and
> private. They are git-ignored and are **not** in the repo. Upload key/password:
> alias `naarni-upload` (see `keystore.properties`). **Change the default
> password** if this leaves your machine.

To verify a build locally without publishing:
```bash
./gradlew assembleRelease      # installable APK, same signing
```

---

## 2. What's already handled in code ✅

- Release signing config (upload key, not debug) — `app/build.gradle.kts`
- `targetSdk = 35` (Play requirement for new apps) — `app/build.gradle.kts`
- Legacy launcher icons for API 24–25 (`mipmap-mdpi`…`xxxhdpi`, square + round)
- Adaptive icon + monochrome (themed icons) — already present
- `usesCleartextTraffic="false"` (HTTPS only)
- Backup / data-extraction rules excluding the encrypted session token
- Android 12+ splash screen
- Runtime notification permission (Android 13+) + notification channel
- **Prominent disclosure** dialog before requesting location — Play policy
- 512×512 hi-res listing icon — `android-app/play_store_512.png`

---

## 3. Play Console — Data safety form

App → App content → Data safety. Answer as follows (matches the code):

**Does your app collect or share user data?** → **Yes**

| Data type | Collected | Shared | Purpose | Optional? |
|-----------|-----------|--------|---------|-----------|
| Phone number | Yes | No | Account management (OTP login) | Required |
| Name | Yes | No | App functionality | Required |
| Precise location | Yes | No | App functionality (photo stamping) | **Optional** |
| Photos | Yes | No | App functionality | Required |
| Device or other IDs | Yes | Yes* | App functionality (push routing) | Required |

\* Device push token is processed by Firebase Cloud Messaging to deliver
notifications.

- **Is all data encrypted in transit?** → Yes
- **Can users request data deletion?** → Yes (via the contact email in the policy)
- **Location collected in background?** → **No** (foreground, at capture only)

Privacy policy URL: _publish `PRIVACY_POLICY.md` and paste its HTTPS URL here._

---

## 4. Play Console — Permissions declarations

- **Location (`ACCESS_FINE_LOCATION`)** — foreground only, no background
  permission is declared, so **no** Location Permissions declaration form is
  required. If asked, the use is: "Geo-stamp job photos at capture time for
  field-work verification."
- No other sensitive/restricted permissions (no SMS, no All files access, no
  background location).

---

## 5. Store listing copy (draft)

**App name:** Naarni Service

**Short description (≤80 chars):**
> Field job cards, inspections and photo-verified vehicle maintenance for staff.

**Full description:**
> Naarni Service is the field companion for vehicle maintenance teams. Service
> engineers and technicians manage job cards end to end — inspect vehicles,
> capture geo- and time-stamped photos, log parts and labour, and move work
> through its lifecycle. Managers get instant push notifications for
> assignments, approvals and SLA alerts. Sign in securely with your phone number
> (OTP). This app is for authorised Naarni staff only.

**App category:** Business
**Content rating:** complete the questionnaire → expected **Everyone** (business
tool, no user-generated public content, no ads).
**Contains ads:** No
**In-app purchases:** No
**Target audience:** 18+ (workforce app)

---

## 6. Listing assets — exact Play dimensions & status

Google Play graphic-asset rules (verified):

| Asset | Required spec | Count | Status |
|-------|---------------|-------|--------|
| App icon (hi-res) | **512×512** px, 32-bit PNG, ≤1 MB | 1 | ✅ `play_store_512.png` |
| Feature graphic | **1024×500** px, PNG/JPG, **no alpha** | 1 | ✅ `play_store_feature_graphic.png` |
| Phone screenshots | PNG/JPG; each side **320–3840** px; **max side ≤ 2× min side**; **2–8 required** | 2–8 | ✅ 4 in `play_store_screenshots/final/` |
| 7-inch tablet screenshots | up to 3840 px/side, ≤2× rule | 0–8 (optional) | ☐ optional |
| 10-inch tablet screenshots | up to 3840 px/side, ≤2× rule | 0–8 (optional) | ☐ optional |
| Privacy policy URL | public HTTPS | — | ⏳ pages built; publish + deploy (§9) |

> ⚠️ **Aspect-ratio gotcha:** raw Pixel 9 captures are 1080×2424 (2.24:1) which
> **exceeds Play's 2:1 limit and would be rejected.** The framed screenshots in
> `play_store_screenshots/final/` are **1080×1920 (1.78:1)** — compliant. Use those.

**Screenshots produced (real app, on Pixel 9 emulator, then framed):**
1. `final/01_home.png` — Home / "Welcome back"
2. `final/02_create_jobcard.png` — New Job Card guided flow
3. `final/03_profile.png` — Profile: Privacy / Terms / Delete account
4. `final/04_delete_confirm.png` — Account-deletion confirmation (Play compliance)

Raw (unframed) versions are in `play_store_screenshots/` for reference.

**To add data-rich screenshots** (Job Card list, Job Card detail, stamping camera,
Alerts with severity, Fleet): these need an account **with jobs/fleet data and
depot access**. The test account used (Service Engineer, no depot) shows empty
states, and Fleet returned `list_fleet not whitelisted` — but that method **is**
whitelisted in the current code, so production is simply running an older deploy
(the app targets `service.naarni.com`). A redeploy fixes the Fleet tab. Provide a
populated login and I can capture + frame the full set.

---

## 7. Distribution options (internal workforce app)

Because this is staff-only, consider:
- **Closed testing** track first (invite engineers by email) — fastest to hand
  out, still reviewed.
- Or **Managed Google Play / Internal app sharing** for a private rollout.
- Public production listing is fine too, but the description makes clear it's an
  authorised-staff tool.

---

## 8. Pre-launch checklist

- [ ] `./gradlew bundleRelease` succeeds
- [ ] Upload key + `keystore.properties` backed up securely
- [ ] Privacy policy published at a public HTTPS URL
- [ ] Data safety form completed (section 3)
- [ ] Content rating questionnaire completed
- [ ] Store listing text + screenshots + feature graphic uploaded
- [ ] Firebase project for FCM matches `google-services.json` package
- [ ] App tested on a physical device against the production backend
