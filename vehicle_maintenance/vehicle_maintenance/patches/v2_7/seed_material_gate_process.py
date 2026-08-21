"""Seed the Material Gate as a Process Definition — data, not code.

The same move Battery Assembly QC made: the gate becomes a record an admin can
edit in Desk, and it inherits everything the engine already does — offline
capture with a Room-backed queue, resume after a dead battery, sign-off, the
photo stamp, the scanner, the certificate.

**One run is one item.** That is the engine's natural shape (a fixed list of
questions about one subject) and it is what makes a forty-item truck
parallelisable across several people rather than one clerk's forty-minute form.
The runs reassemble into the gate register: `material_movement.gate_process`
turns each finished run into a `Material Movement` line, so Desk keeps showing
one register and no report has to know two shapes.

The direction step drives two branches through the engine's own visibility
conditions, so an inward run asks "came from" and an outward run asks "going
to" — one process, not two that drift apart.

Idempotent: it seeds only when the family has no version at all, so a migrate
never overwrites a version the plant has since edited.
"""

from __future__ import annotations

import frappe

from vehicle_maintenance.process_engine import constants as C

FAMILY = "MATERIAL_GATE"
PROCESS_CODE = f"{FAMILY}-v1"

DIRECTION_SET = "GATE_DIRECTION"
ITEM_ENTITY = "MATERIAL_ITEM"

STAGE = "GATE"

#: Reasons an operator may skip a step. Every one of them is a real thing that
#: happens at a gate, which is the point: a skip reason list that does not
#: describe reality trains people to pick the first entry.
SKIP_REASONS = (
	"Label damaged or missing\nItem sealed in packaging\nNo weighbridge available\nSupplier document pending"
)

# Seed sources. The list grows from the runner — an operator adds a missing one
# inline — so this is a starting point, not a closed set.
SOURCES = [
	("Hubli — Bus Manufacturing Plant", "Plant", "Hubli"),
	("Narsapura — Battery Manufacturing / Assembly Plant", "Plant", "Narsapura"),
	("Scrap Vendor", "Scrap Vendor", ""),
]


