"""Shared vocabulary for the material gate.

Every literal the module branches on lives here, so the doctype JSON, the
controllers, the whitelisted API and the app renderer cannot drift apart — the
same discipline `process_engine.constants` enforces, and for the same reason: a
status string typed differently in two files is a bug that only shows up in
production, on a Saturday.
"""

from __future__ import annotations

# ------------------------------------------------------------- movement types

INWARD = "Inward"
OUTWARD = "Outward"

MOVEMENT_TYPES = (INWARD, OUTWARD)

# ------------------------------------------------------------------- statuses

STATUS_DRAFT = "Draft"
STATUS_IN_PROGRESS = "In Progress"
STATUS_AWAITING_VERIFICATION = "Awaiting Verification"
STATUS_COMPLETED = "Completed"
STATUS_CANCELLED = "Cancelled"

STATUSES = (
	STATUS_DRAFT,
	STATUS_IN_PROGRESS,
	STATUS_AWAITING_VERIFICATION,
	STATUS_COMPLETED,
	STATUS_CANCELLED,
)

#: Movements in these states accept no further writes. `_assert_open` is what
#: stops a verified gate record from being edited after the fact.
TERMINAL_STATUSES = (STATUS_COMPLETED, STATUS_CANCELLED)

#: States an operator may still add items to.
EDITABLE_STATUSES = (STATUS_DRAFT, STATUS_IN_PROGRESS)

# ------------------------------------------------------------------ purposes

#: Purposes offered per movement type. Showing "Return to Supplier" on an inward
#: gate note is how a clerk picks it by accident at 6 a.m.
PURPOSES = {
	INWARD: (
		"Supplier Delivery",
		"Inter-Plant Transfer In",
		"Return from Job Work",
		"Customer Return",
		"Rework Return",
		"Sample / Trial",
	),
	OUTWARD: (
		"Inter-Plant Transfer Out",
		"Return to Supplier",
		"Dispatch to Customer",
		"Job Work Out",
		"Scrap / Disposal",
		"Sample / Trial",
	),
}

# --------------------------------------------------------------- item fields

CONDITION_OK = "OK"
CONDITIONS = ("OK", "Damaged", "Short", "Excess", "Rejected")

QR_SCANNED = "Scanned"
QR_TYPED = "Typed"
QR_SOURCES = (QR_SCANNED, QR_TYPED)

NO_PHOTO_REASONS = ("Sealed packaging", "Poor light", "Camera unavailable", "Bulk item", "Other")

PARTY_TYPES = ("Supplier", "Customer", "Inter-Plant", "Job Work", "Transporter", "Internal")

REFERENCE_TYPES = ("Invoice", "Delivery Challan", "E-Way Bill", "Purchase Order", "Gate Pass", "Other")

PHOTO_KINDS = ("Item", "Document", "Vehicle", "Gate", "Damage")

#: Units the inline item-create form offers. Deliberately short: a picker with
#: forty units is a picker nobody reads, and "Nos" covers most of the catalogue.
UOMS = ("Nos", "Set", "Pair", "Roll", "Metre", "Kg", "Litre")

# ----------------------------------------------------------------- item codes

#: Prefix for items created at the gate. Kept distinct from every catalogue
#: prefix (CHS/STR/EXT/AGG/INT/SMB/CAB/ELE/CLG/FAS/TLS) so a glance at a code
#: says whether it came from the engineering sheet or from the floor.
GATE_ITEM_PREFIX = "NEW"

# ---------------------------------------------------------------------- roles

ROLE_OPERATOR = "Material Gate Operator"
ROLE_SUPERVISOR = "Material Supervisor"
ROLE_VIEWER = "Material Viewer"

MATERIAL_ROLES = (ROLE_OPERATOR, ROLE_SUPERVISOR, ROLE_VIEWER)

#: Roles that may verify or reject. `Depot Manager` is here because a plant
#: manager already carries it and should not need a second role assigned.
SUPERVISOR_ROLES = (ROLE_SUPERVISOR, "Depot Manager", "System Manager", "Administrator")

#: Roles that may record a movement.
WRITE_ROLES = (ROLE_OPERATOR, *SUPERVISOR_ROLES)

#: Everyone who may see the Material tab at all.
ALL_ROLES = (*MATERIAL_ROLES, "Depot Manager", "System Manager", "Administrator")

# --------------------------------------------------------------- site config

#: Site flag that lets the submitter verify their own movement. Exists so a
#: single-clerk night shift is not deadlocked; off by default, because the whole
#: point of verification is that a second pair of eyes saw the truck.
#:
#:     bench --site X set-config material_allow_self_verify 1
CONF_ALLOW_SELF_VERIFY = "material_allow_self_verify"
