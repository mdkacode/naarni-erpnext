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

		# The primary reading is OPTIONAL: an alert may be defined purely by its
		# Conditions table (AND/OR). Require at least one of the two, and clear stray
		# primary fields when it's conditions-only.
		has_primary = bool((self.parameter or "").strip())
		if not has_primary and not (self.conditions or []):
			frappe.throw(_("Add a primary reading or at least one condition."))

		if has_primary:
			self._validate_rule()
		else:
			self.op = None
			self.default_threshold = 0
			self.match_value = None

		self._validate_conditions()
		self._validate_display_parameters()

	def _validate_conditions(self) -> None:
		"""Each additional condition must be self-consistent: numeric reading -> a
		number + numeric operator; category/Yes-No -> an equality operator + a value.
		This is the engine's safety net regardless of what the form did."""
		for c in self.conditions or []:
			sym = op_to_symbol(c.op)
			ptype = frappe.db.get_value("Telemetry Parameter", c.parameter, "data_type") or "Numeric"
			value = (c.value or "").strip()
			if not value:
				frappe.throw(_("Set a value for the condition on {0}.").format(c.parameter))
			if ptype in ("Categorical", "Boolean"):
				if sym not in ("==", "!="):
					frappe.throw(
						_('Condition on {0} ({1}) must use "is exactly" or "is not".').format(
							c.parameter, ptype
						)
					)
			else:
				try:
					float(value)
				except ValueError:
					frappe.throw(_("Condition on {0} needs a number, got '{1}'.").format(c.parameter, value))
			if not c.condition_group or c.condition_group < 1:
				c.condition_group = 1

	def _validate_display_parameters(self) -> None:
		seen = set()
		for d in self.display_parameters or []:
			if d.parameter in seen:
				frappe.throw(_("Reading {0} is listed twice in 'Readings to Show'.").format(d.parameter))
			seen.add(d.parameter)
			# Must match the engine's placeholder exactly: f"p_{parameter}" (raw
			# Telemetry Parameter name = the silver column the engine reads).
			d.placeholder = "{p_" + d.parameter + "}"

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
