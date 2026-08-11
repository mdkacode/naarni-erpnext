"""Duty Roster controller.

A depot's plan for a date range. The rules worth knowing:

* **Draft is invisible.** `get_planned_duty` only reads Published rosters, so a
  half-built roster never shows a wrong shift on someone's phone.
* **One duty per engineer per date**, enforced both inside this roster and
  across other Published rosters for the same depot period. A double-booked
  engineer makes attendance ambiguous, and ambiguous attendance is disputed
  attendance.
* **Publishing does not freeze the roster.** Plans change — a swap at 6am is
  normal — so edits stay allowed and are captured by `track_changes`. This is
  deliberately unlike Process Definition, where an immutable published version
  is what makes an audit reproducible.
"""

from __future__ import annotations

from collections import defaultdict

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import add_days, getdate, now_datetime


class DutyRoster(Document):
	def validate(self) -> None:
		self._validate_period()
		self._validate_entries()
		self._validate_no_double_booking()

	def _validate_period(self) -> None:
		if getdate(self.to_date) < getdate(self.from_date):
			frappe.throw(_("To Date cannot be before From Date."))
		# 92 days ~ a quarter. Beyond that the entry grid becomes unusable long
		# before it becomes slow, so the limit is a UX guardrail, not a perf one.
		if (getdate(self.to_date) - getdate(self.from_date)).days > 92:
			frappe.throw(_("A roster covers at most 92 days. Create a second roster for the next period."))

	def _validate_entries(self) -> None:
		start, end = getdate(self.from_date), getdate(self.to_date)
		for row in self.entries or []:
			d = getdate(row.duty_date)
			if d < start or d > end:
				frappe.throw(
					_("Row {0}: {1} is outside this roster's period.").format(
						row.idx, frappe.format(d, "Date")
					)
				)
			# Frappe pre-fills every Time field with the current time, so the times
			# alone can never stand in for "this row is configured" — only the
			# explicit Custom Timing flag can.
			if not row.is_week_off and not row.shift and not row.has_custom_time:
				frappe.throw(
					_(
						"Row {0}: pick a shift, or tick Custom Timing and set the hours, or mark it Week Off."
					).format(row.idx)
				)

	def _validate_no_double_booking(self) -> None:
		seen: dict[tuple, int] = {}
		for row in self.entries or []:
			key = (row.engineer, str(getdate(row.duty_date)))
			if key in seen:
				frappe.throw(
					_("Row {0}: {1} is already rostered on {2} in row {3}.").format(
						row.idx,
						row.engineer_name or row.engineer,
						frappe.format(getdate(row.duty_date), "Date"),
						seen[key],
					)
				)
			seen[key] = row.idx

		if self.status != "Published" or not seen:
			return

		# Another *published* roster covering the same engineer/date is the real
		# hazard — two depots each thinking they have the person.
		clashes = frappe.db.sql(
			"""
			SELECT e.engineer, e.duty_date, r.name
			FROM `tabDuty Roster Entry` e
			INNER JOIN `tabDuty Roster` r ON r.name = e.parent
			WHERE r.name != %(self_name)s
			  AND r.status = 'Published'
			  AND e.duty_date BETWEEN %(from_date)s AND %(to_date)s
			  AND e.engineer IN %(engineers)s
			""",
			{
				"self_name": self.name or "",
				"from_date": self.from_date,
				"to_date": self.to_date,
				"engineers": tuple({k[0] for k in seen}) or ("",),
			},
			as_dict=True,
		)
		for c in clashes:
			if (c.engineer, str(getdate(c.duty_date))) in seen:
				frappe.throw(
					_("{0} is already rostered on {1} by {2}. Remove them there first.").format(
						frappe.db.get_value("User", c.engineer, "full_name") or c.engineer,
						frappe.format(getdate(c.duty_date), "Date"),
						c.name,
					)
				)

	# ------------------------------------------------------------------ actions

	def publish(self) -> None:
		if not self.entries:
			frappe.throw(_("Add at least one duty day before publishing."))
		self.status = "Published"
		self.published_by = frappe.session.user
		self.published_at = now_datetime()
		self.save()

	def fill(
		self,
		engineers: list[str],
		shift: str | None = None,
		weekdays: list[int] | None = None,
		week_off_days: list[int] | None = None,
		replace: bool = False,
	) -> int:
		"""Generate entries across the period — the alternative to typing 30 rows.

		`weekdays` / `week_off_days` are Python weekday numbers (Mon=0..Sun=6).
		A day in `week_off_days` becomes a Week Off row rather than being left
		blank, so the engineer sees "Week off" in the app instead of nothing and
		wondering whether the roster was published.

		Returns the number of rows added.
		"""
		if not engineers:
			frappe.throw(_("Pick at least one engineer."))

		work_days = set(weekdays) if weekdays else set(range(7))
		off_days = set(week_off_days or [])

		if replace:
			self.set("entries", [])

		existing = {(r.engineer, str(getdate(r.duty_date))) for r in self.entries or []}

		added = 0
		day = getdate(self.from_date)
		last = getdate(self.to_date)
		while day <= last:
			dow = day.weekday()
			for engineer in engineers:
				if (engineer, str(day)) in existing:
					continue
				is_off = dow in off_days
				if not is_off and dow not in work_days:
					continue
				self.append(
					"entries",
					{
						"duty_date": day,
						"engineer": engineer,
						"shift": None if is_off else shift,
						"is_week_off": 1 if is_off else 0,
					},
				)
				existing.add((engineer, str(day)))
				added += 1
			day = add_days(day, 1)

		# Chronological, then by engineer — the order a depot manager reads it in.
		self.entries.sort(key=lambda r: (str(getdate(r.duty_date)), r.engineer or ""))
		for i, row in enumerate(self.entries, start=1):
			row.idx = i
		return added

	def coverage(self) -> list[dict]:
		"""Per-date headcount — powers the 'thin day' warning in the authoring UI."""
		by_date: dict[str, int] = defaultdict(int)
		for row in self.entries or []:
			if not row.is_week_off:
				by_date[str(getdate(row.duty_date))] += 1

		out = []
		day = getdate(self.from_date)
		last = getdate(self.to_date)
		while day <= last:
			out.append({"date": str(day), "on_duty": by_date.get(str(day), 0)})
			day = add_days(day, 1)
		return out
