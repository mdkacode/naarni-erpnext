# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Invite-only access, and the profile a person fills in once they are through.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_onboarding

The property this file exists to protect is the one that would be catastrophic
and silent: **closing signup must not lock out the people already using the
app.** Everything else here is ordinary validation; that one is a depot standing
at a gate on a Monday morning.
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import auth, profile
from vehicle_maintenance.fleet_service.doctype.vm_user_invite import vm_user_invite as invites


def _clear(phone: str) -> None:
	for name in frappe.get_all("VM User Invite", filters={"phone": phone}, pluck="name"):
		frappe.delete_doc("VM User Invite", name, force=True, ignore_permissions=True)
	for name in frappe.get_all("User", filters={"mobile_no": phone}, pluck="name"):
		frappe.delete_doc("User", name, force=True, ignore_permissions=True)


class OnboardingTestBase(FrappeTestCase):
	def setUp(self):
		frappe.set_user("Administrator")
		self.addCleanup(frappe.db.rollback)
		self.addCleanup(frappe.set_user, "Administrator")
		# Sending is not what these tests are about, and a bench with no mail
		# account configured would otherwise fail them for the wrong reason.
		self._real_sendmail = frappe.sendmail
		frappe.sendmail = lambda **kw: self.sent.append(kw)
		self.sent = []
		self.addCleanup(setattr, frappe, "sendmail", self._real_sendmail)
		if not frappe.db.exists("VM Designation", "Technician"):
			frappe.get_doc(
				{"doctype": "VM Designation", "designation_name": "Technician", "sort_order": 10}
			).insert(ignore_permissions=True)

	def _invite(self, phone, name="New Joiner", **extra):
		return frappe.get_doc(
			{"doctype": "VM User Invite", "phone": phone, "full_name": name, **extra}
		).insert(ignore_permissions=True)


class TestInviteGate(OnboardingTestBase):
	"""Who is allowed to become a user at all."""

	def test_an_uninvited_number_cannot_create_an_account(self):
		phone = "9198000001"
		_clear(phone)

		with self.assertRaises(frappe.AuthenticationError):
			auth._provision_naarni_user(phone, naarni_uuid=None, authorities=[])

		self.assertFalse(frappe.db.exists("User", {"mobile_no": phone}))

	def test_an_invited_number_gets_an_account_with_its_invited_name(self):
		phone = "9198000002"
		_clear(phone)
		self._invite(phone, "Ravi Kumar")

		user = auth._provision_naarni_user(phone, naarni_uuid=None, authorities=[])

		self.assertEqual(frappe.db.get_value("User", user, "full_name"), "Ravi Kumar")

	def test_accepting_an_invite_records_who_used_it(self):
		phone = "9198000003"
		_clear(phone)
		invite = self._invite(phone)

		user = auth._provision_naarni_user(phone, naarni_uuid=None, authorities=[])

		invite.reload()
		self.assertEqual(invite.status, "Accepted")
		self.assertEqual(invite.user, user)
		self.assertTrue(invite.accepted_at)

	def test_an_existing_user_without_an_invite_still_gets_in(self):
		"""The regression that would empty a depot.

		Everyone using the app today predates invites. If the gate is ever moved
		above the existing-user branch, they all stop at the login screen — and
		the failure looks exactly like a broken OTP, so it would be diagnosed
		slowly and in public.
		"""
		phone = "9198000004"
		_clear(phone)
		frappe.get_doc(
			{
				"doctype": "User",
				"email": f"{phone}@naarni.phone",
				"first_name": "Already Here",
				"mobile_no": phone,
				"user_type": "System User",
				"send_welcome_email": 0,
			}
		).insert(ignore_permissions=True)

		user = auth._provision_naarni_user(phone, naarni_uuid=None, authorities=[])

		self.assertEqual(frappe.db.get_value("User", user, "full_name"), "Already Here")

	def test_a_revoked_invite_does_not_open_the_gate(self):
		phone = "9198000005"
		_clear(phone)
		invite = self._invite(phone)
		invite.db_set("status", "Revoked", update_modified=False)

		with self.assertRaises(frappe.AuthenticationError):
			auth._provision_naarni_user(phone, naarni_uuid=None, authorities=[])

	def test_an_accepted_invite_is_not_reusable(self):
		# One invite, one account. A second person cannot ride in on somebody
		# else's acceptance by presenting the same number after they left.
		phone = "9198000006"
		_clear(phone)
		self._invite(phone)
		auth._provision_naarni_user(phone, naarni_uuid=None, authorities=[])
		frappe.db.delete("User", {"mobile_no": phone})

		with self.assertRaises(frappe.AuthenticationError):
			auth._provision_naarni_user(phone, naarni_uuid=None, authorities=[])

	def test_the_gate_can_be_switched_off_per_site(self):
		# A site mid-migration, or a demo, may want the old open behaviour, and
		# finding that out only from a code change is worse than a config key.
		phone = "9198000007"
		_clear(phone)
		frappe.conf.require_user_invite = 0
		self.addCleanup(frappe.conf.pop, "require_user_invite", None)

		user = auth._provision_naarni_user(phone, naarni_uuid=None, authorities=[])

		self.assertTrue(frappe.db.exists("User", user))

	def test_a_stray_country_code_cannot_walk_around_the_gate(self):
		"""The invite and the login must agree on what the number *is*."""
		_clear("9198000008")
		self._invite("+91 98000 00008")

		stored = frappe.db.get_value("VM User Invite", {"full_name": "New Joiner"}, "phone")
		self.assertEqual(stored, invites.normalize_phone("+91 98000 00008"))


