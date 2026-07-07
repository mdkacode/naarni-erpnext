"""Index Alert Event for the grouped Alerts feed.

`get_my_alert_groups` partitions by `dedup_key` ordered by the event clock, so a
composite index on (dedup_key, triggered_at) keeps it index-assisted as the log
grows. `frappe.db.add_index` is a no-op if the index already exists — safe to
re-run on every migrate.
"""

import frappe


def execute() -> None:
	if not frappe.db.table_exists("Alert Event"):
		return
	frappe.db.add_index("Alert Event", ["dedup_key", "triggered_at"])
	frappe.db.add_index("Alert Event", ["vehicle", "severity"])
