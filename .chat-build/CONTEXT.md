# Chat module — overnight build context

Durable state for the autonomous build loop. Updated at the end of every cycle.
If context is lost, **read this file first** — it is the source of truth for progress.

- **Started:** 2026-08-11
- **Branch:** `feat/chat-module` (created off `fix/seed-parts-catalog-customer-code`)
- **Blueprint:** https://claude.ai/code/artifact/2c61386a-797b-4876-b9d3-2b41fce0676e

## Environment (verified 2026-08-11)

| Thing | Value |
|---|---|
| Bench | `~/frappe-bench`, site `dev.localhost` |
| App symlink | `apps/vehicle_maintenance` → `/Users/mayank/Documents/frappe/vehicle_maintenance` (edits apply live) |
| Frappe | v15.103.3 · MariaDB 10.11 + Redis already running |
| Device | Xiaomi `25028RN03I` (serenity_in), **Android 15 / API 35**, USB |
| adb | `~/Library/Android/sdk/platform-tools/adb` (NOT on PATH — always use full path) |
| Java | 17.0.16 · gradlew present in `android-app/` |

## Deploy scope — IMPORTANT

"Deploy Frappe" is being done on the **local dev bench (`dev.localhost`) only**.
Production (`service.naarni.com`) is NOT being touched. Pushing a brand-new,
untested chat system to prod unattended is outward-facing and hard to reverse —
that needs an explicit go-ahead. See "Needs Mayank" below.

## Build order (from blueprint §8)

Backend first, because everything downstream assumes the contract.

1. [ ] DocTypes: VM Chat Room, VM Chat Member (child), VM Chat Message
2. [ ] Per-room `seq` allocator (row-locked) + `client_id` unique index
3. [ ] `api/chat.py` — list_rooms, list_messages, sync, send_message, mark_read
4. [ ] Chunked upload — begin / upload_chunk / commit / status (blueprint B1+B2)
5. [ ] Realtime fan-out (user room envelopes + doc room bodies)
6. [ ] hooks.py wiring + fixtures
7. [ ] Backend tests (`bench run-tests --app vehicle_maintenance`)
8. [ ] Deploy to dev.localhost (`bench migrate`) + bench start, smoke via curl
9. [ ] Android: Room schema + DAOs + repository
10. [ ] Android: socket codec (Origin-header handshake — blueprint B3)
11. [ ] Android: Chat tab, thread UI, Paging3
12. [ ] Android: capture/EXIF fix + ChunkUploadWorker
13. [ ] Android: FCM channel + deep link
14. [ ] Execute TC01–TC06 on the Xiaomi

## Progress log

### Cycle 0 — 2026-08-11, setup
- Verified environment (table above). Device connected and authorised.
- Created branch `feat/chat-module`.
- Wrote this file.
- Next: DocType JSONs.

## Needs Mayank (do not proceed without an answer)

1. **Production deploy** of the chat backend — not doing this unattended.
2. **Gemini / Deepgram** keys — mentioned as "later". Nothing is being built against
   them yet; the message `kind` enum leaves room for `audio` + a `transcript` field so
   Deepgram can slot in without a migration.

## Gotchas hit (append as found)

- `adb` is not on PATH; the bench is not auto-started (`bench start` needed).
- Working tree carried uncommitted process-engine work from the previous branch —
  only chat paths are being committed here.
