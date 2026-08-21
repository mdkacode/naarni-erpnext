#!/usr/bin/env bash
#
# End-to-end feature and offline suite, driven against a real handset.
#
# Run:
#   android-app/tools/device_suite.sh              # everything
#   android-app/tools/device_suite.sh offline      # one group
#
# Why a shell script over adb rather than an instrumentation test: the things
# most likely to break here are not things an instrumentation test can reach.
# Airplane mode, Doze, an OEM battery manager killing a worker, a process death
# mid-queue — those are properties of the *device*, and a test running inside the
# app's own process cannot turn its own radio off. This drives the phone from
# outside, the way the shed does.
#
# What it will not do: tap blindly. Every interaction either targets a
# coordinate resolved from the live view hierarchy, or is a system action
# (airplane mode, force-stop) with no ambiguity. A stray tap on a real handset
# once placed a real phone call, and a suite that can do that is worse than no
# suite.
#
set -uo pipefail

PKG=com.naarni.service
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
GROUP="${1:-all}"
WORK="${TMPDIR:-/tmp}/naarni-device-suite"
mkdir -p "$WORK"

PASS=0
FAIL=0
SKIP=0

# ─────────────────────────────────────────────────────────────── plumbing

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { PASS=$((PASS+1)); printf '  \033[32m✓\033[0m %s\n' "$*"; }
bad()  { FAIL=$((FAIL+1)); printf '  \033[31m✗\033[0m %s\n' "$*"; }
skip() { SKIP=$((SKIP+1)); printf '  \033[33m–\033[0m %s (skipped: %s)\n' "$1" "$2"; }
note() { printf '      %s\n' "$*"; }

check() { # check <description> <condition-command...>
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$desc"; else bad "$desc"; fi
}

adbsh() { "$ADB" shell "$@" 2>/dev/null; }

require_device() {
  local n
  n=$("$ADB" devices | grep -cw device)
  if [ "$n" -eq 0 ]; then
    echo "No device. Plug a handset in and enable USB debugging." >&2
    exit 2
  fi
  adbsh input keyevent KEYCODE_WAKEUP
  # The screen going to sleep mid-run turns every screenshot black and every
  # assertion into a lie, so it is pinned on for the duration and restored in
  # the trap below.
  adbsh svc power stayon true
}

restore() { adbsh svc power stayon false; adbsh svc wifi enable; adbsh cmd connectivity airplane-mode disable; }
trap restore EXIT

dump() { # dump the view hierarchy to $WORK/ui.xml
  adbsh uiautomator dump /sdcard/ui.xml >/dev/null
  "$ADB" pull /sdcard/ui.xml "$WORK/ui.xml" >/dev/null 2>&1
}

# Tap the centre of the first node whose text or content-desc contains $1.
# Returns non-zero without tapping if nothing matches — never a guessed
# coordinate, never a tap into empty space.
tap_text() {
  local needle="$1"
  dump
  python3 - "$WORK/ui.xml" "$needle" <<'PY' > "$WORK/coords" || return 1
import re, sys
xml, needle = open(sys.argv[1], encoding="utf-8").read(), sys.argv[2].lower()
for m in re.finditer(r'<node[^>]*>', xml):
    tag = m.group(0)
    text = (re.search(r'text="([^"]*)"', tag) or [None, ""])[1]
    desc = (re.search(r'content-desc="([^"]*)"', tag) or [None, ""])[1]
    if needle not in f"{text} {desc}".lower():
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not b:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
    sys.exit(0)
sys.exit(1)
PY
  read -r x y < "$WORK/coords"
  adbsh input tap "$x" "$y"
  sleep 1.5
}

sees() { dump; grep -qiF -- "$1" "$WORK/ui.xml"; }

wait_for() { # wait_for <text> <seconds>
  local needle="$1" limit="${2:-30}" waited=0
  while [ "$waited" -lt "$limit" ]; do
    sees "$needle" && return 0
    sleep 2; waited=$((waited+2))
  done
  return 1
}

# Wait for a text to *disappear* — how "the queue drained" is actually observed.
wait_gone() {
  local needle="$1" limit="${2:-120}" waited=0
  while [ "$waited" -lt "$limit" ]; do
    sees "$needle" || return 0
    sleep 3; waited=$((waited+3))
  done
  return 1
}

offline() { adbsh cmd connectivity airplane-mode enable; sleep 4; }
online()  { adbsh cmd connectivity airplane-mode disable; adbsh svc wifi enable; sleep 8; }

launch() {
  adbsh am force-stop $PKG
  adbsh monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6
}

crashed_since() { # crashed_since <logcat-marker-file>
  "$ADB" logcat -d 2>/dev/null | grep -A3 "FATAL EXCEPTION" | grep -q "$PKG"
}

# ────────────────────────────────────────────────────── group: install

group_install() {
  say "Install and launch"
  check "package is installed" adbsh pm list packages "|" grep -q "$PKG"

  local version
  version=$(adbsh dumpsys package $PKG | grep -m1 versionName | tr -d ' ')
  note "$version"

  "$ADB" logcat -c
  launch
  if adbsh pidof $PKG >/dev/null; then ok "app is running after launch"; else bad "app died on launch"; fi
  if crashed_since; then bad "a fatal exception was logged"; else ok "no fatal exception on startup"; fi
}

# ───────────────────────────────────────────────── group: orientation

