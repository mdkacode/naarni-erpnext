"""Battery Assembly QC v2 — close the four gaps against the plant's paper sheets.

v1 was a faithful transcription in almost every respect: sections, the sheets'
own numbering (including Installation's 17 → 19 jump), inspection method,
torque bands, and the Max/Min/Difference triples the diagnosis rows ask for. A
line-by-line re-read against the two CSVs found four places where it did not
say what the paper says:

1. **"Mark the bolt head after torquing" was only captured on two of five rows.**
   Installation 4, 6, 8, 12 and 13 all end with that instruction; only 4 and 6
   had the Yes/No step recording it. A marked bolt head is the plant's own
   evidence that a torque wrench was actually applied — capturing it on some
   rows and not others is worse than not capturing it at all, because the gap
   looks like the check was not required.

2. **Before-Installation #9's "insulation resistance > 2 ohm" was never
   measured at that stage.** v1 treated it as a typo for the Installation
   sheet's "> 2 MΩ" and cross-referenced it in help text. But it is a check on
   the *module* before it goes in, and the pack-level check at Installation 16
   is on the assembled battery — finding a bad module after it is bolted down
   is exactly the cost this sheet exists to avoid. Both are now measured.

3. **"Performed by — Employee Name" and "Signature" were missing entirely.**
   Both sheets end with them. The run already records who was logged in, but a
   signature is the operator's own attestation and it is what makes the printed
   certificate mean anything.

4. **Only one of the two header identifiers was captured.** Both sheets head
   with *Battery Serial Number* and *Battery Pack number*. The run identifier
   carries the pack number; the serial had nowhere to go.

Everything else is carried over unchanged by the clone. Seeds as **Draft** —
publishing is deliberate, and publishing v2 retires v1 for every operator at
once.
"""

import frappe

from vehicle_maintenance.process_engine import constants as C

FAMILY = "BATTERY_QC"
SOURCE = f"{FAMILY}-v1"
TARGET = f"{FAMILY}-v2"

CRITICAL_ACTIONS = "CRITICAL_RESPONSE"
SKIP_REASONS = (
	"Part not available\nTool not available\nChecked by another operator\nNot applicable to this variant"
)

# The sheets' own wording, so an operator holding the paper recognises it.
BOLT_MARK_LABEL = "Bolt head marked after torquing?"
BOLT_MARK_LABEL_ALT = "टॉर्क के बाद बोल्ट हेड पर निशान लगाया?"

# Installation rows whose text ends "...and mark the bolt head after torquing",
# mapped to the torque step each one follows.
BOLT_MARK_ROWS = [
	("INS_08_MARK", "8", "INS_08B", "Slave BMS, Bus Bar & Insulation Sheet"),
	("INS_12_MARK", "12", "INS_12", "Top Cover & Labeling"),
	("INS_13_MARK", "13", "INS_13", "Top Cover & Labeling"),
]


def execute() -> None:
	try:
		_seed()
	except Exception:
		frappe.log_error(title="seed_battery_qc_v2", message=frappe.get_traceback())


def _seed() -> None:
	if not frappe.db.exists("Process Definition", SOURCE):
		return
	# Idempotent, and never resurrects a version the plant deleted on purpose:
	# any v2-or-later in this family means this patch has already had its say.
	if frappe.db.get_value("Process Definition", {"family": FAMILY, "version": [">=", 2]}, "name"):
		return

	source = frappe.get_doc("Process Definition", SOURCE)
	doc = frappe.get_doc("Process Definition", source.clone_new_version())

	_add_serial_number(doc)
	_measure_module_insulation(doc)
	_add_missing_bolt_marks(doc)
	_add_signoffs(doc)
	_resequence(doc)

	doc.save(ignore_permissions=True)
	frappe.db.commit()


# ------------------------------------------------------------------ corrections


def _add_serial_number(doc) -> None:
	"""Header: *Battery Serial Number*, alongside the pack number the run carries.

	Text rather than a scan step: the serial is read off the name plate, and the
	standing rule is that nothing about identity is ever mandatory — a worn label
	must not stop the inspection.
	"""
	doc.append(
		"steps",
		{
			"step_code": "BEF_SERIAL",
			"display_no": "—",
			"stage": "BEFORE_INSTALL",
			"section": "Bottom Cooling Plate",
			"label": "Battery serial number",
			"label_alt": "बैटरी सीरियल नंबर",
			"method_label": "Record",
			"response_type": C.TEXT_SHORT,
			"help_text": "From the name plate. Leave blank if the label is unreadable.",
			"is_mandatory": 0,
			"allow_skip": 1,
			"skip_reasons": SKIP_REASONS,
			"weight": 0,
			"expected_seconds": 15,
			"is_active": 1,
		},
	)


