"""Everyone on staff can record an inspection — including its photographs.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_run_write_access

Uploading a photo goes through Frappe's own `upload_file`, which calls
`check_write_permission(doctype, docname)` and therefore needs **write** on
Process Run. That happens in core, before any of this app's code runs, so it
cannot be satisfied by the `has_permission` hook — a controller hook can only
refuse, never grant. Write has to come from a role.

An engineer without that role can walk a whole pack, answer every check and
photograph every terminal, and have none of it reach the server, silently,
because the handset has saved it all locally. These tests pin the fix: the role
is automatic for staff, so no forgotten setup step can strand a day's evidence.
"""

import frappe
from frappe.tests.utils import FrappeTestCase

OPERATOR = "Process Operator"


class TestStaffCanRecordARun(FrappeTestCase):
	def setUp(self):
		frappe.set_user("Administrator")

	def _staff(self, mobile: str, roles=("Technician",)) -> str:
		"""A system user. Any desk role at all makes somebody one."""
		email = f"staff-{frappe.generate_hash(length=8)}@test.localhost"
		doc = frappe.new_doc("User")
		doc.email = email
		doc.first_name = "Staff"
		doc.mobile_no = mobile
		doc.send_welcome_email = 0
		for role in roles:
			doc.append("roles", {"role": role})
		doc.insert(ignore_permissions=True)
		return email

	def test_a_new_technician_can_write_a_run(self):
		user = self._staff("9813300001")
		self.assertIn(OPERATOR, frappe.get_roles(user))
		self.assertTrue(
			frappe.permissions.has_permission("Process Run", ptype="write", user=user),
			"write on Process Run is what upload_file checks before attaching a photo",
		)

	def test_a_role_nobody_thought_of_still_gets_it(self):
		"""The actual failure: staff whose role was not on anybody's list."""
		user = self._staff("9813300002", roles=("Fleet Reports",))
		self.assertIn(OPERATOR, frappe.get_roles(user))
		self.assertTrue(frappe.permissions.has_permission("Process Run", ptype="write", user=user))

	def test_they_can_create_and_read_one(self):
		user = self._staff("9813300003")
		self.assertTrue(frappe.permissions.has_permission("Process Run", ptype="create", user=user))
		self.assertTrue(frappe.permissions.has_permission("Process Run", ptype="read", user=user))

	def test_but_not_delete_it(self):
		"""Recording an inspection is everybody's job. Removing one is not."""
		user = self._staff("9813300004")
		self.assertFalse(frappe.permissions.has_permission("Process Run", ptype="delete", user=user))

	def test_the_permission_holds_against_an_actual_document(self):
		"""`upload_file` checks the document, not just the doctype."""
		user = self._staff("9813300005")
		run = frappe.get_all("Process Run", fields=["name"], limit_page_length=1)
		if not run:
			self.skipTest("no Process Run on this site to check against")
		doc = frappe.get_doc("Process Run", run[0]["name"])
		self.assertTrue(frappe.permissions.has_permission("Process Run", doc=doc, ptype="write", user=user))

	def test_a_customer_portal_account_gets_nothing(self):
		"""Why this is a role and not the `All` permission.

		`All` is held by every user including website users, so putting write on
		Process Run there would have handed inspection records to customers.
		Frappe's own security lint blocks it, and rightly.
		"""
		email = f"portal-{frappe.generate_hash(length=8)}@test.localhost"
		doc = frappe.new_doc("User")
		doc.email = email
		doc.first_name = "Portal"
		doc.mobile_no = "9813300099"
		doc.send_welcome_email = 0
		doc.insert(ignore_permissions=True)

		# No desk role, so Frappe's own User.validate makes them a website user.
		self.assertEqual(frappe.db.get_value("User", email, "user_type"), "Website User")
		self.assertNotIn(OPERATOR, frappe.get_roles(email))
		self.assertFalse(frappe.permissions.has_permission("Process Run", ptype="write", user=email))

	def test_a_disabled_account_is_left_alone(self):
		user = self._staff("9813300006")
		doc = frappe.get_doc("User", user)
		doc.roles = [row for row in doc.roles if row.role != OPERATOR]
		doc.enabled = 0
		doc.save(ignore_permissions=True)
		self.assertNotIn(OPERATOR, [row.role for row in frappe.get_doc("User", user).roles])

	def test_removing_it_from_active_staff_does_not_stick(self):
		"""Documented intent, not an oversight.

		This is a permission everyone who works here needs, and an account
		quietly missing it is the exact failure this exists to prevent. To stop
		somebody working, disable the account.
		"""
		user = self._staff("9813300007")
		doc = frappe.get_doc("User", user)
		doc.roles = [row for row in doc.roles if row.role != OPERATOR]
		doc.save(ignore_permissions=True)
		self.assertIn(OPERATOR, [row.role for row in frappe.get_doc("User", user).roles])
