"""Whitelisted API for the material gate — inward and outward movements.

Every method returns the app's standard `{success, data, message}` envelope and
opens with a permission check, because the app's tab gating hides a tab and
secures nothing.

Three properties this module is built around, each learned the hard way
elsewhere in this codebase:

*   **Idempotency.** A gate is the worst network in the plant. `start_movement`
    is keyed on a client-generated `client_uuid`, `save_item` on `row_uuid` and
    `attach_photo` on `client_uuid`, so a phone that loses signal mid-save and
    retries produces one row, not two.
*   **One round trip for context.** `get_gate_context` returns every list the
    New-Movement screen needs at once. Six sequential calls over plant Wi-Fi is
    the difference between a screen that opens and a screen that spins.
*   **Advisory, not obstructive.** Missing photos, an unreadable QR, a duplicate
    serial and a fix outside the geofence all produce a *warning* the supervisor
    can see. None of them refuse the write. A gate clerk who cannot record the
    truck records nothing at all, and nothing is worse than imperfect.
"""

from __future__ import annotations

import json
import re

import frappe
from frappe import _
from frappe.utils import cint, flt, now_datetime

from vehicle_maintenance.material_movement import constants as C

#: Cap on a single list page. A gate register query with no ceiling is how one
#: phone stalls a worker for everybody.
MAX_PAGE = 100

#: Rows the item picker returns. Deliberately small — a bottom sheet nobody
#: scrolls past twenty rows of.
MAX_SUGGESTIONS = 40


# ------------------------------------------------------------------- helpers


def _ok(data=None, message: str | None = None) -> dict:
	return {"success": True, "data": data if data is not None else {}, "message": message}


def _roles() -> set[str]:
	return set(frappe.get_roles(frappe.session.user))


def _assert_roles(allowed: tuple[str, ...], message: str) -> None:
	"""Explicit role gate.

	Deliberately not `frappe.only_for`. That helper returns early whenever
	`frappe.flags.in_test` is set, so a permission written with it passes every
	test it has regardless of whether it works — and an untested permission check
	is one that quietly stops working. This has the same contract without the
	blind spot, which is what lets `TestPermissions` actually prove the gates.
	"""
	if frappe.session.user == "Administrator":
		return
	if not set(frappe.get_roles(frappe.session.user)) & set(allowed):
		raise frappe.PermissionError(message)


def _assert_can_read() -> None:
	_assert_roles(C.ALL_ROLES, _("You do not have access to material movements."))


def _assert_can_write() -> None:
	_assert_roles(C.WRITE_ROLES, _("Only gate staff can record a material movement."))


def _assert_can_verify() -> None:
	_assert_roles(C.SUPERVISOR_ROLES, _("Only a material supervisor can verify or send back a movement."))


def _can_verify() -> bool:
	return bool(_roles() & set(C.SUPERVISOR_ROLES))


def _load(movement: str, ptype: str = "write"):
	"""Fetch a movement the caller is allowed to touch."""
	doc = frappe.get_doc("Material Movement", movement)
	if not doc.has_permission(ptype):
		raise frappe.PermissionError(_("Not permitted on {0}.").format(movement))
	return doc


def _assert_open(doc) -> None:
	"""Refuse every write against a finished movement.

	This is what stops a verified gate record being edited after the fact, which
	is the only reason anyone downstream can trust it.
	"""
	if doc.status in C.TERMINAL_STATUSES:
		frappe.throw(_("This movement is {0} and can no longer be changed.").format(doc.status))


def _assert_not_verified_by_recorder(doc) -> None:
	"""Four-eyes. Off only when a site explicitly opts out (single-clerk shifts)."""
	if frappe.conf.get(C.CONF_ALLOW_SELF_VERIFY):
		return
	submitter = doc.submitted_by or doc.started_by
	if submitter == frappe.session.user:
		frappe.throw(_("A movement must be verified by somebody other than the person who recorded it."))


def _parse_rows(value) -> list[dict]:
	"""Accept a JSON string or an already-decoded list, as the app sends either."""
	if not value:
		return []
	if isinstance(value, str):
		try:
			value = json.loads(value)
		except ValueError:
			frappe.throw(_("Malformed item payload."))
	if isinstance(value, dict):
		value = [value]
	return list(value)


def _normalise_name(text: str) -> str:
	"""Fold a typed item name for near-duplicate comparison.

	Lowercased, punctuation and whitespace stripped, so "Head Lamp",
	"headlamp" and "HEADLAMP(front)" ... do not all collide — the last one still
	differs by "front", which is a real distinction. This catches the spacing and
	casing variants, which is the overwhelming majority of gate duplicates.
	"""
	return re.sub(r"[^a-z0-9]+", "", (text or "").lower())


