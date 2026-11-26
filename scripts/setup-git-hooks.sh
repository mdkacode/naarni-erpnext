#!/usr/bin/env bash
# One-command git hook setup for this repo.
# Canonical copy: naarni-workspace/standards/pr-governance/setup-git-hooks.sh, synced into every repo
# as scripts/setup-git-hooks.sh by naarni-workspace/scripts/sync-pr-governance.sh.
#
# Activates the committed hooks through whatever this repo already uses, so nothing that works
# today is replaced:
#   .pre-commit-config.yaml -> `make hooks-install`, else `pre-commit install` (pre-commit + pre-push);
#                              the config's pre-push stage calls .githooks/pre-push
#   .husky/                 -> `npx husky` (core.hooksPath=.husky/_); .husky/pre-push calls .githooks/pre-push
#   neither                 -> git config core.hooksPath .githooks
# Safe to re-run. Outside a git checkout (`npm ci` in a Docker build) it does nothing and exits 0.
#   scripts/setup-git-hooks.sh [--quiet]
set -u
quiet=0
[ "${1:-}" = --quiet ] && quiet=1
log() { [ "$quiet" = 1 ] || printf 'setup-git-hooks: %s\n' "$*"; }

root="$(git rev-parse --show-toplevel 2>/dev/null)" || { log "not a git checkout; nothing to do"; exit 0; }
cd "$root" || exit 1
[ -d .githooks ] && chmod +x .githooks/* 2>/dev/null
current="$(git config --get core.hooksPath || true)"

if [ -f .pre-commit-config.yaml ]; then
  # pre-commit refuses to install while core.hooksPath is set. Clear it only if it is the value an
  # earlier run of this script set; anything else was set on purpose and is reported, not changed.
  if [ "$current" = .githooks ]; then git config --unset core.hooksPath; current=""; fi
  if [ -n "$current" ]; then
    log "core.hooksPath is '$current', which pre-commit cannot install over. If you did not set it on purpose: git config --unset core.hooksPath"
    exit 1
  fi
  if [ -f Makefile ] && grep -qE '^hooks-install:' Makefile; then
    make hooks-install || exit 1
  else
    pre-commit install --hook-type pre-commit --hook-type pre-push || exit 1
  fi
  log "hooks active through pre-commit (.git/hooks); its pre-push stage runs .githooks/pre-push"
elif [ -d .husky ]; then
  npx --no-install husky || exit 1
  log "hooks active through Husky (core.hooksPath=$(git config --get core.hooksPath)); .husky/pre-push runs .githooks/pre-push"
else
  git config core.hooksPath .githooks || exit 1
  log "hooks active: core.hooksPath=.githooks (pre-push = 10-file limit + verification)"
fi
