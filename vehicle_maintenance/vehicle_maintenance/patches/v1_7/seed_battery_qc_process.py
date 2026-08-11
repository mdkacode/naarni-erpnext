"""Seed Battery Assembly QC as a Process Definition — data, not code.

This is the whole point of the engine: the plant's two paper sheets become one
`Process Definition` record. Nothing about batteries appears anywhere in the
app's source; changing a check from here on is a Desk edit.

Transcribed from the supplied sheets, with four corrections applied (all
confirmed):

* **Installation has 22 checks, not 23.** The sheet is numbered 1-23 but has no
  #18 — it jumps 17 → 19. The display numbers below preserve the sheet's own
  numbering so an operator holding the paper still recognises the flow.
* **Insulation resistance is MΩ, not ohm.** The Before-Installation remark said
  "> 2 ohm", which is a typo for the "> 2 MΩ" on the Installation sheet.
* **Bus bar torque is split.** Installation #8 read "15 & 10 Nm" — two different
  bolt groups cannot share one tolerance band, so it is two steps, 8a and 8b.
* **Module voltage is three steps.** "Check all 3 module voltages" needs three
  values, so it is 10a/10b/10c with the same > 52 V band.

Runs idempotently: it only creates the process if the family has no version yet,
so a `bench migrate` never overwrites a version the plant has since edited.
"""

import frappe

from vehicle_maintenance.process_engine import constants as C

FAMILY = "BATTERY_QC"
PROCESS_CODE = f"{FAMILY}-v1"

# Shared vocabularies and automations, seeded by `seed_process_engine`. Steps
# link to these rather than each carrying its own copy, so retranslating "Fail"
# or changing who gets alerted on a critical failure is one edit, not fifty.
PFN = "PASS_FAIL_NA"
PFN_MINOR = "PASS_FAIL_NA_MINOR"
CRITICAL_ACTIONS = "CRITICAL_RESPONSE"

SKIP_REASONS = (
	"Part not available\nTool not available\nChecked by another operator\nNot applicable to this variant"
)


def _visual(code, no, section, label, label_alt=None, critical=False, **kw):
	"""A Pass/Fail/NA visual check — the most common shape on both sheets."""
	return {
		"step_code": code,
		"display_no": no,
		"section": section,
		"label": label,
		"label_alt": label_alt,
		"method_label": "Visual",
		"response_type": C.CHOICE,
		"outcome_set": PFN if critical else PFN_MINOR,
		"is_critical": 1 if critical else 0,
		"allow_skip": 1,
		"skip_reasons": SKIP_REASONS,
		"weight": 2 if critical else 1,
		"expected_seconds": 15,
		"requires_photo": 1,
		"photo_policy": "On Fail",
		"action_set": CRITICAL_ACTIONS if critical else None,
		**kw,
	}


def _functional(code, no, section, label, label_alt=None, critical=True, **kw):
	step = _visual(code, no, section, label, label_alt, critical, **kw)
	step["method_label"] = "Functional"
	step["expected_seconds"] = 30
	return step


def _torque(code, no, section, label, nominal, tol, label_alt=None, **kw):
	"""A torque check: numeric with a tolerance band, plus the bolt-head mark.

	The sheet asks for the bolt head to be marked after torquing; that is a
	separate observable fact, so it gets its own Yes/No step rather than being
	buried in the instruction text.
	"""
	return {
		"step_code": code,
		"display_no": no,
		"section": section,
		"label": label,
		"label_alt": label_alt,
		"method_label": "Torque",
		"response_type": C.NUMBER_WITH_TOLERANCE,
		"nominal_value": nominal,
		"tolerance": tol,
		"unit": "Nm",
		"decimals": 1,
		"is_critical": 1,
		"allow_skip": 1,
		"skip_reasons": SKIP_REASONS,
		"weight": 3,
		"expected_seconds": 40,
		"requires_photo": 1,
		"photo_policy": "On Fail",
		"action_set": CRITICAL_ACTIONS,
		**kw,
	}


