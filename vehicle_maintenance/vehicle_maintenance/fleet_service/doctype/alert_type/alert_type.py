"""Alert Type DocType controller.

Naarni-managed catalog row. Customers do not create these; they subscribe via
Alert Subscription.

The machine id (engine rule id) is generated automatically from the Alert Name —
staff never type a snake_case token. The user picks a plain-English condition
("is greater than (>)"); `op_to_symbol` maps it to the operator the engine uses.
"""

import re

import frappe
from frappe import _
from frappe.model.document import Document

# Friendly condition label -> operator symbol the alert engine evaluates.
OP_LABELS = {
	"is greater than (>)": ">",
	"is greater than or equal to (≥)": ">=",
	"is less than (<)": "<",
	"is less than or equal to (≤)": "<=",
	"is exactly (=)": "==",
	"is not (≠)": "!=",
}
# Reverse map (symbol -> label), used to upgrade legacy rows that stored symbols.
SYMBOL_TO_LABEL = {v: k for k, v in OP_LABELS.items()}
SYMBOL_OPS = set(OP_LABELS.values())
# Conditions valid for category / Yes-No readings (equality only).
CATEGORICAL_LABELS = {"is exactly (=)", "is not (≠)"}


def op_to_symbol(op: str | None) -> str:
	"""Map a stored Operator (friendly label or legacy symbol) to the engine symbol."""
	op = (op or "").strip()
	if op in SYMBOL_OPS:  # legacy rows that still hold ">" etc.
		return op
	return OP_LABELS.get(op, ">")


class AlertType(Document):
	def autoname(self) -> None:
		"""Generate a stable snake_case id from the Alert Name (e.g. 'Battery Pack
		Overheating' -> 'battery_pack_overheating'), de-duplicating on collision.
		An explicitly preset name (e.g. from a seeder) is honoured as-is."""
		if self.name:
			return
		base = re.sub(r"[^a-z0-9_]+", "_", frappe.scrub(self.alert_name or "alert")).strip("_")
		base = base or "alert"
		name, i = base, 2
		while frappe.db.exists("Alert Type", name):
			name, i = f"{base}_{i}", i + 1
		self.name = name

	def validate(self) -> None:
		if not (self.alert_name or "").strip():
			frappe.throw(_("Give this alert a name."))
		if not self.title:
			self.title = self.alert_name
		self._validate_rule()

	def _validate_rule(self) -> None:
		"""Number readings need a value + numeric condition; Category / Yes-No
		readings need a match value + an equality condition."""
		sym = op_to_symbol(self.op)
		ptype = frappe.db.get_value("Telemetry Parameter", self.parameter, "data_type") or "Numeric"
		if ptype in ("Categorical", "Boolean"):
			if sym not in ("==", "!="):
				frappe.throw(
					_('For a {0} reading, the condition must be "is exactly" or "is not".').format(ptype)
				)
			if not (self.match_value or "").strip():
				frappe.throw(_("Pick the value to match for {0}.").format(self.parameter))
			self.default_threshold = 0
		else:
			if self.default_threshold is None:
				frappe.throw(_("Enter the number to compare against for {0}.").format(self.parameter))
			self.match_value = None
