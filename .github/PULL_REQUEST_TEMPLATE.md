<!--
  Fill in this form; do not write your description above or below it.
  Replace every [ ... ] placeholder (only [Add verifier name] may stay until a reviewer is assigned)
  and write something in all 7 numbered sections. Title: Feature Release: / Bug Fix: / Hotfix: /
  Refactor: / Infra: / Security: / Chore: followed by a concise title. At most 10 changed files.
  A PR that fails the "PR governance" check is closed automatically within seconds; fix the title
  and description and it reopens by itself.
-->
# [Type: Feature Release / Bug Fix / Refactor / Infra]: [Concise Title]

| Field | Value |
| :--- | :--- |
| **Feature** | [One-line summary of the feature or change] |
| **Developer** | [Developer Name] |
| **Verifier** | [Add verifier name] |
| **Branch / Commit** | `[branch-name]` · `[short-commit-sha]` |
| **Date** | [DD Month YYYY] |

## 1. What's new
- [Bullet points describing user-facing or system changes]
- [Call out dependent PRs in other repos: repo, branch and link, or "No cross-repo dependency"]

## 2. New APIs
- [Method, path, request, response and required role for each new or changed endpoint, or "None (<reason>)"]

## 3. How it works
- [Design and data flow: which modules, services or tables are involved, and why this approach]

## 4. Database
- [Migrations (version and file), new or changed tables and columns, backfills, or "No database changes"]

## 5. Who can see what
- [Roles, permissions and data visibility this change affects, or "No access changes"]

## 6. Testing
- [Tests written first, the verification command and its result: N tests, N passed, N failed]

## 7. Deployment
- [Deploy order across repos, config and secrets, zero-downtime evidence for infra, and the rollback]
