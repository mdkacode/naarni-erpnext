"""Alert Ignore List (single) — central mute list for the alert engine.

Buses selected here are excluded from ALL alerts. `get_engine_config` reads this
doc and unions the device_ids into the `muted_devices` payload the engine honours
(alongside the per-Vehicle "Mute Alerts" checkbox). No deploy needed to change it —
the engine picks it up on its next config refresh (~1 minute).
"""

import frappe
from frappe.model.document import Document


class AlertIgnoreList(Document):
	def validate(self):
		# Guard against duplicate buses in the multi-select (harmless but noisy).
		seen = set()
		for row in self.vehicles or []:
			if row.vehicle in seen:
				frappe.throw(f"{row.vehicle} is selected more than once — remove the duplicate.")
			seen.add(row.vehicle)


def ignored_vehicles() -> list[str]:
	"""Vehicle names on the ignore list (deduped). Safe if the single doc is missing."""
	try:
		doc = frappe.get_single("Alert Ignore List")
	except Exception:
		return []
	out: list[str] = []
	for row in doc.vehicles or []:
		if row.vehicle and row.vehicle not in out:
			out.append(row.vehicle)
	return out


def muted_device_ids() -> list[str]:
	"""device_ids for the buses on the ignore list. The Table MultiSelect stores only
	the Vehicle link, so device_id is resolved from Vehicle here (one batched query)."""
	vehicles = ignored_vehicles()
	if not vehicles:
		return []
	rows = frappe.get_all(
		"Vehicle",
		filters={"name": ["in", vehicles], "device_id": ["is", "set"]},
		fields=["device_id"],
	)
	out: list[str] = []
	for r in rows:
		dev = str(r["device_id"]).strip()
		if dev and dev not in out:
			out.append(dev)
	return out