def _measure(code, no, section, label, unit, lo=None, hi=None, label_alt=None, critical=True, **kw):
	"""A measured value judged against a band.

	`pass_condition` is what decides which bound applies — a Float of 0 is
	otherwise indistinguishable from an unset one.
	"""
	if lo is not None and hi is not None:
		condition = C.WITHIN_RANGE
	elif lo is not None:
		condition = C.GREATER_THAN_MIN
	elif hi is not None:
		condition = C.LESS_THAN_MAX
	else:
		condition = C.ANY_VALUE
	return {
		"step_code": code,
		"display_no": no,
		"section": section,
		"label": label,
		"label_alt": label_alt,
		"method_label": "Measurement",
		"response_type": C.NUMBER_IN_RANGE if condition != C.ANY_VALUE else C.NUMBER,
		"min_value": lo or 0,
		"max_value": hi or 0,
		"pass_condition": condition,
		"unit": unit,
		"decimals": 2,
		"is_critical": 1 if critical else 0,
		"allow_skip": 1,
		"skip_reasons": SKIP_REASONS,
		"weight": 3 if critical else 1,
		"expected_seconds": 45,
		"requires_photo": 1,
		"photo_policy": "On Fail",
		"action_set": CRITICAL_ACTIONS if critical else None,
		**kw,
	}


def _yes_no(code, no, section, label, label_alt=None, **kw):
	return {
		"step_code": code,
		"display_no": no,
		"section": section,
		"label": label,
		"label_alt": label_alt,
		"method_label": "Visual",
		"response_type": C.YES_NO,
		"outcome_set": "YES_NO",
		"allow_skip": 1,
		"skip_reasons": SKIP_REASONS,
		"weight": 1,
		"expected_seconds": 10,
		**kw,
	}


def _computed(code, no, section, label, expression, unit, hi=None, label_alt=None, **kw):
	"""A derived value — the spread the paper sheet asks the operator to work out."""
	return {
		"step_code": code,
		"display_no": no,
		"section": section,
		"label": label,
		"label_alt": label_alt,
		"method_label": "Calculated",
		"response_type": C.COMPUTED,
		"computed_expression": expression,
		"unit": unit,
		"max_value": hi or 0,
		"pass_condition": C.LESS_THAN_MAX if hi is not None else C.ANY_VALUE,
		"decimals": 3,
		"weight": 2,
		"is_critical": 1 if hi is not None else 0,
		"expected_seconds": 5,
		"action_set": CRITICAL_ACTIONS if hi is not None else None,
		**kw,
	}


def _scan(code, no, section, label, entity, count, label_alt=None, **kw):
	"""A component identity capture. Never mandatory — that is the standing rule."""
	return {
		"step_code": code,
		"display_no": no,
		"section": section,
		"label": label,
		"label_alt": label_alt,
		"method_label": "Scan",
		"response_type": C.SCAN,
		"requires_scan": 1,
		"scan_entity_type": entity,
		"scan_count": count,
		"is_mandatory": 0,
		"allow_skip": 1,
		"skip_reasons": "Label missing\nLabel damaged\nScanner not working\nEntered manually",
		"weight": 1,
		"expected_seconds": 20,
		**kw,
	}


# --------------------------------------------------------------- Stage 1 of 2
# 17 checks, 5 sections. The sheet's own grouping is 2-5 checks per section,
# which is exactly the chunk size the anti-pencil-whipping research prescribes —
# so each section becomes one screen with no restructuring needed.