def _serialise_item(row) -> dict:
	return {
		"row_uuid": row.row_uuid,
		"item": row.item,
		"item_name": row.item_name,
		"item_group": row.item_group,
		"qty": flt(row.qty),
		"uom": row.uom,
		"expected_qty": flt(row.expected_qty),
		"condition": row.condition,
		"has_qr": cint(row.has_qr),
		"qr_code": row.qr_code,
		"qr_source": row.qr_source,
		"batch_no": row.batch_no,
		"mfg_date": str(row.mfg_date) if row.mfg_date else None,
		"chassis_no": row.chassis_no,
		"photo_count": cint(row.photo_count),
		"no_photo_reason": row.no_photo_reason,
		"is_new_item": cint(row.is_new_item),
		"remarks": row.remarks,
	}


def _serialise_photo(row) -> dict:
	return {
		"client_uuid": row.client_uuid,
		"file_url": row.file_url,
		"kind": row.kind,
		"item_row": row.item_row,
		"caption": row.caption,
		"captured_at": str(row.captured_at) if row.captured_at else None,
		"captured_by": row.captured_by,
		"latitude": flt(row.latitude),
		"longitude": flt(row.longitude),
	}


def _serialise(doc, with_children: bool = True) -> dict:
	data = {
		"name": doc.name,
		"movement_type": doc.movement_type,
		"location": doc.location,
		"location_name": frappe.db.get_value("Material Location", doc.location, "location_name")
		if doc.location
		else None,
		"gate": doc.gate,
		"status": doc.status,
		"purpose": doc.purpose,
		"party_type": doc.party_type,
		"party_name": doc.party_name,
		"reference_type": doc.reference_type,
		"reference_no": doc.reference_no,
		"reference_date": str(doc.reference_date) if doc.reference_date else None,
		"transport_vehicle_no": doc.transport_vehicle_no,
		"driver_name": doc.driver_name,
		"driver_phone": doc.driver_phone,
		"vehicle": doc.vehicle,
		"job_card": doc.job_card,
		"total_items": cint(doc.total_items),
		"total_qty": flt(doc.total_qty),
		"photo_count": cint(doc.photo_count),
		"qr_count": cint(doc.qr_count),
		"damaged_count": cint(doc.damaged_count),
		"new_item_count": cint(doc.new_item_count),
		"evidence_pct": flt(doc.evidence_pct),
		"started_by": doc.started_by,
		"started_at": str(doc.started_at) if doc.started_at else None,
		"submitted_by": doc.submitted_by,
		"submitted_at": str(doc.submitted_at) if doc.submitted_at else None,
		"verified_by": doc.verified_by,
		"verified_at": str(doc.verified_at) if doc.verified_at else None,
		"geofence_status": doc.geofence_status,
		"remarks": doc.remarks,
		"rejection_reason": doc.rejection_reason,
		"can_verify": _can_verify() and doc.status == C.STATUS_AWAITING_VERIFICATION,
		"can_edit": doc.status in C.EDITABLE_STATUSES,
	}
	if with_children:
		data["items"] = [_serialise_item(r) for r in doc.items or []]
		data["photos"] = [_serialise_photo(r) for r in doc.photos or []]
	return data


def _notify(users: list[str], title: str, body: str, movement: str) -> None:
	"""Realtime first, then the app's push path. Best effort — never blocks a save.

	Same shape as `process_engine.actions._notify`, so the app's existing
	`vm_notification` subscriber picks these up with no client change.
	"""
	for user in dict.fromkeys(u for u in users if u and u not in ("Administrator", "Guest")):
		try:
			frappe.publish_realtime(
				event="vm_notification",
				message={
					"title": title,
					"body": body,
					"severity": "Minor",
					"doctype": "Material Movement",
					"name": movement,
				},
				user=user,
			)
		except Exception:
			frappe.log_error(title="Material gate: realtime notify failed", message=frappe.get_traceback())

	try:
		from vehicle_maintenance.fleet_service import notifications as vm_notifications

		sender = getattr(vm_notifications, "send_push_to_users", None)
		if callable(sender):
			sender(users, title, body)
	except Exception:
		frappe.log_error(title="Material gate: push notify failed", message=frappe.get_traceback())


def _supervisors_at(location: str) -> list[str]:
	"""Enabled users holding a supervisor role. Location scoping is deliberate…

	…in its absence: `Material Location` carries no user roster, so every
	supervisor is notified. When plants grow their own rosters this is the one
	function to change.
	"""
	rows = frappe.get_all(
		"Has Role",
		filters={"role": ["in", list(C.SUPERVISOR_ROLES)], "parenttype": "User"},
		fields=["parent"],
		limit_page_length=500,
	)
	candidates = {r.parent for r in rows} - {"Administrator", "Guest"}
	if not candidates:
		return []
	enabled = frappe.get_all(
		"User",
		filters={"name": ["in", list(candidates)], "enabled": 1},
		pluck="name",
		limit_page_length=500,
	)
	return enabled