def _measure_module_insulation(doc) -> None:
	"""Before-Installation #9's own remark: insulation resistance > 2 MΩ.

	Sits immediately after the #9 visual check, sharing its display number,
	because on the paper it *is* part of row 9 — the remark column against it.
	"""
	doc.append(
		"steps",
		{
			"step_code": "BEF_09_IR",
			"display_no": "9",
			"stage": "BEFORE_INSTALL",
			"section": "Module",
			"label": "Module insulation resistance",
			"label_alt": "मॉड्यूल इन्सुलेशन प्रतिरोध",
			"method_label": "Measurement",
			"response_type": C.NUMBER_IN_RANGE,
			"min_value": 2,
			"max_value": 0,
			"pass_condition": C.GREATER_THAN_MIN,
			"unit": "MΩ",
			"decimals": 2,
			"help_text": (
				"The sheet's remark against row 9. Catching a bad module here is the "
				"whole point of checking before installation — the pack-level reading at "
				"Installation 16 comes after it is bolted down."
			),
			"is_critical": 1,
			"allow_skip": 1,
			"skip_reasons": SKIP_REASONS,
			"weight": 3,
			"expected_seconds": 45,
			"requires_photo": 1,
			"photo_policy": "On Fail",
			"action_set": CRITICAL_ACTIONS,
			"is_active": 1,
		},
	)


def _add_missing_bolt_marks(doc) -> None:
	"""Rows 8, 12 and 13 also say to mark the bolt head. Rows 4 and 6 already do."""
	existing = {s.step_code for s in doc.steps}
	for code, display_no, _after, section in BOLT_MARK_ROWS:
		if code in existing:
			continue
		doc.append(
			"steps",
			{
				"step_code": code,
				"display_no": display_no,
				"stage": "INSTALLATION",
				"section": section,
				"label": BOLT_MARK_LABEL,
				"label_alt": BOLT_MARK_LABEL_ALT,
				"method_label": "Visual",
				"response_type": C.YES_NO,
				"outcome_set": "YES_NO",
				"allow_skip": 1,
				"skip_reasons": SKIP_REASONS,
				"weight": 1,
				"expected_seconds": 10,
				"is_active": 1,
			},
		)


def _add_signoffs(doc) -> None:
	"""Both sheets end with "Performed by — Employee Name" and "Signature"."""
	for stage, code, section in (
		("BEFORE_INSTALL", "BEF_SIGN", "Slave BMS"),
		("INSTALLATION", "INS_SIGN", "Diagnosis Check"),
	):
		doc.append(
			"steps",
			{
				"step_code": code,
				"display_no": "—",
				"stage": stage,
				"section": section,
				"label": "Performed by — sign to confirm every check above",
				"label_alt": "प्रदर्शनकर्ता — ऊपर की सभी जाँच की पुष्टि हेतु हस्ताक्षर करें",
				"method_label": "Sign-off",
				"response_type": C.SIGNATURE,
				"help_text": (
					"Your name and the time are recorded automatically; this is your own "
					"attestation, and it is what the printed certificate carries."
				),
				# Not mandatory, per the standing rule that no gate blocks the work:
				# an unsigned run is visible as unsigned rather than being unfinishable.
				"is_mandatory": 0,
				"allow_skip": 1,
				"skip_reasons": SKIP_REASONS,
				"requires_signature": 1,
				"weight": 0,
				"expected_seconds": 20,
				"is_active": 1,
			},
		)


def _resequence(doc) -> None:
	"""Put the appended steps where the paper puts them.

	Appending leaves the new rows at the end of the table; the operator sees the
	order `sequence` defines, so it has to be rebuilt. Each new step is placed
	relative to an anchor rather than at an absolute index, so this survives any
	edit the plant has already made to v1.
	"""
	after = {
		# new step        -> the step it follows
		"BEF_09_IR": "BEF_09",
		"INS_08_MARK": "INS_08B",
		"INS_12_MARK": "INS_12",
		"INS_13_MARK": "INS_13",
	}
	first = {"BEF_SERIAL": "BEFORE_INSTALL"}
	last = {"BEF_SIGN": "BEFORE_INSTALL", "INS_SIGN": "INSTALLATION"}

	by_stage: dict[str, list] = {}
	for step in sorted(doc.steps, key=lambda s: int(s.sequence or 0)):
		by_stage.setdefault(step.stage, []).append(step)

	moved = set(after) | set(first) | set(last)

	ordered: list = []
	for stage, steps in by_stage.items():
		body = [s for s in steps if s.step_code not in moved]

		for code, target_stage in first.items():
			if target_stage == stage:
				match = next((s for s in steps if s.step_code == code), None)
				if match:
					body.insert(0, match)

		for code, anchor in after.items():
			match = next((s for s in steps if s.step_code == code), None)
			if not match:
				continue
			index = next((i for i, s in enumerate(body) if s.step_code == anchor), None)
			body.insert(index + 1 if index is not None else len(body), match)

		for code, target_stage in last.items():
			if target_stage == stage:
				match = next((s for s in steps if s.step_code == code), None)
				if match:
					body.append(match)

		ordered.extend(body)

	for index, step in enumerate(ordered, start=1):
		step.sequence = index * 10
		step.idx = index
	doc.set("steps", ordered)
