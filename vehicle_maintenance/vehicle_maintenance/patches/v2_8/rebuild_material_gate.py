"""Version 2 of the Material Gate — the six questions the gate actually asks.

Version 1 asked seven, in an order nobody at a gate thinks in: it wanted the
item's own serial before it knew what the item was, and it asked "where did this
come from" as a separate master-data lookup when the answer is already printed
on the challan in the operator's other hand. It was never published, so nobody
had to live with it; this replaces it before anybody does.

The order here is the order of the work. A truck arrives, so the direction is
known first. The paperwork is in hand, so its number goes in next. The store
knows which bus the parts are for, so the chassis is named before the parts are
picked. Then the part, its photograph, its weight — and a last screen that reads
the whole lot back before it is committed.

**One run is one item**, unchanged: forty items on a truck are forty short runs
several people can work at once, not one clerk's forty-minute form.

Authored as a new version rather than an edit to v1: a definition is a record
somebody can change in Desk, and a patch that rewrites one in place would undo
their change on the next migrate. v1 is retired instead, so the family has one
live version and the old shape stays visible in its history.
"""

from __future__ import annotations

import frappe
from frappe.utils import cint

from vehicle_maintenance.patches.v2_7.seed_material_gate_process import (
	DIRECTION_SET,
	FAMILY,
	_grant_access,
)
from vehicle_maintenance.process_engine import constants as C

PROCESS_CODE = f"{FAMILY}-v2"
VERSION = 2

STAGE = "GATE"

#: The chassis a part is going into. Its own entity type rather than the item
#: one, because a chassis QR and a component label are different populations and
#: sharing a type would make every duplicate warning meaningless.
CHASSIS_ENTITY = "BUS_CHASSIS"

#: Reasons an operator may skip. Each one is a real thing that happens at a gate,
#: which is the point — a list that does not describe reality trains people to
#: pick the first entry.
SKIP_DOCUMENT = "Documents not with the truck\nDocuments already filed\nNot applicable"
SKIP_CHASSIS = "Going to general stock\nChassis not allocated yet\nNot for a specific bus"
SKIP_WEIGHT = "No weighbridge available\nItem too large to weigh\nWeight on the challan"


def _steps() -> list[dict]:
	"""The gate, in order. One screen each."""
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
				"Answer this first — it is printed across every photograph you take on this "
				"entry, so the picture still says which way the item was going once it has "
				"left this record."
			),
		},
		{
			"step_code": "DOC_NO",
			"display_no": "2",
			"section": "Paperwork",
			"label": "Document number, if there is one",
			"label_alt": "दस्तावेज़ नंबर (यदि हो)",
			"response_type": C.TEXT_SHORT,
			# Deliberately not `requires_photo`. The camera is offered on every
			# step; demanding one here would block a clerk holding goods whose
			# challan is following by email, and a clerk who cannot record the
			# load writes it on a scrap of paper instead.
			"photo_hint": "Invoice, delivery challan, e-way bill or gate pass.",
			"is_mandatory": 0,
			"allow_skip": 1,
			"skip_reasons": SKIP_DOCUMENT,
			"help_text": (
				"Type the challan or invoice number, and photograph the paper with the camera "
				"below. Skip it if the paperwork is coming separately."
			),
		},
		{
			"step_code": "CHASSIS",
			"display_no": "3",
			"section": "Used for",
			"label": "Which chassis is this for?",
			"label_alt": "यह किस चेसिस के लिए है?",
			"response_type": C.SCAN,
			"requires_scan": 1,
			"scan_entity_type": CHASSIS_ENTITY,
			"scan_count": 1,
			# Mandatory *and* skippable: the question is always asked, and a skip
			# records why rather than leaving a blank nobody can interpret later.
			"is_mandatory": 1,
			"allow_skip": 1,
			"skip_reasons": SKIP_CHASSIS,
			"help_text": (
				"Scan the chassis QR, or type the number. This is what makes “which parts "
				"went into this bus” a question the register can answer."
			),
		},
		{
			"step_code": "ITEM",
			"display_no": "4",
			"section": "Item",
			"label": "Which part?",
			"label_alt": "कौन सा पुर्ज़ा?",
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
			"step_code": "PHOTO",
			"display_no": "5",
			"section": "Item",
			"label": "Photograph the part",
			"label_alt": "पुर्ज़े की फोटो लें",
			"response_type": C.PHOTO_ONLY,
			"requires_photo": 1,
			"photo_policy": "Always",
			"min_photos": 1,
			"max_photos": 4,
			"photo_hint": "Whole part in frame. Tap a picture to see it full size.",
			# Required, with no skip. The person who can take this photograph is
			# standing in front of the part; the person who will need it is reading
			# the register next month.
			"is_mandatory": 1,
			"allow_skip": 0,
			"help_text": (
				"The date, time, place, your name and the direction are burned into the "
				"picture. Tap it afterwards to check it came out."
			),
		},
		{
			"step_code": "WEIGHT",
			"display_no": "6",
			"section": "Item",
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
			"skip_reasons": SKIP_WEIGHT,
			"help_text": (
				"Photograph the scale — the phone reads the number off the display and fills "
				"this in. Check it against the scale and correct it if the reading is off."
			),
		},
		{
			"step_code": "VERIFY",
			"display_no": "7",
			"section": "Confirm",
			"label": "Check this over before you finish",
			"label_alt": "पूरा करने से पहले जाँच लें",
			"response_type": C.REVIEW,
			"is_mandatory": 1,
			"allow_skip": 0,
			"help_text": (
				"Everything you entered, with the photographs. Tap any line to go back and "
				"change it. Nothing reaches the register until you confirm."
			),
		},
	]


