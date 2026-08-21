# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""The gate on who gets an account.

Until this existed, anyone who could receive an OTP on any phone number got a
Frappe user created for them on first login — the number was the only
credential, and nobody had to know who was joining. An invite makes that
deliberate: somebody who knows the person types their number in first.

Two properties are worth keeping in mind before changing anything here:

* **Existing accounts are never gated.** The check runs only where a *new* user
  would be provisioned. Adding this must not lock out the people already using
  the app, which is exactly what a naive "no invite, no login" would do.
* **The email is best-effort.** An invite whose welcome bounced is still a valid
  invite; the joiner is simply told by their supervisor instead. Failing the
  insert because a mail server was down would be the wrong trade.
"""

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import now_datetime

from vehicle_maintenance.overrides.user import normalize_phone

STATUS_PENDING = "Pending"
STATUS_ACCEPTED = "Accepted"
STATUS_REVOKED = "Revoked"


class VMUserInvite(Document):
	def validate(self):
		# Normalised here rather than at lookup time so the stored value and the
		# login path's value are the same string by construction. A gate that can
		# be walked around by typing "+91 98765 43210" is not a gate.
		self.phone = normalize_phone(self.phone)
		self.full_name = (self.full_name or "").strip()
		self.email = (self.email or "").strip().lower() or None
		if not self.full_name:
			frappe.throw(_("Enter the person's name — they see it on their first screen."))
		if self.status == STATUS_ACCEPTED and not self.user:
			frappe.throw(_("An invite is marked accepted by the login that accepted it, not by hand."))

	def before_insert(self):
		existing = frappe.db.get_value("User", {"mobile_no": self.phone}, ["name", "enabled"], as_dict=True)
		if existing:
			# Not an error: re-inviting someone who already has an account is how
			# a disabled account gets handed back. Say so, and record the link.
			self.user = existing.name
			frappe.msgprint(
				_(
					"{0} already has an account. This invite records the request; it does not create a second one."
				).format(self.phone),
				indicator="orange",
			)

	def after_insert(self):
		self.send_welcome()

	def send_welcome(self) -> bool:
		"""Tell the joiner they can sign in. Never raises."""
		if not self.email:
			return False
		try:
			frappe.sendmail(
				recipients=[self.email],
				subject=_("Your Naarni Fleet Service access is ready"),
				message=_welcome_body(self),
				# Queued, not sent inline: an admin adding ten people should not
				# wait on ten SMTP round trips, and a slow mail server should not
				# look like a slow app.
				now=False,
			)
			self.db_set("email_sent_at", now_datetime(), update_modified=False)
			return True
		except Exception:
			frappe.log_error(
				title=f"Invite welcome email failed ({self.name})", message=frappe.get_traceback()
			)
			return False

	def mark_accepted(self, user: str) -> None:
		"""Stamped by the login that used this invite."""
		self.db_set(
			{"status": STATUS_ACCEPTED, "user": user, "accepted_at": now_datetime()},
			update_modified=False,
		)


def _welcome_body(invite) -> str:
	"""Plain, short, and about the one thing they have to do next.

	Deliberately no link and no password: the app is the only way in, and the
	credential is the phone number itself. A "click here to set your password"
	mail would be both useless and a phishing lesson we do not want to teach.
	"""
	designation = f" as {invite.designation}" if invite.designation else ""
	return _(
		"""<p>Hello {name},</p>
<p>You have been added to Naarni Fleet Service{designation}.</p>
<p>Open the Naarni app and sign in with this phone number:<br>
<b>{phone}</b></p>
<p>You will get a one-time code by SMS. The first time you sign in, the app will
ask for your photo and a few details so your colleagues know who they are
talking to.</p>
<p>If you were not expecting this, you can ignore it — nothing happens until you
sign in.</p>"""
	).format(name=invite.full_name, designation=designation, phone=invite.phone)


# ------------------------------------------------------------------- the gate


def pending_invite_for(phone: str):
	"""The open invite for `phone`, or None. Normalises before matching."""
	try:
		phone = normalize_phone(phone)
	except Exception:
		return None
	name = frappe.db.get_value("VM User Invite", {"phone": phone, "status": STATUS_PENDING}, "name")
	return frappe.get_doc("VM User Invite", name) if name else None


def invites_are_enforced() -> bool:
	"""Whether a new phone number needs an invite to get an account.

	On by default, and switchable per site rather than per deployment: a site
	mid-migration, or a demo, may legitimately want the old open behaviour, and
	discovering that only from a code change is worse than a config key.
	"""
	value = (frappe.conf or {}).get("require_user_invite")
	return True if value is None else bool(value)