class TestInviteRecord(OnboardingTestBase):
	def test_the_joiner_is_told(self):
		phone = "9198000010"
		_clear(phone)
		self._invite(phone, "Sunil", email="sunil@example.com")

		self.assertEqual(len(self.sent), 1)
		self.assertEqual(self.sent[0]["recipients"], ["sunil@example.com"])
		self.assertIn(invites.normalize_phone(phone), self.sent[0]["message"])

	def test_no_email_address_is_not_an_error(self):
		# Not every driver has one. The invite still works; the supervisor tells
		# them instead.
		phone = "9198000011"
		_clear(phone)
		invite = self._invite(phone)

		self.assertEqual(self.sent, [])
		self.assertEqual(invite.status, "Pending")

	def test_a_dead_mail_server_does_not_lose_the_invite(self):
		phone = "9198000012"
		_clear(phone)

		def boom(**kw):
			raise Exception("smtp is down")

		frappe.sendmail = boom
		invite = self._invite(phone, email="x@example.com")

		self.assertEqual(invite.status, "Pending")
		self.assertFalse(invite.email_sent_at)

	def test_one_invite_per_number(self):
		phone = "9198000013"
		_clear(phone)
		self._invite(phone)

		with self.assertRaises((frappe.UniqueValidationError, frappe.DuplicateEntryError)):
			self._invite(phone, "Someone Else")

	def test_accepted_cannot_be_set_by_hand(self):
		phone = "9198000014"
		_clear(phone)

		with self.assertRaises(frappe.ValidationError):
			self._invite(phone, status="Accepted")


class TestProfile(OnboardingTestBase):
	def setUp(self):
		super().setUp()
		self.phone = "9198000020"
		_clear(self.phone)
		self._invite(self.phone, "Anil Sharma")
		self.user = auth._provision_naarni_user(self.phone, naarni_uuid=None, authorities=[])
		frappe.set_user(self.user)

	def test_a_fresh_account_reports_what_is_missing(self):
		data = profile.get_my_profile()["data"]

		self.assertTrue(data["has_name"])
		self.assertFalse(data["has_photo"])
		self.assertFalse(data["profile_complete"])

	def test_an_account_still_named_after_its_phone_number_does_not_count_as_named(self):
		"""A phone-provisioned account starts with its own number as a name.

		"Has a full_name" is therefore the wrong question — every account passes
		it — and getting this wrong would mean the banner never appears for the
		exact people who need it.
		"""
		frappe.db.set_value("User", self.user, "first_name", self.phone, update_modified=False)
		frappe.db.set_value("User", self.user, "full_name", self.phone, update_modified=False)

		self.assertFalse(profile.get_my_profile()["data"]["has_name"])

	def test_saving_a_name(self):
		out = profile.update_my_profile(full_name="  Anil K Sharma  ")

		self.assertEqual(out["data"]["full_name"], "Anil K Sharma")
		self.assertTrue(out["data"]["has_name"])

	def test_a_blank_name_is_refused(self):
		with self.assertRaises(frappe.ValidationError):
			profile.update_my_profile(full_name="   ")

	def test_designation_must_come_from_the_list(self):
		with self.assertRaises(frappe.ValidationError):
			profile.update_my_profile(designation="Chief Vibes Officer")

	def test_designation_from_the_list_is_kept(self):
		out = profile.update_my_profile(designation="Technician")

		self.assertEqual(out["data"]["designation"], "Technician")

	def test_fields_save_independently(self):
		# The onboarding screens save as they go; holding everything until a
		# final Done is what loses work when the app is killed mid-flow.
		profile.update_my_profile(full_name="Anil Sharma")
		profile.update_my_profile(designation="Technician")

		data = profile.get_my_profile()["data"]
		self.assertEqual(data["full_name"], "Anil Sharma")
		self.assertEqual(data["designation"], "Technician")

	def test_the_prompt_can_be_snoozed_and_says_until_when(self):
		out = profile.snooze_profile_prompt()

		self.assertTrue(out["data"]["prompt_snoozed_until"])


