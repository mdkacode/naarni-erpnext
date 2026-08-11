"""QR / barcode payload parsing, driven entirely by admin configuration.

Vendors change label formats without telling anyone, so parsing lives in
`Process Entity Type.qr_pattern` — one Python regex per line, using named groups
— rather than in code. Patterns are tried in order and the first match wins.

**Nothing is ever rejected.** When no pattern matches, the raw payload is stored
verbatim and the scan is flagged `parse_failed`. An admin adds a pattern later
and re-parses; the data was never lost. That matters because a scan is never
mandatory in this engine, and a parser that threw would turn an optional
convenience into a blocker.
"""

from __future__ import annotations

import re

import frappe
from frappe.utils import cint

from vehicle_maintenance.process_engine import constants as C

#: Compiled patterns are cached per entity type; cleared when the type is saved.
_CACHE_KEY = "process_engine:qr_patterns"


def compiled_patterns(entity_type: str) -> list:
	"""Compiled regexes for an entity type, newest config first.

	Invalid regexes are skipped and logged rather than raised — one malformed
	pattern must not stop every scan on the line.
	"""
	cache = frappe.cache()
	cached = cache.hget(_CACHE_KEY, entity_type)
	if cached is not None:
		return [re.compile(p) for p in cached]

	raw = frappe.db.get_value("Process Entity Type", entity_type, "qr_pattern") or ""
	sources = []
	for line in raw.splitlines():
		line = line.strip()
		if not line or line.startswith("#"):
			continue
		try:
			re.compile(line)
		except re.error as exc:
			frappe.log_error(
				title=f"Process Engine: bad QR pattern on {entity_type}",
				message=f"Pattern: {line}\nError: {exc}",
			)
			continue
		sources.append(line)

	cache.hset(_CACHE_KEY, entity_type, sources)
	return [re.compile(p) for p in sources]


def clear_pattern_cache(entity_type: str | None = None) -> None:
	"""Drop cached patterns after an entity type is edited."""
	cache = frappe.cache()
	if entity_type:
		cache.hdel(_CACHE_KEY, entity_type)
	else:
		cache.delete_key(_CACHE_KEY)


def parse_mfg_date(raw: str, fmt: str = "DDMMYY") -> str | None:
	"""Convert a compact manufacturing date to ``YYYY-MM-DD``.

	`DDMMYY` is the convention on cell and module labels across the industry.
	Two-digit years resolve to 2000+, which is correct for anything a battery
	plant will ever scan and wrong only for pre-2000 stock that does not exist.
	Returns ``None`` on anything unparseable — a bad date must not lose the scan.
	"""
	if not raw:
		return None
	digits = re.sub(r"\D", "", str(raw))
	slices = C.MFG_DATE_FORMATS.get((fmt or "DDMMYY").upper())
	if not slices or len(digits) < max(end for _, end in slices):
		return None

	(ds, de), (ms, me), (ys, ye) = slices
	try:
		day, month, year = int(digits[ds:de]), int(digits[ms:me]), int(digits[ys:ye])
	except ValueError:
		return None

	if year < 100:
		year += 2000
	if not (1 <= month <= 12 and 1 <= day <= 31 and 2000 <= year <= 2099):
		return None
	return f"{year:04d}-{month:02d}-{day:02d}"


def parse_payload(entity_type: str, payload: str) -> dict:
	"""Pull structured fields out of a scanned payload.

	Returns ``{serial_no, mfg_date, module_number, batch_ref, revision,
	raw_payload, parse_failed}``. On no match, `serial_no` falls back to the
	trimmed raw payload so the scan still identifies *something* — a serial an
	admin can search for beats a blank row.
	"""
	raw = (payload or "").strip()
	out = {
		"raw_payload": raw,
		"serial_no": None,
		"mfg_date": None,
		"module_number": None,
		"batch_ref": None,
		"revision": None,
		"parse_failed": 1,
	}
	if not raw:
		return out

	fmt = frappe.db.get_value("Process Entity Type", entity_type, "mfg_date_format") or "DDMMYY"

	for pattern in compiled_patterns(entity_type):
		match = pattern.search(raw)
		if not match:
			continue
		groups = match.groupdict()
		out["serial_no"] = (groups.get("serial") or "").strip() or None
		out["module_number"] = (groups.get("module") or "").strip() or None
		out["batch_ref"] = (groups.get("batch") or "").strip() or None
		out["revision"] = (groups.get("rev") or "").strip() or None
		out["mfg_date"] = parse_mfg_date(groups.get("mfg"), fmt)
		out["parse_failed"] = 0
		break

	if out["parse_failed"]:
		# No pattern matched. Keep the payload as the identity so the scan is
		# still searchable and still counts toward traceability completeness.
		out["serial_no"] = raw[:140]

	return out


def find_duplicate(entity_type: str, serial_no: str, exclude_run: str | None = None) -> str | None:
	"""The most recent other run that already recorded this serial, if any.

	Used to honour the entity type's `duplicate_policy`. Default is *Warn*,
	never *Block* — a legitimate rework re-scan must not be stopped at the
	station.
	"""
	if not serial_no:
		return None
	filters = {"entity_type": entity_type, "serial_no": serial_no}
	rows = frappe.get_all(
		"Process Run Scan",
		filters=filters,
		fields=["parent"],
		limit_page_length=5,
		order_by="creation desc",
	)
	for row in rows:
		if row.parent and row.parent != exclude_run:
			return row.parent
	return None


def expected_counts(step_defs: list[dict]) -> dict:
	"""Map ``entity_type`` → expected count across a process's scan steps.

	Reads the step's `scan_count` where set, otherwise the entity type's own
	`expected_count`, so a process can ask for fewer than the full complement
	without editing the shared master.
	"""
	out: dict[str, int] = {}
	for step in step_defs:
		if not cint(step.get("requires_scan")) and step.get("response_type") != C.SCAN:
			continue
		etype = step.get("scan_entity_type")
		if not etype:
			continue
		count = cint(step.get("scan_count"))
		if not count:
			count = cint(frappe.db.get_value("Process Entity Type", etype, "expected_count")) or 1
		out[etype] = out.get(etype, 0) + count
	return out