def _steps() -> list[dict]:
	"""The six questions, in the order the gate actually asks them."""
	return [
		{
			"step_code": "DIRECTION",
			"display_no": "1",
			"section": "Movement",
			"label": "Is this coming in or going out?",
			"label_alt": "अंदर आ रहा है या बाहर जा रहा है?",
			"response_type": C.CHOICE,
			"outcome_set": DIRECTION_SET,
			"is_mandatory": 1,
			"allow_skip": 0,
			"help_text": (
				"Answer this first — it decides what the rest of the run asks, and it is "
				"printed across every photo you take."
			),
		},
		{
			"step_code": "ITEM",
			"display_no": "2",
			"section": "Movement",
			"label": "Which item?",
			"label_alt": "कौन सा आइटम?",
			"response_type": C.LINK,
			"link_doctype": "Part",
			"allow_inline_create": 1,
			"is_mandatory": 1,
			"allow_skip": 0,
			"help_text": (
				"Search the catalogue by name, code or spec — “8.7” finds both windshields. "
				"Not in the list? Add it here; an administrator reviews it later."
			),
		},
		{
			"step_code": "SERIAL",
			"display_no": "3",
			"section": "Movement",
			"label": "Scan the QR or serial label",
			"label_alt": "QR या सीरियल लेबल स्कैन करें",
			"response_type": C.SCAN,
			"requires_scan": 1,
			"scan_entity_type": ITEM_ENTITY,
			"scan_count": 1,
			"is_mandatory": 0,
			"allow_skip": 1,
			"skip_reasons": SKIP_REASONS,
			"help_text": (
				"Only if the item carries one. Type it if the label is scratched or greasy — "
				"a damaged sticker must never stop a truck being recorded."
			),
		},
		{
			"step_code": "SOURCE_IN",
			"display_no": "4",
			"section": "Movement",
			"label": "Where did it come from?",
			"label_alt": "यह कहाँ से आया?",
			"response_type": C.LINK,
			"link_doctype": "Material Source",
			"allow_inline_create": 1,
			"is_mandatory": 1,
			"allow_skip": 1,
			"skip_reasons": SKIP_REASONS,
			"help_text": "Start typing — the list learns. Add a new one if it is a first delivery.",
		},
		{
			"step_code": "SOURCE_OUT",
			"display_no": "4",
			"section": "Movement",
			"label": "Where is it going?",
			"label_alt": "यह कहाँ जा रहा है?",
			"response_type": C.LINK,
			"link_doctype": "Material Source",
			"allow_inline_create": 1,
			"is_mandatory": 1,
			"allow_skip": 1,
			"skip_reasons": SKIP_REASONS,
			"help_text": "Start typing — the list learns. Add a new one if it is a first dispatch.",
		},
		{
			"step_code": "DOCUMENT",
			"display_no": "5",
			"section": "Evidence",
			"label": "Photograph the paperwork",
			"label_alt": "कागज़ात की फोटो लें",
			"response_type": C.PHOTO_ONLY,
			"requires_photo": 1,
			"photo_policy": "Always",
			"min_photos": 1,
			"max_photos": 4,
			"photo_hint": "Invoice, delivery challan, e-way bill or gate pass.",
			# Optional, and it has to stay optional: a truck is not always
			# accompanied by its documents, and a clerk blocked on a challan they
			# were never handed records the load on a scrap of paper instead.
			"is_mandatory": 0,
			"allow_skip": 1,
			"skip_reasons": "Documents not with the truck\nDocuments already filed\nNot applicable",
			"help_text": "Only if you have it. Skip this if the paperwork is coming separately.",
		},
		{
			"step_code": "PHOTO",
			"display_no": "6",
			"section": "Evidence",
			"label": "Photograph the item",
			"label_alt": "आइटम की फोटो लें",
			"response_type": C.PHOTO_ONLY,
			"requires_photo": 1,
			"photo_policy": "Always",
			"min_photos": 1,
			"max_photos": 4,
			"photo_hint": "Whole item in frame. INWARD or OUTWARD is printed across the picture.",
			# Mandatory, unlike the paperwork. The person who can take this picture
			# is standing in front of the item; the person who will need it is
			# reading the record next month.
			"is_mandatory": 1,
			"allow_skip": 0,
			"help_text": (
				"The date, time, place and your name are burned into the picture, along with "
				"the direction — so the photo still says what it is once it has left this record."
			),
		},
		{
			"step_code": "WEIGHT",
			"display_no": "7",
			"section": "Evidence",
			"label": "Weight in KG",
			"label_alt": "वज़न (किलोग्राम)",
			"response_type": C.WEIGHT_PHOTO,
			"unit": "KG",
			"decimals": 2,
			"min_value": 0.01,
			"max_value": 50000,
			"pass_condition": C.WITHIN_RANGE,
			"requires_photo": 1,
			"photo_policy": "Always",
			"min_photos": 1,
			"max_photos": 2,
			"photo_hint": "Point at the scale display so the number is readable.",
			"is_mandatory": 0,
			"allow_skip": 1,
			"skip_reasons": SKIP_REASONS,
			"help_text": (
				"Photograph the scale — the phone reads the number off the display and fills "
				"this in. Check it against the scale and correct it if the reading is off."
			),
		},
	]


#: The direction branch. `SOURCE_IN` shows on an inward run, `SOURCE_OUT` on an
#: outward one — the engine's own visibility conditions, so the branching is a
#: Desk edit and not a code path.
CONDITIONS = [
	{"step_code": "SOURCE_IN", "when_step": "DIRECTION", "operator": "Equals", "value": "INWARD"},
	{"step_code": "SOURCE_OUT", "when_step": "DIRECTION", "operator": "Equals", "value": "OUTWARD"},
]

