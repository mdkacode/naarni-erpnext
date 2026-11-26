# Frappe / vehicle_maintenance Engineering Guidelines

How code is written, tested, reviewed and shipped in the Frappe app `vehicle_maintenance`
(repo `mdkacode/naarni-erpnext`, base branch `develop`). The app is the workshop and service ERP:
job cards, alerts and tickets, process engine, material movement, chat, KM reports. Its REST layer
serves the Android app and the web dashboard.

This file is the canonical copy in `naarni-workspace/standards/`; the repo carries an identical copy
at `docs/engineering/FRAPPE_GUIDELINES.md` (outside `vehicle_maintenance/`, so a docs change does
not trigger a deploy). It follows the NaArNi engineering
standard (`naarni-workspace/standards/ENGINEERING_STANDARDS.md`) and the PR rules of ADR-007
(`naarni-workspace/context/decisions/ADR-007-pr-governance.md`).

## 1. Where things live

| Path | What |
| --- | --- |
| `vehicle_maintenance/vehicle_maintenance/api/` | Whitelisted REST methods (`/api/method/vehicle_maintenance.api.<module>.<fn>`), one module per area, `test_*.py` beside them |
| `vehicle_maintenance/vehicle_maintenance/<module>/doctype/` | DocTypes: JSON schema, controller `.py`, tests |
| `vehicle_maintenance/vehicle_maintenance/patches/`, `patches.txt` | Data patches run by `bench migrate` |
| `vehicle_maintenance/vehicle_maintenance/hooks.py` | Doc events, scheduler, overrides |
| `vehicle_maintenance/frontend/` | Vue frontend |
| `android-app/` | Kotlin app that calls the API |
| everything else at the root | Inherited ERPNext tree; not ours to change |

## 2. Enforcement: what runs, when

| When | What | Blocks? |
| --- | --- | --- |
| `git commit` | pre-commit: ast, merge markers, debug statements, ruff import sorter, ruff lint, ruff format | Yes |
| `git push` | pre-commit pre-push hook `pr-governance` (`.githooks/pre-push`): at most 10 files vs `develop` | Yes |
| Pull request | **PR governance** (10 files, title prefix, 5 metadata rows, 7 sections), **semgrep** (Frappe rules), **commitlint** (Conventional Commits) | PR governance closes a failing PR; the others fail the PR |
| By hand, before the PR | `bench --site <site> run-tests --module <module>` (needs MariaDB and Redis) | Required: the result goes in section 6 of the PR |

Install the hooks once per clone: `scripts/setup-git-hooks.sh`.

**Noisy inherited checks.** "linters" and "Documentation Required" come from upstream ERPNext. They
fail on every PR in this fork; the second looks up the PR number in `frappe/erpnext`. Say so in the
PR rather than working around them.

## 3. Code style

- **Ruff only**, through pre-commit. It formats with **tabs**, as the root config says; never
  reformat files you did not change.
- **Python ≥ 3.11**: type hints on every whitelisted method (`-> dict`) and helper.
- **Small helpers, named for what they mean**: `_user_vehicle_names(user)`, not `get_data2`.
  Private helpers start with `_` and are never whitelisted.
- **Docstrings say why**, and what the caller can rely on. Comments never restate the code.

## 4. Whitelisted API rules

1. **Signed in, then scoped.** Refuse Guest first (`frappe.throw(..., frappe.PermissionError)`).
   Then narrow to what the caller may see: a Service Engineer sees the vehicles at their depots
   (`_user_depots`, `_user_vehicle_names`). A filter may narrow that scope but never widen it.
   A vehicle outside the depots matches nothing.
2. **One envelope:** `{"success": bool, "data": …, "message"?: str}`. Clients peel Frappe's
   `{message: …}` and this envelope; never return bare lists or ad-hoc shapes.
3. **Validate every argument** before touching the database: types, allowed values, date shape
   (`YYYY-MM-DD`, then `getdate`), ordering (`from ≤ to`), `limit` capped at 100. A bad argument is a
   `ValidationError` with a sentence a user can act on.