BEFORE_STEPS = [
	_scan(
		"BEF_SCAN_PLATE",
		"—",
		"Bottom Cooling Plate",
		"Scan the cooling plate label",
		"COOLING_PLATE",
		1,
		"कूलिंग प्लेट लेबल स्कैन करें",
	),
	_visual(
		"BEF_01",
		"1",
		"Bottom Cooling Plate",
		"Check cooling plate welding quality and joints",
		"वेल्डिंग गुणवत्ता और जोड़ जाँचें",
		critical=True,
	),
	_visual(
		"BEF_02",
		"2",
		"Bottom Cooling Plate",
		"Check the cooling plate for any damage or bending",
		"कूलिंग प्लेट में क्षति या मुड़ाव जाँचें",
		critical=True,
	),
	_visual(
		"BEF_03",
		"3",
		"Bottom Cooling Plate",
		"Check all mounting threads in the cooling plate for good condition",
		"सभी माउंटिंग थ्रेड की स्थिति जाँचें",
	),
	_functional(
		"BEF_04",
		"4",
		"Bottom Cooling Plate",
		"Check the cooling plate for air leakage",
		"कूलिंग प्लेट में हवा लीक जाँचें",
	),
	_visual(
		"BEF_05",
		"5",
		"Bottom Cooling Plate",
		"Check the TIM pad is properly pasted",
		"TIM पैड सही से लगा है जाँचें",
		critical=True,
	),
	_scan(
		"BEF_SCAN_CASE", "—", "Upper Case", "Scan the upper case label", "UPPER_CASE", 1, "ऊपरी केस लेबल स्कैन करें"
	),
	_visual(
		"BEF_06",
		"6",
		"Upper Case",
		"Check the upper case for any dent or damage",
		"ऊपरी केस में डेंट या क्षति जाँचें",
		photo_hint="Also capture the battery name plate",
	),
	_visual(
		"BEF_07",
		"7",
		"Upper Case",
		"Check the upper case inner insulation is properly placed",
		"ऊपरी केस की अंदरूनी इन्सुलेशन जाँचें",
		critical=True,
	),
	_visual(
		"BEF_08",
		"8",
		"Upper Case",
		"Check name plate and identification stickers are available on the upper case",
		"नेम प्लेट और पहचान स्टिकर जाँचें",
		requires_photo=1,
		photo_policy="Always",
		photo_hint="Close-up of the name plate",
	),
	_scan(
		"BEF_SCAN_MODULES",
		"—",
		"Module",
		"Scan all 3 cell modules",
		"CELL_MODULE",
		3,
		"तीनों सेल मॉड्यूल स्कैन करें",
		help_text=(
			"Cells are traced through their module — the cell-to-module link is already recorded upstream, "
			"and step 21 reads all 48 cell voltages from the diagnosis laptop. Scanning cells individually "
			"would be 576 scans per bus for a link that is already derivable."
		),
	),
	_functional(
		"BEF_09",
		"9",
		"Module",
		"Check the module for damage, leakage, or loose connections",
		"मॉड्यूल में क्षति, रिसाव या ढीला कनेक्शन जाँचें",
		help_text="Insulation resistance must be greater than 2 MΩ — measured at Installation step 16.",
	),
	_measure("BEF_10A", "10a", "Module", "Module 1 voltage", "V", lo=52, label_alt="मॉड्यूल 1 वोल्टेज"),
	_measure("BEF_10B", "10b", "Module", "Module 2 voltage", "V", lo=52, label_alt="मॉड्यूल 2 वोल्टेज"),
	_measure("BEF_10C", "10c", "Module", "Module 3 voltage", "V", lo=52, label_alt="मॉड्यूल 3 वोल्टेज"),
	_visual("BEF_11", "11", "Module", "Check the heater and wiring connections", "हीटर और वायरिंग कनेक्शन जाँचें"),
	_visual(
		"BEF_12",
		"12",
		"Bus Bar and Connector Plate",
		"Check the insulation of all positive and negative bus bars is in good condition",
		"सभी बस बार की इन्सुलेशन जाँचें",
		critical=True,
	),
	_visual(
		"BEF_13",
		"13",
		"Bus Bar and Connector Plate",
		"Check the bus bar condition — ensure it is not bent or damaged",
		"बस बार मुड़ा या क्षतिग्रस्त नहीं है जाँचें",
		critical=True,
	),
	_visual(
		"BEF_14",
		"14",
		"Bus Bar and Connector Plate",
		"Check the connector plate positive and negative terminal fitment and condition",
		"कनेक्टर प्लेट टर्मिनल फिटमेंट जाँचें",
		critical=True,
	),
	_visual(
		"BEF_15",
		"15",
		"Bus Bar and Connector Plate",
		"Check the MSD base fitment and condition",
		"MSD बेस फिटमेंट और स्थिति जाँचें",
		critical=True,
	),
	_scan("BEF_SCAN_BMS", "—", "Slave BMS", "Scan the Slave BMS label", "SLAVE_BMS", 1, "स्लेव BMS लेबल स्कैन करें"),
	_visual(
		"BEF_16", "16", "Slave BMS", "Check the Slave BMS for any damage", "स्लेव BMS में क्षति जाँचें", critical=True
	),
	_visual("BEF_17", "17", "Slave BMS", "Check the Slave BMS plate mounting", "स्लेव BMS प्लेट माउंटिंग जाँचें"),
]