# ------------------------------------------------------------------- context


@frappe.whitelist()
def get_gate_context() -> dict:
	"""Everything the New-Movement screen needs, in one round trip.

	Returns locations, the operator's default, the purpose lists per movement
	type, the picklists, the item groups, counterparties this user has dealt with
	recently, and whether the caller may verify.
	"""
	_assert_can_read()

	locations = frappe.get_all(
		"Material Location",
		filters={"is_active": 1},
		fields=["name", "location_code", "location_name", "plant_type", "gates", "city"],
		order_by="location_name asc",
		limit_page_length=50,
	)
	for loc in locations:
		loc["gates"] = [line.strip() for line in (loc.get("gates") or "").splitlines() if line.strip()]

	# The operator's default location is simply the one they last recorded at.
	# Better than a configured default nobody maintains, and it self-corrects the
	# moment somebody transfers plants.
	default_location = frappe.db.get_value(
		"Material Movement",
		{"started_by": frappe.session.user},
		"location",
		order_by="creation desc",
	) or (locations[0]["name"] if locations else None)

	recent_parties = frappe.get_all(
		"Material Movement",
		filters={"party_name": ["!=", ""]},
		fields=["party_name", "party_type"],
		order_by="creation desc",
		limit_page_length=60,
	)
	seen: set[str] = set()
	parties: list[dict] = []
	for row in recent_parties:
		key = (row.party_name or "").strip().lower()
		if key and key not in seen:
			seen.add(key)
			parties.append({"party_name": row.party_name, "party_type": row.party_type})
		if len(parties) >= 15:
			break

	groups = frappe.get_all(
		"Part Group",
		fields=["name", "part_group_name"],
		order_by="part_group_name asc",
		limit_page_length=0,
	)

	return _ok(
		{
			"locations": locations,
			"default_location": default_location,
			"movement_types": list(C.MOVEMENT_TYPES),
			"purposes": {k: list(v) for k, v in C.PURPOSES.items()},
			"party_types": list(C.PARTY_TYPES),
			"reference_types": list(C.REFERENCE_TYPES),
			"conditions": list(C.CONDITIONS),
			"no_photo_reasons": list(C.NO_PHOTO_REASONS),
			"uoms": list(C.UOMS),
			"item_groups": groups,
			"recent_parties": parties,
			"can_verify": _can_verify(),
			"can_write": bool(_roles() & set(C.WRITE_ROLES)),
		}
	)


# ---------------------------------------------------------------- item picker


@frappe.whitelist()
def search_items(txt: str = "", group: str = "", limit: int = MAX_SUGGESTIONS) -> dict:
	"""The item suggest dropdown: recents first, then the catalogue.

	Opens *populated* — a blank `txt` returns what this operator has moved
	recently, topped up from the catalogue — so the sheet is useful before a
	single keystroke. Typing searches item code, name and spec together, so
	"8.7" finds both windshields and "YST240" finds the structure bays.
	"""
	_assert_can_read()
	limit = max(1, min(int(limit or MAX_SUGGESTIONS), MAX_SUGGESTIONS))
	txt = (txt or "").strip()

	def _fields(rows: list[dict], recent: bool) -> list[dict]:
		return [
			{
				"value": r["name"],
				"label": r.get("part_name") or r["name"],
				"sublabel": " · ".join(x for x in (r.get("part_group"), r.get("spec")) if x) or None,
				"badge": "QR" if cint(r.get("has_qr")) else None,
				"recent": recent,
				"uom": r.get("stock_uom") or "Nos",
				"qty_per_bus": flt(r.get("qty_per_bus")),
				"has_qr": cint(r.get("has_qr")),
				"item_group": r.get("part_group"),
			}
			for r in rows
		]

	fields = ["name", "part_name", "part_group", "spec", "stock_uom", "has_qr", "qty_per_bus"]
	base: list = [["is_active", "=", 1]]
	if group:
		base.append(["part_group", "=", group])

	results: list[dict] = []
	seen: set[str] = set()

	if not txt:
		# Recents: what this operator actually reaches for. Two queries rather
		# than a join, because the child table has no index on `parent.started_by`
		# and the recent set is tiny.
		recent_movements = frappe.get_all(
			"Material Movement",
			filters={"started_by": frappe.session.user},
			pluck="name",
			order_by="creation desc",
			limit_page_length=25,
		)
		if recent_movements:
			recent_items = frappe.get_all(
				"Material Movement Item",
				filters={"parent": ["in", recent_movements]},
				fields=["item"],
				order_by="creation desc",
				limit_page_length=100,
			)
			ordered: list[str] = []
			for row in recent_items:
				if row.item and row.item not in seen:
					seen.add(row.item)
					ordered.append(row.item)
				if len(ordered) >= 8:
					break
			if ordered:
				rows = frappe.get_all(
					"Part", filters={"name": ["in", ordered]}, fields=fields, limit_page_length=len(ordered)
				)
				by_name = {r["name"]: r for r in rows}
				results.extend(_fields([by_name[n] for n in ordered if n in by_name], recent=True))
	else:
		base_or = [
			["part_name", "like", f"%{txt}%"],
			["part_code", "like", f"%{txt}%"],
			["spec", "like", f"%{txt}%"],
		]
		rows = frappe.get_all(
			"Part",
			filters=base,
			or_filters=base_or,
			fields=fields,
			order_by="part_name asc",
			limit_page_length=limit,
		)
		results.extend(_fields(rows, recent=False))
		seen.update(r["value"] for r in results)

	# Top up from the catalogue ONLY on a blank query. Padding a *search* is how
	# "8.7" comes back as two windshields followed by thirty-eight unrelated
	# items, which buries the answer under the thing the clerk did not ask for.
	# On a blank query the padding is the whole point: the sheet opens usable
	# before a keystroke.
	if not txt and len(results) < limit:
		filler = frappe.get_all(
			"Part",
			filters=[*base, ["name", "not in", list(seen)]] if seen else base,
			fields=fields,
			order_by="part_name asc",
			limit_page_length=limit - len(results),
		)
		results.extend(_fields(filler, recent=False))

	return _ok(results[:limit])