STAGES = [
	{
		"stage_code": STAGE,
		"label": "Gate",
		"label_alt": "गेट",
		"sequence": 10,
		"screen_grouping": "One Per Screen",
		"instructions": "One item per run. Answer in order — the first question decides the rest.",
	}
]


def execute() -> None:
	_seed_direction_set()
	_seed_entity_type()
	_seed_sources()
	_seed_definition()
	_upgrade_evidence_steps()
	_grant_access()


def _seed_direction_set() -> None:
	if frappe.db.exists("Process Outcome Set", DIRECTION_SET):
		return
	frappe.get_doc(
		{
			"doctype": "Process Outcome Set",
			"set_code": DIRECTION_SET,
			"set_name": "Gate Direction",
			"description": "Inward or outward. Both are a pass — this records a fact, it does not judge one.",
			"is_active": 1,
			"options": [
				{
					"value": "INWARD",
					"label": "Inward",
					"label_alt": "अंदर",
					# Both count as a pass: a direction is a fact about the movement,
					# not a verdict on it. Marking one a fail would quarantine every
					# outward run and score the process into nonsense.
					"is_pass": 1,
					"color": "green",
					"icon": "⬇",
					"sequence": 10,
				},
				{
					"value": "OUTWARD",
					"label": "Outward",
					"label_alt": "बाहर",
					"is_pass": 1,
					"color": "blue",
					"icon": "⬆",
					"sequence": 20,
				},
			],
		}
	).insert(ignore_permissions=True)


def _seed_entity_type() -> None:
	if frappe.db.exists("Process Entity Type", ITEM_ENTITY):
		return
	frappe.get_doc(
		{
			"doctype": "Process Entity Type",
			"entity_code": ITEM_ENTITY,
			"label": "Material Item",
			"label_alt": "सामग्री आइटम",
			"icon": "📦",
			"expected_count": 1,
			"is_scan_enabled": 1,
			# Warn, never block. The same serial legitimately crosses the gate twice
			# on a return-and-redispatch, and a block would make that unrecordable.
			"duplicate_policy": "Warn",
			# No pattern: supplier labels across a bus BOM follow no single shape, and
			# an unmatched payload is stored verbatim rather than rejected. Add
			# patterns per supplier from Desk as they become known.
			"qr_pattern": "",
			"is_active": 1,
		}
	).insert(ignore_permissions=True)


def _seed_sources() -> None:
	for name, source_type, city in SOURCES:
		if frappe.db.exists("Material Source", name):
			continue
		frappe.get_doc(
			{
				"doctype": "Material Source",
				"source_name": name,
				"source_type": source_type,
				"city": city,
				"is_active": 1,
			}
		).insert(ignore_permissions=True)


def _seed_definition() -> None:
	if frappe.db.exists("Process Definition", {"family": FAMILY}):
		return
	if not frappe.db.exists("Process Outcome Set", DIRECTION_SET):
		return

	steps = []
	for index, step in enumerate(_steps()):
		steps.append({**step, "stage": STAGE, "sequence": 10 + index * 10, "is_active": 1, "weight": 1})

	frappe.get_doc(
		{
			"doctype": "Process Definition",
			"process_code": PROCESS_CODE,
			"process_name": "Material Gate — Inward / Outward",
			"family": FAMILY,
			"version": 1,
			"status": C.DEF_DRAFT,
			"description": (
				"One item through the gate: direction, what it is, its serial, where it came "
				"from or is going to, a photograph, and its weight read off the scale."
			),
			"icon": "📦",
			"stage_label": "Stage",
			"subject_label": "Gate Note",
			# The run names itself. A gate clerk has no number to type before the
			# truck is open, and making one up is how two people record the same load.
			"identifier_mode": "Auto Generate",
			"identifier_pattern": "GN-.YYYY.-.#####",
			# Deliberately unscored. Every step here records a fact; none of them is
			# a judgement that could fail, so a score would be a number that is
			# always 100 and means nothing.
			"scoring_enabled": 0,
			"skipped_steps_count_as": "Excluded",
			"allow_offline": 1,
			"allow_resume": 1,
			"expected_minutes": 2,
			"allowed_roles": [
				{"role": "Material Gate Operator"},
				{"role": "Material Supervisor"},
				{"role": "Process Operator"},
			],
			"author_roles": [{"role": "Material Supervisor"}],
			"stages": STAGES,
			"steps": steps,
			"step_conditions": CONDITIONS,
		}
	).insert(ignore_permissions=True)


