#!/usr/bin/env bash
# vehicle_maintenance — dev control script
#
# Frees stuck ports and (re)starts the Vite frontend dev server.
# Optional flags can also rebuild the production bundle and bounce the
# Frappe bench.
#
# Usage:
#   scripts/dev.sh                # kill Vite on stuck ports, restart dev
#   scripts/dev.sh --rebuild      # also wipe + rebuild the production bundle
#   scripts/dev.sh --clean        # nuke Vite dep cache before restarting
#   scripts/dev.sh --bench        # also bounce Frappe (kills `bench start`)
#   scripts/dev.sh --no-dev       # do everything but DON'T start Vite at the end
#   scripts/dev.sh --status       # report what's running, no changes
#
# Combine flags freely. Idempotent — safe to re-run.

set -euo pipefail

# ── Paths (resolve from this script's location, not the caller's CWD) ──
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
FRONTEND_DIR="${APP_ROOT}/frontend"
BUILD_DIR="${APP_ROOT}/vehicle_maintenance/public/frontend"
VITE_CACHE_DIR="${FRONTEND_DIR}/node_modules/.vite"
BENCH_DIR="$(cd "${APP_ROOT}/../.." && pwd)"  # ~/frappe-bench
LOG_DIR="${APP_ROOT}/.dev-logs"
mkdir -p "${LOG_DIR}"

# ── Defaults ──
DO_REBUILD=0
DO_CLEAN=0
DO_BENCH=0
DO_DEV=1
DO_STATUS_ONLY=0

# ── Args ──
for arg in "$@"; do
  case "$arg" in
    --rebuild)  DO_REBUILD=1 ;;
    --clean)    DO_CLEAN=1 ;;
    --bench)    DO_BENCH=1 ;;
    --no-dev)   DO_DEV=0 ;;
    --status)   DO_STATUS_ONLY=1 ;;
    -h|--help)
      sed -n '2,18p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      echo "Unknown flag: $arg (try --help)" >&2
      exit 64
      ;;
  esac
done

# ── Pretty output ──
if [[ -t 1 ]]; then
  C_BLUE=$'\033[34m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
  C_RED=$'\033[31m';  C_DIM=$'\033[2m';   C_OFF=$'\033[0m'
else
  C_BLUE= C_GREEN= C_YELLOW= C_RED= C_DIM= C_OFF=
fi
say()  { printf "%s\n" "${C_BLUE}▶${C_OFF} $*"; }
ok()   { printf "%s\n" "${C_GREEN}✓${C_OFF} $*"; }
warn() { printf "%s\n" "${C_YELLOW}!${C_OFF} $*"; }
die()  { printf "%s\n" "${C_RED}✗${C_OFF} $*" >&2; exit 1; }

# ── Helpers ──
list_vite_pids()  { pgrep -f "node.*vite" 2>/dev/null || true; }
list_bench_pids() { pgrep -f "bench_helper.*serve\|honcho start\|bench start" 2>/dev/null || true; }

port_listener() {
  # Returns the PID(s) listening on a port, or empty.
  local port="$1"
  lsof -nP -iTCP:"${port}" -sTCP:LISTEN -t 2>/dev/null || true
}

wait_for_port_free() {
  local port="$1" tries=10
  while (( tries > 0 )); do
    if [[ -z "$(port_listener "${port}")" ]]; then return 0; fi
    sleep 0.5
    (( tries-- ))
  done
  return 1
}

# ── Status ──
show_status() {
  printf "\n${C_DIM}── status ──${C_OFF}\n"
  local v b
  v="$(list_vite_pids)" ; b="$(list_bench_pids)"
  if [[ -n "$v" ]]; then ok "Vite running (PIDs: $(echo "$v" | tr '\n' ' '))"; else warn "Vite NOT running"; fi
  if [[ -n "$b" ]]; then ok "Bench running (PIDs: $(echo "$b" | tr '\n' ' '))"; else warn "Bench NOT running"; fi
  for p in 8000 8080 8081; do
    local owners ; owners="$(port_listener "$p")"
    if [[ -n "$owners" ]]; then
      ok "Port ${p}: held by PID(s) $(echo "$owners" | tr '\n' ' ')"
    else
      printf "  ${C_DIM}port %s: free${C_OFF}\n" "$p"
    fi
  done
  printf "\n"
}