# --------------------------------------------------------------- Stage 2 of 2
# 22 checks, 6 sections. Numbering follows the paper sheet, including its jump
# from 17 to 19.

INSTALL_STEPS = [
	_visual(
		"INS_01",
		"1",
		"Module & Cooling Plate Installation",
		"Check the module is properly placed on the TIM pad",
		"मॉड्यूल TIM पैड पर सही से रखा है जाँचें",
		critical=True,
	),
	_visual(
		"INS_02",
		"2",
		"Module & Cooling Plate Installation",
		"Check the module-to-cooling plate bolt holes are aligned",
		"बोल्ट होल संरेखित हैं जाँचें",
	),
	_visual(
		"INS_03",
		"3",
		"Module & Cooling Plate Installation",
		"Check the sequence of all 3 modules (1, 2, 3)",
		"तीनों मॉड्यूल का क्रम जाँचें",
		critical=True,
	),
	_torque(
		"INS_04",
		"4",
		"Module & Cooling Plate Installation",
		"Module mounting bolt torque",
		10,
		1,
		"मॉड्यूल माउंटिंग बोल्ट टॉर्क",
	),
	_yes_no(
		"INS_04M",
		"4",
		"Module & Cooling Plate Installation",
		"Bolt head marked after torquing?",
		"टॉर्क के बाद बोल्ट हेड मार्क किया?",
	),
	_visual(
		"INS_05",
		"5",
		"Module & Cooling Plate Installation",
		"Check the heater wiring connections between modules",
		"मॉड्यूल के बीच हीटर वायरिंग जाँचें",
	),
	_torque(
		"INS_06",
		"6",
		"Slave BMS, Bus Bar & Insulation Sheet",
		"Slave BMS mounting bolt torque",
		10,
		1,
		"स्लेव BMS माउंटिंग बोल्ट टॉर्क",
	),
	_yes_no(
		"INS_06M",
		"6",
		"Slave BMS, Bus Bar & Insulation Sheet",
		"Bolt head marked after torquing?",
		"टॉर्क के बाद बोल्ट हेड मार्क किया?",
	),
	_visual(
		"INS_07",
		"7",
		"Slave BMS, Bus Bar & Insulation Sheet",
		"Check all 3 module-to-Slave BMS connectors are properly connected",
		"तीनों मॉड्यूल-BMS कनेक्टर जुड़े हैं जाँचें",
		critical=True,
	),
	# The sheet's "15 & 10 Nm" is two bolt groups; one step cannot carry two bands.
	_torque(
		"INS_08A",
		"8a",
		"Slave BMS, Bus Bar & Insulation Sheet",
		"Bus bar connection bolt torque — 15 Nm group",
		15,
		1,
		"बस बार बोल्ट टॉर्क — 15 Nm",
	),
	_torque(
		"INS_08B",
		"8b",
		"Slave BMS, Bus Bar & Insulation Sheet",
		"Bus bar connection bolt torque — 10 Nm group",
		10,
		1,
		"बस बार बोल्ट टॉर्क — 10 Nm",
	),
	_visual(
		"INS_09",
		"9",
		"Slave BMS, Bus Bar & Insulation Sheet",
		"Check the bus bar is connected to the main positive and negative terminals",
		"बस बार मुख्य टर्मिनल से जुड़ा है जाँचें",
		critical=True,
	),
	_visual(
		"INS_10",
		"10",
		"Slave BMS, Bus Bar & Insulation Sheet",
		"Check insulation sheets on all 3 modules are properly fixed and secured; no excess cable tie length",
		"इन्सुलेशन शीट सुरक्षित हैं, केबल टाई अतिरिक्त नहीं",
	),
	_visual(
		"INS_11",
		"11",
		"Top Cover & Labeling",
		"Check the top cover is properly closed",
		"टॉप कवर सही से बंद है जाँचें",
	),
	_torque(
		"INS_12",
		"12",
		"Top Cover & Labeling",
		"Top cover to cooling plate bolt torque",
		6,
		0.5,
		"टॉप कवर–कूलिंग प्लेट बोल्ट टॉर्क",
	),
	_torque(
		"INS_13",
		"13",
		"Top Cover & Labeling",
		"Top cover to connector plate bolt torque",
		6,
		0.5,
		"टॉप कवर–कनेक्टर प्लेट बोल्ट टॉर्क",
	),
	_visual(
		"INS_14",
		"14",
		"Top Cover & Labeling",
		"Check labeling is consistent across modules, Slave BMS, top cover and cooling plate",
		"सभी लेबल एक समान हैं जाँचें",
		requires_photo=1,
		photo_policy="Always",
		photo_hint="Photo showing the labels together",
	),
	_measure(
		"INS_15", "15", "Voltage & Resistance", "Battery pack voltage", "V", lo=156, label_alt="बैटरी पैक वोल्टेज"
	),
	_measure(
		"INS_16",
		"16",
		"Voltage & Resistance",
		"Battery insulation resistance",
		"MΩ",
		lo=2,
		label_alt="बैटरी इन्सुलेशन प्रतिरोध",
	),
	_functional(
		"INS_17", "17", "Air Leak Testing", "Check the upper cover for air leakage", "ऊपरी कवर में हवा लीक जाँचें"
	),
	_functional(
		"INS_19", "19", "Air Leak Testing", "Check the coolant plate for air leakage", "कूलेंट प्लेट में हवा लीक जाँचें"
	),
	_functional(
		"INS_20",
		"20",
		"Diagnosis Check",
		"Check the BMU number in the diagnosis laptop matches the battery number",
		"BMU नंबर बैटरी नंबर से मेल खाता है जाँचें",
		requires_photo=1,
		photo_policy="Always",
		photo_hint="Diagnosis laptop screen",
	),
	_measure(
		"INS_21_MAX",
		"21",
		"Diagnosis Check",
		"Highest cell voltage (of 48)",
		"V",
		hi=4.25,
		label_alt="सबसे अधिक सेल वोल्टेज",
	),
	_measure(
		"INS_21_MIN",
		"21",
		"Diagnosis Check",
		"Lowest cell voltage (of 48)",
		"V",
		lo=3.0,
		label_alt="सबसे कम सेल वोल्टेज",
	),
	_computed(
		"INS_21_DIFF",
		"21",
		"Diagnosis Check",
		"Cell voltage spread",
		"DIFF(INS_21_MAX, INS_21_MIN)",
		"V",
		hi=0.05,
		label_alt="सेल वोल्टेज अंतर",
	),
	_measure(
		"INS_22_MAX",
		"22",
		"Diagnosis Check",
		"Highest temperature (of 9 sensors)",
		"°C",
		hi=45,
		label_alt="सबसे अधिक तापमान",
	),
	_measure(
		"INS_22_MIN",
		"22",
		"Diagnosis Check",
		"Lowest temperature (of 9 sensors)",
		"°C",
		label_alt="सबसे कम तापमान",
		critical=False,
	),
	_computed(
		"INS_22_DIFF",
		"22",
		"Diagnosis Check",
		"Temperature spread",
		"DIFF(INS_22_MAX, INS_22_MIN)",
		"°C",
		hi=5,
		label_alt="तापमान अंतर",
	),
	_functional(
		"INS_23",
		"23",
		"Diagnosis Check",
		"Check for any fault codes in the diagnosis laptop",
		"डायग्नोसिस लैपटॉप में फॉल्ट कोड जाँचें",
		requires_photo=1,
		photo_policy="On Fail",
	),
]

