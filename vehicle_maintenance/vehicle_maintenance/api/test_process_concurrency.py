"""What happens to a Battery QC run when a whole shift works at once.

Run:
    bench --site dev.localhost run-tests --module vehicle_maintenance.api.test_process_concurrency

Every property here was found by load, not by reading. Driving 20 operators
through a full 59-step inspection against a bench exposed two failures that no
single-user test can reach, because neither is about what one request does — both
are about what two requests do to each other:

* **Deadlocks.** Saving an answer rewrites the run's child tables, so concurrent
  operators contend for the same index gaps in `tabProcess Run Result`. InnoDB
  breaks the tie by killing one transaction. Measured: **195 of 1180 answers**
  (one in six) came back as a 500 on a tap the operator had already made. With
  the retry: 2.

* **Idempotent starts that were not.** `start_run` checks for an existing
  `client_uuid` and then inserts, with nothing holding the gap between the two.
  Thirty simultaneous retries of one uuid produced one run and **29 errors** —
  the unique index was doing its job while the endpoint threw the result away.

The stress harness itself is not in the repo (it needs a running site and real
sessions); these are the properties it found, pinned so they cannot regress.
"""

import frappe
from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.api import process


class TestDeadlockRetry(FrappeTestCase):
	"""`with_deadlock_retry` — replay the interleaving, not the bug."""

	def setUp(self):
		self.addCleanup(frappe.db.rollback)

	def test_a_deadlocked_write_is_replayed_until_it_lands(self):
		attempts = []

		def work():
			attempts.append(1)
			if len(attempts) < 3:
				raise frappe.QueryDeadlockError("(1213, 'Deadlock found')")
			return "saved"

		self.assertEqual(process.with_deadlock_retry(work, attempts=4), "saved")
		self.assertEqual(len(attempts), 3)

	def test_it_gives_up_rather_than_spinning_forever(self):
		attempts = []

		def work():
			attempts.append(1)
			raise frappe.QueryDeadlockError("(1213, 'Deadlock found')")

		with self.assertRaises(frappe.QueryDeadlockError):
			process.with_deadlock_retry(work, attempts=2)
		self.assertEqual(len(attempts), 2)

	def test_only_deadlocks_are_retried(self):
		"""A validation error replayed six times is six times the wrong answer.

		This is the property that makes the retry safe to wrap whole endpoints
		in: it must be blind to everything except the one error MariaDB tells us
		to retry.
		"""
		attempts = []

		def work():
			attempts.append(1)
			frappe.throw("that step is not part of this process")

		with self.assertRaises(frappe.ValidationError):
			process.with_deadlock_retry(work, attempts=4)
		self.assertEqual(len(attempts), 1)

	def test_the_default_is_deep_enough_to_matter(self):
		# Four attempts still lost 1.5% of answers at 20 concurrent operators;
		# six lost 0.17%. A regression to a smaller number would be silent.
		self.assertGreaterEqual(process.DEADLOCK_ATTEMPTS, 6)


class TestIdempotentStartUnderRace(FrappeTestCase):
	"""Losing the insert race is a success: the run we wanted now exists."""

	def setUp(self):
		self.addCleanup(frappe.db.rollback)
		self.addCleanup(frappe.set_user, "Administrator")
		frappe.set_user("Administrator")

	def test_the_unique_index_is_what_actually_enforces_one_run_per_uuid(self):
		"""The pre-check is an optimisation; the constraint is the guarantee.

		If this ever stops raising, the check-then-insert in `start_run` has
		become the only guard and a retried start can duplicate a pack record.
		"""
		uuid = frappe.generate_hash(length=18)
		first = frappe.get_doc(
			{
				"doctype": "Process Run",
				"process_definition": _any_definition(),
				"client_uuid": uuid,
				"status": "In Progress",
			}
		)
		first.insert(ignore_permissions=True)

		second = frappe.get_doc(
			{
				"doctype": "Process Run",
				"process_definition": first.process_definition,
				"client_uuid": uuid,
				"status": "In Progress",
			}
		)
		with self.assertRaises((frappe.UniqueValidationError, frappe.DuplicateEntryError)):
			second.insert(ignore_permissions=True)

	def test_start_run_recovers_the_run_the_race_created(self):
		"""Simulate losing the race: the pre-check misses, the insert collides.

		Blanking the pre-check is exactly the window a second request occupies —
		it read before the winner committed. What must not happen is the loser
		reporting failure for a run that exists and is theirs.
		"""
		uuid = frappe.generate_hash(length=18)
		definition = _any_definition()
		winner = frappe.get_doc(
			{
				"doctype": "Process Run",
				"process_definition": definition,
				"client_uuid": uuid,
				"status": "In Progress",
			}
		)
		winner.insert(ignore_permissions=True)

		real_get_value = frappe.db.get_value
		blinded = {"done": False}

		def get_value(*args, **kwargs):
			# Miss once — the pre-check — then behave normally, which is what the
			# recovery path relies on to find the winner.
			if not blinded["done"] and args and args[0] == "Process Run" and isinstance(args[1], dict):
				if args[1].get("client_uuid") == uuid:
					blinded["done"] = True
					return None
			return real_get_value(*args, **kwargs)

		frappe.db.get_value = get_value
		self.addCleanup(setattr, frappe.db, "get_value", real_get_value)

		result = process.start_run(process=definition, client_uuid=uuid)

		self.assertTrue(result["success"])
		self.assertEqual(result["data"]["name"], winner.name)
		self.assertEqual(
			frappe.db.count("Process Run", {"client_uuid": uuid}),
			1,
			"a lost race must resume the existing run, never create a second",
		)


def _any_definition() -> str:
	"""A published definition to hang test runs off, whichever site this is."""
	name = frappe.db.get_value("Process Definition", {"status": "Published"}, "name")
	if not name:
		name = frappe.db.get_value("Process Definition", {}, "name")
	if not name:
		raise AssertionError("no Process Definition on this site to test against")
	return name