if (( DO_STATUS_ONLY )); then
  show_status
  exit 0
fi

# ── Kill Vite (and free the ports it might hold) ──
say "Stopping any running Vite dev servers"
vite_pids="$(list_vite_pids)"
if [[ -n "$vite_pids" ]]; then
  echo "$vite_pids" | xargs kill 2>/dev/null || true
  sleep 1
  # Hard-kill stragglers
  vite_pids="$(list_vite_pids)"
  if [[ -n "$vite_pids" ]]; then echo "$vite_pids" | xargs kill -9 2>/dev/null || true; fi
  ok "Vite stopped"
else
  warn "No Vite process was running"
fi

# Also reclaim the dev ports if anything else is sitting on them.
for p in 8080 8081; do
  owner="$(port_listener "$p")"
  if [[ -n "$owner" ]]; then
    warn "Port ${p} still held by PID(s) ${owner}; killing"
    echo "$owner" | xargs kill 2>/dev/null || true
    sleep 0.5
    owner="$(port_listener "$p")"
    [[ -n "$owner" ]] && echo "$owner" | xargs kill -9 2>/dev/null || true
    wait_for_port_free "$p" || warn "Port ${p} still busy (may be in TIME_WAIT — Vite will pick the next one)"
  fi
done

# ── Optional: bounce bench ──
if (( DO_BENCH )); then
  say "Restarting Frappe bench"
  bench_pids="$(list_bench_pids)"
  if [[ -n "$bench_pids" ]]; then
    echo "$bench_pids" | xargs kill 2>/dev/null || true
    sleep 2
    bench_pids="$(list_bench_pids)"
    [[ -n "$bench_pids" ]] && echo "$bench_pids" | xargs kill -9 2>/dev/null || true
    ok "Bench stopped"
  fi
  say "Starting bench in background → ${LOG_DIR}/bench.log"
  ( cd "${BENCH_DIR}" && nohup bench start >"${LOG_DIR}/bench.log" 2>&1 & )
  ok "Bench launched (tail ${LOG_DIR}/bench.log)"
fi

# ── Optional: clean caches ──
if (( DO_CLEAN )); then
  say "Wiping Vite dep cache"
  rm -rf "${VITE_CACHE_DIR}" && ok "${VITE_CACHE_DIR} removed"
fi

# ── Optional: rebuild production bundle ──
if (( DO_REBUILD )); then
  say "Wiping stale production build at ${BUILD_DIR}"
  rm -rf "${BUILD_DIR}"
  say "Running npm run build"
  ( cd "${FRONTEND_DIR}" && npm run build ) || die "npm run build failed"
  ok "Production bundle rebuilt"
fi

# ── Start dev server ──
if (( DO_DEV )); then
  say "Starting Vite dev server (background) → ${LOG_DIR}/vite.log"
  ( cd "${FRONTEND_DIR}" && nohup npm run dev >"${LOG_DIR}/vite.log" 2>&1 & )
  # Wait a moment for Vite to print its URL line
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    sleep 0.5
    if grep -q "Local:" "${LOG_DIR}/vite.log" 2>/dev/null; then break; fi
  done
  url="$(grep -E 'Local:[[:space:]]+http' "${LOG_DIR}/vite.log" 2>/dev/null | head -1 | awk '{print $NF}' || true)"
  if [[ -n "$url" ]]; then
    ok "Vite dev server up at ${url}"
  else
    warn "Vite started but URL not detected yet — tail ${LOG_DIR}/vite.log"
  fi
fi

show_status
ok "Done."
