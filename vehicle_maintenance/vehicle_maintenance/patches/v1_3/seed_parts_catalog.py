"""Seed a starter Parts catalog for commercial electric buses.

Idempotent: Part Groups and Parts are created only when missing (matched by name
/ part_code), so it never duplicates and never overwrites the customer's own
edits. Safe to re-run on every `bench migrate`. This gives a usable baseline; the
real catalog is loaded via Data Import (CSV/XLSX) — templates in `sample_imports/`.
"""

import frappe

# (part_group_name, bus_system)
PART_GROUPS = [
	("HV Battery Pack", "Battery & BMS"),
	("Battery Management System", "Battery & BMS"),
	("Traction Motor", "Powertrain"),
	("Motor Controller / Inverter", "Powertrain"),
	("DC-DC Converter", "Electrical & Wiring"),
	("On-board Charger", "Electrical & Wiring"),
	("Thermal / Cooling System", "Thermal Management"),
	("Brake System", "Brakes"),
	("Air Suspension", "Suspension & Steering"),
	("Steering System", "Suspension & Steering"),
	("HVAC Unit", "HVAC"),
	("Pneumatic Doors", "Doors & Pneumatics"),
	("Tyres & Wheels", "Tyres & Wheels"),
	("Lighting", "Electrical & Wiring"),
	("VCU / Software", "Software & Firmware"),
]

# (part_code, part_name, part_group, stock_uom, standard_cost, manufacturer, lead_time_days)
PARTS = [
	("BAT-MOD-001", "HV Battery Module 3.5kWh", "HV Battery Pack", "Nos", 95000, "CATL", 21),
	("BAT-CON-002", "HV Battery Contactor 500A", "HV Battery Pack", "Nos", 8500, "TE Connectivity", 14),
	("BMS-SLAVE-001", "BMS Slave Board", "Battery Management System", "Nos", 6200, "Inhouse", 10),
	("BMS-MAIN-002", "BMS Master Controller", "Battery Management System", "Nos", 22000, "Inhouse", 14),
	("MOT-TRC-001", "Traction Motor 150kW PMSM", "Traction Motor", "Nos", 185000, "Dana TM4", 30),
	("MOT-BRG-002", "Motor Bearing Set", "Traction Motor", "Set", 4500, "SKF", 7),
	("INV-CTRL-001", "Motor Controller 150kW", "Motor Controller / Inverter", "Nos", 78000, "Bosch", 21),
	("INV-IGBT-002", "IGBT Module", "Motor Controller / Inverter", "Nos", 14500, "Infineon", 18),
	("DCDC-24V-001", "DC-DC Converter 24V 3kW", "DC-DC Converter", "Nos", 21000, "Valeo", 14),
	("OBC-22K-001", "On-board Charger 22kW", "On-board Charger", "Nos", 64000, "BRUSA", 21),
	("COOL-PUMP-001", "Coolant Pump 12V", "Thermal / Cooling System", "Nos", 5400, "Bosch", 7),
	("COOL-FLD-002", "EV Coolant (Glycol) 5L", "Thermal / Cooling System", "Litre", 850, "Shell", 3),
	("COOL-RAD-003", "Battery Radiator", "Thermal / Cooling System", "Nos", 12500, "Valeo", 10),
	("BRK-PAD-001", "Brake Pad Set (Front)", "Brake System", "Set", 3200, "Brembo", 5),
	("BRK-PAD-002", "Brake Pad Set (Rear)", "Brake System", "Set", 2950, "Brembo", 5),
	("BRK-DISC-003", "Brake Disc", "Brake System", "Nos", 6800, "Brembo", 7),
	("BRK-AIR-004", "Air Brake Chamber", "Brake System", "Nos", 4100, "WABCO", 7),
	("SUS-BAG-001", "Air Suspension Bellow", "Air Suspension", "Nos", 7600, "Continental", 10),
	("SUS-COMP-002", "Air Compressor", "Air Suspension", "Nos", 18500, "WABCO", 14),
	("STR-PUMP-001", "Electric Power Steering Pump", "Steering System", "Nos", 16400, "ZF", 14),
	("STR-TIE-002", "Tie Rod End", "Steering System", "Nos", 2200, "ZF", 5),
	("HVAC-COMP-001", "Electric AC Compressor", "HVAC Unit", "Nos", 42000, "Denso", 18),
	("HVAC-FLT-002", "Cabin Air Filter", "HVAC Unit", "Nos", 650, "Mann", 3),
	("DOOR-CYL-001", "Door Pneumatic Cylinder", "Pneumatic Doors", "Nos", 5200, "Ventura", 10),
	("DOOR-VLV-002", "Door Control Valve", "Pneumatic Doors", "Nos", 3400, "Ventura", 10),
	("TYR-295-001", "Tyre 295/80 R22.5", "Tyres & Wheels", "Nos", 24500, "Apollo", 7),
	("TYR-RIM-002", "Steel Rim 22.5", "Tyres & Wheels", "Nos", 8900, "Wheels India", 7),
	("LGT-HEAD-001", "LED Headlamp Assembly", "Lighting", "Nos", 7300, "Lumax", 7),
	("LGT-TAIL-002", "LED Tail Lamp", "Lighting", "Nos", 3100, "Lumax", 7),
	("VCU-ECU-001", "Vehicle Control Unit", "VCU / Software", "Nos", 36000, "Inhouse", 21),
]


