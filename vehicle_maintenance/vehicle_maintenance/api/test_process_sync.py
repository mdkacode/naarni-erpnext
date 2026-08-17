# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Offline sync: an inspection done in a shed, landing on the server intact.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_process_sync

The whole offline design rests on one assumption, and these tests exist to stop
it being an assumption: **the device will send the same batch twice.** Not
occasionally — routinely. The commonest failure on a plant network is not a
refused request, it is a request that was applied and whose response never came
back, and the handset has no way to tell that apart from a request that never
arrived. So it resends.

Everything here is a way of asking "what if it sends it again?":

* the same forty answers, twice — thirty-nine unchanged rows and no second
  deviation, because a replay is not a new answer;
* the same scan, twice — one genealogy row, not two, because the serial in a
  pack's record is evidence and a phantom duplicate is a recall;
* the same submit, twice — one sign-off, not a second one dated an hour later;
* the same photo, twice — one image, because the operator took one.

The second theme is that a batch never fails as a unit. An engineer with a queue
they cannot drain is an engineer who eventually reinstalls the app, and that
takes the inspection with it. So a bad step code costs its own row and nothing
else, and the response says which one it was.
"""

import json

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import process, process_sync
from vehicle_maintenance.process_engine import constants as C

CODE = "TEST-SYNC-PROC"
ENTITY = "TEST-SYNC-MODULE"
OUTCOME_SET = "TEST-SYNC-OUTCOMES"


class SyncTestBase(FrappeTestCase):
	def setUp(self):
		frappe.set_user("Administrator")
		self.addCleanup(frappe.db.rollback)
		self.addCleanup(frappe.set_user, "Administrator")
		self.definition = _ensure_definition()
		_ensure_entity_type()
		self.uuid = frappe.generate_hash(length=20)
		# A label unique to each test. One pack now means one run: a second batch
		# quoting a label that is already open *joins* that inspection rather than
		# starting a rival one — which is the point of the feature and fatal to a
		# fixture that reused a single identifier across tests whose runs commit.
		self.pack = f"PACK-SYNC-{frappe.generate_hash(length=8).upper()}"

	# -- helpers ------------------------------------------------------------

	def batch(self, **over) -> dict:
		"""A minimal well-formed batch for this process."""
		payload = {
			"client_uuid": self.uuid,
			"process": CODE,
			"identifier": self.pack,
			"answers": [],
			"scans": [],
			"submit_stages": [],
		}
		payload.update(over)
		return payload

	def sync(self, batch: dict) -> dict:
		"""Post a batch the way the app does — as a JSON string."""
		return process_sync.sync_run(json.dumps(batch))["data"]

	def answer(self, step_code: str, seq: int, **over) -> dict:
		row = {
			"step_code": step_code,
			"response": "Pass",
			"client_seq": seq,
			"answered_at": frappe.utils.now_datetime().strftime("%Y-%m-%d %H:%M:%S"),
		}
		row.update(over)
		return row

	def every_answer(self, **over) -> list[dict]:
		"""Every mandatory step answered — what a stage submit actually needs.

		Named rather than inlined because the interlock is real: forget the
		torque check and the submit is correctly rejected, and the test that
		meant to prove something about sign-offs proves something about the
		interlock instead.
		"""
		return [
			self.answer("C1", 1, **over),
			self.answer("C2", 2, **over),
			self.answer("T1", 3, response=None, value=10.0),
		]

	def run_doc(self):
		name = frappe.db.get_value("Process Run", {"client_uuid": self.uuid}, "name")
		self.assertTrue(name, "the batch did not create a run")
		return frappe.get_doc("Process Run", name)


class TestRunCreation(SyncTestBase):
	def test_a_run_started_offline_is_created_on_first_sync(self):
		out = self.sync(self.batch(answers=[self.answer("C1", 1)]))

		self.assertTrue(out["sync"]["created"])
		self.assertEqual(out["run_identifier"], self.pack)
		self.assertEqual(len(out["results"]), 1)

	def test_the_second_sync_finds_the_same_run(self):
		"""The identity is the UUID the app minted at Start, not the server name.

		If this ever regresses, an engineer with a flaky link produces one
		Process Run per retry — a shelf of half-finished duplicate inspections
		for the same pack, and no way to tell which is the real one.
		"""
		first = self.sync(self.batch(answers=[self.answer("C1", 1)]))
		second = self.sync(self.batch(answers=[self.answer("C2", 2)]))

		self.assertEqual(first["name"], second["name"])
		self.assertFalse(second["sync"]["created"])
		self.assertEqual(
			frappe.db.count("Process Run", {"client_uuid": self.uuid}),
			1,
			"a retry created a duplicate run",
		)

	def test_the_start_time_is_the_handsets_not_the_servers(self):
		"""An inspection done at 09:00 and synced at 17:00 started at 09:00.

		Duration is a number this shop floor reads, and a run that claims to have
		taken eight hours because that is when the van reached signal makes every
		other duration in the report untrustworthy too.
		"""
		started = "2026-08-17 06:30:00"
		self.sync(self.batch(started_at=started, answers=[self.answer("C1", 1)]))

		self.assertEqual(str(self.run_doc().started_at), started)

	def test_a_run_created_online_syncs_by_name(self):
		created = process.start_run(process=CODE, identifier="PACK-ONLINE", client_uuid=None)
		name = created["data"]["name"]

		out = self.sync({"run": name, "answers": [self.answer("C1", 1)]})

		self.assertEqual(out["name"], name)
		self.assertFalse(out["sync"]["created"])


class TestReplayIsFree(SyncTestBase):
	def test_the_same_batch_twice_leaves_one_of_everything(self):
		batch = self.batch(
			answers=[self.answer("C1", 1), self.answer("C2", 2)],
			scans=[_scan("s-1", "MOD-A"), _scan("s-2", "MOD-B")],
		)

		self.sync(batch)
		self.sync(batch)

		doc = self.run_doc()
		self.assertEqual(len(doc.results), 2, "answers duplicated on replay")
		self.assertEqual(len(doc.scans), 2, "scans duplicated on replay")
		self.assertEqual(doc.answered_count, 2)

	def test_ten_replays_are_the_same_as_one(self):
		# The retry loop is exponential but unbounded — over a bad afternoon a
		# batch really can go up ten times.
		batch = self.batch(answers=[self.answer("C1", 1)], scans=[_scan("s-1", "MOD-A")])
		for _ in range(10):
			self.sync(batch)

		doc = self.run_doc()
		self.assertEqual(len(doc.results), 1)
		self.assertEqual(len(doc.scans), 1)

	def test_an_unchanged_answer_reports_itself_as_unchanged(self):
		"""`changed` is what stops actions firing twice — assert it directly.

		The user-visible symptom of losing this is a supervisor's phone buzzing
		once per retry for a deviation that happened once.
		"""
		batch = self.batch(answers=[self.answer("C1", 1, response="Fail")])

		first = self.sync(batch)
		second = self.sync(batch)

		self.assertTrue(first["sync"]["answers_applied"][0]["changed"])
		self.assertFalse(second["sync"]["answers_applied"][0]["changed"])

	def test_a_genuinely_corrected_answer_is_not_mistaken_for_a_replay(self):
		"""The other half of the same rule, and the more dangerous half.

		An operator who re-checks a pack and changes Fail to Pass must have that
		re-judged. Treating it as a replay would leave the deviation standing
		against a battery that is fine.
		"""
		self.sync(self.batch(answers=[self.answer("C1", 1, response="Fail")]))
		out = self.sync(self.batch(answers=[self.answer("C1", 2, response="Pass")]))

		self.assertTrue(out["sync"]["answers_applied"][0]["changed"])
		self.assertEqual(self.run_doc().results[0].response, "Pass")
		self.assertEqual(self.run_doc().results[0].is_pass, 1)

	def test_a_replayed_submit_does_not_sign_off_twice(self):
		batch = self.batch(answers=self.every_answer(), submit_stages=["S1"])

		self.sync(batch)
		self.sync(batch)

		signoffs = [s for s in self.run_doc().signoffs if s.stage == "S1" and s.level == "Operator"]
		self.assertEqual(len(signoffs), 1, "the stage was signed off twice")

	def test_a_replayed_photo_attaches_once(self):
		self.sync(self.batch(answers=[self.answer("C1", 1)]))
		run = self.run_doc().name
		args = dict(
			run=run,
			step_code="C1",
			file_url="/private/files/weld.jpg",
			client_uuid="photo-1",
		)

		first = process_sync.attach_photo_synced(**args)["data"]
		second = process_sync.attach_photo_synced(**args)["data"]

		self.assertFalse(first["duplicate"])
		self.assertTrue(second["duplicate"])
		self.assertEqual(len(self.run_doc().photos), 1)


class TestAnswersLandCorrectly(SyncTestBase):
	def test_the_server_judges_the_answer_not_the_device(self):
		"""The device's own verdict is never sent, and never trusted if it were.

		The on-device evaluator exists so the engineer sees a result instantly.
		The record is the server's, always — which is what makes an advisory
		on-device judgement safe to have at all.
		"""
		out = self.sync(self.batch(answers=[self.answer("C1", 1, response="Fail")]))

		applied = out["sync"]["answers_applied"][0]
		self.assertEqual(applied["is_pass"], 0)
		self.assertEqual(applied["is_deviation"], 1)

	def test_answers_apply_in_the_operators_order_not_json_order(self):
		# Two answers to one step, queued offline in the order the operator gave
		# them. The last one is what they meant.
		out = self.sync(
			self.batch(
				answers=[
					self.answer("C1", 2, response="Pass"),
					self.answer("C1", 1, response="Fail"),
				]
			)
		)

		self.assertEqual(self.run_doc().results[0].response, "Pass")
		self.assertEqual(len(out["sync"]["answers_applied"]), 2)

	def test_a_numeric_answer_is_judged_against_the_band(self):
		self.sync(self.batch(answers=[self.answer("T1", 1, response=None, value=14.0)]))

		row = next(r for r in self.run_doc().results if r.step_code == "T1")
		self.assertEqual(row.value_numeric, 14.0)
		self.assertEqual(row.is_pass, 0, "14 Nm is outside 10 ± 1")

	def test_a_skip_carries_its_reason(self):
		self.sync(
			self.batch(
				answers=[self.answer("C1", 1, response=None, skipped=1, skip_reason="Part not fitted")]
			)
		)

		row = self.run_doc().results[0]
		self.assertEqual(row.is_skipped, 1)
		self.assertEqual(row.skip_reason, "Part not fitted")

	def test_the_answered_time_is_when_the_operator_answered(self):
		when = "2026-08-17 07:15:00"
		self.sync(self.batch(answers=[self.answer("C1", 1, answered_at=when)]))

		self.assertEqual(str(self.run_doc().results[0].answered_at), when)


class TestPartialFailure(SyncTestBase):
	def test_one_bad_step_code_does_not_cost_the_other_answers(self):
		"""The single most important property for a handset that must drain.

		Reject the batch and the device resends it for ever, the queue never
		empties, and the engineer's other thirty-nine answers never arrive.
		"""
		out = self.sync(
			self.batch(
				answers=[
					self.answer("C1", 1),
					self.answer("NO-SUCH-STEP", 2),
					self.answer("C2", 3),
				]
			)
		)

		self.assertEqual(len(out["sync"]["answers_applied"]), 2)
		self.assertEqual(len(out["sync"]["answers_rejected"]), 1)
		self.assertEqual(out["sync"]["answers_rejected"][0]["step_code"], "NO-SUCH-STEP")
		self.assertEqual(len(self.run_doc().results), 2)

	def test_an_answer_with_no_step_code_is_reported_not_thrown(self):
		out = self.sync(self.batch(answers=[{"client_seq": 1}, self.answer("C1", 2)]))

		self.assertEqual(len(out["sync"]["answers_applied"]), 1)
		self.assertEqual(len(out["sync"]["answers_rejected"]), 1)

	def test_a_submit_the_server_will_not_accept_is_reported_with_what_is_missing(self):
		"""The device thought the stage was done; a conditional step says otherwise.

		Naming the missing checks is the difference between an engineer who walks
		back to the pack and one who does not know there is anything to do.
		"""
		out = self.sync(self.batch(answers=[self.answer("C1", 1)], submit_stages=["S1"]))

		rejected = out["sync"]["stages_rejected"]
		self.assertEqual(len(rejected), 1)
		self.assertIn("2", rejected[0]["missing"])
		self.assertEqual(len(self.run_doc().signoffs), 0)

	def test_answers_still_land_when_the_submit_is_rejected(self):
		out = self.sync(self.batch(answers=[self.answer("C1", 1)], submit_stages=["S1"]))

		self.assertEqual(len(out["sync"]["answers_applied"]), 1)
		self.assertEqual(len(self.run_doc().results), 1)

	def test_a_stage_that_is_not_part_of_the_process_is_reported(self):
		out = self.sync(self.batch(answers=[self.answer("C1", 1)], submit_stages=["NOPE"]))

		self.assertEqual(out["sync"]["stages_rejected"][0]["stage"], "NOPE")


class TestSubmitOnSync(SyncTestBase):
	def test_a_stage_finished_offline_is_signed_off_on_sync(self):
		out = self.sync(self.batch(answers=self.every_answer(), submit_stages=["S1"]))

		self.assertEqual(out["sync"]["stages_submitted"], ["S1"])
		signoff = self.run_doc().signoffs[0]
		self.assertEqual(signoff.level, "Operator")
		self.assertEqual(signoff.decision, "Submitted")

	def test_a_whole_inspection_done_offline_finishes_on_sync(self):
		"""End to end: nothing on the server, then a complete finished run.

		This is the shape of the actual day — the engineer opens the app in the
		shed, works the pack, and the van gets signal on the way back.
		"""
		out = self.sync(
			self.batch(
				answers=[
					self.answer("C1", 1),
					self.answer("C2", 2),
					self.answer("T1", 3, response=None, value=10.0),
				],
				submit_stages=["S1"],
			)
		)

		self.assertEqual(out["status"], C.STATUS_PASSED)
		self.assertTrue(out["completed_at"])
		self.assertEqual(out["sync"]["outstanding"], {"stages": [], "steps": []})

	def test_a_failed_check_quarantines_the_run_it_finishes(self):
		out = self.sync(
			self.batch(
				answers=[
					self.answer("C1", 1, response="Fail"),
					self.answer("C2", 2),
					self.answer("T1", 3, response=None, value=10.0),
				],
				submit_stages=["S1"],
			)
		)

		self.assertEqual(out["status"], C.STATUS_QUARANTINED)

	def test_syncing_a_finished_run_again_is_answered_not_refused(self):
		"""A closed run must still clear the handset's queue.

		Throwing here would leave the device retrying a completed inspection
		until it gave up and marked the work failed — which is exactly the
		outcome the retry loop exists to prevent.
		"""
		batch = self.batch(
			answers=[
				self.answer("C1", 1),
				self.answer("C2", 2),
				self.answer("T1", 3, response=None, value=10.0),
			],
			submit_stages=["S1"],
		)
		self.sync(batch)

		out = self.sync(batch)

		self.assertTrue(out["sync"]["already_closed"])
		self.assertEqual(out["status"], C.STATUS_PASSED)


class TestScans(SyncTestBase):
	def test_a_scan_captured_offline_is_parsed_on_arrival(self):
		self.sync(self.batch(answers=[self.answer("C1", 1)], scans=[_scan("s-1", "MOD-A")]))

		scan = self.run_doc().scans[0]
		self.assertEqual(scan.serial_no, "MOD-A")
		self.assertEqual(scan.client_uuid, "s-1")

	def test_two_real_scans_of_the_same_serial_stay_two_rows(self):
		"""Dedup is on the device's row UUID, never on the serial.

		A rework genuinely re-scans the same module, and collapsing those would
		quietly rewrite the pack's genealogy.
		"""
		self.sync(
			self.batch(
				answers=[self.answer("C1", 1)],
				scans=[_scan("s-1", "MOD-A"), _scan("s-2", "MOD-A")],
			)
		)

		self.assertEqual(len(self.run_doc().scans), 2)

	def test_an_unparseable_label_is_kept_verbatim_and_flagged(self):
		self.sync(self.batch(answers=[self.answer("C1", 1)], scans=[_scan("s-1", "!!! not a serial !!!")]))

		scan = self.run_doc().scans[0]
		self.assertEqual(scan.raw_payload, "!!! not a serial !!!")


class TestBootstrap(SyncTestBase):
	def test_bootstrap_carries_the_definitions_needed_to_start_offline(self):
		out = process_sync.bootstrap()["data"]

		names = {d["name"] for d in out["definitions"]}
		self.assertIn(CODE, names)

	def test_every_listed_process_has_its_definition(self):
		"""A summary without a definition is a process the engineer can see and
		cannot start — the worst possible offline failure, because it looks like
		the app is working."""
		out = process_sync.bootstrap()["data"]

		listed = {p["name"] for p in out["processes"]}
		fetched = {d["name"] for d in out["definitions"]}
		self.assertEqual(listed - fetched, set())

	def test_the_evaluator_settings_come_down_with_it(self):
		out = process_sync.bootstrap()["data"]

		self.assertIn("fast_entry_threshold_pct", out["settings"])
		self.assertIn("geofence_enabled", out["settings"])


class TestGuards(SyncTestBase):
	def test_junk_json_is_a_clean_error(self):
		with self.assertRaises(frappe.ValidationError):
			process_sync.sync_run("{not json")

	def test_a_batch_with_no_identity_is_refused(self):
		with self.assertRaises(frappe.ValidationError):
			process_sync.sync_run(json.dumps({"answers": []}))

	def test_an_oversized_batch_is_refused_before_it_is_parsed(self):
		huge = self.batch(answers=[self.answer(f"C{i}", i) for i in range(process_sync.MAX_ANSWERS + 1)])
		with self.assertRaises(frappe.ValidationError):
			process_sync.sync_run(json.dumps(huge))


# ----------------------------------------------------------------- fixtures


def _scan(uuid: str, payload: str) -> dict:
	return {
		"client_uuid": uuid,
		"entity_type": ENTITY,
		"step_code": "C1",
		"position_index": 0,
		"payload": payload,
		"is_manual_entry": 0,
	}


def _ensure_entity_type() -> str:
	if frappe.db.exists("Process Entity Type", ENTITY):
		return ENTITY
	doc = frappe.get_doc(
		{
			"doctype": "Process Entity Type",
			"entity_code": ENTITY,
			"label": "Sync Test Module",
			"expected_count": 2,
			"duplicate_policy": "Warn",
		}
	)
	doc.insert(ignore_permissions=True)
	return doc.name


def _ensure_outcome_set() -> str:
	"""Pass/Fail as a shared vocabulary.

	Options are not authored on the step — they live on a `Process Outcome Set`
	the step links to, which is what lets one shop change "Fail" to "Reject"
	across ninety checks at once. A step with no set has no vocabulary, and every
	answer to it reads as one the template no longer offers.
	"""
	if frappe.db.exists("Process Outcome Set", OUTCOME_SET):
		return OUTCOME_SET
	doc = frappe.get_doc(
		{
			"doctype": "Process Outcome Set",
			"set_code": OUTCOME_SET,
			"set_name": "Sync Test Outcomes",
		}
	)
	doc.append("options", {"value": "Pass", "label": "Pass", "is_pass": 1, "is_critical": 0})
	doc.append(
		"options",
		{"value": "Fail", "label": "Fail", "is_pass": 0, "is_critical": 1, "requires_photo": 1},
	)
	doc.insert(ignore_permissions=True)
	return doc.name


def _ensure_definition() -> str:
	"""One stage, two choice checks and a torque check — enough to finish a run."""
	if frappe.db.exists("Process Definition", CODE):
		return CODE
	outcome_set = _ensure_outcome_set()
	doc = frappe.get_doc(
		{
			"doctype": "Process Definition",
			"process_code": CODE,
			"process_name": "Sync Test Process",
			"family": CODE,
			"version": 1,
			"status": "Published",
			"pass_threshold_pct": 100,
		}
	)
	doc.append("stages", {"stage_code": "S1", "label": "Stage One", "sequence": 1})
	for i, code in enumerate(("C1", "C2"), start=1):
		doc.append(
			"steps",
			{
				"step_code": code,
				"stage": "S1",
				"sequence": i,
				"display_no": str(i),
				"label": f"Check {i}",
				"response_type": "Choice",
				"outcome_set": outcome_set,
				"is_mandatory": 1,
				"is_active": 1,
				"is_critical": 1 if code == "C1" else 0,
				"weight": 1,
			},
		)
	doc.append(
		"steps",
		{
			"step_code": "T1",
			"stage": "S1",
			"sequence": 3,
			"display_no": "3",
			"label": "Terminal torque",
			"response_type": "Number with Tolerance",
			"nominal_value": 10.0,
			"tolerance": 1.0,
			"unit": "Nm",
			"decimals": 1,
			"is_mandatory": 1,
			"is_active": 1,
			"weight": 1,
		},
	)
	doc.insert(ignore_permissions=True)
	frappe.clear_cache(doctype="Process Definition")
	return doc.name