#: The first people to hold the gate, by phone number — the app's identity, not
#: email. Adding somebody later is a Desk edit on their User record; this list
#: exists so the first migrate lands with the process already usable rather than
#: with nobody able to open it.
INITIAL_OPERATORS = (
	"9936142130",  # Riyaz
	"9936142128",  # Mayank
)

#: What a gate operator needs. `Material Gate Operator` carries the doctype
#: permissions on the register; `Process Operator` is what lets the engine open a
#: run at all. Neither implies the other, and holding one without the other is a
#: tab that answers every tap with an error.
OPERATOR_ROLES = ("Material Gate Operator", "Process Operator")


def _grant_access() -> None:
	"""Give the initial operators what they need to open a run.

	Guarded on existence so a site without these people migrates cleanly, and
	idempotent so a second migrate is a no-op rather than a duplicate role row.
	"""
	for phone in INITIAL_OPERATORS:
		name = frappe.db.get_value("User", {"mobile_no": phone}, "name")
		if not name:
			continue
		user = frappe.get_doc("User", name)
		if user.user_type != "System User":
			# A Website User silently drops Desk roles on save, which is why an
			# earlier grant looked as though it had worked and had not.
			user.user_type = "System User"
		changed = user.has_value_changed("user_type")
		for role in OPERATOR_ROLES:
			if frappe.db.exists("Role", role) and not any(r.role == role for r in user.roles):
				user.append("roles", {"role": role})
				changed = True
		if changed:
			user.save(ignore_permissions=True)
			frappe.clear_cache(user=user.name)


def _upgrade_evidence_steps() -> None:
	"""Add the paperwork step and make the item photo compulsory, once.

	The seeder above only fires when the family has no version at all, which is
	right — it must never overwrite a process the plant has edited. But the first
	cut of this process shipped with the item photo optional and no place for a
	challan, and both were wrong: a gate note whose items were never photographed
	is a list of claims.

	So this is a one-time, self-guarded correction rather than a reseed. It runs
	only while the process is still the untouched v1 draft, and does nothing at
	all once `DOCUMENT` exists — which is also what makes it safe to leave in the
	after_migrate list forever.
	"""
	name = frappe.db.get_value("Process Definition", {"family": FAMILY, "version": 1}, "name")
	if not name:
		return

	definition = frappe.get_doc("Process Definition", name)
	if any(s.step_code == "DOCUMENT" for s in definition.steps):
		return
	# Published versions are frozen by design; a change there is a new version,
	# authored by a human who can see what they are changing.
	if definition.status != C.DEF_DRAFT:
		frappe.logger().info(f"material gate: {name} is {definition.status}; evidence steps left alone")
		return

	by_code = {s.step_code: s for s in definition.steps}
	photo = by_code.get("PHOTO")
	weight = by_code.get("WEIGHT")
	if not photo:
		return

	photo.is_mandatory = 1
	photo.allow_skip = 0
	photo.skip_reasons = None
	photo.display_no = "6"
	if weight:
		weight.display_no = "7"

	document = next(s for s in _steps() if s["step_code"] == "DOCUMENT")
	definition.append(
		"steps",
		{
			**document,
			"stage": STAGE,
			# Between the source step and the item photo, matching the authored order.
			"sequence": photo.sequence - 5,
			"is_active": 1,
			"weight": 1,
		},
	)
	definition.save(ignore_permissions=True)
	frappe.db.commit()  # nosemgrep — after_migrate runs outside a request transaction
	print(f"material gate: {name} — added DOCUMENT step, item photo is now mandatory")
