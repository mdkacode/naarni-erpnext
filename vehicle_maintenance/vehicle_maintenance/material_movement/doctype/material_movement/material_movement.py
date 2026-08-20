"""Material Movement — one gate event, and the numbers derived from it.

The controller keeps *derivation* here and *transitions* in `api.material`: this
class recomputes every rollup from whatever child rows exist, so a row edited by
hand in Desk produces the same totals as one saved through the app. There is
exactly one place that decides what a movement's counts are.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import cint, flt, now_datetime

from vehicle_maintenance.material_movement import constants as C


class MaterialMovement(Document):
	def before_insert(self):
		if not self.started_at:
			self.started_at = now_datetime()
		if not self.started_by:
			self.started_by = frappe.session.user
		self._stamp_geofence()

	def validate(self):
		self._validate_purpose()
		self._normalise_items()
		self._recompute()

	# ------------------------------------------------------------- validation

	def _validate_purpose(self) -> None:
		"""An outward purpose on an inward note is a data-entry error, not a choice."""
		if not self.purpose:
			return
		allowed = C.PURPOSES.get(self.movement_type, ())
		if self.purpose not in allowed:
			frappe.throw(
				_("{0} is not a valid purpose for an {1} movement.").format(self.purpose, self.movement_type)
			)

	def _normalise_items(self) -> None:
		"""Fill the denormalised item columns and reject rows that say nothing.

		Names are copied from the master at write time rather than read through
		the Link on display, so a movement recorded in March still reads
		correctly after the item is renamed in June. That is the whole point of a
		gate record.
		"""
		seen_uuids: set[str] = set()
		for row in self.items or []:
			if not row.row_uuid:
				row.row_uuid = frappe.generate_hash(length=20)
			if row.row_uuid in seen_uuids:
				frappe.throw(_("Duplicate row identifier {0} on this movement.").format(row.row_uuid))
			seen_uuids.add(row.row_uuid)

			if flt(row.qty) <= 0:
				frappe.throw(_("Row {0}: quantity must be greater than zero.").format(row.idx))

			if row.item and not (row.item_name and row.uom):
				master = frappe.db.get_value(
					"Part", row.item, ["part_name", "part_group", "stock_uom"], as_dict=True
				)
				if master:
					row.item_name = row.item_name or master.part_name
					row.item_group = row.item_group or master.part_group
					row.uom = row.uom or master.stock_uom

			# A serial with no source recorded is almost always a typed one — a
			# scan always sets it. Guessing "Typed" understates nothing.
			if row.qr_code and not row.qr_source:
				row.qr_source = C.QR_TYPED

	# ------------------------------------------------------------ derivation

	def _recompute(self) -> None:
		"""Recompute every rollup from the child rows. Idempotent by construction."""
		items = self.items or []
		photos = self.photos or []

		photographed = {p.item_row for p in photos if p.item_row}

		self.total_items = len(items)
		self.total_qty = flt(sum(flt(r.qty) for r in items), 3)
		self.photo_count = len(photos)
		self.qr_count = sum(1 for r in items if (r.qr_code or "").strip())
		self.damaged_count = sum(1 for r in items if r.condition and r.condition != C.CONDITION_OK)
		self.new_item_count = sum(1 for r in items if cint(r.is_new_item))

		for row in items:
			row.photo_count = sum(1 for p in photos if p.item_row == row.row_uuid)

		with_photo = sum(1 for r in items if r.row_uuid in photographed)
		self.evidence_pct = flt(with_photo * 100.0 / len(items), 2) if items else 0.0

	def _stamp_geofence(self) -> None:
		"""Advisory only — a movement outside the fence is flagged, never refused.

		A gate is routinely a hundred metres from wherever the phone last got a
		fix, and a hard fence would simply teach clerks to record the truck after
		they walk back to the office.
		"""
		if not (self.latitude and self.longitude and self.location):
			self.geofence_status = "Unknown"
			return

		plant = frappe.db.get_value(
			"Material Location", self.location, ["latitude", "longitude", "geofence_radius_m"], as_dict=True
		)
		if not plant or not (plant.latitude and plant.longitude):
			self.geofence_status = "Unknown"
			return

		radius = cint(plant.geofence_radius_m) or 500
		self.geofence_status = (
			"Inside"
			if _metres_between(self.latitude, self.longitude, plant.latitude, plant.longitude) <= radius
			else "Outside"
		)


def _metres_between(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
	"""Great-circle distance in metres.

	Equirectangular rather than haversine: over the few hundred metres a geofence
	spans the error is centimetres, and this is on the save path.
	"""
	import math

	mean_lat = math.radians((flt(lat1) + flt(lat2)) / 2)
	x = math.radians(flt(lon2) - flt(lon1)) * math.cos(mean_lat)
	y = math.radians(flt(lat2) - flt(lat1))
	return math.sqrt(x * x + y * y) * 6_371_000


def get_permission_query_conditions(user: str | None = None) -> str:
	"""Restrict the Desk list view to what the user may see.

	Supervisors and managers see every movement; an operator sees the ones they
	recorded. Mirrors `process_run.get_permission_query_conditions`, and is the
	server-side half of the app's tab gating — the tab hides, this secures.
	"""
	user = user or frappe.session.user
	if user == "Administrator":
		return ""

	roles = set(frappe.get_roles(user))
	if roles & set(C.SUPERVISOR_ROLES) or "Material Viewer" in roles:
		return ""

	if C.ROLE_OPERATOR in roles:
		return f"""(`tabMaterial Movement`.started_by = {frappe.db.escape(user)})"""

	return "1=0"


def has_permission(doc, ptype: str = "read", user: str | None = None) -> bool:
	"""Document-level gate matching the list conditions above."""
	user = user or frappe.session.user
	if user == "Administrator":
		return True

	roles = set(frappe.get_roles(user))
	if roles & set(C.SUPERVISOR_ROLES):
		return True

	if ptype == "read" and "Material Viewer" in roles:
		return True

	if C.ROLE_OPERATOR in roles:
		# `create` is checked by Document.insert BEFORE before_insert runs, so a
		# brand-new document has no `started_by` yet and comparing it to the
		# session user would refuse every operator their first save.
		if ptype == "create" or not doc.started_by:
			return True
		return doc.started_by == user

	return False
