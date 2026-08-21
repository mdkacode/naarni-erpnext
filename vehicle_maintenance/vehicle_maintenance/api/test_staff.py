"""Adding a member of staff, and who is allowed to open a chat group.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_staff

Two things are being pinned here, and the second is the one that would hurt.

**The form's own rules** — phone, name and role required, email optional, depots
only where the job is depot-scoped — because this screen exists precisely so
that adding an engineer is four answers instead of thirty.

**The escalation boundary.** An account-creation endpoint that can grant
`System Manager` is a privilege-escalation hole wearing a form, and a depot
manager must not be able to mint one. That is tested directly rather than left
to the shape of a dropdown, because the dropdown is not what stops it.
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import chat, staff


class StaffTestBase(FrappeTestCase):
	def setUp(self):
		frappe.set_user("Administrator")
		self.depot_a = self._depot("Test Depot A")
		self.depot_b = self._depot("Test Depot B")

	def _depot(self, label):
		name = f"{label} {frappe.generate_hash(length=4)}"
		doc = frappe.get_doc({"doctype": "Depot", "depot_name": name, "city": "Hubli"})
		doc.insert(ignore_permissions=True)
		return doc.name

	def _phone(self):
		"""A unique ten-digit number per call — the account is keyed on it."""
		return "9" + frappe.generate_hash(length=9).encode().hex()[:9].translate(
			str.maketrans("abcdef", "012345")
		)

	def _actor(self, roles, mobile=None):
		email = f"actor-{frappe.generate_hash(length=8)}@test.localhost"
		doc = frappe.new_doc("User")
		doc.email = email
		doc.first_name = "Actor"
		doc.mobile_no = mobile or self._phone()
		doc.send_welcome_email = 0
		for role in roles:
			doc.append("roles", {"role": role})
		doc.insert(ignore_permissions=True)
		return email


class TestCreateStaff(StaffTestBase):
	def test_a_phone_a_name_and_a_role_is_enough(self):
		phone = self._phone()
		out = staff.create_staff(phone=phone, full_name="Ravi M", role="Technician")["data"]
		self.assertEqual(out["phone"], phone)
		user = frappe.get_doc("User", out["user"])
		self.assertEqual(user.full_name, "Ravi M")
		self.assertEqual(user.mobile_no, phone)
		self.assertIn("Technician", [r.role for r in user.roles])

	def test_the_account_can_sign_in_by_phone(self):
		"""The only identity that matters: auth.py looks a user up by mobile_no."""
		phone = self._phone()
		staff.create_staff(phone=phone, full_name="Asha K", role="Technician")
		self.assertTrue(frappe.db.exists("User", {"mobile_no": phone, "enabled": 1}))

	def test_email_is_optional(self):
		phone = self._phone()
		out = staff.create_staff(phone=phone, full_name="No Email", role="Technician")["data"]
		self.assertTrue(out["user"].endswith("@naarni.phone"))

	def test_email_when_given_becomes_the_address(self):
		phone = self._phone()
		email = f"real-{frappe.generate_hash(length=6)}@naarni.com"
		out = staff.create_staff(phone=phone, full_name="With Email", role="Technician", email=email)["data"]
		self.assertEqual(out["user"], email)
		# And phone sign-in still resolves, which is what makes it safe.
		self.assertTrue(frappe.db.exists("User", {"mobile_no": phone}))

	def test_a_name_is_required(self):
		with self.assertRaises(frappe.ValidationError):
			staff.create_staff(phone=self._phone(), full_name="  ", role="Technician")

	def test_a_role_is_required(self):
		with self.assertRaises(frappe.ValidationError):
			staff.create_staff(phone=self._phone(), full_name="Nameless Role", role="")

	def test_a_short_phone_is_refused(self):
		with self.assertRaises(frappe.ValidationError):
			staff.create_staff(phone="12345", full_name="Too Short", role="Technician")

	def test_the_same_number_cannot_be_added_twice(self):
		phone = self._phone()
		staff.create_staff(phone=phone, full_name="First", role="Technician")
		with self.assertRaises(frappe.ValidationError):
			staff.create_staff(phone=phone, full_name="Second", role="Technician")

	def test_a_formatted_number_is_the_same_number(self):
		"""'+91 98765 43210' and '9876543210' must not become two accounts."""
		phone = self._phone()
		staff.create_staff(phone=phone, full_name="Canonical", role="Technician")
		with self.assertRaises(frappe.ValidationError):
			staff.create_staff(phone=f"+91 {phone}", full_name="Duplicate", role="Technician")


class TestDepotAccess(StaffTestBase):
	def test_a_service_engineer_can_be_given_several_depots(self):
		phone = self._phone()
		out = staff.create_staff(
			phone=phone,
			full_name="Multi Depot",
			role="Service Engineer",
			depots=[self.depot_a, self.depot_b],
		)["data"]
		self.assertEqual(sorted(out["depots"]), sorted([self.depot_a, self.depot_b]))

		from vehicle_maintenance.api.tickets import _user_depots

		self.assertEqual(sorted(_user_depots(out["user"])), sorted([self.depot_a, self.depot_b]))

	def test_depots_are_ignored_for_a_role_that_is_not_depot_scoped(self):
		out = staff.create_staff(
			phone=self._phone(), full_name="Bench Tech", role="Technician", depots=[self.depot_a]
		)["data"]
		self.assertEqual(out["depots"], [])

	def test_a_depot_that_does_not_exist_is_refused(self):
		with self.assertRaises(frappe.ValidationError):
			staff.create_staff(
				phone=self._phone(),
				full_name="Bad Depot",
				role="Service Engineer",
				depots=["No Such Depot"],
			)

	def test_no_depots_is_allowed(self):
		out = staff.create_staff(phone=self._phone(), full_name="Unassigned SE", role="Service Engineer")[
			"data"
		]
		self.assertEqual(out["depots"], [])


class TestEscalationBoundary(StaffTestBase):
	def test_system_manager_can_never_be_assigned(self):
		"""The hole this endpoint must not be."""
		self.assertNotIn("System Manager", staff.ASSIGNABLE_ROLES)
		with self.assertRaises(frappe.ValidationError):
			staff.create_staff(phone=self._phone(), full_name="Would Be Admin", role="System Manager")

	def test_a_depot_manager_cannot_assign_an_elevated_role(self):
		actor = self._actor(["Depot Manager"])
		frappe.set_user(actor)
		with self.assertRaises(frappe.PermissionError):
			staff.create_staff(phone=self._phone(), full_name="Elevated", role="Central Ops")

	def test_a_depot_manager_can_still_add_a_technician(self):
		actor = self._actor(["Depot Manager"])
		frappe.set_user(actor)
		out = staff.create_staff(phone=self._phone(), full_name="Floor Tech", role="Technician")
		self.assertTrue(out["success"])

	def test_somebody_with_no_admin_role_cannot_add_anyone(self):
		actor = self._actor(["Technician"])
		frappe.set_user(actor)
		with self.assertRaises(frappe.PermissionError):
			staff.create_staff(phone=self._phone(), full_name="Sneaky", role="Technician")

	def test_the_role_list_hides_elevated_roles_from_a_depot_manager(self):
		actor = self._actor(["Depot Manager"])
		frappe.set_user(actor)
		offered = {r["name"] for r in staff.assignable_roles()["data"]["roles"]}
		self.assertIn("Technician", offered)
		self.assertNotIn("Central Ops", offered)
		self.assertNotIn("System Manager", offered)


class TestGroupAdmin(StaffTestBase):
	"""Opening a chat room is now a permission, not a side effect of seniority."""

	def test_a_service_engineer_can_no_longer_create_a_group(self):
		actor = self._actor(["Service Engineer"])
		frappe.set_user(actor)
		with self.assertRaises(frappe.PermissionError):
			chat.create_room(title="Ad hoc room", kind="Group")

	def test_a_depot_manager_can_no_longer_either(self):
		actor = self._actor(["Depot Manager"])
		frappe.set_user(actor)
		with self.assertRaises(frappe.PermissionError):
			chat.create_room(title="Ad hoc room", kind="Group")

	def test_a_group_admin_can(self):
		actor = self._actor(["Service Engineer", "Group Admin"])
		frappe.set_user(actor)
		out = chat.create_room(title="Sanctioned room", kind="Group")
		self.assertTrue(out["success"])
		room = frappe.get_doc("VM Chat Room", out["data"]["room"])
		# The creator is enrolled as an Admin member, as before.
		self.assertEqual([m.member_role for m in room.members if m.user == actor], ["Admin"])

	def test_the_role_can_be_granted_through_the_staff_screen(self):
		out = staff.create_staff(phone=self._phone(), full_name="Room Opener", role="Group Admin")["data"]
		self.assertIn("Group Admin", frappe.get_roles(out["user"]))