class TestProfilePhoto(OnboardingTestBase):
	def setUp(self):
		super().setUp()
		self.phone = "9198000030"
		_clear(self.phone)
		self._invite(self.phone, "Photo Person")
		self.user = auth._provision_naarni_user(self.phone, naarni_uuid=None, authorities=[])
		frappe.set_user(self.user)

	def _file(self, is_private=0, owner=None):
		doc = frappe.get_doc(
			{
				"doctype": "File",
				"file_name": f"face-{frappe.generate_hash(length=6)}.png",
				"is_private": is_private,
				"content": b"\x89PNG\r\n\x1a\n" + b"0" * 64,
			}
		)
		doc.insert(ignore_permissions=True)
		if owner:
			frappe.db.set_value("File", doc.name, "owner", owner, update_modified=False)
		return doc

	def test_a_public_photo_of_your_own_is_adopted(self):
		f = self._file()

		out = profile.set_profile_photo(file_url=f.file_url)

		self.assertTrue(out["data"]["has_photo"])
		self.assertEqual(out["data"]["photo"], f.file_url)
		self.assertTrue(out["data"]["profile_complete"])

	def test_a_private_photo_is_refused(self):
		"""Private avatars render as broken circles for everyone but their owner.

		A File is permission-checked against the doc it hangs off, and a
		technician cannot read another technician's User — so this has to be
		caught here rather than discovered as "why is everyone's picture blank".
		"""
		f = self._file(is_private=1)

		with self.assertRaises(frappe.ValidationError):
			profile.set_profile_photo(file_url=f.file_url)

	def test_you_cannot_wear_somebody_else_s_face(self):
		f = self._file(owner="Administrator")

		with self.assertRaises(frappe.PermissionError):
			profile.set_profile_photo(file_url=f.file_url)

	def test_an_unknown_url_is_refused(self):
		with self.assertRaises(frappe.ValidationError):
			profile.set_profile_photo(file_url="/files/not-a-real-file.png")

	def test_a_photo_can_be_taken_down(self):
		f = self._file()
		profile.set_profile_photo(file_url=f.file_url)

		out = profile.remove_profile_photo()

		self.assertFalse(out["data"]["has_photo"])


class TestNotificationTones(OnboardingTestBase):
	def setUp(self):
		super().setUp()
		self.phone = "9198000040"
		_clear(self.phone)
		self._invite(self.phone, "Tone Person")
		self.user = auth._provision_naarni_user(self.phone, naarni_uuid=None, authorities=[])
		frappe.set_user(self.user)

	def test_a_bundled_tone_is_accepted(self):
		out = profile.set_notification_tones(chat_tone="chime", alert_tone="bell")

		self.assertEqual(out["data"]["chat_tone"], "chime")
		self.assertEqual(out["data"]["alert_tone"], "bell")

	def test_a_sound_from_the_phone_is_accepted(self):
		# The escape hatch: anything the system picker returns, kept verbatim.
		uri = "system:content://media/internal/audio/media/27"

		out = profile.set_notification_tones(chat_tone=uri)

		self.assertEqual(out["data"]["chat_tone"], uri)

	def test_nonsense_is_refused(self):
		with self.assertRaises(frappe.ValidationError):
			profile.set_notification_tones(chat_tone="../../etc/passwd")

	def test_chat_and_alerts_are_separate(self):
		# A breakdown alert at 2am must not sound like a message.
		profile.set_notification_tones(chat_tone="ping")

		self.assertEqual(profile.get_my_profile()["data"]["alert_tone"], "default")

	def test_silence_is_a_choice(self):
		out = profile.set_notification_tones(alert_tone="none", vibrate=0)

		self.assertEqual(out["data"]["alert_tone"], "none")
		self.assertFalse(out["data"]["vibrate"])


class TestDesignationList(OnboardingTestBase):
	def test_only_active_designations_are_offered(self):
		frappe.get_doc(
			{
				"doctype": "VM Designation",
				"designation_name": "Retired Role",
				"is_active": 0,
				"sort_order": 5,
			}
		).insert(ignore_permissions=True)

		names = [d["name"] for d in profile.list_designations()["data"]["designations"]]

		self.assertNotIn("Retired Role", names)

	def test_the_admin_s_order_is_respected(self):
		# The common answers belong at the top of a picker on a phone.
		rows = profile.list_designations()["data"]["designations"]
		orders = [r["sort_order"] for r in rows]

		self.assertEqual(orders, sorted(orders))
