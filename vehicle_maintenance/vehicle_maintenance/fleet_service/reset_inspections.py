"""Wipe inspection runs for one process, so a pilot can start from zero.

Run (dry run — counts only, changes nothing):

    bench --site <site> execute \\
        vehicle_maintenance.fleet_service.reset_inspections.run \\
        --kwargs '{"process_name": "Battery Assembly QC"}'

Run for real:

    bench --site <site> execute \\
        vehicle_maintenance.fleet_service.reset_inspections.run \\
        --kwargs '{"process_name": "Battery Assembly QC", "confirm": "DELETE"}'

Three deliberate safeguards, because this deletes evidence and there is no undo.

**Dry run is the default.** Every invocation prints exactly what it would remove
and stops. Deleting requires passing the literal string ``DELETE``, which cannot
be produced by a mistyped flag or a shell expansion.

**It is scoped to one process by name.** Never "all runs" — the whole point is to
clear a pilot without touching a process somebody else depends on. A name that
matches nothing is an error, not a silent no-op that reports success.

**Child rows go with the parent.** Results, scans, photos and sign-offs are child
tables of `Process Run`, and Frappe's `delete_doc` takes them; the counts printed
first are read from those tables directly so the report is what is actually on
disk rather than what the parent implies.

Attached photo *files* are deliberately left alone. They are `File` records that
may be referenced elsewhere, and orphaning bytes is recoverable in a way that
deleting somebody's evidence is not.
"""

from __future__ import annotations

import frappe


def _runs_for(process_name: str) -> list[str]:
	definitions = frappe.get_all(
		"Process Definition", filters={"process_name": process_name}, pluck="name"
	)
	if not definitions:
		frappe.throw(f"No process is called {process_name!r}. Nothing was touched.")
	return frappe.get_all(
		"Process Run", filters={"process_definition": ["in", definitions]}, pluck="name"
	)


def _child_counts(runs: list[str]) -> dict[str, int]:
	if not runs:
		return {}
	tables = (
		"Process Run Result",
		"Process Run Scan",
		"Process Run Photo",
		"Process Run Signoff",
	)
	return {t: frappe.db.count(t, {"parent": ["in", runs]}) for t in tables}


def run(process_name: str, confirm: str | None = None) -> dict:
	"""Report — or, with ``confirm="DELETE"``, remove — every run of one process."""
	runs = _runs_for(process_name)
	children = _child_counts(runs)

	by_status: dict[str, int] = {}
	for row in frappe.get_all(
		"Process Run",
		filters={"name": ["in", runs]} if runs else {"name": "__none__"},
		fields=["status", "count(name) as n"],
		group_by="status",
	):
		by_status[row.status] = row.n

	print(f"\nProcess: {process_name}")
	print(f"  runs: {len(runs)}")
	for status, n in sorted(by_status.items()):
		print(f"    {status:<24} {n}")
	for table, n in children.items():
		print(f"  {table:<24} {n}")

	if confirm != "DELETE":
		print("\nDRY RUN — nothing deleted. Pass confirm=\"DELETE\" to go ahead.\n")
		return {"runs": len(runs), "children": children, "deleted": False}

	deleted = 0
	for name in runs:
		# `force` because a submitted or cancelled run would otherwise refuse,
		# and `ignore_permissions` because this is an operator-scoped doctype and
		# the person running a bench command is not the operator who owns it.
		frappe.delete_doc(
			"Process Run", name, force=True, ignore_permissions=True, delete_permanently=True
		)
		deleted += 1
		# Committed in batches rather than at the end: a few hundred runs with
		# their child rows is a large transaction, and a failure half way through
		# should leave the work already done rather than rolling all of it back
		# into a state nobody can tell apart from the start.
		if deleted % 50 == 0:
			frappe.db.commit()
			print(f"  …{deleted}/{len(runs)}")

	frappe.db.commit()
	print(f"\nDeleted {deleted} run(s) and their results, scans, photos and sign-offs.\n")
	return {"runs": deleted, "children": children, "deleted": True}
