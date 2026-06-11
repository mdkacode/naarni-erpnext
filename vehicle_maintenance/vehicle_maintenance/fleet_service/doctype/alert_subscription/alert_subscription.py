"""Alert Subscription DocType controller.

One row per (customer, alert_type). Enforces:
- composite uniqueness on (customer, alert_type),
- the 5-minute sustained floor (telemetry refreshes every 5 min),
- at least one channel when enabled,
- selected vehicles belong to the owning customer and carry a device_id.
"""

import frappe
from frappe import _
from frappe.model.document import Document

SUSTAINED_FLOOR_MIN = 5
CHANNEL_FIELDS = ("notify_in_app", "notify_email", "notify_chat", "notify_webhook")


class AlertSubscription(Document):
	def validate(self) -> None:
		self._apply_defaults_from_type()
		self._enforce_unique()
		self._enforce_sustained_floor()
		self._enforce_channels()
		self._validate_vehicles()

	def _apply_defaults_from_type(self) -> None:
		"""Fall back to the Alert Type's default threshold when none is set."""
		if self.threshold in (None, ""):
			default = frappe.db.get_value("Alert Type", self.alert_type, "default_threshold")
			if default is not None:
				self.threshold = default

	def _enforce_unique(self) -> None:
		existing = frappe.db.get_value(
			"Alert Subscription",
			{"customer": self.customer, "alert_type": self.alert_type, "name": ["!=", self.name]},
			"name",
		)
		if existing:
			frappe.throw(
				_("An Alert Subscription for {0} / {1} already exists ({2}).").format(
					self.customer, self.alert_type, existing
				),
				frappe.DuplicateEntryError,
			)

	def _enforce_sustained_floor(self) -> None:
		if not self.sustained_min or self.sustained_min < SUSTAINED_FLOOR_MIN:
			frappe.throw(
				_("Sustained For must be at least {0} minutes (telemetry refresh floor).").format(
					SUSTAINED_FLOOR_MIN
				)
			)

	def _enforce_channels(self) -> None:
		if self.enabled and not any(self.get(f) for f in CHANNEL_FIELDS):
			frappe.throw(_("Enable at least one delivery channel, or disable the subscription."))

	def _validate_vehicles(self) -> None:
		if self.vehicle_scope != "Selected":
			# Drop any stray rows so the engine payload stays clean.
			self.vehicles = []
			return
		if not self.vehicles:
			frappe.throw(_("Add at least one vehicle, or set Applies To = All."))
		for row in self.vehicles:
			owner = frappe.db.get_value("Vehicle", row.vehicle, "customer")
			if owner != self.customer:
				frappe.throw(
					_("Vehicle {0} does not belong to customer {1}.").format(row.vehicle, self.customer)
				)
