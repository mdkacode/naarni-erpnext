"""Upgrade existing Alert Type rows from operator symbols to friendly labels.

Earlier rows stored `op` as ">", "<", "==" etc. The Alert Type form now uses
plain-English Operator labels ("is greater than (>)", ...). This rewrites any
legacy symbol values so the form shows a valid selection. The engine is unaffected:
`get_engine_config` maps the label back to the symbol via `op_to_symbol`.

Idempotent: rows already holding a label are left untouched.
"""

import frappe

from vehicle_maintenance.fleet_service.doctype.alert_type.alert_type import SYMBOL_TO_LABEL


def execute() -> None:
	if not frappe.db.has_column("Alert Type", "op"):
		return
	for name, op in frappe.get_all("Alert Type", fields=["name", "op"], as_list=True):
		label = SYMBOL_TO_LABEL.get((op or "").strip())
		if label:  # only legacy symbol values map; labels return None and are skipped
			frappe.db.set_value("Alert Type", name, "op", label, update_modified=False)
