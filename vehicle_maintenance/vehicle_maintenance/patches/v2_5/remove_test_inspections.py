# Copyright (c) 2026, Naarni and contributors
# For license information, please see license.txt

"""Remove the inspections created while testing, and nothing else.

Three runs were opened against packs that do not exist — `FFGGG`,
`AUTO-134813` and `RUN-2026-00012` — and one real pack picked up a handful of
rows during device testing. This deletes exactly those and stops.

**This deletes production records, so every rule here is about blast radius:**

* runs are named explicitly, never matched by pattern;
* the real pack keeps its run — only the test rows on it go, identified by the
  exact value that was typed (`SN-TEST-9931-ABC`) and by the day;
* a count interlock refuses to touch the real pack if it holds more than the
  handful of rows the testing produced, because that would mean somebody has
  since done real work on it;
* everything removed is printed before it is removed, so the migrate log is the
  record of what happened;
* it runs once, tracked by a marker, so a later `bench migrate` cannot delete a
  pack that has legitimately been given one of these labels since.
"""

import frappe

#: Runs opened against packs that do not exist. Matched on the pack label.
TEST_IDENTIFIERS = ("FFGGG", "AUTO-134813")

#: Matched on the Process Run name itself, not the label.
TEST_RUN_NAMES = ("RUN-2026-00012",)

#: A real pack that collected test rows during device testing on 2026-08-17.
REAL_PACK = "SO351260320005-20260407-0017-5"
TEST_ANSWER = "SN-TEST-9931-ABC"
TEST_DAY = "2026-08-17"

#: The interlock. More than this on the real pack means somebody has done actual
#: work on it since, and this patch must leave it alone.
MAX_TEST_RESULTS = 3
MAX_TEST_PHOTOS = 2

MARKER = "vm_test_inspections_removed"


def execute() -> None:
	if frappe.db.get_default(MARKER):
		return

	_delete_whole_runs()
	_clean_real_pack()

	frappe.db.set_default(MARKER, "1")


def _delete_whole_runs() -> None:
	"""Runs that are entirely test data — the pack never existed."""
	names = set(TEST_RUN_NAMES)
	for identifier in TEST_IDENTIFIERS:
		names.update(
			row["name"]
			for row in frappe.get_all("Process Run", filters={"run_identifier": identifier}, fields=["name"])
		)

	for name in sorted(names):
		if not frappe.db.exists("Process Run", name):
			print(f"remove_test_inspections: {name} not found, skipping")
			continue
		label = frappe.db.get_value("Process Run", name, "run_identifier")
		print(f"remove_test_inspections: deleting run {name} (pack {label!r})")
		_delete_attachments(name)
		frappe.delete_doc("Process Run", name, force=True, ignore_permissions=True, delete_permanently=True)


def _clean_real_pack() -> None:
	"""A real pack keeps its run; only the rows typed during testing go."""
	runs = frappe.get_all("Process Run", filters={"run_identifier": REAL_PACK}, fields=["name"])
	if not runs:
		print(f"remove_test_inspections: no run for {REAL_PACK}, nothing to clean")
		return

	for row in runs:
		doc = frappe.get_doc("Process Run", row["name"])

		# The interlock. If this pack has since been genuinely worked, the test
		# rows are not worth the risk of taking somebody's inspection with them.
		if len(doc.results or []) > MAX_TEST_RESULTS or len(doc.photos or []) > MAX_TEST_PHOTOS:
			print(
				f"remove_test_inspections: {doc.name} has "
				f"{len(doc.results or [])} answers and {len(doc.photos or [])} photos — "
				"more than testing produced, leaving it alone"
			)
			continue

		kept_results = []
		for result in doc.results or []:
			is_test = TEST_ANSWER in (result.response or "", result.value_text or "") or str(
				result.answered_at or ""
			).startswith(TEST_DAY)
			if is_test:
				print(f"remove_test_inspections: dropping answer {result.step_code} on {doc.name}")
			else:
				kept_results.append(result)

		kept_photos = []
		for photo in doc.photos or []:
			if str(photo.captured_at or photo.creation or "").startswith(TEST_DAY):
				print(f"remove_test_inspections: dropping photo {photo.file_url} on {doc.name}")
			else:
				kept_photos.append(photo)

		if len(kept_results) == len(doc.results or []) and len(kept_photos) == len(doc.photos or []):
			print(f"remove_test_inspections: nothing to clean on {doc.name}")
			continue

		doc.results = kept_results
		doc.photos = kept_photos
		# Saved rather than written field by field, so `_recompute` rebuilds the
		# counts and the run does not keep claiming answers it no longer has.
		doc.save(ignore_permissions=True)


def _delete_attachments(run: str) -> None:
	"""Files hanging off a run being deleted, so no orphan bytes are left behind."""
	files = frappe.get_all(
		"File",
		filters={"attached_to_doctype": "Process Run", "attached_to_name": run},
		fields=["name"],
	)
	for file in files:
		frappe.delete_doc("File", file["name"], force=True, ignore_permissions=True)