4. **No string-built SQL.** Prefer `frappe.get_all` / `frappe.qb`. When raw SQL is unavoidable
   (window functions), values go only through `%s` parameters, and the only thing interpolated is
   fixed text or `%s` placeholders. semgrep's `frappe-sql-format-injection` blocks an f-string or
   `.format()` inside `frappe.db.sql(...)`.
5. **Backwards compatible.** The Android app ships on its own schedule, so new parameters are
   optional with defaults that keep today's behaviour, and fields are added, never renamed or removed.
6. **Times:** stored in the site's time zone (IST). Date filters are inclusive IST days: `>= day
   00:00` and `< next day 00:00`.

## 5. DocTypes, patches and data

- **Schema changes** live in the DocType JSON and are applied by `bench migrate` on deploy. Add
  fields; avoid renames, which break the Android app and old patches.
- **Data changes** are patches listed in `patches.txt`, idempotent (safe to run twice), and never
  edited once merged. A fix is a new patch.
- **No manual `frappe.db.commit()`** in request handlers: Frappe commits at the end of the request.
  Where one is truly needed (background jobs, push delivery), justify it on the line with
  `# nosemgrep — <reason>`.
- **Slow or external work** (push notifications, chat broadcast, emails) goes through
  `frappe.enqueue`, never inline in a request.

## 6. Test-driven development

1. **Write the test first** and watch it fail for the right reason (a missing parameter, a wrong
   count), not a fixture error. Then write the code, and refactor.
2. **Stack:** `FrappeTestCase`, run inside a bench site:
   `bench --site <site> run-tests --module vehicle_maintenance.api.test_<area>`. Tests sit beside the
   code (`api/test_<area>.py`, `doctype/<x>/test_<x>.py`).
3. **Isolation.**
   - Patch `frappe.db.commit` to a no-op in `setUp` and restore it in `tearDown`.
   - Patch `frappe.enqueue` the same way when inserts fire hooks that queue work; a test bench may
     have no queue.
   - Delete the rows your test creates before creating them again.
   - Use unique emails, phone numbers and registrations per test module; phone numbers are unique
     per user on this site.
4. **Fixtures carry only what the behaviour needs.** When a DocType's own validation needs unrelated
   setup (an Alert Type needs a Telemetry Parameter), set `doc.flags.ignore_validate = True` and say
   why in a comment.
5. **What needs a test:** every whitelisted method you add or change. That covers its scope (another
   depot sees nothing), each filter, bad input, and the empty case. Every doc event and patch you add
   needs one too.
6. **Report the real result** in the PR: the command, and `Ran N tests … OK`.

## 7. Commits and pull requests

- **Commits:** Conventional Commits (`feat(alerts): …`, `fix(process): …`). commitlint checks the
  type (`build chore ci docs feat fix perf refactor revert style test`).
- **PRs follow the NaArNi standard**, enforced by the PR governance check:
  - at most **10 files**;
  - the title starts with `Feature Release:`, `Bug Fix:`, `Hotfix:`, `Refactor:`, `Infra:`,
    `Security:` or `Chore:`;
  - the body is `.github/PULL_REQUEST_TEMPLATE.md`, with all 7 sections filled from live data.
    In this repo:
    - **4. Database:** list doctype JSON changes and patches, or "No database changes".
    - **6. Testing:** the `bench run-tests` result.
    - **7. Deployment:** the maintenance-mode deploy.
- **Base branch:** `develop`.
- **Cross-repo:** this app ships before the web dashboard or Android change that reads it. Name the
  dependent PR in section 1, and the order in section 7.
- **Older PRs:** PRs opened before 26 Nov 2026 (`PR_GOVERNANCE_STRICT_SINCE`) get a comment listing
  what is missing, and are not closed.

## 8. Deploy

- **Merging to `develop` deploys** (`.github/workflows/deploy-azure.yml`). On the Azure VM it:
  1. turns maintenance mode on;
  2. syncs the app;
  3. runs `bench migrate`;
  4. builds the frontend;
  5. restarts every service.
- **Impact:** the ERP site and the Android app are unavailable for a few minutes.
- **Window:** deploy outside 10:00–18:00 IST unless Mayank overrides it for that change.
- **Rollback:** revert the PR on `develop`, which redeploys. A data patch needs a compensating patch;
  a revert does not undo it.
