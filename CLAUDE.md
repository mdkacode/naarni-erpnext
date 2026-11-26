# NaArNi vehicle_maintenance (Frappe): rules for AI assistants

This repo is a fork of ERPNext. **Our code is the Frappe app in `vehicle_maintenance/`**, plus
`android-app/`; everything else at the root is inherited ERPNext and is not changed.

Read [`docs/engineering/FRAPPE_GUIDELINES.md`](docs/engineering/FRAPPE_GUIDELINES.md)
before editing. It covers:
- whitelisted APIs, depot scope and validation;
- no string-built SQL;
- doctypes and patches;
- tests first with `FrappeTestCase`;
- commits, PRs and deploy.

## Always

- **Test first.** A failing `FrappeTestCase` comes first, then the code. Run
  `bench --site <site> run-tests --module vehicle_maintenance.api.test_<area>` and put the real
  result (`Ran N tests … OK`) in the PR.
- **Hooks:** run `scripts/setup-git-hooks.sh` once per clone. Commit hooks (ruff, ast) and the push
  hook (at most 10 files vs `develop`) must pass. Never bypass them with `--no-verify`.
- **Commits** are Conventional Commits (`feat(alerts): …`); commitlint checks them.
- **APIs:**
  - refuse Guest, then scope to the caller's depots;
  - return `{success, data, message?}`;
  - add only optional parameters, because the Android app ships separately;
  - use `frappe.get_all` / `frappe.qb`, or `%s` parameters, never an f-string inside
    `frappe.db.sql` (semgrep blocks it).
- **Data changes** are new idempotent patches in `patches.txt`, never edits to merged ones.

## Never

- Commit `.env*`, site configs, dumps, certificates or keys.
- Push to `develop` directly. Merging to `develop` **deploys** to the Azure VM, with a few minutes of
  maintenance mode for the ERP and the Android app. Deploy outside 10:00–18:00 IST unless Mayank
  overrides that for the change.
- Change the inherited ERPNext code or its workflows as a side effect of a feature.

## Repo facts

| | |
| --- | --- |
| Base branch | `develop` (merge = deploy, `.github/workflows/deploy-azure.yml`) |
| Python | ≥ 3.11, ruff via pre-commit (tabs) |
| Tests | `bench --site <site> run-tests --module …` (needs MariaDB + Redis; not on push) |
| CI on PRs | PR governance, semgrep (Frappe rules), commitlint. "linters" and "Documentation Required" are inherited from ERPNext and fail on every PR in this fork. |
| Older PRs | PRs opened before 26 Nov 2026 get a governance comment and are not closed (repository variable `PR_GOVERNANCE_STRICT_SINCE`) |

<!-- pr-governance:start · synced from naarni-workspace/standards/pr-governance/CLAUDE.section.md by scripts/sync-pr-governance.sh; edit it there -->
## Pull requests (mandatory; enforced by `.githooks/pre-push` and `.github/workflows/pr-governance.yml`)

- **At most 10 files per PR.** Added, modified, deleted and renamed files all count, tests included.
  Before writing code, estimate the files the change will touch. If it is more than 10, stop and plan
  sequential PRs, e.g. PR 1: migration + domain entities; PR 2: service logic + unit tests; PR 3: API
  endpoints + RBAC + integration tests. The pre-push hook blocks a branch with more than 10 files
  against the repo's default branch, `origin/main` or `origin/master` (whichever it is closest to). For a branch stacked on
  another PR branch: `PR_GOVERNANCE_BASE=<parent-branch> git push`. The GitHub check counts each PR.
- **Title** starts with `Feature Release:`, `Bug Fix:`, `Hotfix:`, `Refactor:`, `Infra:`,
  `Security:` or `Chore:`, then a concise title. Squash-merge keeps it as the commit subject.
- **Body** is `.github/PULL_REQUEST_TEMPLATE.md`, filled from this repo's live data, never an
  ad-hoc summary:
  - **Developer**: `git config user.name`
  - **Branch / Commit**: `` `<git branch --show-current>` · `<git rev-parse --short HEAD>` ``
  - **Date**: `LC_ALL=C date '+%d %B %Y'` (e.g. `23 November 2026`)
  - **Verifier**: the reviewer's name; `[Add verifier name]` only until one is assigned
  - **6. Testing**: the real test count and pass/fail summary from this repo's verification command
- **All 7 numbered sections are mandatory**, even when one does not apply. Say what was evaluated,
  e.g. `- None (Frontend-only change)` under `## 2. New APIs`, `- No database changes` under
  `## 4. Database`, `- No access changes` under `## 5. Who can see what`.
- **No `[...]` placeholder may remain** anywhere in the title or body (only `[Add verifier name]`
  before a reviewer is assigned). Literal brackets go in backticks or a code block.
- **Cross-repo features**: the backend / database PR goes first. Name the dependent repo and branch
  in `## 1. What's new` and the deploy order in `## 7. Deployment`.
- **Raise it with** `gh pr create --base <base> --title "<Prefix>: <title>" --body-file <filled template>`,
  then confirm the **PR governance** check is green. If it is red, fix the title, description or
  split the PR; never work around it.
- **A PR that fails is closed automatically** within seconds (drafts too, whatever the base branch),
  labelled `pr-governance: blocked`, and given one comment listing every problem. Editing the title
  or description re-runs the check, and the bot reopens the PR once it passes. Every push to
  `main`/`master`/`develop` re-checks all open PRs. Only PRs opened by bots are exempt. In a repo that
  sets the `PR_GOVERNANCE_STRICT_SINCE` variable, PRs opened before that date get the comment but are
  not closed.
<!-- pr-governance:end -->