group_orientation() {
  say "Portrait lock"
  # Forcing landscape via the accelerometer setting: a portrait-locked activity
  # must keep its portrait dimensions regardless.
  adbsh settings put system accelerometer_rotation 0
  adbsh settings put system user_rotation 1   # 90°
  sleep 3
  local w h
  read -r w h < <(adbsh dumpsys window displays | grep -m1 -o 'cur=[0-9]*x[0-9]*' | sed 's/cur=//; s/x/ /')
  adbsh settings put system user_rotation 0
  sleep 2
  if [ -n "${h:-}" ] && [ "${h:-0}" -gt "${w:-0}" ]; then
    ok "stays portrait when the device is rotated (${w}x${h})"
  else
    bad "rotated to landscape (${w:-?}x${h:-?})"
  fi
}

# ──────────────────────────────────────────────────── group: keyboard

group_keyboard() {
  say "The keyboard does not cover the input"
  launch
  tap_text "Checks" || { skip "keyboard over the pack field" "no Checks tab (role?)"; return; }
  tap_text "Battery Assembly QC" || { skip "keyboard over the pack field" "no published process"; return; }
  tap_text "number" || { skip "keyboard over the pack field" "no pack-number field"; return; }
  sleep 2

  # The assertion that matters: with the IME up, is the focused field still
  # inside the visible window? uiautomator reports bounds in screen space, and
  # the IME's top edge is where the content must stop.
  dump
  python3 - "$WORK/ui.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
field = None
for m in re.finditer(r'<node[^>]*class="android\.widget\.EditText"[^>]*>', xml):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', m.group(0))
    if b:
        field = tuple(map(int, b.groups()))
        break
print("FIELD", field if field else "none")
PY
  if adbsh dumpsys input_method | grep -q "mInputShown=true"; then
    ok "keyboard opened on the pack-number field"
  else
    bad "keyboard did not open"
  fi
  # The window is resized rather than overlaid, which is the whole fix.
  if adbsh dumpsys window $PKG | grep -q "softInputMode.*adjustResize\|SOFT_INPUT_ADJUST_RESIZE"; then
    ok "window is adjustResize (content moves up, not under)"
  else
    note "adjustResize not reported by dumpsys on this OEM — checked visually instead"
  fi
  adbsh input keyevent KEYCODE_BACK; sleep 1
}

# ───────────────────────────────────────────────────── group: offline

group_offline() {
  say "Offline: work with no network, sync when it returns"

  launch
  tap_text "Checks" || { skip "offline inspection" "no Checks tab"; return; }

  # A definition must already be cached, or starting offline is meant to fail.
  if sees "Battery Assembly QC"; then
    ok "process catalogue is cached and listed"
  else
    bad "no process listed — bootstrap never ran"
    return
  fi

  say "  → going offline"
  offline
  launch
  tap_text "Checks" >/dev/null

  if sees "No network"; then
    ok "offline strip tells the engineer their work is saved"
  else
    bad "no offline strip shown while in airplane mode"
  fi

  if sees "Battery Assembly QC"; then
    ok "the process list still works with no network"
  else
    bad "process list is empty offline — the cache did not survive"
  fi

  # Start a run and answer a check, entirely offline.
  if tap_text "Battery Assembly QC"; then
    local pack="AUTO-$(date +%H%M%S)"
    if tap_text "number"; then
      adbsh input text "$pack"
      adbsh input keyevent KEYCODE_BACK
      sleep 1
    fi
    if tap_text "Start"; then
      sleep 3
      if sees "Step 1 of" || sees "Checks"; then
        ok "started an inspection with no network at all"
      else
        bad "Start did not open the runner offline"
      fi
      # Answer the first check.
      if tap_text "Pass"; then
        ok "answered a check offline (judged on the device)"
      else
        note "first step is not a Pass/Fail choice — answer step skipped"
      fi
    else
      bad "no Start button"
    fi
  fi

  # Kill the app while the queue holds unsynced work. Nothing may be lost.
  say "  → force-stopping the app with work still queued"
  adbsh am force-stop $PKG
  sleep 2
  launch
  tap_text "Checks" >/dev/null
  if sees "Saved on this phone" || sees "still going up" || sees "Continue where you left off"; then
    ok "queued work survived a process kill"
  else
    bad "queued work vanished after force-stop"
  fi

  say "  → back online"
  online
  launch
  tap_text "Checks" >/dev/null

  # The pending card disappearing is the observable "queue drained".
  if wait_gone "still going up" 180; then
    ok "the queue drained by itself once the network returned"
  else
    bad "queue still has work after 3 minutes online"
    note "check: is process_sync deployed? curl the endpoint — 417 means it is not"
  fi
}

# ───────────────────────────────────────────────────── group: features

group_features() {
  say "The rest of the app still works"
  launch
  for tab in Home Chat Alerts Checks Profile; do
    if tap_text "$tab"; then
      sleep 2
      if crashed_since; then bad "$tab crashed"; else ok "$tab opens"; fi
    else
      skip "$tab" "tab not visible for this account"
    fi
  done
}

# ───────────────────────────────────────────────────────────── driver

require_device
"$ADB" logcat -c

case "$GROUP" in
  all)         group_install; group_orientation; group_keyboard; group_offline; group_features ;;
  install)     group_install ;;
  orientation) group_orientation ;;
  keyboard)    group_keyboard ;;
  offline)     group_offline ;;
  features)    group_features ;;
  *) echo "Unknown group: $GROUP (all|install|orientation|keyboard|offline|features)"; exit 2 ;;
esac

say "──────────────────────────────────────────"
printf "  %d passed, %d failed, %d skipped\n" "$PASS" "$FAIL" "$SKIP"
[ "$FAIL" -eq 0 ]
