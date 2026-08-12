"""Duty roster & attendance: shift windows, punch pairing, lateness, geofence.

Run:
    bench --site <site> run-tests --module vehicle_maintenance.fleet_service.test_roster
"""

from datetime import datetime, timedelta

import frappe
from frappe.tests.utils import FrappeTestCase
from frappe.utils import add_days, getdate

from vehicle_maintenance.api import roster as API
from vehicle_maintenance.fleet_service import roster as R

DEPOT = "Roster Test Depot"
ENGINEER = "roster_test_engineer@example.com"
SHIFT_DAY = "Roster Test Day"
SHIFT_NIGHT = "Roster Test Night"


class TestRoster(FrappeTestCase):
	@classmethod
	def setUpClass(cls):
		super().setUpClass()
		cls._ensure_depot()
		cls._ensure_engineer()
		cls._ensure_shifts()

	def setUp(self):
		self.today = getdate()
		# Punch tests use absolute clock times (09:00, 17:30…). Anchoring them to a
		# past day keeps them deterministic: on today's date, "check out at 17:30"
		# is a future timestamp whenever the suite runs in the morning, and
		# Duty Punch rightly rejects that as a bad device clock.
		self.day = add_days(self.today, -2)
		self._purge()

	def tearDown(self):
		self._purge()

	# ------------------------------------------------------------------ fixtures

	@classmethod
	def _ensure_depot(cls):
		if not frappe.db.exists("Depot", DEPOT):
			frappe.get_doc(
				{
					"doctype": "Depot",
					"depot_name": DEPOT,
					"location_code": "RTD01",
					"city": "Bengaluru",
					# Kempegowda-ish coordinates; only the arithmetic matters.
					"latitude": 12.971600,
					"longitude": 77.594600,
					"geofence_radius_m": 200,
				}
			).insert(ignore_permissions=True)

	@classmethod
	def _ensure_engineer(cls):
		if not frappe.db.exists("User", ENGINEER):
			user = frappe.get_doc(
				{
					"doctype": "User",
					"email": ENGINEER,
					"first_name": "Roster",
					"last_name": "Tester",
					"mobile_no": "9000000091",
					"send_welcome_email": 0,
				}
			)
			user.flags.ignore_permissions = True
			user.insert(ignore_permissions=True)
		if not frappe.db.exists("Depot Engineer", {"user": ENGINEER, "parent": DEPOT}):
			frappe.get_doc(
				{
					"doctype": "Depot Engineer",
					"parent": DEPOT,
					"parenttype": "Depot",
					"parentfield": "service_engineers",
					"user": ENGINEER,
					"idx": 1,
				}
			).insert(ignore_permissions=True)

	@classmethod
	def _ensure_shifts(cls):
		if not frappe.db.exists("Duty Shift", SHIFT_DAY):
			frappe.get_doc(
				{
					"doctype": "Duty Shift",
					"shift_name": SHIFT_DAY,
					"start_time": "09:00:00",
					"end_time": "18:00:00",
					"grace_minutes": 15,
					"full_day_hours": 8,
					"half_day_hours": 4,
				}
			).insert(ignore_permissions=True)
		if not frappe.db.exists("Duty Shift", SHIFT_NIGHT):
			frappe.get_doc(
				{
					"doctype": "Duty Shift",
					"shift_name": SHIFT_NIGHT,
					"start_time": "22:00:00",
					"end_time": "06:00:00",
					"crosses_midnight": 1,
					"grace_minutes": 15,
					"full_day_hours": 8,
					"half_day_hours": 4,
				}
			).insert(ignore_permissions=True)

	def _purge(self):
		for att in frappe.get_all("Duty Attendance", filters={"user": ENGINEER}, pluck="name"):
			frappe.db.delete("Duty Punch", {"attendance": att})
			frappe.delete_doc("Duty Attendance", att, force=True, ignore_permissions=True)
		frappe.db.delete("Duty Punch", {"user": ENGINEER})
		for r in frappe.get_all("Duty Roster", filters={"depot": DEPOT}, pluck="name"):
			frappe.delete_doc("Duty Roster", r, force=True, ignore_permissions=True)
		frappe.db.commit()

	def _roster(self, shift=SHIFT_DAY, on_date=None, publish=True, is_week_off=0):
		day = on_date or self.today
		doc = frappe.get_doc(
			{
				"doctype": "Duty Roster",
				"depot": DEPOT,
				"from_date": day,
				"to_date": day,
				"status": "Draft",
				"entries": [
					{
						"duty_date": day,
						"engineer": ENGINEER,
						"shift": None if is_week_off else shift,
						"is_week_off": is_week_off,
					}
				],
			}
		)
		doc.insert(ignore_permissions=True)
		if publish:
			doc.publish()
		return doc

	# ------------------------------------------------------------ shift windows

	def test_day_shift_window_is_same_day(self):
		start, end = R.shift_window(self.today, R._shift_doc(SHIFT_DAY))
		self.assertEqual(start.hour, 9)
		self.assertEqual(end.hour, 18)
		self.assertEqual(start.date(), end.date())

	def test_night_shift_ends_next_day(self):
		start, end = R.shift_window(self.today, R._shift_doc(SHIFT_NIGHT))
		self.assertEqual(start.hour, 22)
		self.assertEqual(end.hour, 6)
		# The whole point: 22:00 to 06:00 is eight hours, not minus sixteen.
		self.assertEqual((end - start), timedelta(hours=8))

	def test_entry_override_beats_the_shift(self):
		start, end = R.shift_window(self.today, R._shift_doc(SHIFT_DAY), "07:30:00", "16:30:00")
		self.assertEqual((start.hour, start.minute), (7, 30))
		self.assertEqual((end.hour, end.minute), (16, 30))

	def test_shift_rejects_a_missing_next_day_tick(self):
		doc = frappe.get_doc(
			{
				"doctype": "Duty Shift",
				"shift_name": "Roster Test Broken",
				"start_time": "22:00:00",
				"end_time": "06:00:00",
			}
		)
		with self.assertRaises(frappe.ValidationError):
			doc.insert(ignore_permissions=True)

	# ------------------------------------------------------------------- roster

	def test_draft_roster_is_invisible_to_the_engineer(self):
		self._roster(publish=False)
		self.assertIsNone(R.get_planned_duty(ENGINEER, self.today))

	def test_published_roster_resolves_the_plan(self):
		self._roster()
		planned = R.get_planned_duty(ENGINEER, self.today)
		self.assertIsNotNone(planned)
		self.assertEqual(planned["shift"], SHIFT_DAY)
		self.assertEqual(planned["depot"], DEPOT)
		self.assertEqual(planned["planned_start"].hour, 9)

	def test_same_engineer_twice_on_one_date_is_rejected(self):
		doc = frappe.get_doc(
			{
				"doctype": "Duty Roster",
				"depot": DEPOT,
				"from_date": self.today,
				"to_date": self.today,
				"entries": [
					{"duty_date": self.today, "engineer": ENGINEER, "shift": SHIFT_DAY},
					{"duty_date": self.today, "engineer": ENGINEER, "shift": SHIFT_NIGHT},
				],
			}
		)
		with self.assertRaises(frappe.ValidationError):
			doc.insert(ignore_permissions=True)

	def test_entry_outside_the_period_is_rejected(self):
		doc = frappe.get_doc(
			{
				"doctype": "Duty Roster",
				"depot": DEPOT,
				"from_date": self.today,
				"to_date": self.today,
				"entries": [{"duty_date": add_days(self.today, 3), "engineer": ENGINEER, "shift": SHIFT_DAY}],
			}
		)
		with self.assertRaises(frappe.ValidationError):
			doc.insert(ignore_permissions=True)

	def test_fill_generates_working_days_and_week_offs(self):
		doc = frappe.get_doc(
			{
				"doctype": "Duty Roster",
				"depot": DEPOT,
				"from_date": self.today,
				"to_date": add_days(self.today, 6),
			}
		)
		doc.insert(ignore_permissions=True)
		added = doc.fill(engineers=[ENGINEER], shift=SHIFT_DAY, week_off_days=[6])
		doc.save()

		self.assertEqual(added, 7)
		offs = [r for r in doc.entries if r.is_week_off]
		self.assertEqual(len(offs), 1)
		self.assertEqual(getdate(offs[0].duty_date).weekday(), 6)
		# A week off carries no shift; every other day does.
		self.assertTrue(all(r.shift == SHIFT_DAY for r in doc.entries if not r.is_week_off))

	def test_fill_is_additive_and_does_not_duplicate(self):
		doc = frappe.get_doc(
			{
				"doctype": "Duty Roster",
				"depot": DEPOT,
				"from_date": self.today,
				"to_date": add_days(self.today, 2),
			}
		)
		doc.insert(ignore_permissions=True)
		doc.fill(engineers=[ENGINEER], shift=SHIFT_DAY)
		doc.fill(engineers=[ENGINEER], shift=SHIFT_DAY)
		doc.save()
		self.assertEqual(len(doc.entries), 3)

	# ------------------------------------------------------------------ punches

	def test_check_in_then_out_produces_hours(self):
		self._roster(on_date=self.day)
		base = datetime.combine(self.day, datetime.min.time())

		R.record_punch(R.PUNCH_IN, user=ENGINEER, punch_time=base + timedelta(hours=9))
		R.record_punch(R.PUNCH_OUT, user=ENGINEER, punch_time=base + timedelta(hours=17, minutes=30))

		att = frappe.get_doc("Duty Attendance", {"dedup_key": f"{ENGINEER}|{self.day}"})
		self.assertEqual(att.worked_hours, 8.5)
		self.assertEqual(att.status, R.STATUS_PRESENT)
		self.assertEqual(att.punch_count, 2)
		self.assertFalse(att.is_late)

	def test_open_check_in_reads_as_on_duty_and_banks_no_hours(self):
		self._roster(on_date=self.day)
		base = datetime.combine(self.day, datetime.min.time())
		R.record_punch(R.PUNCH_IN, user=ENGINEER, punch_time=base + timedelta(hours=9))

		att = frappe.get_doc("Duty Attendance", {"dedup_key": f"{ENGINEER}|{self.day}"})
		self.assertEqual(att.status, R.STATUS_ON_DUTY)
		# An in-progress shift must not write a total that changes on every read.
		self.assertEqual(att.worked_hours, 0)

	def test_multiple_pairs_sum(self):
		self._roster(on_date=self.day)
		base = datetime.combine(self.day, datetime.min.time())
		for h_in, h_out in ((9, 13), (14, 18)):
			R.record_punch(R.PUNCH_IN, user=ENGINEER, punch_time=base + timedelta(hours=h_in))
			R.record_punch(R.PUNCH_OUT, user=ENGINEER, punch_time=base + timedelta(hours=h_out))

		att = frappe.get_doc("Duty Attendance", {"dedup_key": f"{ENGINEER}|{self.day}"})
		self.assertEqual(att.worked_hours, 8.0)
		self.assertEqual(att.status, R.STATUS_PRESENT)

	def test_late_beyond_grace_is_flagged(self):
		self._roster(on_date=self.day)
		base = datetime.combine(self.day, datetime.min.time())
		R.record_punch(R.PUNCH_IN, user=ENGINEER, punch_time=base + timedelta(hours=9, minutes=40))
		R.record_punch(R.PUNCH_OUT, user=ENGINEER, punch_time=base + timedelta(hours=18))

		att = frappe.get_doc("Duty Attendance", {"dedup_key": f"{ENGINEER}|{self.day}"})
		self.assertTrue(att.is_late)
		self.assertEqual(att.late_by_minutes, 40)

	def test_arrival_inside_grace_is_not_late(self):
		self._roster(on_date=self.day)
		base = datetime.combine(self.day, datetime.min.time())
		R.record_punch(R.PUNCH_IN, user=ENGINEER, punch_time=base + timedelta(hours=9, minutes=10))
		R.record_punch(R.PUNCH_OUT, user=ENGINEER, punch_time=base + timedelta(hours=18))

		att = frappe.get_doc("Duty Attendance", {"dedup_key": f"{ENGINEER}|{self.day}"})
		self.assertFalse(att.is_late)
		self.assertEqual(att.late_by_minutes, 0)

	def test_short_day_is_half_day_never_absent(self):
		# Someone who actually worked must never be auto-marked absent — that is
		# the thing people dispute, and rightly.
		self._roster(on_date=self.day)
		base = datetime.combine(self.day, datetime.min.time())
		R.record_punch(R.PUNCH_IN, user=ENGINEER, punch_time=base + timedelta(hours=9))
		R.record_punch(R.PUNCH_OUT, user=ENGINEER, punch_time=base + timedelta(hours=10))

		att = frappe.get_doc("Duty Attendance", {"dedup_key": f"{ENGINEER}|{self.day}"})
		self.assertEqual(att.worked_hours, 1.0)
		self.assertEqual(att.status, R.STATUS_HALF_DAY)

	def test_punch_is_idempotent_on_client_uuid(self):
		self._roster()
		uuid = "roster-test-uuid-1"
		first = R.record_punch(R.PUNCH_IN, user=ENGINEER, client_uuid=uuid)
		second = R.record_punch(R.PUNCH_IN, user=ENGINEER, client_uuid=uuid)

		self.assertFalse(first["duplicate"])
		self.assertTrue(second["duplicate"])
		self.assertEqual(first["punch"]["name"], second["punch"]["name"])
		self.assertEqual(frappe.db.count("Duty Punch", {"user": ENGINEER}), 1)

	def test_week_off_status(self):
		self._roster(is_week_off=1)
		att = R.get_or_create_attendance(ENGINEER, self.today)
		self.assertEqual(att.status, R.STATUS_WEEK_OFF)

	def test_unrostered_day_still_records_a_punch(self):
		# allow_punch_without_roster is on by default: a depot that has not yet
		# published a roster must not lose its attendance entirely.
		R.record_punch(R.PUNCH_IN, user=ENGINEER)
		att = frappe.get_doc("Duty Attendance", {"dedup_key": f"{ENGINEER}|{self.today}"})
		self.assertEqual(att.status, R.STATUS_ON_DUTY)
		self.assertIsNone(att.shift)

	def test_night_shift_checkout_lands_on_the_start_day(self):
		night = add_days(self.today, -3)
		self._roster(shift=SHIFT_NIGHT, on_date=night)
		base = datetime.combine(night, datetime.min.time())

		R.record_punch(R.PUNCH_IN, user=ENGINEER, punch_time=base + timedelta(hours=22))
		# 06:00 the next morning — this belongs to yesterday's duty, not today's.
		R.record_punch(R.PUNCH_OUT, user=ENGINEER, punch_time=base + timedelta(hours=30))

		att = frappe.get_doc("Duty Attendance", {"dedup_key": f"{ENGINEER}|{night}"})
		self.assertEqual(att.worked_hours, 8.0)
		self.assertEqual(att.punch_count, 2)
		next_day = add_days(night, 1)
		self.assertFalse(frappe.db.exists("Duty Attendance", {"dedup_key": f"{ENGINEER}|{next_day}"}))

	def test_manual_override_survives_recompute(self):
		self._roster()
		R.record_punch(R.PUNCH_IN, user=ENGINEER)
		att = frappe.get_doc("Duty Attendance", {"dedup_key": f"{ENGINEER}|{self.today}"})
		att.manual_status = R.STATUS_PRESENT
		att.override_reason = "Phone died on site"
		att.save(ignore_permissions=True)

		R.recompute(att.name)
		self.assertEqual(frappe.db.get_value("Duty Attendance", att.name, "status"), R.STATUS_PRESENT)

	def test_override_without_a_reason_is_rejected(self):
		self._roster()
		att = R.get_or_create_attendance(ENGINEER, self.today)
		att.manual_status = R.STATUS_ABSENT
		with self.assertRaises(frappe.ValidationError):
			att.save(ignore_permissions=True)

	# ----------------------------------------------------------------- geofence

	def test_geofence_flags_a_distant_punch_but_still_records_it(self):
		self._settings(geofence_mode="Warn")
		self._roster()
		# ~1.5 km north of the depot.
		result = R.record_punch(R.PUNCH_IN, user=ENGINEER, latitude=12.985000, longitude=77.594600)
		punch = frappe.get_doc("Duty Punch", result["punch"]["name"])
		self.assertTrue(punch.outside_geofence)
		self.assertGreater(punch.distance_from_depot_m, 200)
		self.assertTrue(result["warnings"])

	def test_geofence_accepts_a_punch_at_the_depot(self):
		self._settings(geofence_mode="Warn")
		self._roster()
		result = R.record_punch(R.PUNCH_IN, user=ENGINEER, latitude=12.971650, longitude=77.594650)
		punch = frappe.get_doc("Duty Punch", result["punch"]["name"])
		self.assertFalse(punch.outside_geofence)
		self.assertLess(punch.distance_from_depot_m, 200)
		self.assertEqual(result["warnings"], [])

	def test_missing_location_never_blocks_by_default(self):
		self._settings(geofence_mode="Warn", require_location=0)
		self._roster()
		result = R.record_punch(R.PUNCH_IN, user=ENGINEER)
		punch = frappe.get_doc("Duty Punch", result["punch"]["name"])
		self.assertFalse(punch.location_available)
		self.assertFalse(punch.outside_geofence)
		self.assertTrue(result["warnings"])

	def test_geofence_off_records_no_distance(self):
		self._settings(geofence_mode="Off")
		self._roster()
		result = R.record_punch(R.PUNCH_IN, user=ENGINEER, latitude=12.985000, longitude=77.594600)
		punch = frappe.get_doc("Duty Punch", result["punch"]["name"])
		self.assertFalse(punch.outside_geofence)
		self.assertFalse(punch.distance_from_depot_m)

	def test_haversine_matches_a_known_distance(self):
		# One degree of latitude is ~111.19 km anywhere on the globe.
		metres = R.haversine_m(12.0, 77.0, 13.0, 77.0)
		self.assertAlmostEqual(metres / 1000.0, 111.19, delta=0.5)

	def _settings(self, **values):
		cfg = frappe.get_single("Roster Settings")
		for k, v in values.items():
			setattr(cfg, k, v)
		cfg.save(ignore_permissions=True)
		frappe.clear_document_cache("Roster Settings", "Roster Settings")

	# --------------------------------------------------------------------- API

	def test_role_without_punch_permission_gets_no_duty_card(self):
		outsider = "roster_test_outsider@example.com"
		if not frappe.db.exists("User", outsider):
			doc = frappe.get_doc(
				{
					"doctype": "User",
					"email": outsider,
					"first_name": "Roster",
					"last_name": "Outsider",
					"mobile_no": "9000000092",
					"send_welcome_email": 0,
				}
			)
			doc.flags.ignore_permissions = True
			doc.insert(ignore_permissions=True)

		frappe.set_user(outsider)
		try:
			data = API.get_my_duty()["data"]
			self.assertFalse(data["can_punch"])
			self.assertEqual(data["next_action"], "none")
			with self.assertRaises(frappe.PermissionError):
				API.punch(punch_type=R.PUNCH_IN)
		finally:
			frappe.set_user("Administrator")

	def test_api_retry_with_the_same_uuid_is_not_a_double_punch(self):
		# Regression: the state-machine guard used to run before the idempotency
		# lookup, so a retry after a network timeout came back as "You are already
		# checked in" — exactly when the retry was legitimate.
		self._grant_engineer_role()
		self._roster()
		frappe.set_user(ENGINEER)
		try:
			first = API.punch(punch_type=R.PUNCH_IN, client_uuid="retry-test-1")
			second = API.punch(punch_type=R.PUNCH_IN, client_uuid="retry-test-1")

			self.assertFalse(first["data"]["duplicate"])
			self.assertTrue(second["data"]["duplicate"])
			self.assertEqual(first["data"]["punch"]["name"], second["data"]["punch"]["name"])
			self.assertEqual(frappe.db.count("Duty Punch", {"user": ENGINEER}), 1)
		finally:
			frappe.set_user("Administrator")

	def test_api_blocks_a_genuine_second_check_in(self):
		self._grant_engineer_role()
		self._roster()
		frappe.set_user(ENGINEER)
		try:
			API.punch(punch_type=R.PUNCH_IN)
			with self.assertRaises(frappe.ValidationError):
				API.punch(punch_type=R.PUNCH_IN)
		finally:
			frappe.set_user("Administrator")

	def test_api_rejects_check_out_when_not_checked_in(self):
		self._grant_engineer_role()
		self._roster()
		frappe.set_user(ENGINEER)
		try:
			with self.assertRaises(frappe.ValidationError):
				API.punch(punch_type=R.PUNCH_OUT)
		finally:
			frappe.set_user("Administrator")

	def test_board_shows_a_rostered_engineer_who_never_arrived(self):
		# The row a depot manager actually opens the board to find.
		self._grant_engineer_role()
		self._roster()
		board = API.get_attendance_board(depot=DEPOT)["data"]
		mine = [r for r in board["rows"] if r["user"] == ENGINEER]
		self.assertEqual(len(mine), 1)
		self.assertEqual(mine[0]["status"], R.STATUS_NOT_STARTED)
		self.assertEqual(board["summary"]["not_started"], 1)

	def _grant_engineer_role(self):
		user = frappe.get_doc("User", ENGINEER)
		if "Service Engineer" not in [r.role for r in user.roles]:
			user.append("roles", {"role": "Service Engineer"})
			user.flags.ignore_permissions = True
			user.save(ignore_permissions=True)
