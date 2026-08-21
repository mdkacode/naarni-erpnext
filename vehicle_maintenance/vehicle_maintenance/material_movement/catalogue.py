"""The GENE 13.5M EV Bus item catalogue, transcribed from the weight sheet (13.csv).

This module is *data*, not logic. It is the single source of truth for the
starter catalogue: `patches.v2_6.seed_material_movement` inserts it as Part
Groups and Parts, and `MATERIAL_MOVEMENT_PRD.md` renders its tables from here.
Two copies of a 151-row list drift within a week; one does not.

Transcription rules — every one of them is deliberate, because a gate clerk
searching this list at 6 a.m. is the person who pays for a sloppy import:

*   **Every sheet row appears exactly once.** `SOURCE_ROWS` records the CSV line
    each item came from, and `verify()` asserts rows 5-159 are covered with no
    gaps and no row claimed twice. Row 160 is the sheet's "Total" and is not an
    item.
*   **Three pairs are the same physical part listed twice** — the sheet records
    lighting once under *Aggregates* and again in the unnamed section 8. They are
    merged into one item carrying both row numbers. Merging is safe here and
    nowhere else: a duplicate item code is a second place for stock to hide.
*   **Section 8 of the sheet has no system name.** It is 64 rows of cab, harness,
    plumbing, fastener and tool items pooled together. Leaving them in one bucket
    would make the largest group in the catalogue also the least searchable, so
    they are split by function into Cab & Controls, Electrical & Harness, Cooling
    & Fluid Lines, Fasteners & Consumables and Tools & Loose Equipment.
*   **Names are corrected, meanings are not.** "Seteering cloumn", "Doam",
    "Beedings" and "leaver" are typed as they were meant. The sheet's own wording
    is preserved verbatim in `sheet_name` whenever it differs, so anyone holding
    the printed sheet can still find the row.
*   **`qty_per_bus` is the sheet's stated quantity**, read from the Description
    column ("Qty - 4", "Total glasses : 14"). It is a *hint* that pre-fills the
    quantity box at the gate — never a limit, because a truck may bring a
    part-load or a double-load and the clerk must be able to say so.
*   **`has_qr` marks parts that carry a serial label** worth recording against the
    movement: aggregates that are individually traceable and warranty-bearing.
    It is a default for the app to act on, not a rule — the operator can record a
    QR on any item, and can move a QR-marked item that arrived without a label.
"""

from __future__ import annotations

# --------------------------------------------------------------------- groups

#: (group_name, bus_system, description). `bus_system` must be one of the Select
#: options on `Part Group.bus_system`; it is what the Job Card side already
#: reports by, so a new group that invents its own value would silently vanish
#: from those reports.
GROUPS: list[tuple[str, str, str]] = [
	("Chassis & Driveline", "Powertrain", "Rolling chassis, axles, traction, HV packs and dampers."),
	("Body Structure", "Body & Structure", "YST240/YST355 skeleton bays: side, front, rear and roof."),
	("Exterior & Glazing", "Body & Structure", "Skin panels, flaps, doors, fascias and all glass."),
	(
		"Aggregates & Fitments",
		"Other",
		"Bought-out assemblies fitted to the body: HVAC, lighting, seating, wheels.",
	),
	("Interior & Trim", "Other", "Cabin build-out: panels, flooring, berths, ducting and soft trim."),
	("Mounting Brackets", "Body & Structure", "Chassis-to-body and equipment mounting brackets."),
	("Cab & Controls", "Other", "Driver station: pedals, column, screens, switches and defrost."),
	(
		"Electrical & Harness",
		"Electrical & Wiring",
		"Harnesses, HV cables, grounding, telematics and electrical fitments.",
	),
	("Cooling & Fluid Lines", "Thermal Management", "Coolant hoses, connectors, valves and fittings."),
	("Fasteners & Consumables", "Other", "Bulk-issue fasteners, clips, clamps and sealing items."),
	("Tools & Loose Equipment", "Other", "Loose equipment dispatched with the bus."),
]

# ---------------------------------------------------------------------- items

