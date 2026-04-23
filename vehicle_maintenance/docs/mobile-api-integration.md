# Mobile API Integration Guide

> For the future React Native (Expo) app consuming the Vehicle Maintenance backend.

---

## 1. Authentication

Frappe supports token-based authentication for headless/mobile clients. Every API request must include the `Authorization` header.

### 1.1 Generating API Keys

Each user needs an **API Key** and **API Secret** pair, generated from:

- **Desk UI:** User doctype → API Access section → "Generate Keys"
- **Programmatically:**
  ```python
  # Users are identified by phone number (not email)
  user = frappe.get_doc("User", "9876543210")
  api_key = user.api_key  # auto-generated on user creation
  api_secret = frappe.generate_hash()  # or use user.get_password("api_secret")
  ```

### 1.2 Request Format

```
Authorization: token api_key:api_secret
```

**Example (React Native / fetch):**

```typescript
const API_BASE = "https://your-site.frappe.cloud";

const headers = {
  "Authorization": `token ${apiKey}:${apiSecret}`,
  "Content-Type": "application/json",
};

const response = await fetch(`${API_BASE}/api/method/vehicle_maintenance.api.job_card.get_my_job_cards`, {
  method: "POST",
  headers,
  body: JSON.stringify({ status: "WIP", limit: 20, offset: 0 }),
});

const result = await response.json();
// result.message => { success: true, data: [...], message: "" }
```

> **Note:** Frappe wraps all `@frappe.whitelist()` return values inside `{ "message": <your_return_value> }`. The actual response envelope you defined (`success`, `data`, `message`) is nested under `result.message`.

### 1.3 Secure Storage

Store `api_key:api_secret` in the device's secure storage:

- **iOS:** Keychain (via `expo-secure-store`)
- **Android:** EncryptedSharedPreferences (via `expo-secure-store`)

```typescript
import * as SecureStore from "expo-secure-store";

await SecureStore.setItemAsync("frappe_token", `${apiKey}:${apiSecret}`);
const token = await SecureStore.getItemAsync("frappe_token");
```

---

## 2. API Endpoints Reference

All endpoints use **POST** to `/api/method/<dotted_path>`.

### 2.1 Job Card APIs

Base path: `vehicle_maintenance.api.job_card`

| Method | Params | Description | Roles |
|---|---|---|---|
| `get_my_job_cards` | `status?`, `limit?`, `offset?` | Paginated list scoped by role | All |
| `get_job_card_summary` | `job_card_name` | Single card detail (role-filtered fields) | All |
| `transition_job_card` | `job_card_name`, `action` | Apply a workflow transition | Write-permitted |

### 2.2 Estimate APIs

Base path: `vehicle_maintenance.api.estimate`

| Method | Params | Description | Roles |
|---|---|---|---|
| `create_estimate` | `job_card_name`, `items` (JSON array) | Create a linked estimate | SE, Depot Mgr |
| `approve_estimate` | `estimate_name`, `remarks?` | Customer approves | Customer |
| `reject_estimate` | `estimate_name`, `remarks` (required) | Customer rejects | Customer |

### 2.3 Inventory APIs

Base path: `vehicle_maintenance.api.inventory`

| Method | Params | Description | Roles |
|---|---|---|---|
| `submit_inventory_request` | `job_card_name`, `items` (JSON array) | Request parts; auto-blocks if > 1,000 | SE, Tech, Depot Mgr |
| `release_blocked_inventory` | `job_card_name` | Release blocked parts after approval | SE, Depot Mgr |

---

## 3. Request / Response Patterns

### 3.1 Standard Response Envelope

Every custom API returns:

```json
{
  "success": true,
  "data": { ... },
  "message": "Human-readable status"
}
```

This is nested inside Frappe's wrapper:

```json
{
  "message": {
    "success": true,
    "data": { ... },
    "message": "..."
  }
}
```

### 3.2 Error Responses

