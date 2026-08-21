"""Helpers for seeding Desk workspaces idempotently.

Exists because of one specific Frappe behaviour that is easy to get wrong and
fails silently when you do.

**`Number Card` ignores the `name` you pass it.** Its autoname derives from
`label`, appending a numeric suffix on collision — so a card seeded as
`{"name": "FS Open Job Cards", "label": "Open Job Cards"}` is actually stored as
`Open Job Cards`, then `Open Job Cards-1`, `Open Job Cards-2`… Two things follow,
and both were live in this app:

* `frappe.db.exists("Number Card", "FS Open Job Cards")` is never true, so the
  "only create it if missing" guard never fires and **every `bench migrate`
  inserts another copy**. Twenty-three migrates had left twenty-three copies.
* The workspace's `number_cards` rows referenced that same never-existing name,
  so Frappe dropped them and **the cards never appeared on the page at all**.

The failure is silent in both directions: no error, just a dashboard that is
quietly missing its numbers and a table that grows forever. Hence this module —
find the card by what actually identifies it, and hand back its real name.
"""

from __future__ import annotations

import json

import frappe


def ensure_number_card(label: str, document_type: str, filters: list, function: str = "Count") -> str | None:
	"""Find-or-create a public Number Card. Returns its real `name`, or None.

	Also clears up any duplicates a previous run left behind, keeping the oldest
	so an admin who pinned the card to their own dashboard keeps the one they
	pinned. Only ever called for cards this app seeds, so it never touches a card
	somebody created by hand.
	"""
	if not frappe.db.exists("DocType", document_type):
		return None

	matches = frappe.get_all(
		"Number Card",
		filters={"label": label, "document_type": document_type},
		fields=["name"],
		order_by="creation asc",
		limit_page_length=0,
	)

	if matches:
		keeper = matches[0]["name"]
		for extra in matches[1:]:
			frappe.delete_doc("Number Card", extra["name"], force=True, ignore_permissions=True)

		# Keep the *definition* in sync, not just the card's existence. Returning
		# early here meant a card seeded with a broken filter stayed broken forever:
		# the row exists, so the create branch never runs, and no migrate could ever
		# correct it. That is exactly how `attendance_date = "Today"` survived on
		# the Duty Roster workspace — the seeder was fixed, the site was not.
		desired = json.dumps(filters)
		current = frappe.db.get_value("Number Card", keeper, ["filters_json", "function"], as_dict=True)
		if current and (current.filters_json != desired or current.function != function):
			frappe.db.set_value(
				"Number Card",
				keeper,
				{"filters_json": desired, "function": function},
				update_modified=False,
			)
		return keeper

	doc = frappe.get_doc(
		{
			"doctype": "Number Card",
			"label": label,
			"document_type": document_type,
			"function": function,
			"is_public": 1,
			"filters_json": json.dumps(filters),
		}
	).insert(ignore_permissions=True, ignore_if_duplicate=True)
	return doc.name


def upsert_workspace(payload: dict) -> None:
	"""Create or refresh a public Workspace, replacing its child tables.

	`payload` carries the same keys as a Workspace doc, with `links`,
	`shortcuts` and `number_cards` as plain lists of dicts.
	"""
	name = payload["name"]
	children = {field: payload.pop(field, []) for field in ("links", "shortcuts", "number_cards")}

	if not frappe.db.exists("Workspace", name):
		doc = frappe.get_doc({**payload, **children})
		doc.insert(ignore_permissions=True, ignore_if_duplicate=True)
		return

	doc = frappe.get_doc("Workspace", name)
	doc.content = payload["content"]
	doc.public = 1
	for field, rows in children.items():
		doc.set(field, [])
		for row in rows:
			doc.append(field, row)
	doc.save(ignore_permissions=True)