@frappe.whitelist()
def create_item(item_name: str, item_group: str = "", uom: str = "Nos", has_qr: int = 0) -> dict:
	"""Create a catalogue item inline, from the gate.

	A clerk holding a part that is not in the list cannot be told to phone an
	administrator, so this exists. What it must not become is the reason the
	catalogue grows four spellings of "headlamp", so:

	*   the typed name is compared case- and punctuation-insensitively against
	    every existing item, and a match returns *that* item with a message
	    rather than creating a twin;
	*   what is created is stamped `is_gate_created`, giving admins a single
	    filter for reviewing everything the floor invented.
	"""
	_assert_can_write()

	item_name = (item_name or "").strip()
	if not item_name:
		frappe.throw(_("Item name is required."))
	if len(item_name) < 3:
		frappe.throw(_("Item name is too short to be findable later."))

	# Folding cannot be expressed in SQL, so the comparison happens in Python over
	# every item — but over two columns only, which is a few hundred kilobytes even
	# at ten thousand parts, and this runs once per inline create rather than once
	# per keystroke. Full rows are fetched only for the one row that matches.
	folded = _normalise_name(item_name)
	twin = next(
		(
			row.name
			for row in frappe.get_all("Part", fields=["name", "part_name"], limit_page_length=0)
			if _normalise_name(row.part_name) == folded
		),
		None,
	)
	if twin:
		existing = frappe.db.get_value(
			"Part", twin, ["name", "part_name", "part_group", "stock_uom", "has_qr"], as_dict=True
		)
		return _ok(
			{
				"value": existing.name,
				"label": existing.part_name,
				"sublabel": existing.part_group,
				"uom": existing.stock_uom or "Nos",
				"has_qr": cint(existing.has_qr),
				"item_group": existing.part_group,
				"created": 0,
			},
			_("We already have this item — using “{0}”.").format(existing.part_name),
		)

	if item_group and not frappe.db.exists("Part Group", item_group):
		frappe.throw(_("Unknown item group {0}.").format(item_group))
	if uom and uom not in C.UOMS:
		frappe.throw(_("Unknown unit {0}.").format(uom))

	doc = frappe.get_doc(
		{
			"doctype": "Part",
			"part_code": _next_gate_code(),
			"part_name": item_name,
			"part_group": item_group or None,
			"stock_uom": uom or "Nos",
			"has_qr": cint(has_qr),
			"is_gate_created": 1,
			"is_active": 1,
			"description": _("Added at the material gate by {0}.").format(frappe.session.user),
		}
	)
	doc.insert(ignore_permissions=True)

	return _ok(
		{
			"value": doc.name,
			"label": doc.part_name,
			"sublabel": doc.part_group,
			"uom": doc.stock_uom,
			"has_qr": cint(doc.has_qr),
			"item_group": doc.part_group,
			"created": 1,
		},
		_("“{0}” added to the catalogue.").format(doc.part_name),
	)


