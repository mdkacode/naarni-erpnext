# CRM (Leads) + Product Catalogue — Implementation Plan

> Single source of truth for the CRM and Product Catalogue modules. All work
> tracked in this repo must stay aligned with this document. Update the file
> (don't fork it) when scope changes.

---

## 1. Scope & UX principles

### 1.1 What we're adding
1. **CRM / Leads module** — capture prospects from sales pitches, track status
   through a workflow, log activities, schedule email reminders, upload
   supporting images/files, convert winners to Customers.
2. **Product Catalogue module** — maintain a catalogue of sellable
   products/services with images, standard pricing, and **per-customer price
   overrides** that feed into Service Estimate / Job Card line items.

### 1.2 Roles
- **Sales Executive** (new role) + **Ops Manager** + **Admin** → full CRM access
- **Service Engineer** → read-only on Leads they own
- **Customer** → no access to CRM
- Catalogue: list/read open to SE, Sales, Ops, Admin; write restricted to
  Ops/Admin

### 1.3 EAS (Eliminate · Automate · Simplify)
Every free-text field is replaced with a dropdown/autocomplete wherever a
finite list exists. Typing is allowed only for: lead name, phone, notes, price
overrides, product name, description. Everything else — source, status,
industry, product category, UOM, tax template, reminder cadence — is a Link or
Select field rendered as a dropdown.

---

## 2. Module 1 — CRM / Leads

### 2.1 New DocTypes (under `vehicle_maintenance/fleet_service/doctype/`)

| DocType | Type | Purpose |
|---|---|---|
| `Lead` | Master | One row per sales-pitch prospect |
| `Lead Source` | Master (Select-backed) | Referral, Walk-in, Website, Exhibition, Cold Call, Partner, Other |
| `Lead Status` | Master | New, Contacted, Qualified, Proposal Sent, Negotiation, Won, Lost, On Hold |
| `Lead Activity` | Child of Lead | Timeline of calls/visits/notes |
| `Lead Reminder` | Standalone | Scheduled email reminders, linked to Lead |
| `Lead Attachment` | Child of Lead | Image/file uploads |

### 2.2 `Lead` fields

**Identity (typed)**
- `lead_name` (Data, required)
- `phone` (Data, required, 10-digit validation — matches existing auth convention)
- `email` (Data, optional)

**Qualification (dropdowns)**
- `lead_source` → Link to Lead Source
- `industry` → Select: Logistics, Mining, Construction, Municipal, Agriculture, Private Fleet, Other
- `fleet_size_bucket` → Select: 1–5, 6–20, 21–50, 51–100, 100+
- `interested_in` → Select: AMC, Spot Repair, Full Maintenance Contract, Parts Only, Inspection Only
- `assigned_to` → Link to User (filtered to Sales role)
- `depot` → Link to existing Depot DocType
- `status` → Link to Lead Status (defaults to "New")
- `priority` → Select: Low, Medium, High
- `expected_close_date` → Date
- `estimated_value` → Currency

**Location (dropdown-driven)**
- `state` → Select (pre-seeded Indian states)
- `city` → Data with autocomplete against existing customers' cities
- `address_line` → Small Text

**Meta**
- `converted_to_customer` → Link to Customer (read-only, set on conversion)
- `activities` → Table of Lead Activity
- `attachments` → Table of Lead Attachment
- `notes` → Small Text

**Naming:** `LEAD-.YYYY.-.#####`

### 2.3 `Lead Activity` child table
- `activity_type` → Select: Call, Visit, Demo, Quote Sent, Email, WhatsApp, Other
- `activity_date` → Datetime (defaults to now)
- `outcome` → Select: Interested, Not Interested, Callback Requested, Meeting Scheduled, Converted, No Response
- `next_action` → Select: Call Back, Send Quote, Schedule Demo, Close as Lost, None
- `summary` → Small Text (<=500 chars)

### 2.4 `Lead Reminder` DocType
- `lead` → Link to Lead
- `reminder_datetime` → Datetime
- `channel` → Select: Email, System Notification
- `subject` → Data
- `message_template` → Link to Email Template
- `status` → Select: Scheduled, Sent, Failed, Cancelled
- `sent_at` → Datetime (read-only)

**Delivery:** `scheduler_events` cron `*/15 * * * *` calls
`vehicle_maintenance.api.crm.dispatch_due_reminders`. Picks all `Lead Reminder`
where `reminder_datetime <= now AND status = "Scheduled"`, renders Email
Template, calls `frappe.sendmail(...)`, marks sent/failed. Failures
→ `frappe.log_error`.

### 2.5 `Lead Attachment` child table
- `file` → Attach Image / Attach
- `caption` → Data
- `uploaded_on` → Datetime (auto)

Uploads use Frappe's `File` DocType (private by default). Cap 5 MB per file;
MIME whitelist: `image/jpeg`, `image/png`, `image/webp`, `application/pdf`.

### 2.6 Whitelisted API — `vehicle_maintenance/api/crm.py`

| Method | Purpose |
|---|---|
| `get_lead_list(filters, page, page_size)` | Paginated list with status/assigned_to/source filters |
| `get_lead(name)` | Full detail incl. activities, attachments, reminders |
| `create_lead(payload)` | Validates phone; returns created name |
| `update_lead_status(name, new_status)` | Workflow-guarded; auto-appends Activity |
| `add_activity(lead, payload)` | Append to Lead Activity |
| `schedule_reminder(lead, datetime, template, subject)` | Creates Lead Reminder |
| `cancel_reminder(name)` | Soft-cancels a pending reminder |
| `dispatch_due_reminders()` | Scheduler job |
| `convert_lead_to_customer(name)` | Creates Customer, links back, sets status = Won |
| `upload_lead_attachment(lead, file_url, caption)` | Links uploaded File to Lead |
| `get_lead_dropdowns()` | Bulk dropdown payload |

All methods:
- Start with `frappe.only_for([...])` or `frappe.has_permission(...)`
- Return `{success, data, message}` envelope
- Wrap user-facing strings in `_()`
- Use parameterized queries only

### 2.7 Workflow fixture

```
New → Contacted → Qualified → Proposal Sent → Negotiation → Won → (Customer)
                                                          ↘ Lost
 Any → On Hold → (back to prior state)
```

- Advances restricted to `assigned_to` + Ops Manager
- `Won` transition auto-calls `convert_lead_to_customer`
- Exported as `Workflow`, `Workflow State`, `Workflow Action Master` fixtures

### 2.8 Vue 3 SPA pages (`frontend/src/pages/`)

| File | Route | Purpose |
|---|---|---|
| `LeadsList.vue` | `/crm/leads` | Sortable/filterable list; dropdown filters |
| `LeadDetail.vue` | `/crm/leads/:name` | Tabs: Overview · Activities · Reminders · Attachments |
| `LeadNew.vue` | `/crm/leads/new` | **4-step wizard** |
| `LeadReminderModal.vue` | component | Dropdown-driven reminder scheduler |

**Wizard steps (EAS, max 4–5 fields each)**
1. **Who** — lead_name, phone, email
2. **What** — industry ▾, interested_in ▾, fleet_size_bucket ▾, estimated_value
3. **Where** — state ▾, city ▾, depot ▾, address_line
4. **Assign** — lead_source ▾, assigned_to ▾, priority ▾, expected_close_date, notes

Composables: `useLeads.js`, `useCrmDropdowns.js` (caches `get_lead_dropdowns`
once per session).

### 2.9 Seeded Email Templates (fixtures)
- "Follow-up after initial contact"
- "Quote reminder"
- "Demo scheduling"
- "Re-engagement after silence"

Variables: `{{ lead_name }}`, `{{ assigned_to }}`, `{{ estimated_value }}`,
`{{ expected_close_date }}`.

---

## 3. Module 2 — Product Catalogue + Pricing

### 3.1 New DocTypes

| DocType | Purpose |
|---|---|
| `Product` | Catalogue item (distinct from `Part`, which stays inventory-focused) |
| `Product Category` | Lubricants, Filters, Tyres, Brakes, Electrical, Services, Consumables, Other |
| `Product UOM` | Nos, Ltr, Kg, Box, Hour, Service |
| `Product Brand` | Lightweight master |
| `Product Image` | Child table on Product |
| `Customer Product Price` | Customer-specific override price |

Reuse existing Frappe **`Price List`** and **`Item Tax Template`** DocTypes.

### 3.2 `Product` fields

**Identity**
- `product_code` (Data, unique, auto-suggested from name)
- `product_name` (Data, required)
- `category` → Link Product Category
- `uom` → Link Product UOM
- `hsn_code` → Link existing HSN master
- `tax_template` → Link Item Tax Template

**Pricing defaults**
- `standard_selling_price` → Currency
- `standard_cost` → Currency (Admin/Ops only)
- `currency` → Link Currency (default INR)

**Attributes (dropdowns)**
- `brand` → Link Product Brand
- `is_service` → Check
- `is_active` → Check (default yes)
- `lead_time_days` → Int

**Media**
- `images` → Table Product Image (max 6, one primary enforced in `validate`)
- `description` → Text Editor (optional)

**Naming:** `PROD-.#####`

### 3.3 `Product Image` child table
- `image` → Attach Image (required)
- `is_primary` → Check
- `alt_text` → Data

### 3.4 `Customer Product Price`
- `customer` → Link Customer (required)
- `product` → Link Product (required)
- `price_list` → Link Price List (optional)
- `selling_price` → Currency
- `discount_percentage` → Percent (alternative to explicit price)
- `valid_from` → Date
- `valid_upto` → Date
- `min_qty` → Float (slab pricing)
- `is_active` → Check

Unique constraint: `(customer, product, price_list, valid_from)`.

**Resolver (`api/catalogue.py → get_price_for_customer`)**
1. Find active `Customer Product Price` matching customer/product/date/min_qty
2. If found → override price
3. Else → `Product.standard_selling_price`
4. Return `{price, source, applied_record}`

### 3.5 Whitelisted API — `vehicle_maintenance/api/catalogue.py`

| Method | Purpose |
|---|---|
| `get_product_list(filters, page)` | Paginated; filters: category, brand, is_active, search |
| `get_product(name)` | Full detail incl. images |
| `create_product(payload)` | Ops/Admin only |
| `update_product(name, payload)` | Ops/Admin only |
| `upload_product_image(product, file_url, is_primary, alt_text)` | Image management |
| `get_customer_prices(customer, page)` | Overrides for a customer |
| `set_customer_price(payload)` | Upsert override |
| `remove_customer_price(name)` | Soft-deactivate |
| `get_price_for_customer(customer, product, qty, on_date)` | Used by Estimate/Job Card |
| `get_catalogue_dropdowns()` | Bulk: categories, UOMs, brands, tax templates, price lists |

### 3.6 Vue 3 SPA pages

| File | Route | Purpose |
|---|---|---|
| `ProductsList.vue` | `/catalogue/products` | Grid with thumbnails + dropdown filters |
| `ProductDetail.vue` | `/catalogue/products/:name` | Image carousel, pricing tabs |
| `ProductNew.vue` | `/catalogue/products/new` | **3-step wizard** |
| `ProductImageUploader.vue` | component | Drag-drop + preview + set-primary |
| `CustomerPricingList.vue` | `/catalogue/customer-pricing` | Filter by customer ▾ or product ▾ |
| `CustomerPricingEditor.vue` | modal | Upsert override |

**Product wizard**
1. **Basics** — product_name, category ▾, brand ▾, is_service ✓
2. **Pricing & tax** — uom ▾, standard_selling_price, hsn_code ▾, tax_template ▾
3. **Images & description** — upload, primary pick, description

**Customer pricing editor** — customer ▾ (search), product ▾ (search, shows
standard inline), selling_price OR discount_percentage (radio), valid_from,
valid_upto, min_qty, price_list ▾.

### 3.7 Integration with existing flows
- Add optional Link `product` on `Service Estimate Item` and `Job Card Item`
  (alongside existing `part`)
- On product select, frontend calls `get_price_for_customer` to auto-fill `rate`
- Badge in UI: "Customer price" vs "Standard price"
- Existing Part-based estimates continue to work unchanged

### 3.8 Image storage & delivery
- Upload via Frappe `/api/method/upload_file` → `File` DocType (private)
- `Product Image` stores `file_url` only (no base64)
- Cap 3 MB, types: jpeg/png/webp
- Client-side resize in `useImageResize.js` before upload

---

## 4. Delivery phases

| Phase | Scope | Est. effort |
|---|---|---|
| **1. CRM backend** | Lead, Lead Activity, Lead Source/Status, Lead Attachment DocTypes + API + workflow fixture + Sales Executive role + permissions | 2–3 days |
| **2. CRM frontend** | LeadsList, LeadDetail, LeadNew wizard, dropdown cache composable | 2–3 days |
| **3. Reminders** | Lead Reminder DocType + scheduler job + seeded Email Templates + UI modal | 1–2 days |
| **4. Catalogue backend** | Product, Product Image, Product Category, UOM, Brand DocTypes + catalogue API + image upload | 2 days |
| **5. Pricing engine** | Customer Product Price + resolver API + integration points in Estimate/Job Card | 1–2 days |
| **6. Catalogue frontend** | ProductsList, ProductDetail, ProductNew wizard, image uploader, CustomerPricingList/Editor | 2–3 days |
| **7. Fixtures + tests + role perms** | Workflow/Role fixtures, `test_lead.py`, `test_product.py`, `test_customer_product_price.py`, security checklist review | 1–2 days |

Total: **~11–17 working days** for a solid v1.

---

## 5. Open decisions (to confirm as we go)
1. Sales Executive role — new role, or fold into Ops Manager? → **Default: new role.**
2. Reminder channels v1 — **Email only**; WhatsApp/SMS via pluggable channel dispatcher in a later phase.
3. Product vs Part — keep **separate** (Product = catalogue/sellable, Part = inventory SKU).
4. Customer pricing — **per-customer overrides only** in v1; tier-based Price Lists later.
5. Lead → Customer conversion — auto-create on "Won", with a review modal to confirm mapped fields.

---

## 6. Definition of Done (v1)

- [ ] All new DocTypes created, migrated, and covered by `test_*.py`
- [ ] Every whitelisted API method has permission check + docstring + envelope return
- [ ] Workflow fixture committed; `bench migrate` reproduces it on a fresh site
- [ ] Role, Custom Field, Property Setter, Workflow, Client Script fixtures exported in `hooks.py`
- [ ] SPA pages: Leads CRUD + Activity logging + Reminder scheduling + Product CRUD + Customer pricing
- [ ] Image uploads end-to-end (validate type, size, primary flag)
- [ ] Security checklist from `CLAUDE.md §13` green