When `frappe.throw()` is called, Frappe returns HTTP 4xx with:

```json
{
  "exc_type": "ValidationError",
  "exception": "frappe.exceptions.ValidationError: ...",
  "_server_messages": "[\"{ \\\"message\\\": \\\"You do not have permission...\\\" }\"]"
}
```

**Parsing errors in React Native:**

```typescript
async function callAPI<T>(endpoint: string, params: Record<string, unknown>): Promise<T> {
  const res = await fetch(`${API_BASE}/api/method/${endpoint}`, {
    method: "POST",
    headers,
    body: JSON.stringify(params),
  });

  const json = await res.json();

  if (!res.ok) {
    // Extract user-facing message from _server_messages
    const serverMessages = JSON.parse(json._server_messages || "[]");
    const firstMsg = serverMessages[0] ? JSON.parse(serverMessages[0]).message : "Unknown error";
    throw new Error(firstMsg);
  }

  return json.message as T;
}
```

### 3.3 Sending Complex Params (JSON arrays)

For params like `items`, send as a JSON string within the POST body:

```typescript
await callAPI("vehicle_maintenance.api.inventory.submit_inventory_request", {
  job_card_name: "JC-2026-00042",
  items: JSON.stringify([
    { item_description: "Oil Filter", item_type: "Part", qty: 1, rate: 350 },
    { item_description: "Brake Pad Set", item_type: "Part", qty: 2, rate: 800 },
  ]),
});
```

---

## 4. Workflow Transitions from Mobile

Available actions depend on the card's current `workflow_state` and the user's role.

```typescript
// Fetch current state
const { data: card } = await callAPI("...get_job_card_summary", {
  job_card_name: "JC-2026-00042",
});

// Apply transition
const result = await callAPI("...transition_job_card", {
  job_card_name: "JC-2026-00042",
  action: "Start Work",  // must match a valid Workflow Action for the current state + role
});

console.log(result.data.workflow_state); // "WIP"
```

**Action reference:**

| Current State | Action | Next State |
|---|---|---|
| Open | Start Work | WIP |
| WIP | Send Estimate | Awaiting Customer Approval |
| WIP | Request Parts | Awaiting Parts |
| WIP | Submit for Verification | Verification Pending |
| Awaiting Customer Approval | Customer Approves | Awaiting Parts |
| Awaiting Customer Approval | Customer Rejects | WIP |
| Awaiting Parts | Parts Received & Fitted | Parts Fitted |
| Parts Fitted | Continue Work | WIP |
| Parts Fitted | Submit for Verification | Verification Pending |
| Verification Pending | Close Job Card | Closed |
| Verification Pending | Verification Failed | WIP |

---

## 5. SLA Breach Monitoring (Realtime)

The backend emits a `sla_breach` realtime event when a Job Card's TAT exceeds its SLA target. Mobile apps can listen via Frappe's Socket.IO:

```typescript
import io from "socket.io-client";

const socket = io(API_BASE, {
  extraHeaders: { "Authorization": `token ${token}` },
});

socket.on("sla_breach", (data: { job_card: string; service_type: string }) => {
  showNotification(`SLA breached on ${data.job_card}`);
});
```

---

## 6. File Uploads

For attaching photos (e.g., vehicle damage) use Frappe's file upload endpoint:

```
POST /api/method/upload_file
Content-Type: multipart/form-data
Authorization: token api_key:api_secret

Form fields:
  - file: <binary>
  - doctype: "Job Card"
  - docname: "JC-2026-00042"
  - is_private: 1
```

---

## 7. Pagination Pattern

All list endpoints support `limit` (max 100) and `offset`:

```typescript
let offset = 0;
const PAGE_SIZE = 20;

async function loadNextPage() {
  const { data } = await callAPI("...get_my_job_cards", {
    status: "WIP",
    limit: PAGE_SIZE,
    offset,
  });
  offset += data.length;
  return data;  // empty array means no more pages
}
```