def _next_gate_code() -> str:
	"""Next `NEW-####` code, from Frappe's persistent series counter.

	Deliberately not "highest existing code + 1": deleting the newest gate item
	would rewind that, and the next create would hand its code to a different
	part. The counter in `tabSeries` only ever goes up, so a code retired by a
	deletion is never reissued — which is what makes an old export still mean
	what it said. The `exists` loop covers a series counter that starts behind
	codes imported by some other route.
	"""
	from frappe.model.naming import getseries

	for _attempt in range(100):
		# getseries returns the zero-padded counter only — never the key — so the
		# prefix is joined on here.
		code = f"{C.GATE_ITEM_PREFIX}-{getseries(f'{C.GATE_ITEM_PREFIX}-', 4)}"
		if not frappe.db.exists("Part", code):
			return code
	frappe.throw(_("Could not allocate an item code. Ask an administrator to check the series."))


# ------------------------------------------------------------------ lifecycle


@frappe.whitelist()
def start_movement(
	movement_type: str,
	location: str,
	client_uuid: str,
	gate: str = "",
	purpose: str = "",
	party_type: str = "",
	party_name: str = "",
	reference_type: str = "",
	reference_no: str = "",
	reference_date: str = "",
	transport_vehicle_no: str = "",
	driver_name: str = "",
	driver_phone: str = "",
	vehicle: str = "",
	job_card: str = "",
	latitude: float | None = None,
	longitude: float | None = None,
	is_test: int = 0,
) -> dict:
	"""Open a Draft movement, or return the one this `client_uuid` already made."""
	_assert_can_write()

	if movement_type not in C.MOVEMENT_TYPES:
		frappe.throw(_("Movement type must be Inward or Outward."))
	if not frappe.db.exists("Material Location", location):
		frappe.throw(_("Unknown location {0}.").format(location))
	client_uuid = (client_uuid or "").strip()
	if not client_uuid:
		frappe.throw(_("A client reference is required so a retry cannot create a second movement."))

	existing = frappe.db.get_value("Material Movement", {"client_uuid": client_uuid}, "name")
	if existing:
		return _ok(_serialise(frappe.get_doc("Material Movement", existing)), _("Movement resumed."))

	doc = frappe.get_doc(
		{
			"doctype": "Material Movement",
			"movement_type": movement_type,
			"location": location,
			"client_uuid": client_uuid,
			"gate": gate,
			"status": C.STATUS_DRAFT,
			"purpose": purpose or None,
			"party_type": party_type or None,
			"party_name": party_name,
			"reference_type": reference_type or None,
			"reference_no": reference_no,
			"reference_date": reference_date or None,
			"transport_vehicle_no": transport_vehicle_no,
			"driver_name": driver_name,
			"driver_phone": driver_phone,
			"vehicle": vehicle or None,
			"job_card": job_card or None,
			"latitude": flt(latitude),
			"longitude": flt(longitude),
			"is_test": cint(is_test),
		}
	)
	doc.insert()
	return _ok(_serialise(doc), _("Movement opened."))


@frappe.whitelist()
def update_header(movement: str, updates: str | dict) -> dict:
	"""Edit the header of an open movement — the counterparty, document, transport."""
	_assert_can_write()
	doc = _load(movement)
	_assert_open(doc)

	if isinstance(updates, str):
		updates = json.loads(updates or "{}")

	allowed = {
		"gate",
		"purpose",
		"party_type",
		"party_name",
		"reference_type",
		"reference_no",
		"reference_date",
		"transport_vehicle_no",
		"driver_name",
		"driver_phone",
		"vehicle",
		"job_card",
		"remarks",
	}
	unknown = set(updates) - allowed
	if unknown:
		frappe.throw(_("Cannot update {0} on a movement.").format(", ".join(sorted(unknown))))

	for field, value in updates.items():
		doc.set(field, value or None)
	doc.save()
	return _ok(_serialise(doc, with_children=False), _("Saved."))


