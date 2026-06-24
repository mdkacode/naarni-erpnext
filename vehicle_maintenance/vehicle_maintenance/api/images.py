"""Bus image gallery — multi-angle photos mapped to a Vehicle or a Job Card.

The Naarni Service app uploads a (geo/time/user-stamped) photo via Frappe's
built-in `upload_file`, then calls `upload_bus_image` to map that file URL to a
named angle on the parent record. One image per angle (re-uploading an angle
replaces it), so the gallery stays a stable grid. The `Master` angle (or an
explicit `is_primary`) also stamps the Vehicle's `master_image` for a quick
identity preview in the Frappe admin dashboard.

Both parents reuse the same `Vehicle Image` child doctype:
  • Vehicle.images       — the persistent identity gallery
  • Job Card.bus_images  — per-visit snapshots
"""

import frappe
from frappe import _

# Parent doctype → the child Table fieldname that holds the gallery.
_GALLERY_FIELD = {
	"Vehicle": "images",
	"Job Card": "bus_images",
}

ANGLES = ("Master", "Front", "Rear", "Left", "Right", "Engine", "Interior", "Odometer", "Damage")


def _resolve(parent_doctype: str) -> str:
	"""Validate the parent doctype and return its gallery fieldname."""
	field = _GALLERY_FIELD.get(parent_doctype)
	if not field:
		frappe.throw(_("Images are only supported on Vehicle or Job Card."))
	return field


@frappe.whitelist()
def upload_bus_image(
	parent_doctype: str,
	parent_name: str,
	angle: str,
	file_url: str,
	is_primary: int | bool = 0,
	latitude: float | None = None,
	longitude: float | None = None,
	notes: str | None = None,
) -> dict:
	"""Map an already-uploaded file URL to a named angle on a Vehicle / Job Card.

	Args:
	    parent_doctype: "Vehicle" or "Job Card".
	    parent_name: The parent document name.
	    angle: One of ANGLES (Master, Front, Rear, …).
	    file_url: The `/files/...` URL returned by Frappe's upload_file.
	    is_primary: When truthy (or angle == "Master"), marks this the primary
	        image and copies it to Vehicle.master_image.
	    latitude/longitude: Optional capture coordinates (the photo is also
	        visually stamped client-side).
	    notes: Optional free-text note.

	Re-uploading an existing angle replaces that row (stable grid). Returns the
	refreshed gallery.
	"""
	field = _resolve(parent_doctype)
	frappe.has_permission(parent_doctype, doc=parent_name, ptype="write", throw=True)

	if angle not in ANGLES:
		frappe.throw(_("Invalid angle: {0}").format(angle))
	if not file_url:
		frappe.throw(_("A file URL is required."))

	primary = bool(int(is_primary)) if str(is_primary).isdigit() else bool(is_primary)
	if angle == "Master":
		primary = True

	doc = frappe.get_doc(parent_doctype, parent_name)
	now = frappe.utils.now_datetime()

	# Replace the existing row for this angle, keep the others.
	rows = [r for r in doc.get(field, []) if r.angle != angle]
	doc.set(field, [])
	for r in rows:
		# If this upload is primary, clear any prior primary flag.
		if primary:
			r.is_primary = 0
		doc.append(field, r)

	doc.append(
		field,
		{
			"angle": angle,
			"image": file_url,
			"is_primary": 1 if primary else 0,
			"captured_by": frappe.session.user,
			"captured_at": now,
			"latitude": frappe.utils.flt(latitude) if latitude not in (None, "") else None,
			"longitude": frappe.utils.flt(longitude) if longitude not in (None, "") else None,
			"notes": notes or "",
		},
	)

	# Mirror the primary onto the Vehicle's identity image for the admin preview.
	if parent_doctype == "Vehicle" and primary:
		doc.master_image = file_url

	doc.save(ignore_permissions=True)
	frappe.db.commit()

	return {
		"success": True,
		"data": _serialize_gallery(doc, field),
		"message": _("{0} photo saved.").format(angle),
	}


@frappe.whitelist()
def get_bus_images(parent_doctype: str, parent_name: str) -> dict:
	"""Return the angle gallery for a Vehicle or Job Card (newest angle wins)."""
	field = _resolve(parent_doctype)
	frappe.has_permission(parent_doctype, doc=parent_name, throw=True)
	doc = frappe.get_doc(parent_doctype, parent_name)
	return {"success": True, "data": _serialize_gallery(doc, field)}


@frappe.whitelist()
def delete_bus_image(parent_doctype: str, parent_name: str, angle: str) -> dict:
	"""Remove the photo for a given angle from the parent's gallery."""
	field = _resolve(parent_doctype)
	frappe.has_permission(parent_doctype, doc=parent_name, ptype="write", throw=True)

	doc = frappe.get_doc(parent_doctype, parent_name)
	kept = [r for r in doc.get(field, []) if r.angle != angle]
	doc.set(field, [])
	for r in kept:
		doc.append(field, r)
	if parent_doctype == "Vehicle" and angle == "Master":
		doc.master_image = None
	doc.save(ignore_permissions=True)
	frappe.db.commit()

	return {"success": True, "data": _serialize_gallery(doc, field), "message": _("Photo removed.")}


def _serialize_gallery(doc, field: str) -> list[dict]:
	"""Plain-dict view of a gallery child table, in ANGLES order."""
	by_angle = {
		r.angle: {
			"angle": r.angle,
			"image": r.image,
			"is_primary": int(r.is_primary or 0),
			"captured_by": r.captured_by,
			"captured_at": str(r.captured_at) if r.captured_at else None,
			"latitude": float(r.latitude) if r.latitude else None,
			"longitude": float(r.longitude) if r.longitude else None,
			"notes": r.notes,
		}
		for r in doc.get(field, [])
	}
	# Stable order: declared ANGLES first, then any unexpected extras.
	ordered = [by_angle[a] for a in ANGLES if a in by_angle]
	ordered += [v for k, v in by_angle.items() if k not in ANGLES]
	return ordered
