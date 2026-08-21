"""The platform owner's administrator account.

The last test is the one that matters. Granting `System Manager` is easy to
assert and easy to get wrong in a way assertions miss — this app makes
`User.mobile_no` mandatory through an override, so "holds the role" and "can
actually add a colleague" are different claims and only the second one is the
request behind the patch.
"""

from __future__ import annotations

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.patches.v3_0 import grant_platform_admin as patch

PHONE = "9812309876"
EMAIL = "platform.owner@test.local"


def _make(email: str, mobile: str, user_type: str = "System User") -> str:
	if not frappe.db.exists("User", email):
		doc = frappe.get_doc(
			{
				"doctype": "User",
				"email": email,
				"first_name": email.split("@")[0],
				"mobile_no": mobile,
				"user_type": user_type,
				"send_welcome_email": 0,
			}
		)
		doc.insert(ignore_permissions=True, ignore_mandatory=True)
	return email


class PlatformAdminTestCase(FrappeTestCase):
	def setUp(self):
		self.addCleanup(frappe.set_user, "Administrator")
		self.addCleanup(setattr, patch, "PLATFORM_ADMINS", patch.PLATFORM_ADMINS)
		patch.PLATFORM_ADMINS = (PHONE,)


class TestTheGrant(PlatformAdminTestCase):
	def test_the_phones_owner_becomes_a_system_manager(self):
		_make(EMAIL, PHONE)
		patch.execute()
		self.assertIn("System Manager", frappe.get_roles(EMAIL))

	def test_running_it_twice_grants_one_of_each_role(self):
		_make(EMAIL, PHONE)
		patch.execute()
		patch.execute()
		held = [r.role for r in frappe.get_doc("User", EMAIL).roles]
		self.assertEqual(len(held), len(set(held)))

	def test_a_website_user_is_promoted_first(self):
		"""A Website User silently drops desk roles on save.

		Which is how an earlier grant in this codebase looked as though it had
		worked and had not.
		"""
		# Its own number: the patch finds people *by* phone, and the tests in
		# this class commit, so two of them cannot own the same one.
		email = "website.owner@test.local"
		_make(email, "9812309878", user_type="Website User")
		patch.PLATFORM_ADMINS = ("9812309878",)
		patch.execute()
		self.assertEqual(frappe.db.get_value("User", email, "user_type"), "System User")
		self.assertIn("System Manager", frappe.get_roles(email))

	def test_a_site_where_nobody_has_that_number_is_left_alone(self):
		# A number no fixture and no demo seed on this site uses. Picking a
		# plausible-looking one is how this test first passed for the wrong
		# reason: it granted the roles to a demo account and counted them.
		unused = "9799799799"
		self.assertFalse(frappe.db.exists("User", {"mobile_no": unused}))
		patch.PLATFORM_ADMINS = (unused,)
		before = frappe.db.count("Has Role")
		patch.execute()
		self.assertEqual(frappe.db.count("Has Role"), before)

	def test_a_number_stored_with_its_country_code_is_still_found(self):
		"""Accounts from the Naarni directory can carry one; equality would miss them."""
		email = "intl.owner@test.local"
		# Its own number while it is created — the override refuses a duplicate —
		# then rewritten to the country-coded form the directory would store.
		_make(email, "9812309877")
		frappe.db.set_value("User", email, "mobile_no", f"+91{PHONE}", update_modified=False)
		self.addCleanup(frappe.db.set_value, "User", email, "mobile_no", "9812309877", update_modified=False)
		patch.PLATFORM_ADMINS = (PHONE,)
		patch.execute()
		self.assertIn("System Manager", frappe.get_roles(email))

	def test_a_role_somebody_trimmed_is_not_forced_back(self):
		"""It grants what is missing; it never overrules a later decision.

		Asserted on a role the patch does not name at all — the ones it does name
		are re-granted by design, and testing removal of those would be testing
		that the patch does not work.
		"""
		_make(EMAIL, PHONE)
		patch.execute()
		user = frappe.get_doc("User", EMAIL)
		user.append("roles", {"role": "Technician"})
		user.flags.ignore_phone_requirement = True
		user.save(ignore_permissions=True)

		user = frappe.get_doc("User", EMAIL)
		user.roles = [r for r in user.roles if r.role != "Technician"]
		user.flags.ignore_phone_requirement = True
		user.save(ignore_permissions=True)
		frappe.clear_cache(user=EMAIL)

		patch.execute()
		self.assertNotIn("Technician", frappe.get_roles(EMAIL))


class TestWhatTheAdminCanDo(PlatformAdminTestCase):
	def test_they_can_add_a_colleague_and_give_them_a_role(self):
		"""The request behind the patch, asserted as the thing that was asked for.

		Run *as* the granted account rather than as Administrator, because
		Administrator bypasses the permission system and would prove nothing.
		"""
		_make(EMAIL, PHONE)
		patch.execute()
		frappe.db.commit()  # nosemgrep — the role cache is read on the next set_user

		frappe.set_user(EMAIL)
		colleague = frappe.get_doc(
			{
				"doctype": "User",
				"email": "new.starter@test.local",
				"first_name": "New",
				"last_name": "Starter",
				# Mandatory on this site by override — a colleague logs in by phone.
				"mobile_no": "9812309999",
				"send_welcome_email": 0,
				"roles": [{"role": "Technician"}],
			}
		)
		colleague.insert()
		self.addCleanup(
			lambda: frappe.delete_doc("User", colleague.name, force=True, ignore_permissions=True)
		)

		self.assertTrue(frappe.db.exists("User", "new.starter@test.local"))
		self.assertIn("Technician", frappe.get_roles("new.starter@test.local"))

	def test_a_colleague_without_a_phone_is_refused(self):
		"""The override that makes phone login work, seen from the admin's chair."""
		_make(EMAIL, PHONE)
		patch.execute()
		frappe.db.commit()  # nosemgrep — as above

		frappe.set_user(EMAIL)
		with self.assertRaises(frappe.ValidationError):
			frappe.get_doc(
				{
					"doctype": "User",
					"email": "no.phone@test.local",
					"first_name": "No",
					"send_welcome_email": 0,
				}
			).insert()
