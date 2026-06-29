"""PMS inspection check sheets (A/B/C/D), the shared source of truth.

Ported verbatim from the Vue composable
`frontend/src/composables/useInspectionSheet.js` so the Android app, the web SPA
and the backend all agree on the component grids and PRD odometer thresholds
(20K / 40K / 80K). Each item is a single component the technician inspects.

Item shape:
    {
        "id": str,            # stable check id (also used as inspection_results key)
        "label": str,         # human-readable description
        "category": str,      # grouping for the UI (Fluids, Brakes, Tyres, …)
        "input_type": str,    # "three_tier" | "measurement" | "text"
        "unit": str | None,   # for measurement (psi, mm, …)
        "min": float | None,  # acceptable range lower bound (measurement)
        "max": float | None,  # acceptable range upper bound (measurement)
    }
"""

from __future__ import annotations


def _t(id: str, label: str, category: str) -> dict:
	return {
		"id": id,
		"label": label,
		"category": category,
		"input_type": "three_tier",
		"unit": None,
		"min": None,
		"max": None,
	}


def _m(id: str, label: str, category: str, unit: str, lo: float, hi: float) -> dict:
	return {
		"id": id,
		"label": label,
		"category": category,
		"input_type": "measurement",
		"unit": unit,
		"min": lo,
		"max": hi,
	}


def _txt(id: str, label: str, category: str) -> dict:
	return {
		"id": id,
		"label": label,
		"category": category,
		"input_type": "text",
		"unit": None,
		"min": None,
		"max": None,
	}


SHEET_A_ITEMS: list[dict] = [
	_t("a_engine_oil", "Engine oil level & condition", "Fluids"),
	_t("a_coolant", "Coolant level", "Fluids"),
	_t("a_brake_fluid", "Brake fluid level", "Fluids"),
	_m("a_tyre_pressure_fl", "Tyre pressure — Front Left", "Tyres", "psi", 30, 35),
	_m("a_tyre_pressure_fr", "Tyre pressure — Front Right", "Tyres", "psi", 30, 35),
	_m("a_tyre_pressure_rl", "Tyre pressure — Rear Left", "Tyres", "psi", 30, 35),
	_m("a_tyre_pressure_rr", "Tyre pressure — Rear Right", "Tyres", "psi", 30, 35),
	_t("a_lights", "All lights functional", "Electrical"),
	_t("a_horn", "Horn functional", "Electrical"),
	_t("a_wipers", "Wipers & washer fluid", "Electrical"),
	_txt("a_exterior", "Body exterior condition", "Body"),
]


def _rekey(items: list[dict], old: str, new: str) -> list[dict]:
	return [{**it, "id": it["id"].replace(old, new, 1)} for it in items]


SHEET_B_ITEMS: list[dict] = [
	*_rekey(SHEET_A_ITEMS, "a_", "b_"),
	_m("b_brake_pad_fl", "Brake pad thickness — Front Left", "Brakes", "mm", 3, 12),
	_m("b_brake_pad_fr", "Brake pad thickness — Front Right", "Brakes", "mm", 3, 12),
	_m("b_brake_pad_rl", "Brake pad thickness — Rear Left", "Brakes", "mm", 3, 12),
	_m("b_brake_pad_rr", "Brake pad thickness — Rear Right", "Brakes", "mm", 3, 12),
	_m("b_tyre_tread_fl", "Tyre tread depth — Front Left", "Tyres", "mm", 1.6, 8),
	_m("b_tyre_tread_fr", "Tyre tread depth — Front Right", "Tyres", "mm", 1.6, 8),
	_t("b_suspension", "Suspension — visual check for leaks/damage", "Chassis"),
	_t("b_exhaust", "Exhaust system — leaks or damage", "Chassis"),
	_t("b_battery", "Battery terminal condition", "Electrical"),
	_t("b_ac", "A/C blows cold", "Comfort"),
	_t("b_belt_condition", "Drive belt condition", "Engine"),
]

SHEET_C_ITEMS: list[dict] = [
	*_rekey(SHEET_B_ITEMS, "b_", "c_"),
	_t("c_transmission_fluid", "Transmission fluid level & color", "Fluids"),
	_t("c_power_steering", "Power steering fluid", "Fluids"),
	_t("c_wheel_bearing_fl", "Wheel bearing play — Front Left", "Chassis"),
	_t("c_wheel_bearing_fr", "Wheel bearing play — Front Right", "Chassis"),
	_t("c_cv_joints", "CV joint boots — cracks or leaks", "Chassis"),
	_t("c_radiator", "Radiator — leaks, fin condition", "Engine"),
	_t("c_timing_belt", "Timing belt/chain — condition & tension", "Engine"),
	_t("c_undercarriage", "Undercarriage rust/corrosion", "Body"),
	_t("c_alignment", "Wheel alignment — visual pull check", "Chassis"),
]

SHEET_D_ITEMS: list[dict] = [
	*_rekey(SHEET_C_ITEMS, "c_", "d_"),
	_txt("d_compression", "Engine compression test notes", "Engine"),
	_t("d_injector", "Fuel injector spray pattern", "Engine"),
	_t("d_egr", "EGR valve condition", "Engine"),
	_t("d_gearbox", "Gearbox oil level & condition", "Fluids"),
	_t("d_diff", "Differential oil level", "Fluids"),
	_t("d_body_corrosion", "Structural corrosion (chassis rails)", "Body"),
]

# PRD thresholds (20K / 40K / 80K).
_SHEETS = {
	"A": ("Basic Inspection (0–20,000 km)", SHEET_A_ITEMS),
	"B": ("Standard Inspection (20,001–40,000 km)", SHEET_B_ITEMS),
	"C": ("Comprehensive Inspection (40,001–80,000 km)", SHEET_C_ITEMS),
	"D": ("Major Service (80,001+ km)", SHEET_D_ITEMS),
}


def sheet_id_for_odometer(odometer_km: int | None) -> str:
	"""Return 'A'/'B'/'C'/'D' for the given odometer (PRD 20K/40K/80K thresholds)."""
	km = int(odometer_km or 0)
	if km > 80_000:
		return "D"
	if km > 40_000:
		return "C"
	if km > 20_000:
		return "B"
	return "A"


def get_sheet(odometer_km: int | None = None, sheet_id: str | None = None) -> dict:
	"""Resolve a check sheet by explicit id, else by odometer.

	Returns {sheet_id, sheet_label, items: [...]}.
	"""
	sid = (sheet_id or "").strip().upper() or sheet_id_for_odometer(odometer_km)
	if sid not in _SHEETS:
		sid = sheet_id_for_odometer(odometer_km)
	label, items = _SHEETS[sid]
	return {"sheet_id": sid, "sheet_label": label, "items": items}