#: (part_code, part_name, group, uom, qty_per_bus, has_qr, sheet_rows, spec, sheet_name)
#:
#: `qty_per_bus` of 0 means the sheet gave no number — a bulk or set item whose
#: quantity is decided at the gate. `sheet_name` is "" when the name below is
#: exactly what the sheet says.
ITEMS: list[tuple[str, str, str, str, int, int, tuple[int, ...], str, str]] = [
	# ---------------------------------------------------------- Chassis & Driveline
	(
		"CHS-001",
		"Chassis Frame",
		"Chassis & Driveline",
		"Nos",
		1,
		1,
		(5,),
		"Steel frame, density 7833 kg/m³",
		"Chassis Frame (Steel Frame: Density 7833kg/m3)",
	),
	("CHS-002", "Front Axle", "Chassis & Driveline", "Nos", 1, 1, (6,), "Independent LH and RH", ""),
	("CHS-003", "Rear Axle", "Chassis & Driveline", "Nos", 1, 1, (7,), "", ""),
	(
		"CHS-004",
		"Steering System Assembly",
		"Chassis & Driveline",
		"Set",
		1,
		1,
		(8,),
		"Steering wheel, bevel, PSG, rocker arm",
		"",
	),
	(
		"CHS-005",
		"HV Battery Pack",
		"Chassis & Driveline",
		"Nos",
		12,
		1,
		(9,),
		"12 packs per bus",
		"Battery Weight",
	),
	("CHS-006", "4 in 1 Controller", "Chassis & Driveline", "Nos", 1, 1, (10,), "", ""),
	("CHS-007", "BCS — Battery Cooling System", "Chassis & Driveline", "Nos", 1, 1, (11,), "", "BCS"),
	("CHS-008", "TCS — Thermal Control System", "Chassis & Driveline", "Nos", 1, 1, (12,), "", "TCS"),
	("CHS-009", "Traction Motor", "Chassis & Driveline", "Nos", 1, 1, (13,), "", "Motor"),
	("CHS-010", "Front Shocker", "Chassis & Driveline", "Nos", 2, 0, (103,), "", ""),
	("CHS-011", "Rear Shocker", "Chassis & Driveline", "Nos", 4, 0, (104,), "", ""),
	# --------------------------------------------------------------- Body Structure
	(
		"STR-001",
		"Side Structure LH",
		"Body Structure",
		"Nos",
		1,
		0,
		(14,),
		"YST240 / YST355, design weight 314 kg",
		"LH (YST240, YST355)",
	),
	(
		"STR-002",
		"Side Structure RH",
		"Body Structure",
		"Nos",
		1,
		0,
		(15,),
		"YST240 / YST355, design weight 314 kg",
		"RH (YST240, YST355)",
	),
	(
		"STR-003",
		"Front Structure",
		"Body Structure",
		"Nos",
		1,
		0,
		(16,),
		"YST240 / YST355, design weight 87.7 kg",
		"FRONT (YST240, YST355)",
	),
	(
		"STR-004",
		"Rear Structure",
		"Body Structure",
		"Nos",
		1,
		0,
		(17,),
		"YST240 / YST355, design weight 86.9 kg",
		"REAR (YST240, YST355)",
	),
	(
		"STR-005",
		"Roof Structure",
		"Body Structure",
		"Nos",
		1,
		0,
		(18,),
		"YST240 / YST355, design weight 305 kg",
		"ROOF (YST240, YST355)",
	),
	(
		"STR-006",
		"Structure Add-ons",
		"Body Structure",
		"Set",
		0,
		0,
		(19,),
		"L-bends, connectors etc. YST240",
		"Add-ons(L-Bends, Connectors, etc) [YST240]",
	),
	(
		"STR-007",
		"Service Door Step",
		"Body Structure",
		"Set",
		1,
		0,
		(20,),
		"YST240, 3 steps",
		"Service door Step (YST240)",
	),
	# ---------------------------------------------------------- Exterior & Glazing
	(
		"EXT-001",
		"Luggage Flap",
		"Exterior & Glazing",
		"Nos",
		6,
		0,
		(21,),
		"Aluminium, YST240",
		"Luggage Flap (Aluminium, YST240)",
	),
	(
		"EXT-002",
		"Battery Flap",
		"Exterior & Glazing",
		"Nos",
		6,
		0,
		(22,),
		"Aluminium, YST240",
		"Battery Flap (Aluminium, YST240)",
	),
	(
		"EXT-003",
		"Service Flap — Service / Radiator",
		"Exterior & Glazing",
		"Nos",
		2,
		0,
		(23,),
		"Aluminium, YST240, FRP",
		"Service Flap(Service, radiator) (Aluminium, YST240, FRP)",
	),
	(
		"EXT-004",
		"Front Flap",
		"Exterior & Glazing",
		"Nos",
		1,
		0,
		(24,),
		"FRP, YST240 — flap panel and structure",
		"Front Flap (FRP, YST240)",
	),
	(
		"EXT-005",
		"Rear Flap",
		"Exterior & Glazing",
		"Nos",
		1,
		0,
		(25,),
		"FRP, YST240 — flap panel and structure",
		"Rear Flap (FRP, YST240)",
	),
	(
		"EXT-006",
		"Stretch Panel LH",
		"Exterior & Glazing",
		"Nos",
		1,
		0,
		(26,),
		"YST240, 1 mm thick",
		"Stretch Panel LH (YST240)",
	),
	(
		"EXT-007",
		"Stretch Panel RH",
		"Exterior & Glazing",
		"Nos",
		1,
		0,
		(27,),
		"YST240, 1 mm thick",
		"Stretch Panel RH (YST240)",
	),
	(
		"EXT-008",
		"Front Fascia",
		"Exterior & Glazing",
		"Set",
		1,
		0,
		(28,),
		"FRP, YST240 — main fascia, bumper, inserts",
		"Front Fascia  (FRP, YST240)",
	),
	(
		"EXT-009",
		"Rear Fascia",
		"Exterior & Glazing",
		"Set",
		1,
		0,
		(29,),
		"FRP, YST240 — main fascia, bumper, inserts",
		"Rear Fascia (FRP, YST240)",
	),
	(
		"EXT-010",
		"Roof Panel",
		"Exterior & Glazing",
		"Nos",
		3,
		0,
		(30,),
		"YST240 — LH, RH, middle",
		"Roof Panels (YST240)",
	),
	(
		"EXT-011",
		"Skirt Panel",
		"Exterior & Glazing",
		"Set",
		1,
		0,
		(31,),
		"Aluminium, YST240, MS — panel and structure",
		"Skirt Panel (Aluminium, YST240, MS)",
	),
	(
		"EXT-012",
		"Front Windshield — Top",
		"Exterior & Glazing",
		"Nos",
		1,
		0,
		(32,),
		"Glass, 8.7 mm",
		"Front WindShield-Top (Glass)",
	),
	(
		"EXT-013",
		"Front Windshield — Main",
		"Exterior & Glazing",
		"Nos",
		1,
		0,
		(33,),
		"Glass, 8.7 mm",
		"Front WindShield-Main (Glass)",
	),
	(
		"EXT-014",
		"Rear Windshield",
		"Exterior & Glazing",
		"Nos",
		1,
		0,
		(34,),
		"Glass, 5 mm",
		"Rear WindShield (Glass)",
	),
	(
		"EXT-015",
		"Upper Body Window Glass",
		"Exterior & Glazing",
		"Nos",
		14,
		0,
		(35,),
		"5 mm each, 14 glasses",
		"UB - Window Glass",
	),
	(
		"EXT-016",
		"Lower Body Window Glass",
		"Exterior & Glazing",
		"Nos",
		15,
		0,
		(36,),
		"5 mm each, 15 glasses",
		"LB - Window Glass",
	),
	(
		"EXT-017",
		"Driver Door",
		"Exterior & Glazing",
		"Nos",
		1,
		0,
		(37,),
		"YST240, FRP, MS — structure, window frame, fixed and sliding glass, latch, lock, handle",
		"Driver door (YST240, FRP, MS)",
	),
	(
		"EXT-018",
		"Service Door",
		"Exterior & Glazing",
		"Nos",
		1,
		0,
		(38,),
		"YST240, FRP, MS — structure, window frame, fixed and sliding glass, latch, lock, handle",
		"Service door (YST240, FRP, MS)",
	),
	(
		"EXT-019",
		"Emergency Door",
		"Exterior & Glazing",
		"Nos",
		1,
		0,
		(39,),
		"YST240, FRP, MS — structure, fixed glass, latch, lock, handle",
		"Emergency Door (YST240, FRP, MS)",
	),
	(
		"EXT-020",
		"Chrome Strip",
		"Exterior & Glazing",
		"Set",
		0,
		0,
		(40,),
		"ABS, 3 mm — LH, RH, windshield",
		"Chrome Strip (ABS)",
	),
	("EXT-021", "Wheel Arch — Rear", "Exterior & Glazing", "Nos", 1, 0, (41,), "", "Wheel Arch Rear"),
	("EXT-022", "Wheel Arch — Front", "Exterior & Glazing", "Nos", 1, 0, (42,), "", "Wheel Arch Front"),
	("EXT-023", "Mud Flap", "Exterior & Glazing", "Nos", 4, 0, (119,), "", ""),
	("EXT-024", "Precautionary Beam", "Exterior & Glazing", "Nos", 14, 0, (124,), "", ""),
	# ------------------------------------------------------- Aggregates & Fitments
	(
		"AGG-001",
		"HVAC Unit",
		"Aggregates & Fitments",
		"Nos",
		1,
		1,
		(44,),
		"6 blowers, 4 condenser fans",
		"HVAC",
	),
	("AGG-002", "Escape Hatch", "Aggregates & Fitments", "Nos", 3, 0, (45,), "Roof hatch", ""),
	("AGG-003", "DRL — Front", "Aggregates & Fitments", "Nos", 2, 0, (46,), "LH and RH", "DRL-Front"),
	("AGG-004", "DRL — Rear", "Aggregates & Fitments", "Nos", 2, 0, (47,), "LH and RH", "DRL-Rear"),
	(
		"AGG-005",
		"Headlamp — Low Beam",
		"Aggregates & Fitments",
		"Nos",
		2,
		0,
		(48, 140),
		"LH and RH",
		"Headlamp - Low Beam",
	),
	(
		"AGG-006",
		"Headlamp — High Beam",
		"Aggregates & Fitments",
		"Nos",
		2,
		0,
		(49, 139),
		"LH and RH",
		"Headlamp - High Beam",
	),
	("AGG-007", "Stop Lamp", "Aggregates & Fitments", "Nos", 2, 0, (50,), "LH and RH", ""),
	("AGG-008", "Brake Lamp", "Aggregates & Fitments", "Nos", 2, 0, (51,), "LH and RH", ""),
	(
		"AGG-009",
		"Directional Indicator",
		"Aggregates & Fitments",
		"Nos",
		2,
		0,
		(52, 142),
		"LH and RH",
		"Indicator",
	),
	("AGG-010", "Reverse Lamp", "Aggregates & Fitments", "Nos", 2, 0, (53,), "LH and RH", "Reverse lamp"),
	("AGG-011", "Marker Light", "Aggregates & Fitments", "Nos", 2, 0, (54,), "LH and RH", ""),
	("AGG-012", "Fog Lamp — Front", "Aggregates & Fitments", "Nos", 2, 0, (141,), "", "Fog lamp (Front)"),
	("AGG-013", "Wiper System", "Aggregates & Fitments", "Set", 1, 0, (55,), "", ""),
	("AGG-014", "Driver Seat", "Aggregates & Fitments", "Nos", 1, 0, (56,), "", ""),
	("AGG-015", "Co-Driver Seat", "Aggregates & Fitments", "Nos", 1, 0, (57,), "", "Co-driver seat"),
	("AGG-016", "Wheel Assembly", "Aggregates & Fitments", "Nos", 7, 1, (58,), "Tyre and rim, 7 wheels", ""),
	("AGG-017", "Passenger Seat", "Aggregates & Fitments", "Nos", 24, 0, (59,), "24 sitting berths", ""),
	("AGG-018", "Destination Board", "Aggregates & Fitments", "Nos", 2, 0, (60,), "Front and rear", ""),
	("AGG-019", "AC Louver", "Aggregates & Fitments", "Set", 0, 0, (61,), "", "AC Louvers"),
	("AGG-020", "Cabin Light", "Aggregates & Fitments", "Set", 0, 0, (62,), "", ""),
	("AGG-021", "Roof Light", "Aggregates & Fitments", "Set", 0, 0, (63,), "", "Roof Lights"),
	("AGG-022", "LED Strip", "Aggregates & Fitments", "Set", 0, 0, (64,), "", ""),
	("AGG-023", "Floor Service Hatch", "Aggregates & Fitments", "Nos", 1, 0, (101,), "", ""),
	(
		"AGG-024",
		"Compartment Service Hatch",
		"Aggregates & Fitments",
		"Nos",
		2,
		0,
		(159,),
		"",
		"Service hatch",
	),
	# ----------------------------------------------------------- Interior & Trim
	("INT-001", "Washroom Module", "Interior & Trim", "Nos", 1, 0, (65,), "", "Washroom"),
	("INT-002", "AC Duct", "Interior & Trim", "Set", 0, 0, (66,), "", ""),
	(
		"INT-003",
		"Honeycomb Partition",
		"Interior & Trim",
		"Set",
		0,
		0,
		(67,),
		"Single berth and double berth partition",
		"Honey comb partition",
	),
	("INT-004", "Dashboard", "Interior & Trim", "Nos", 1, 0, (68,), "", ""),
	("INT-005", "Front Inner Dome", "Interior & Trim", "Nos", 1, 0, (69,), "", "Front Inner Doam"),
	("INT-006", "Rear Inner Dome", "Interior & Trim", "Nos", 1, 0, (70,), "", "Rear Inner Doam"),
	("INT-007", "Roof Interior Panel", "Interior & Trim", "Set", 0, 0, (71,), "", "Roof Interior panel"),
	("INT-008", "Berth Cushion", "Interior & Trim", "Set", 0, 0, (72,), "", ""),
	("INT-009", "Berth Floor", "Interior & Trim", "Set", 0, 0, (73,), "", ""),
	(
		"INT-010",
		"Floor Plywood / Honeycomb",
		"Interior & Trim",
		"Set",
		0,
		0,
		(74,),
		"",
		"Floor plywood/Honeycomb",
	),
	("INT-011", "Vinyl Flooring", "Interior & Trim", "Roll", 0, 0, (75,), "", "Vinyl"),
	("INT-012", "Beadings", "Interior & Trim", "Set", 0, 0, (76,), "", "Beedings"),
	("INT-013", "Berth Side ABS Panel", "Interior & Trim", "Set", 0, 0, (78,), "", ""),
	("INT-014", "Foam / Insulation", "Interior & Trim", "Set", 0, 0, (79,), "", "Foam/Insulation"),
	("INT-015", "Luggage Rack", "Interior & Trim", "Set", 0, 0, (80,), "", "Luggage rack"),
	(
		"INT-016",
		"Driver Compartment Side Panel",
		"Interior & Trim",
		"Nos",
		1,
		0,
		(81,),
		"",
		"Driver comp side",
	),
	("INT-017", "Transition Duct", "Interior & Trim", "Nos", 1, 0, (82,), "", ""),
	("INT-018", "Restrainer", "Interior & Trim", "Set", 0, 0, (83,), "", ""),
	("INT-019", "Backrest / Headrest", "Interior & Trim", "Set", 0, 0, (84,), "", "Backrest/Headrest"),
	("INT-020", "A-Pillar LH/RH", "Interior & Trim", "Nos", 2, 0, (85,), "", "A Pillar LH/RH"),
	("INT-021", "Berth Curtain", "Interior & Trim", "Nos", 48, 0, (132,), "", "Berth curtains"),
	# --------------------------------------------------------- Mounting Brackets
	(
		"SMB-001",
		"Chassis Body Mounting Bracket",
		"Mounting Brackets",
		"Nos",
		201,
		0,
		(86,),
		"",
		"Chassis body mounting brackets ( 201 qty )",
	),
	("SMB-002", "Flat Type Side Mounting Bracket", "Mounting Brackets", "Nos", 96, 0, (87,), "", ""),
	("SMB-003", "Z Type Side Mounting Bracket", "Mounting Brackets", "Nos", 10, 0, (88,), "", ""),
	(
		"SMB-004",
		"C-Type Taper Mounting Bracket — Big",
		"Mounting Brackets",
		"Nos",
		4,
		0,
		(89,),
		"",
		"C-type taper mounting bracket - Big",
	),
	(
		"SMB-005",
		"C-Type Taper Mounting Bracket — Medium",
		"Mounting Brackets",
		"Nos",
		4,
		0,
		(90,),
		"",
		"C-type taper mounting bracket - Medium",
	),
	(
		"SMB-006",
		"C-Type Taper Mounting Bracket — Small",
		"Mounting Brackets",
		"Nos",
		8,
		0,
		(91,),
		"",
		"C-type taper mounting bracket - Small",
	),
	(
		"SMB-007",
		"C-Type Mounting Bracket — Large",
		"Mounting Brackets",
		"Nos",
		2,
		0,
		(92,),
		"",
		"C-Type mounting bracket - Large",
	),
	(
		"SMB-008",
		"C-Type Mounting Bracket — Big",
		"Mounting Brackets",
		"Nos",
		70,
		0,
		(93,),
		"",
		"C-Type mounting bracket - Big",
	),
	(
		"SMB-009",
		"C-Type Mounting Bracket — Medium",
		"Mounting Brackets",
		"Nos",
		4,
		0,
		(94,),
		"",
		"C-Type mounting bracket - Medium",
	),
	(
		"SMB-010",
		"C-Type Mounting Bracket — Small",
		"Mounting Brackets",
		"Nos",
		3,
		0,
		(95,),
		"",
		"C-Type mounting bracket - Small",
	),
	("SMB-011", "PLC Bracket", "Mounting Brackets", "Nos", 1, 0, (125,), "", ""),
	("SMB-012", "Battery Frame Layering", "Mounting Brackets", "Nos", 1, 0, (126,), "", ""),
	("SMB-013", "Bracket — General Purpose", "Mounting Brackets", "Nos", 2, 0, (151,), "", "Bracket"),
	# ------------------------------------------------------------ Cab & Controls
	("CAB-001", "Foot Pad", "Cab & Controls", "Nos", 1, 0, (97,), "", ""),
	("CAB-002", "Driver Left Foot Pad", "Cab & Controls", "Nos", 1, 0, (98,), "", ""),
	(
		"CAB-003",
		"Screen 1 — Instrument Cluster",
		"Cab & Controls",
		"Nos",
		1,
		1,
		(99,),
		"",
		"Screen 1 (Instrument cluster)",
	),
	(
		"CAB-004",
		"Screen 2 — Instrument Panel",
		"Cab & Controls",
		"Nos",
		1,
		1,
		(100,),
		"",
		"Screen 2 (Instrument Panel)",
	),
	("CAB-005", "Combination Switch", "Cab & Controls", "Nos", 1, 0, (105,), "", "combination switch"),
	("CAB-006", "Ignition Key Set", "Cab & Controls", "Set", 1, 0, (106,), "", ""),
	("CAB-007", "Park Brake Lever", "Cab & Controls", "Nos", 1, 0, (108,), "", ""),
	("CAB-008", "Accelerator Pedal", "Cab & Controls", "Nos", 1, 0, (109,), "", ""),
	(
		"CAB-009",
		"Dashboard Switch with Switch Plate",
		"Cab & Controls",
		"Nos",
		14,
		0,
		(110,),
		"",
		"Dashboard switches with switch plate",
	),
	("CAB-010", "Steering Column", "Cab & Controls", "Nos", 1, 0, (123,), "", ""),
	("CAB-011", "Steering Angle Sensor Assembly", "Cab & Controls", "Nos", 1, 1, (128,), "", ""),
	(
		"CAB-012",
		"Steering Column Decorative Cover",
		"Cab & Controls",
		"Nos",
		1,
		0,
		(129,),
		"",
		"Seteering cloumn decorative cover",
	),
	("CAB-013", "Steering Wheel", "Cab & Controls", "Nos", 1, 0, (138,), "", ""),
	("CAB-014", "Defroster", "Cab & Controls", "Nos", 1, 0, (122,), "", ""),
	("CAB-015", "Defroster Duct", "Cab & Controls", "Roll", 1, 0, (146,), "", ""),
	# ------------------------------------------------------ Electrical & Harness
	(
		"ELE-001",
		"Horn — High and Low Pitch",
		"Electrical & Harness",
		"Nos",
		2,
		0,
		(107,),
		"Hella",
		"Horns (Hella) High and low pith",
	),
	("ELE-002", "T-Box — Telematics Unit", "Electrical & Harness", "Nos", 1, 1, (111,), "", "T-Box"),
	("ELE-003", "Acoustic Vehicle Alerting System", "Electrical & Harness", "Nos", 1, 1, (113,), "AVAS", ""),
	("ELE-004", "DNR and OBD Connector", "Electrical & Harness", "Set", 1, 0, (114,), "", ""),
	("ELE-005", "Grounding Strap", "Electrical & Harness", "Nos", 15, 0, (116,), "", ""),
	("ELE-006", "LV Battery Terminal Cable", "Electrical & Harness", "Nos", 1, 0, (117,), "", ""),
	("ELE-007", "MSD — Manual Service Disconnect", "Electrical & Harness", "Nos", 12, 1, (118,), "", "MSD"),
	("ELE-008", "Buzzer", "Electrical & Harness", "Nos", 1, 0, (135,), "", ""),
	("ELE-009", "USB Port", "Electrical & Harness", "Nos", 26, 0, (136,), "", "USB ports"),
	("ELE-010", "Reverse Parking Kit Harness", "Electrical & Harness", "Set", 1, 0, (137,), "", ""),
	("ELE-011", "Front Wall Harness", "Electrical & Harness", "Nos", 1, 0, (143,), "", ""),
	("ELE-012", "Dashboard Harness", "Electrical & Harness", "Nos", 1, 0, (144,), "", ""),
	("ELE-013", "Battery HV Cable", "Electrical & Harness", "Nos", 4, 1, (145,), "", "Battery HV cables"),
	("ELE-014", "Ceiling Wire Harness", "Electrical & Harness", "Nos", 1, 0, (147,), "", ""),
	("ELE-015", "Duct and Roof Harness", "Electrical & Harness", "Nos", 2, 0, (148,), "1 pc each", ""),
	("ELE-016", "Interior Wire Harness", "Electrical & Harness", "Set", 0, 0, (77,), "", "Wire Harness"),
	("ELE-017", "Compartment Door Travel Switch", "Electrical & Harness", "Nos", 1, 0, (157,), "", ""),
	# ----------------------------------------------------- Cooling & Fluid Lines
	(
		"CLG-001",
		"C-Type Coolant Hose",
		"Cooling & Fluid Lines",
		"Nos",
		4,
		0,
		(96,),
		"",
		"C-Type coolant hose",
	),
	("CLG-002", "Coolant Hose", "Cooling & Fluid Lines", "Metre", 0, 0, (133,), "Bulk issue", ""),
	("CLG-003", "Battery Coolant Connector", "Cooling & Fluid Lines", "Nos", 12, 0, (134,), "", ""),
	("CLG-004", "Straight Connector and T-Fitting", "Cooling & Fluid Lines", "Nos", 3, 0, (112,), "", ""),
	("CLG-005", "HVAC Connector", "Cooling & Fluid Lines", "Set", 1, 0, (131,), "", ""),
	(
		"CLG-006",
		"Copper Ball Valve — Small",
		"Cooling & Fluid Lines",
		"Nos",
		2,
		0,
		(150,),
		"",
		"Copper ball valve (Small)",
	),
	("CLG-007", "Threaded Transition Joint", "Cooling & Fluid Lines", "Nos", 4, 0, (155,), "", ""),
	# -------------------------------------------------- Fasteners & Consumables
	("FAS-001", "TPMS Sensor Fitting Clamp", "Fasteners & Consumables", "Nos", 6, 0, (115,), "", ""),
	("FAS-002", "Sealing Ring", "Fasteners & Consumables", "Nos", 0, 0, (120,), "Bulk issue", ""),
	("FAS-003", "Insulated Wire Clip Bracket", "Fasteners & Consumables", "Nos", 1, 0, (121,), "", ""),
	("FAS-004", "MSD Screw", "Fasteners & Consumables", "Nos", 0, 0, (127,), "Bulk issue", ""),
	(
		"FAS-005",
		"Nut, Bolt and Fastener — Assorted",
		"Fasteners & Consumables",
		"Kg",
		0,
		0,
		(149,),
		"Bulk issue",
		"All nut bolt and fasteners",
	),
	("FAS-006", "Cable Tie (ZIP)", "Fasteners & Consumables", "Nos", 0, 0, (152,), "Bulk issue", ""),
	("FAS-007", "Compensated Clamp", "Fasteners & Consumables", "Nos", 0, 0, (153,), "Bulk issue", ""),
	("FAS-008", "Non-Standard Pipe Clamp", "Fasteners & Consumables", "Nos", 0, 0, (154,), "Bulk issue", ""),
	("FAS-009", "Insulated Wire Clip", "Fasteners & Consumables", "Nos", 3, 0, (156,), "", ""),
	# -------------------------------------------------- Tools & Loose Equipment
	("TLS-001", "Wheel Chock", "Tools & Loose Equipment", "Nos", 2, 0, (102,), "", ""),
	("TLS-002", "Tool Box with Tools", "Tools & Loose Equipment", "Set", 1, 0, (130,), "", ""),
	(
		"TLS-003",
		"Tyre and Jack Lever",
		"Tools & Loose Equipment",
		"Set",
		2,
		0,
		(158,),
		"",
		"Tire and jack leaver",
	),
]