@frappe.whitelist()
def save_item(
	movement: str,
	row_uuid: str,
	item: str,
	qty: float = 1,
	uom: str = "",
	condition: str = C.CONDITION_OK,
	qr_code: str = "",
	qr_source: str = "",
	batch_no: str = "",
	mfg_date: str = "",
	no_photo_reason: str = "",
	remarks: str = "",
	is_new_item: int = 0,
) -> dict:
	"""Upsert one line, keyed on `row_uuid`.

	Returns the saved row plus any *warnings* — a duplicate serial, a quantity
	that differs from the sheet. Warnings are shown; they never refuse the write.
	"""
	_assert_can_write()
	doc = _load(movement)
	_assert_open(doc)

	row_uuid = (row_uuid or "").strip()
	if not row_uuid:
		frappe.throw(_("A row reference is required so a retry cannot create a second line."))
	if condition and condition not in C.CONDITIONS:
		frappe.throw(_("Unknown condition {0}.").format(condition))
	if qr_source and qr_source not in C.QR_SOURCES:
		frappe.throw(_("Unknown QR source {0}.").format(qr_source))
	if no_photo_reason and no_photo_reason not in C.NO_PHOTO_REASONS:
		frappe.throw(_("Unknown reason {0}.").format(no_photo_reason))
	if flt(qty) <= 0:
		frappe.throw(_("Quantity must be greater than zero."))

	master = frappe.db.get_value(
		"Part", item, ["part_name", "part_group", "stock_uom", "has_qr", "qty_per_bus"], as_dict=True
	)
	if not master:
		frappe.throw(_("Unknown item {0}.").format(item))

	row = next((r for r in doc.items or [] if r.row_uuid == row_uuid), None)
	if row is None:
		row = doc.append("items", {"row_uuid": row_uuid})

	qr_code = (qr_code or "").strip()
	row.update(
		{
			"item": item,
			"item_name": master.part_name,
			"item_group": master.part_group,
			"qty": flt(qty),
			"uom": uom or master.stock_uom or "Nos",
			"expected_qty": flt(master.qty_per_bus),
			"condition": condition or C.CONDITION_OK,
			"has_qr": cint(master.has_qr),
			"qr_code": qr_code,
			"qr_source": (qr_source or (C.QR_TYPED if qr_code else "")) or None,
			"qr_scanned_at": now_datetime() if qr_code and not row.qr_scanned_at else row.qr_scanned_at,
			"batch_no": batch_no,
			"mfg_date": mfg_date or None,
			"no_photo_reason": no_photo_reason or None,
			"remarks": remarks,
			"is_new_item": cint(is_new_item) or cint(row.is_new_item),
		}
	)

	if doc.status == C.STATUS_DRAFT:
		doc.status = C.STATUS_IN_PROGRESS
	doc.save()

	saved = next(r for r in doc.items if r.row_uuid == row_uuid)
	return _ok(
		{
			"item": _serialise_item(saved),
			"movement": _serialise(doc, with_children=False),
			"warnings": _row_warnings(doc, saved),
		}
	)


def _row_warnings(doc, row) -> list[dict]:
	"""Advisory notes for one row. Shown inline; none of them block."""
	warnings: list[dict] = []

	if row.qr_code:
		others = frappe.get_all(
			"Material Movement Item",
			filters={"qr_code": row.qr_code, "parent": ["!=", doc.name]},
			fields=["parent"],
			limit_page_length=3,
		)
		if others:
			warnings.append(
				{
					"code": "duplicate_serial",
					"message": _("Serial {0} was already recorded on {1}.").format(
						row.qr_code, ", ".join(o.parent for o in others)
					),
				}
			)
	elif cint(row.has_qr):
		warnings.append(
			{"code": "missing_serial", "message": _("This item normally carries a QR label — none recorded.")}
		)

	if flt(row.expected_qty) and flt(row.qty) != flt(row.expected_qty):
		warnings.append(
			{
				"code": "qty_differs",
				"message": _("The sheet expects {0} per bus; {1} recorded.").format(
					flt(row.expected_qty), flt(row.qty)
				),
			}
		)

	return warnings


@frappe.whitelist()
def delete_item(movement: str, row_uuid: str) -> dict:
	"""Remove a line and every photo pointed at it.

	The photos go too. Leaving them would strand evidence against a row that no
	longer exists, which is how a `photo_count` ends up larger than the movement.
	"""
	_assert_can_write()
	doc = _load(movement)
	_assert_open(doc)

	doc.items = [r for r in doc.items or [] if r.row_uuid != row_uuid]
	doc.photos = [p for p in doc.photos or [] if p.item_row != row_uuid]
	doc.save()
	return _ok(_serialise(doc), _("Item removed."))


@frappe.whitelist()
def attach_photo(
	movement: str,
	file_url: str,
	kind: str = "Item",
	item_row: str = "",
	caption: str = "",
	client_uuid: str = "",
	captured_at: str = "",
	latitude: float | None = None,
	longitude: float | None = None,
	accuracy_m: float | None = None,
	is_stamped: int = 1,
) -> dict:
	"""Register an already-uploaded, already-stamped photo against a movement.

	The upload itself goes through Frappe's file endpoint; this records what the
	photo *is*. `captured_at` is the phone's shutter time, kept distinct from
	`server_received_at` — at an offline gate the two differ by hours, and only
	the first one answers "when was this taken".
	"""
	_assert_can_write()
	doc = _load(movement)
	_assert_open(doc)

	if not (file_url or "").strip():
		frappe.throw(_("A photo is required."))
	if kind not in C.PHOTO_KINDS:
		frappe.throw(_("Unknown photo kind {0}.").format(kind))
	if item_row and not any(r.row_uuid == item_row for r in doc.items or []):
		frappe.throw(_("No item row {0} on this movement.").format(item_row))

	client_uuid = (client_uuid or "").strip()
	if client_uuid and any(p.client_uuid == client_uuid for p in doc.photos or []):
		return _ok(_serialise(doc, with_children=False), _("Photo already attached."))

	doc.append(
		"photos",
		{
			"file_url": file_url,
			"kind": kind,
			"item_row": item_row or None,
			"caption": caption,
			"client_uuid": client_uuid or frappe.generate_hash(length=20),
			"captured_by": frappe.session.user,
			"captured_at": captured_at or now_datetime(),
			"server_received_at": now_datetime(),
			"latitude": flt(latitude),
			"longitude": flt(longitude),
			"accuracy_m": flt(accuracy_m),
			"is_stamped": cint(is_stamped),
		},
	)
	doc.save()
	return _ok(_serialise(doc), _("Photo attached."))


