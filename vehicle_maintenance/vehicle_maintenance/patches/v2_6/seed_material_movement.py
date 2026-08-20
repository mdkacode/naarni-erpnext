"""Seed the material gate: locations, catalogue groups and the 151 bus items.

Idempotent, like every seeder in this app. It runs after each `bench migrate`,
checks existence before inserting, and — this is the part that matters — **never
overwrites a value a human has changed**. An admin who corrects a UOM or reroutes
an item to a different group must not have that edit reverted by the next deploy,
so an existing Part is only ever *back-filled* on fields that are still empty.

Order matters: `catalogue.verify()` runs before anything is written, so a broken
transcription fails the migrate loudly rather than importing half a catalogue.
"""

from __future__ import annotations

import frappe

from vehicle_maintenance.material_movement import catalogue

# (location_code, location_name, plant_type, city, state, gates)
LOCATIONS = [
	(
		"HUBLI",
		"Hubli — Bus Manufacturing Plant",
		"Bus Manufacturing",
		"Hubli",
		"Karnataka",
		"Main Gate\nMaterial Gate\nDispatch Gate",
	),
	(
		"NARSAPURA",
		"Narsapura — Battery Manufacturing / Assembly Plant",
		"Battery Manufacturing",
		"Narsapura",
		"Karnataka",
		"Main Gate\nMaterial Gate",
	),
]


def execute() -> None:
	catalogue.verify()
	_seed_locations()
	created_groups = _seed_groups()
	created_items, backfilled = _seed_items()
	if created_groups or created_items or backfilled:
		frappe.db.commit()
	print(
		f"Material gate seed: +{created_groups} groups, +{created_items} items, "
		f"{backfilled} existing items back-filled"
	)


def _seed_locations() -> None:
	for code, name, plant_type, city, state, gates in LOCATIONS:
		if frappe.db.exists("Material Location", code):
			continue
		frappe.get_doc(
			{
				"doctype": "Material Location",
				"location_code": code,
				"location_name": name,
				"plant_type": plant_type,
				"city": city,
				"state": state,
				"gates": gates,
				"is_active": 1,
				"geofence_radius_m": 500,
			}
		).insert(ignore_permissions=True)


def _seed_groups() -> int:
	created = 0
	for group_name, bus_system, description in catalogue.GROUPS:
		if frappe.db.exists("Part Group", group_name):
			continue
		frappe.get_doc(
			{
				"doctype": "Part Group",
				"part_group_name": group_name,
				"bus_system": bus_system,
				"description": description,
			}
		).insert(ignore_permissions=True)
		created += 1
	return created


def _seed_items() -> tuple[int, int]:
	"""Insert missing items; back-fill only blank fields on the ones that exist."""
	created = 0
	backfilled = 0

	for code, name, group, uom, qty_per_bus, has_qr, sheet_rows, spec, sheet_name in catalogue.ITEMS:
		sheet_ref = ", ".join(str(r) for r in sheet_rows)
		description = _description(spec, sheet_name)

		existing = frappe.db.get_value(
			"Part", code, ["name", "spec", "sheet_ref", "qty_per_bus", "description"], as_dict=True
		)
		if existing:
			# Back-fill, never overwrite. `has_qr` is deliberately absent from this
			# list: an admin who switched serial tracking off for an item did so on
			# purpose, and a checkbox cannot be told apart from "not set yet".
			updates = {}
			if not existing.spec and spec:
				updates["spec"] = spec
			if not existing.sheet_ref:
				updates["sheet_ref"] = sheet_ref
			if not existing.qty_per_bus and qty_per_bus:
				updates["qty_per_bus"] = qty_per_bus
			if not existing.description and description:
				updates["description"] = description
			if updates:
				frappe.db.set_value("Part", code, updates, update_modified=False)
				backfilled += 1
			continue

		frappe.get_doc(
			{
				"doctype": "Part",
				"part_code": code,
				"part_name": name,
				"part_group": group,
				"stock_uom": uom,
				"has_qr": has_qr,
				"qty_per_bus": qty_per_bus,
				"spec": spec,
				"sheet_ref": sheet_ref,
				"description": description,
				"is_active": 1,
			}
		).insert(ignore_permissions=True)
		created += 1

	return created, backfilled


def _description(spec: str, sheet_name: str) -> str:
	"""Keep the sheet's own wording searchable.

	Somebody holding the printed weight sheet must be able to find the row they
	are looking at, even where the catalogue corrected a typo or expanded an
	abbreviation.
	"""
	parts = []
	if spec:
		parts.append(spec)
	if sheet_name:
		parts.append(f"Weight sheet: “{sheet_name}”")
	return " · ".join(parts)
