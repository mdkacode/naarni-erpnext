# ARCHITECTURAL DECISIONS LOG
> Last Updated: 2026-04-17

## Decision 1: Single Job Card DocType (not separate per type)
- **Rationale:** PRD has unified state machine, unified dashboards, unified SLA. Type-specific fields use Section Breaks with depends_on.
- **Trade-off:** Job Card JSON will be large (100+ fields) but avoids 4x API/UI duplication.

## Decision 2: PMS Checksheet as DocType (not static JSON)
- **Rationale:** Admin should be able to modify checksheet items. Different OEMs may have different sheets. DocType gives Frappe CRUD + permissions.
- **Implementation:** `PMS Checksheet` (master) + `PMS Checksheet Item` (child). Pre-populated via fixtures.

## Decision 3: Custom Job Card Naming
- **Format:** `{CustomerCode}-{DepotCode}-{YYMMDD}-{Serial}`
- **Example:** `ZB-GGN-210326-002` (matches sample reports)
- **Implementation:** Override `autoname` method in Job Card controller.

## Decision 4: Concern Code + Labor Code as Master Data
- **Rationale:** Service history shows structured codes (GC4045, 35132469). Enables warranty tracking, analytics, and standardized reporting.
- **Data source:** Extracted from BharatBenz service history PDF (48 job cards analyzed).

## Decision 5: Health Score = Average of Category Scores
- **Formula:** Category Score = (Sum of Component Scores / Max Possible) x 100. Vehicle Score = Average of Category Scores.
- **Score Map:** Good=10, Recommended=5, Immediately=0
- **Source:** PRD Section 1.B

## Decision 6: Inventory Request as Separate DocType
- **Rationale:** Inventory flow has its own lifecycle (Requested → Allocated → Issued → Acknowledged) that needs tracking independent of Job Card state.

## Decision 7: Notification via Frappe's built-in + custom triggers
- **Approach:** Use `frappe.publish_realtime` for in-app, background jobs for push/SMS, scheduler for timed escalations.