@frappe.whitelist()
def delete_photo(movement: str, client_uuid: str) -> dict:
	"""Drop one photo from a movement."""
	_assert_can_write()
	doc = _load(movement)
	_assert_open(doc)

	before = len(doc.photos or [])
	doc.photos = [p for p in doc.photos or [] if p.client_uuid != client_uuid]
	if len(doc.photos) == before:
		frappe.throw(_("No such photo on this movement."))
	doc.save()
	return _ok(_serialise(doc), _("Photo removed."))


@frappe.whitelist()
def submit_movement(movement: str, remarks: str = "") -> dict:
	"""Hand a movement to a supervisor.

	Needs at least one item. Everything else — missing photos, missing serials —
	is reported back as a warning the operator has already seen and chosen to
	accept, because a gate clerk who cannot record the truck records nothing.
	"""
	_assert_can_write()
	doc = _load(movement)
	_assert_open(doc)

	if doc.status == C.STATUS_AWAITING_VERIFICATION:
		return _ok(_serialise(doc), _("Already submitted."))
	if not (doc.items or []):
		frappe.throw(_("Add at least one item before submitting."))

	if remarks:
		doc.remarks = remarks
	doc.status = C.STATUS_AWAITING_VERIFICATION
	doc.submitted_by = frappe.session.user
	doc.submitted_at = now_datetime()
	doc.save()

	label = _("{0} at {1}").format(doc.movement_type, doc.location)
	_notify(
		_supervisors_at(doc.location),
		_("Gate movement to verify"),
		_("{0}: {1} items from {2}. {3}% have a photo.").format(
			label, cint(doc.total_items), doc.party_name or _("unnamed party"), int(flt(doc.evidence_pct))
		),
		doc.name,
	)
	return _ok(_serialise(doc), _("Sent for verification."))


@frappe.whitelist()
def verify_movement(movement: str, remarks: str = "") -> dict:
	"""Close a movement. Not by the person who recorded it (§4 of the spec)."""
	_assert_can_verify()
	doc = _load(movement)

	if doc.status != C.STATUS_AWAITING_VERIFICATION:
		frappe.throw(_("Only a submitted movement can be verified; this one is {0}.").format(doc.status))
	_assert_not_verified_by_recorder(doc)

	if remarks:
		doc.remarks = f"{doc.remarks}\n{remarks}".strip() if doc.remarks else remarks
	doc.status = C.STATUS_COMPLETED
	doc.verified_by = frappe.session.user
	doc.verified_at = now_datetime()
	doc.completed_at = now_datetime()
	doc.save()

	_notify(
		[doc.started_by, doc.submitted_by],
		_("Gate movement verified"),
		_("{0} {1} was verified.").format(doc.movement_type, doc.name),
		doc.name,
	)
	return _ok(_serialise(doc), _("Verified."))


@frappe.whitelist()
def reject_movement(movement: str, reason: str) -> dict:
	"""Send a movement back to the operator with a reason they can act on."""
	_assert_can_verify()
	doc = _load(movement)

	reason = (reason or "").strip()
	if not reason:
		frappe.throw(_("Say why it is being sent back — the operator has to know what to fix."))
	if doc.status != C.STATUS_AWAITING_VERIFICATION:
		frappe.throw(_("Only a submitted movement can be sent back; this one is {0}.").format(doc.status))

	doc.status = C.STATUS_IN_PROGRESS
	doc.rejection_reason = reason
	doc.submitted_by = None
	doc.submitted_at = None
	doc.save()

	_notify(
		[doc.started_by],
		_("Gate movement sent back"),
		_("{0}: {1}").format(doc.name, reason),
		doc.name,
	)
	return _ok(_serialise(doc), _("Sent back to the operator."))