# (customer_name, customer_code, mobile_no, city, state) — demo B2B bus operators.
# customer_code is reqd + unique and feeds the Job Card naming series, so it has to be
# supplied here: Customer.validate() rejects anything that is not 2-10 uppercase
# alphanumerics, and omitting it made every row in this list fail on insert.
CUSTOMERS = [
	("Zingbus", "ZINGBUS", "9000000001", "Gurugram", "Haryana"),
	("IntrCity SmartBus", "INTRCITY", "9000000002", "Bengaluru", "Karnataka"),
	("Chartered Bus", "CHARTERED", "9000000003", "Hyderabad", "Telangana"),
	("NueGo EV Travels", "NUEGO", "9000000004", "New Delhi", "Delhi"),
]


def _ensure_customer(name: str, code: str, mobile: str, city: str, state: str) -> None:
	# customer_name and customer_code are both unique, so check both: ignore_if_duplicate
	# only swallows a clash on the document name, not on another unique column.
	if frappe.db.exists("Customer", {"customer_name": name}) or frappe.db.exists(
		"Customer", {"customer_code": code}
	):
		return
	frappe.get_doc(
		{
			"doctype": "Customer",
			"customer_name": name,
			"customer_code": code,
			"mobile_no": mobile,
			"city": city,
			"state": state,
		}
	).insert(ignore_permissions=True, ignore_if_duplicate=True)


def _ensure_part_group(name: str, bus_system: str) -> None:
	if frappe.db.exists("Part Group", name):
		return
	frappe.get_doc({"doctype": "Part Group", "part_group_name": name, "bus_system": bus_system}).insert(
		ignore_permissions=True, ignore_if_duplicate=True
	)


def _ensure_part(code, name, group, uom, cost, mfr, lead) -> None:
	if frappe.db.exists("Part", code):
		return
	if not frappe.db.exists("Part Group", group):
		return
	frappe.get_doc(
		{
			"doctype": "Part",
			"part_code": code,
			"part_name": name,
			"part_group": group,
			"stock_uom": uom,
			"standard_cost": cost,
			"manufacturer": mfr,
			"lead_time_days": lead,
			"is_active": 1,
		}
	).insert(ignore_permissions=True, ignore_if_duplicate=True)


def execute() -> None:
	if not frappe.db.exists("DocType", "Part"):
		return
	for cname, ccode, mobile, city, state in CUSTOMERS:
		try:
			_ensure_customer(cname, ccode, mobile, city, state)
		except Exception:
			frappe.log_error(title="seed_parts_catalog:customer", message=frappe.get_traceback())
	for name, system in PART_GROUPS:
		try:
			_ensure_part_group(name, system)
		except Exception:
			frappe.log_error(title="seed_parts_catalog:group", message=frappe.get_traceback())
	for row in PARTS:
		try:
			_ensure_part(*row)
		except Exception:
			frappe.log_error(title="seed_parts_catalog:part", message=frappe.get_traceback())
