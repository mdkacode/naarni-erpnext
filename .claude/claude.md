# CLAUDE.md — Technical Rulebook for Vehicle Maintenance Job Card Platform

> **This document is the single source of truth for all AI-assisted code generation in this repository.**
> Every rule herein is mandatory. No exceptions without explicit human approval in the conversation.

---

## 1. Project Overview

**Platform:** Custom Frappe App (Python 3.11+, MariaDB, Redis)
**Frontend (Simplified UI):** Frappe UI (Vue 3 + Tailwind CSS) — for Technicians, Service Engineers (SEs), and Customers
**Frontend (Admin):** Standard Frappe Desk — for Admin and Ops roles only
**Future Mobile:** React Native (Expo) consuming whitelisted APIs (out of scope for now)
**Domain:** Vehicle Maintenance Job Card lifecycle management

---

## 2. The Golden Rule: Never Modify Core

**NEVER modify any file belonging to `frappe` or `erpnext` core.**

All customization MUST live inside the dedicated custom app (e.g., `vehicle_service`). This includes:

- Custom DocTypes (defined in `vehicle_service/vehicle_service/doctype/`)
- Server Scripts and whitelisted API methods
- Custom Workflows and Workflow States
- Client Scripts (only within the custom app's `public/` directory)
- Hooks (`vehicle_service/hooks.py`)
- Overrides via `override_whitelisted_methods`, `doc_events`, and `fixtures`

If a core behavior needs changing, use Frappe's override/hook mechanisms — never patch core source.

---

## 3. User Roles & RBAC

### 3.1 Five Roles

| Role | Interface | Description |
|---|---|---|
| **Admin** | Frappe Desk | Full system access. Manages master data, users, settings. |
| **Ops Manager** | Frappe Desk | Manages job card assignments, scheduling, reporting. |
| **Service Engineer (SE)** | Frappe UI (Vue 3) | Inspects vehicles, creates estimates, oversees technician work. |
| **Technician** | Frappe UI (Vue 3) | Executes job card tasks, logs labor/parts, updates status. |
| **Customer** | Frappe UI (Vue 3) | Views job card status, approves estimates, provides feedback. |

### 3.2 RBAC Principles

- **Backend:** Every `@frappe.whitelist()` method MUST begin with a role check using `frappe.only_for(roles)` or explicit `frappe.has_permission()` calls. Never trust the frontend.
- **DocType Permissions:** Define granular read/write/create/delete/submit permissions per role in the DocType's Permission table.
- **Frontend (Vue 3):** Use a centralized `permissions.js` utility that reads the logged-in user's roles from `frappe.call('frappe.client.get_list', ...)` and conditionally renders UI sections. Never hide security behind CSS — omit unauthorized components from the DOM entirely.
- **API responses** MUST NOT leak fields the requesting role is not authorized to see. Use explicit field lists in queries, never `SELECT *`.

---

## 4. Architecture: Custom App Structure

```
vehicle_service/
├── vehicle_service/
│   ├── doctype/
│   │   ├── job_card/                # Core DocType
│   │   │   ├── job_card.py          # Server-side controller
│   │   │   ├── job_card.json        # DocType definition
│   │   │   └── test_job_card.py     # Unit tests
│   │   ├── job_card_item/           # Child table
│   │   ├── vehicle/                 # Master DocType
│   │   ├── service_estimate/        # Estimate DocType
│   │   └── ...
│   ├── api/
│   │   ├── __init__.py
│   │   ├── job_card.py              # Whitelisted API methods for job cards
│   │   ├── estimate.py              # Whitelisted API methods for estimates
│   │   └── auth.py                  # Token-based auth helpers
│   ├── workflows/                   # Workflow JSON fixtures
│   ├── hooks.py
│   ├── patches/                     # Data migration patches
│   └── www/                         # Frappe UI (Vue 3) SPA entry
│       └── app/                     # Vue 3 SPA served at /app route
├── frontend/                        # Frappe UI Vue 3 project
│   ├── src/
│   │   ├── pages/                   # Route-level views
│   │   ├── components/              # Shared UI components
│   │   ├── composables/             # Vue 3 composables (useJobCard, useAuth, etc.)
│   │   ├── utils/
│   │   │   ├── permissions.js       # Centralized RBAC utility
│   │   │   └── api.js               # Frappe call wrapper
│   │   └── router.js
│   ├── tailwind.config.js
│   └── package.json
├── setup.py
└── pyproject.toml
```

### 4.1 Naming Conventions

- **DocTypes:** PascalCase with spaces (e.g., `Job Card`, `Service Estimate`)
- **Python files:** snake_case matching DocType (e.g., `job_card.py`)
- **API modules:** snake_case by domain (e.g., `api/job_card.py`)
- **Vue components:** PascalCase `.vue` files (e.g., `JobCardWizard.vue`)
- **Composables:** camelCase prefixed with `use` (e.g., `useJobCard.js`)

---

## 5. State Machine: Job Card Workflow

The Job Card lifecycle is governed by a **Frappe Workflow** (defined as a fixture, version-controlled).

### 5.1 Workflow States

```
Draft → Inspection → Estimate Pending → Estimate Approved → In Progress → Quality Check → Completed → Invoiced → Closed
                                  ↘ Estimate Rejected → (back to Estimate Pending)
```

### 5.2 Workflow Rules

- **Every state transition** MUST be defined in the Workflow fixture. No ad-hoc `doc.workflow_state = "..."` assignments in code.
- **Transition permissions** are role-bound: e.g., only SE can move `Draft → Inspection`; only Customer can trigger `Estimate Approved`.
- **on_update hooks** in the Job Card controller validate that the transition is legal. Use `frappe.throw()` for invalid transitions — never silently ignore.
- **Automatic transitions** (e.g., all tasks complete → Quality Check) are handled via server-side logic in `job_card.py`, triggered by child table updates.

---

## 6. API Layer Design

### 6.1 Whitelisted Methods

All complex business logic MUST be exposed as `@frappe.whitelist()` methods inside `vehicle_service/api/`. This prepares the system for future React Native consumption.

```python
# vehicle_service/api/job_card.py

import frappe

@frappe.whitelist()
def get_job_card_summary(job_card_name: str) -> dict:
    """Returns a role-appropriate summary of a job card."""
    frappe.has_permission("Job Card", doc=job_card_name, throw=True)
    # ... build and return response dict
```

### 6.2 API Rules

- **Every method** MUST have a docstring explaining purpose, params, and return shape.
- **Every method** MUST validate permissions at the top.
- **Return plain dicts/lists**, never Frappe Document objects directly. Serialize explicitly.
- **Input validation:** Use `frappe.validate_value()` or explicit checks. Never trust client data.
- **Error handling:** Use `frappe.throw()` with translatable messages (`_("message")`). Never bare `raise`.
- **Token-based auth:** For future mobile, support `Authorization: Bearer <token>` via Frappe's built-in token auth or OAuth. The API layer must not assume cookie-based sessions.

### 6.3 API Response Format

All custom API methods MUST return a consistent envelope:

```python
return {
    "success": True,
    "data": { ... },
    "message": "Optional human-readable message"
}
```

On error, `frappe.throw()` handles the envelope automatically.

---

## 7. Frontend: Frappe UI (Vue 3 + Tailwind)

### 7.1 Scope

The Vue 3 SPA is the ONLY interface for Technicians, SEs, and Customers. It is built with [Frappe UI](https://frappeui.com) components and Tailwind CSS.

### 7.2 Rules

- **No Frappe Desk dependency.** The SPA must function independently of Desk. All data comes from whitelisted API calls.
- **Use `createResource` / `createListResource`** from `frappe-ui` for all API calls. Never use raw `fetch` or `axios`.
- **Routing:** Use `vue-router`. Each major entity/workflow step gets its own route.
- **State management:** Use Vue 3 composables (`ref`, `reactive`, `computed`). No Vuex/Pinia unless complexity demands it — start simple.
- **Components:** Keep components small and single-responsibility. Shared components go in `components/`, page-specific ones stay in `pages/`.

### 7.3 EAS Framework for Forms (Eliminate, Automate, Simplify)

**Every form in the Vue 3 SPA MUST be designed using EAS:**

1. **Eliminate:** Remove every field that is not strictly necessary for the current user's role and workflow step. If a field can be derived or defaulted, do NOT show it.
2. **Automate:** Pre-fill fields from context (e.g., vehicle details from license plate scan, logged-in user as SE, current date/time as default). Auto-calculate totals, taxes, durations.
3. **Simplify:** Break remaining fields into **multi-step wizards** (max 4-5 fields per step). Use plain language labels. Provide inline validation. Use tap-friendly inputs (large buttons, dropdowns instead of free text where possible).

**Forbidden patterns:**
- Long scrolling forms with 10+ visible fields
- Technical Frappe field names exposed in UI (e.g., show "Vehicle Number" not "registration_plate")
- Raw JSON or Link field references visible to non-Admin users

---

## 8. Backend: Python Code Standards

### 8.1 General

- **Python 3.11+** syntax. Use type hints on all function signatures.
- **No `exec()`, `eval()`, or dynamic code execution.** Ever.
- **SQL:** Use Frappe's ORM (`frappe.get_doc`, `frappe.get_list`, `frappe.db.get_value`, etc.). Raw SQL via `frappe.db.sql()` is permitted ONLY for complex aggregations/reports, and MUST use parameterized queries (`%(param)s` syntax) — never string interpolation.
- **Translations:** All user-facing strings must be wrapped in `_("...")`.

### 8.2 Controller Pattern (DocType .py files)

```python
class JobCard(Document):
    def validate(self):
        self._validate_workflow_transition()
        self._validate_required_fields_for_state()

    def on_update(self):
        self._notify_stakeholders()

    def _validate_workflow_transition(self):
        # Private methods prefixed with underscore
        ...
```

- Use `validate`, `before_save`, `on_update`, `on_submit`, `on_cancel` hooks as per Frappe conventions.
- Keep controller methods focused. Extract complex logic into utility functions in `vehicle_service/utils/`.

### 8.3 Testing

- Every DocType controller MUST have a corresponding `test_*.py` file.
- Use `frappe.tests.utils.FrappeTestCase` as the base class.
- Test all workflow transitions, permission checks, and edge cases.
- Run tests with `bench run-tests --app vehicle_service`.

---

## 9. Data Model Principles

- **Child Tables** for line items (job card items, estimate lines, labor entries). Never store lists as JSON strings in a text field.
- **Link Fields** for all foreign key relationships. Never store names as plain Data fields.
- **Naming Rules:** Use autoname patterns (e.g., `JC-.YYYY.-.#####` for Job Cards).
- **Mandatory fields** must be enforced at the DocType level, not just in UI.
- **Created/Modified tracking** is handled automatically by Frappe — never add custom timestamp fields for this.

---

## 10. Fixtures & Version Control

- **Workflows, Custom Fields, Property Setters, and Role Permissions** MUST be exported as fixtures in `hooks.py` and committed to version control.
- **Never configure workflows or permissions only through the UI** — they must be reproducible via `bench migrate`.
- List all fixture DocTypes in `hooks.py`:

```python
fixtures = [
    {"dt": "Workflow", "filters": [["document_type", "in", ["Job Card", "Service Estimate"]]]},
    {"dt": "Workflow State"},
    {"dt": "Workflow Action Master"},
]
```

---

## 11. Error Handling & Logging

- Use `frappe.throw(_("message"))` for user-facing errors (returns 4xx).
- Use `frappe.log_error(title="context")` for unexpected server errors that need debugging.
- Never swallow exceptions with bare `except: pass`.
- Log meaningful context: include document name, user, and operation in error messages.

---

## 12. Performance Rules

- **List queries** must always specify `fields`, `filters`, `limit_page_length`, and `order_by`. Never fetch all records unbounded.
- **Avoid N+1 queries.** When loading related data, use `frappe.get_all` with appropriate filters in a single call, not loops of `frappe.get_doc`.
- **Cache** expensive computations with `frappe.cache().hget/hset` where appropriate.
- **Background jobs** (`frappe.enqueue`) for any operation that takes >2 seconds (PDF generation, bulk updates, email sending).

---

## 13. Security Checklist

Every PR and code generation session must satisfy:

- [ ] No core Frappe/ERPNext files modified
- [ ] All API methods check permissions
- [ ] All SQL uses parameterized queries
- [ ] No `eval()` or `exec()`
- [ ] User-facing strings wrapped in `_()`
- [ ] Child table data validated server-side (not just client-side)
- [ ] File uploads restricted to allowed types and sizes
- [ ] No sensitive data (passwords, tokens) logged or returned in API responses

---

## 14. Git & Workflow

- **Branch strategy:** `develop` (default) → feature branches → PR to `develop`
- **Commit messages:** Conventional Commits format: `feat(job_card): add inspection step workflow`
- **One concern per commit.** Do not mix feature code with unrelated refactors.
- **Fixtures** must be committed alongside the code that depends on them.