STAGES = [
	{
		"stage_code": "BEFORE_INSTALL",
		"label": "Before Installation",
		"label_alt": "इंस्टॉलेशन से पहले",
		"sequence": 10,
		"screen_grouping": "By Section",
		"blocks_next_on_critical": 1,
		"instructions": "Inspect the parts before you build with them. A defect caught here saves the whole pack's assembly labour.",
	},
	{
		"stage_code": "INSTALLATION",
		"label": "Installation",
		"label_alt": "इंस्टॉलेशन",
		"sequence": 20,
		"screen_grouping": "By Section",
		"requires_second_signoff": 1,
		"second_signoff_role": "Process Verifier",
		"blocks_next_on_critical": 1,
		"instructions": "Torque values are marked on each step. Mark every bolt head after torquing.",
	},
]


def _sequenced(steps: list[dict], stage: str, start: int) -> list[dict]:
	out = []
	for idx, step in enumerate(steps):
		out.append({**step, "stage": stage, "sequence": start + idx * 10})
	return out


def execute() -> None:
	# Only seed when the family has no version at all — never overwrite a
	# process the plant has since edited or published a new version of.
	if frappe.db.exists("Process Definition", {"family": FAMILY}):
		return
	if not frappe.db.exists("Process Entity Type", "CELL_MODULE"):
		# Masters seed first; if they are missing this migrate ran out of order.
		return

	definition = frappe.get_doc(
		{
			"doctype": "Process Definition",
			"process_code": PROCESS_CODE,
			"process_name": "Battery Assembly QC",
			"family": FAMILY,
			"version": 1,
			"status": C.DEF_DRAFT,
			"description": "Pre-installation and installation quality checks for a 3-module, 48-cell traction battery pack.",
			"icon": "🔋",
			# "Module" is what the plant calls a stage. The schema says Stage
			# because Frappe already has Module Def and a pack physically
			# contains 3 cell modules — one word for three things costs forever.
			"stage_label": "Module",
			"subject_label": "Battery Pack",
			"identifier_mode": "Scan QR",
			"scoring_enabled": 1,
			"scoring_mode": "Weighted",
			"pass_threshold_pct": 90,
			"critical_fails_allowed": 0,
			"skipped_steps_count_as": "Excluded",
			"allow_offline": 1,
			"allow_resume": 1,
			"expected_minutes": 30,
			"default_brand": "SKYWORTH" if frappe.db.exists("Report Brand Profile", "SKYWORTH") else None,
			"allowed_roles": [{"role": "Process Operator"}, {"role": "Battery QA Admin"}],
			"author_roles": [{"role": "Battery QA Admin"}],
			"stages": STAGES,
			"steps": (
				_sequenced(BEFORE_STEPS, "BEFORE_INSTALL", 10)
				+ _sequenced(INSTALL_STEPS, "INSTALLATION", 1000)
			),
		}
	)
	definition.insert(ignore_permissions=True)
	frappe.db.commit()
