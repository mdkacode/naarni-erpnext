"""Move duty check-in from an advisory geofence to an enforced 100 m one.

The engine for this already existed — `fleet_service.roster.evaluate_geofence`
measures the haversine distance to the depot and `Block` mode throws. What was
missing was the policy: sites were seeded `Warn` at 300 m, so an out-of-radius
punch was recorded with a flag and accepted anyway.

Runs from `after_migrate` behind its own once-only guard, **not** from
`patches.txt`. That file lives at `vehicle_maintenance/patches/patches.txt`, but
Frappe reads `frappe.get_app_path(app, "patches.txt")` — one directory higher —
so nothing listed in it has ever executed on any site. Until that is corrected,
`after_migrate` is the only hook this app actually has, which is why every other
seeder is wired there too.

Enforcement is still applied exactly once. It is a policy an admin is allowed to
relax again, and re-asserting it on every migrate would silently undo their
decision the next time anyone deploys.

Three things have to be true together or the gate is not actually shut:

* ``geofence_mode = Block`` — otherwise a distant punch is only flagged.
* ``require_location = 1`` — without a fix the geofence cannot be evaluated, and
  an unevaluated punch is never treated as a violation. Blocking on distance
  while accepting fix-less punches means denying location permission bypasses
  the gate entirely.
* the depot has coordinates — ``evaluate_geofence`` returns ``evaluated: False``
  for a depot with no lat/long, so Block over a coordinate-less depot accepts
  everything while *looking* enforced. That is the trap this patch reports on.

Per-depot ``geofence_radius_m`` overrides are cleared rather than rewritten to
100, so every depot inherits ``Roster Settings.default_radius_m`` and a future
change to the policy is one field, not one field per depot.
"""

from __future__ import annotations

import frappe

RADIUS_M = 100

# Bumping the suffix re-runs the migration deliberately; leaving it alone means
# an admin's later change to geofence_mode survives every future deploy.
GUARD = "vm_geofence_enforced_v1"


def execute() -> None:
	if frappe.db.get_default(GUARD):
		return

	mapped, unmapped = _depot_coverage()

	if not mapped:
		# Every depot is unmapped, so there is nothing to measure against and Block
		# would evaluate nothing. Turning on require_location anyway would reject
		# every check-in from a phone without a fix and buy no geofence in return —
		# pure cost. Leave the policy alone.
		#
		# The guard is deliberately NOT set: this is "not yet", not "done". Once an
		# admin puts coordinates on a depot, the next migrate applies enforcement
		# on its own, with no second deploy.
		_log(
			"skipped: no depot has coordinates",
			"Geofence enforcement was NOT enabled — no Depot has latitude/longitude, "
			"so there is nothing to measure against.\n\n"
			f"{_listing(unmapped)}\n\n"
			"Set Latitude and Longitude on at least one Depot; the next migrate "
			"turns on Block at "
			f"{RADIUS_M} m automatically.",
		)
		return

	_apply_settings()
	_clear_depot_overrides()

	if unmapped:
		_log(
			"depots missing coordinates",
			f"Geofence is now Block at {RADIUS_M} m, but these depots have no "
			"latitude/longitude, so check-in there is still accepted from "
			f"anywhere:\n\n{_listing(unmapped)}\n\n"
			"Set Latitude and Longitude on each to close the gate.",
		)

	frappe.db.set_default(GUARD, "1")


def _depot_coverage() -> tuple[list[dict], list[dict]]:
	"""Split active depots into those with usable coordinates and those without.

	0.0 counts as unmapped, not as a position off the coast of Africa — an unset
	Float reads as 0.0 in Frappe, and `evaluate_geofence` already treats it as
	"cannot judge" because it tests falsiness.
	"""
	rows = frappe.get_all(
		"Depot",
		fields=["name", "depot_name", "latitude", "longitude"],
		limit_page_length=0,
	)
	mapped = [r for r in rows if r.get("latitude") and r.get("longitude")]
	return mapped, [r for r in rows if not (r.get("latitude") and r.get("longitude"))]


def _listing(rows: list[dict]) -> str:
	return "\n".join(f"  - {r['name']} ({r.get('depot_name') or '?'})" for r in rows)


def _log(title: str, message: str) -> None:
	frappe.log_error(title=f"enforce_depot_geofence: {title}", message=message)


def _apply_settings() -> None:
	cfg = frappe.get_single("Roster Settings")
	cfg.geofence_mode = "Block"
	cfg.require_location = 1
	cfg.default_radius_m = RADIUS_M
	cfg.save(ignore_permissions=True)


def _clear_depot_overrides() -> None:
	"""Drop per-depot radii so all depots inherit the 100 m default."""
	overridden = frappe.get_all(
		"Depot",
		filters=[["geofence_radius_m", ">", 0], ["geofence_radius_m", "!=", RADIUS_M]],
		fields=["name", "geofence_radius_m"],
		limit_page_length=0,
	)
	for row in overridden:
		frappe.db.set_value("Depot", row["name"], "geofence_radius_m", 0, update_modified=False)

	if overridden:
		frappe.log_error(
			title="enforce_depot_geofence: cleared per-depot radii",
			message="\n".join(
				f"{r['name']}: {r['geofence_radius_m']} m -> inherit {RADIUS_M} m" for r in overridden
			),
		)