#: CSV lines that carry a component. Line 4 is the "Chassis" system header with no
#: component, line 43 is a spacer inside Exterior and line 160 is the "Total" row —
#: none of the three is an item, and `verify()` would otherwise demand they be one.
SOURCE_ROWS = frozenset(set(range(5, 160)) - {43})


def verify() -> None:
	"""Assert the transcription is complete and unambiguous. Raises on any fault.

	Called by the seeder before it writes anything and by the test suite. The
	whole value of a hand-transcribed catalogue is that somebody checked it; this
	is that check, kept next to the data so editing one runs the other.
	"""
	codes = [row[0] for row in ITEMS]
	if len(codes) != len(set(codes)):
		dupes = sorted({c for c in codes if codes.count(c) > 1})
		raise ValueError(f"Duplicate part codes: {dupes}")

	names = [row[1] for row in ITEMS]
	if len(names) != len(set(names)):
		dupes = sorted({n for n in names if names.count(n) > 1})
		raise ValueError(f"Duplicate part names: {dupes}")

	group_names = {g[0] for g in GROUPS}
	unknown = {row[2] for row in ITEMS} - group_names
	if unknown:
		raise ValueError(f"Items reference groups that are not declared: {sorted(unknown)}")

	claimed: dict[int, str] = {}
	for row in ITEMS:
		for line in row[6]:
			if line in claimed:
				raise ValueError(f"CSV line {line} claimed by both {claimed[line]} and {row[0]}")
			claimed[line] = row[0]

	missing = SOURCE_ROWS - claimed.keys()
	if missing:
		raise ValueError(f"CSV lines transcribed by nothing: {sorted(missing)}")

	extra = claimed.keys() - SOURCE_ROWS
	if extra:
		raise ValueError(f"Items cite CSV lines that hold no component: {sorted(extra)}")


def by_group() -> dict[str, list[tuple]]:
	"""Items bucketed by group, in the order `GROUPS` declares them."""
	buckets: dict[str, list[tuple]] = {g[0]: [] for g in GROUPS}
	for row in ITEMS:
		buckets[row[2]].append(row)
	return buckets
