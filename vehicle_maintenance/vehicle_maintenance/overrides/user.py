import re

import frappe
from frappe import _


def normalize_phone(raw: str) -> str:
	"""Canonical phone: last 10 digits, country code and separators stripped.

	Matches the convention used by the service portal login flow so that
	'+91 98765 43210', '+919876543210', '98765-43210' and '9876543210' all
	resolve to the same stored value.
	"""
	digits = re.sub(r"\D", "", raw or "")
	if len(digits) < 10:
		frappe.throw(_("Phone number must be at least 10 digits."))
	return digits[-10:]


#: Recording an inspection is part of everyone's job here, so this is not gated
#: on which floor role somebody holds — see `_grant_operator_role`.
OPERATOR_ROLE = "Process Operator"


def validate_user(doc, method=None):
	_grant_operator_role(doc)

	# Administrator bootstraps the site before any human user exists and
	# therefore can't carry a phone; all other users authenticate by phone.
	if doc.name == "Administrator":
		return
	if getattr(doc, "flags", None) and doc.flags.get("ignore_phone_requirement"):
		return

	if not (doc.mobile_no or "").strip():
		frappe.throw(_("Phone number is mandatory."))

	normalized = normalize_phone(doc.mobile_no)
	doc.mobile_no = normalized

	existing = frappe.db.get_all(
		"User",
		filters={"mobile_no": normalized, "name": ["!=", doc.name or ""]},
		fields=["name"],
		limit=1,
	)
	if existing:
		frappe.throw(
			_("Phone number {0} is already registered to user {1}.").format(normalized, existing[0].name)
		)


def _grant_operator_role(doc) -> None:
	"""Every member of staff can record an inspection. No exceptions to remember.

	Without `Process Operator` a person has no write permission on Process Run,
	and Frappe's own `upload_file` refuses to attach a photograph without it.
	The result is an engineer who can walk a whole pack, answer every check and
	photograph every terminal, and have none of it reach the server — silently,
	because the handset shows the work saved, which it is, locally. Nobody finds
	out until somebody goes looking.

	So it is granted to every enabled system user, on every save, rather than
	being something an admin has to remember when they set an account up.

	**System users only — deliberately not the `All` role.** `All` would have
	been one line in the doctype, but it is held by every user including
	customer portal accounts, so it would have handed inspection records to
	customers. Frappe's own security lint blocks it for that reason.

	`user_type` is trustworthy by the time this runs: Frappe's own `User.validate`
	derives it from whether the person has desk access, and app hooks run after
	the controller. Somebody with no roles at all is a website user, not a member
	of staff, and gets nothing.

	Taking the role away therefore does not stick — it is restored on the next
	save of that user. That is the intent rather than an oversight: this is a
	permission everyone who works here needs, and an account quietly missing it
	is the failure this exists to prevent. To stop somebody working, disable the
	account.
	"""
	if doc.name in ("Administrator", "Guest"):
		return
	if doc.get("user_type") != "System User":
		return
	if not doc.get("enabled", 1):
		return
	if any(row.role == OPERATOR_ROLE for row in (doc.get("roles") or [])):
		return
	if not frappe.db.exists("Role", OPERATOR_ROLE):
		return

	doc.append("roles", {"role": OPERATOR_ROLE})