STAGES = [
	{
		"stage_code": STAGE,
		"label": "Gate",
		"label_alt": "गेट",
		"sequence": 10,
		"screen_grouping": "One Per Screen",
		"instructions": "One part per entry. Six questions, then a check.",
	}
]


#: What the app build that ships with this process can render. Level 3 is the
#: review screen; level 2 was the weight-from-photo step.
APP_CAPABILITY = 3


def execute() -> None:
	_seed_chassis_entity()
	created = _seed_v2()
	if created:
		_retire_v1()
	_raise_app_capability()
	_grant_access()


def _seed_chassis_entity() -> None:
	if frappe.db.exists("Process Entity Type", CHASSIS_ENTITY):
		return
	frappe.get_doc(
		{
			"doctype": "Process Entity Type",
			"entity_code": CHASSIS_ENTITY,
			"label": "Bus Chassis",
			"label_alt": "बस चेसिस",
			"icon": "🚌",
			"expected_count": 1,
			"is_scan_enabled": 1,
			# A repeat is the normal case here, not an error: one chassis takes
			# delivery of forty parts, so warning on the second would train the
			# operator to dismiss the warning by the third.
			"duplicate_policy": "Ignore",
			# No pattern. Chassis numbers at the two plants follow different
			# shapes and an unmatched payload is stored verbatim rather than
			# rejected; add patterns from Desk once they are known.
			"qr_pattern": "",
			"is_active": 1,
		}
	).insert(ignore_permissions=True)


def _seed_v2() -> bool:
	"""Author and publish v2. Returns whether it created anything.

	Guarded on the exact process code rather than on the family, so an
	administrator who later authors a v3 keeps it: this sees v2 already present
	and does nothing, including not re-publishing over their version.
	"""
	if frappe.db.exists("Process Definition", PROCESS_CODE):
		return False
	if not frappe.db.exists("Process Outcome Set", DIRECTION_SET):
		# v2_7 seeds it. If that has not run, neither should this.
		return False

	steps = [
		{**step, "stage": STAGE, "sequence": 10 + index * 10, "is_active": 1, "weight": 1}
		for index, step in enumerate(_steps())
	]

	definition = frappe.get_doc(
		{
			"doctype": "Process Definition",
			"process_code": PROCESS_CODE,
			"process_name": "Material Gate — Inward / Outward",
			"family": FAMILY,
			"version": VERSION,
			"status": C.DEF_DRAFT,
			"description": (
				"One part across the gate: which way it is going, the document number, the "
				"chassis it is for, what it is, a photograph, its weight — then a check."
			),
			"icon": "📦",
			"stage_label": "Stage",
			"subject_label": "Gate Note",
			# The entry names itself. A gate clerk has no number to type before
			# the truck is open, and inventing one is how two people record the
			# same load twice.
			"identifier_mode": "Auto Generate",
			"identifier_pattern": "GN-.YYYY.-.#####",
			# Deliberately unscored. Every step records a fact; none is a
			# judgement that could fail, so a score would be a number that is
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
		}
	).insert(ignore_permissions=True)

	# Published here rather than left for somebody to remember. v1 was seeded as
	# a Draft and never published, and `api.process` only lists Published
	# definitions — so the process existed, was deployed, and was invisible to
	# every phone. A seeded process that nobody can open is not a seeded process.
	definition.publish()
	frappe.db.commit()  # nosemgrep — after_migrate runs outside a request transaction
	print(f"material gate: {PROCESS_CODE} authored and published")
	return True


def _retire_v1() -> None:
	"""Take the old shape out of circulation, keeping it readable.

	Not deleted: runs recorded against it still point at it, and a definition is
	how their answers are read back.
	"""
	name = frappe.db.get_value("Process Definition", {"family": FAMILY, "version": 1}, "name")
	if not name:
		return
	if frappe.db.get_value("Process Definition", name, "status") == C.DEF_RETIRED:
		return
	frappe.db.set_value("Process Definition", name, "status", C.DEF_RETIRED)
	frappe.cache().delete_key(f"process_engine:def:{name}")
	frappe.db.commit()  # nosemgrep — after_migrate runs outside a request transaction
	print(f"material gate: {name} retired in favour of {PROCESS_CODE}")


def _raise_app_capability() -> None:
	"""Tell the engine what the fleet's app can draw. Raises only, never lowers.

	This is the number Desk lints a process against before it is published. It
	is a claim about the handsets, so it is only ever moved up alongside a build
	that ships the new step types — and never down, because an older process
	that already published against a higher level would start warning falsely.
	"""
	current = cint(frappe.db.get_single_value("Process Engine Settings", "app_step_type_capability") or 1)
	if current >= APP_CAPABILITY:
		return
	settings = frappe.get_single("Process Engine Settings")
	settings.app_step_type_capability = APP_CAPABILITY
	settings.save(ignore_permissions=True)
	frappe.db.commit()  # nosemgrep — after_migrate runs outside a request transaction
	print(f"process engine: app step-type capability {current} → {APP_CAPABILITY}")