@frappe.whitelist()
def cancel_movement(movement: str, reason: str) -> dict:
	"""Cancel an open movement. Terminal — a cancelled record is kept, not deleted."""
	_assert_can_write()
	doc = _load(movement)
	_assert_open(doc)

	reason = (reason or "").strip()
	if not reason:
		frappe.throw(_("A cancellation needs a reason."))
	if doc.started_by != frappe.session.user and not _can_verify():
		frappe.throw(_("Only the operator who opened this movement, or a supervisor, may cancel it."))

	doc.status = C.STATUS_CANCELLED
	doc.rejection_reason = reason
	doc.completed_at = now_datetime()
	doc.save()
	return _ok(_serialise(doc, with_children=False), _("Movement cancelled."))


# ----------------------------------------------------------------- read paths


@frappe.whitelist()
def get_movement(name: str) -> dict:
	"""Full document for the detail screen."""
	_assert_can_read()
	return _ok(_serialise(_load(name, ptype="read")))


@frappe.whitelist()
def my_movements(scope: str = "open", location: str = "", limit: int = 25, offset: int = 0) -> dict:
	"""The Material list. `scope` is one of open / awaiting / finished / all.

	An operator sees their own movements; a supervisor sees the plant's. That
	split is enforced by `get_permission_query_conditions`, not repeated here —
	one place decides who sees what.
	"""
	_assert_can_read()
	limit = max(1, min(int(limit or 25), MAX_PAGE))
	offset = max(0, int(offset or 0))

	scopes = {
		# "Open" is the operator's own sense of open: anything not yet closed.
		# It used to stop at In Progress, so an entry the operator had just
		# finished left their list the moment they finished it — the register
		# they had been watching fill up went back to what it said before, and
		# the only chip that would have shown it is the verifier's queue, which
		# an operator cannot see. Waiting on a supervisor is still open work.
		"open": [["status", "in", [C.STATUS_DRAFT, C.STATUS_IN_PROGRESS, C.STATUS_AWAITING_VERIFICATION]]],
		"awaiting": [["status", "=", C.STATUS_AWAITING_VERIFICATION]],
		"finished": [["status", "in", [C.STATUS_COMPLETED, C.STATUS_CANCELLED]]],
		"all": [],
	}
	if scope not in scopes:
		frappe.throw(_("Unknown scope {0}.").format(scope))

	filters = [*scopes[scope], ["is_test", "=", 0]]
	if location:
		filters.append(["location", "=", location])

	rows = frappe.get_all(
		"Material Movement",
		filters=filters,
		fields=[
			"name",
			"movement_type",
			"location",
			"status",
			"purpose",
			"party_name",
			"reference_no",
			"transport_vehicle_no",
			"total_items",
			"total_qty",
			"photo_count",
			"qr_count",
			"damaged_count",
			"evidence_pct",
			"started_by",
			"started_at",
			"submitted_at",
			"verified_at",
		],
		order_by="modified desc",
		limit_page_length=limit,
		limit_start=offset,
	)

	awaiting = frappe.db.count("Material Movement", {"status": C.STATUS_AWAITING_VERIFICATION, "is_test": 0})
	return _ok(
		{
			"movements": rows,
			"has_more": len(rows) == limit,
			"awaiting_count": awaiting if _can_verify() else 0,
			"can_verify": _can_verify(),
		}
	)


@frappe.whitelist()
def where_used(qr_code: str) -> dict:
	"""Every movement a serial has passed through, newest first.

	The gate's answer to "where did this HVAC unit come from" — the same question
	`process.where_used` answers for a battery module, on the same shape of data.
	"""
	_assert_can_read()
	qr_code = (qr_code or "").strip()
	if not qr_code:
		frappe.throw(_("A serial is required."))

	rows = frappe.get_all(
		"Material Movement Item",
		filters={"qr_code": qr_code},
		fields=["parent", "item", "item_name", "qty", "uom", "condition", "creation"],
		order_by="creation desc",
		limit_page_length=50,
	)
	if not rows:
		return _ok({"serial": qr_code, "movements": []})

	headers = {
		h.name: h
		for h in frappe.get_all(
			"Material Movement",
			filters={"name": ["in", [r.parent for r in rows]]},
			fields=["name", "movement_type", "location", "status", "party_name", "started_at"],
			limit_page_length=50,
		)
	}
	out = []
	for row in rows:
		header = headers.get(row.parent)
		if not header:
			continue
		out.append(
			{
				"movement": row.parent,
				"movement_type": header.movement_type,
				"location": header.location,
				"status": header.status,
				"party_name": header.party_name,
				"at": str(header.started_at) if header.started_at else None,
				"item_name": row.item_name,
				"qty": flt(row.qty),
				"uom": row.uom,
				"condition": row.condition,
			}
		)
	return _ok({"serial": qr_code, "movements": out})
