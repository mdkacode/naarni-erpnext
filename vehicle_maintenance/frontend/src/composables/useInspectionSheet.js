/**
 * Inspection Check Sheet auto-selection based on odometer reading.
 *
 * EAS — Automate: The technician never picks a sheet manually.
 * The odometer reading fully determines which checklist applies.
 *
 * PRD thresholds (20K/40K/80K):
 * Sheet A: 0 – 20,000 km       (Basic — first services)
 * Sheet B: 20,001 – 40,000 km  (Standard — mid-life maintenance)
 * Sheet C: 40,001 – 80,000 km  (Comprehensive — aging vehicle)
 * Sheet D: 80,001+ km          (Major — full systems check)
 */

/**
 * @typedef {Object} InspectionItem
 * @property {string} id - Unique key for the check
 * @property {string} label - Human-readable description shown to technician
 * @property {string} category - Grouping label for visual sections
 * @property {'three_tier'|'measurement'|'text'} inputType - What the tech needs to enter
 * @property {string} [unit] - Unit for measurement type (e.g., "mm", "psi")
 * @property {number} [min] - Min acceptable value for measurement
 * @property {number} [max] - Max acceptable value for measurement
 */

/** Three-tier inspection statuses per PRD p.7. Values mirror backend enum. */
export const STATUS_GOOD = "Good";
export const STATUS_RECOMMENDED = "Repair/Replace Recommended";
export const STATUS_IMMEDIATE = "Repair/Replace Immediately";

/** Score contribution per status (matches backend HEALTH_SCORE_MAP). */
export const STATUS_SCORES = {
  [STATUS_GOOD]: 10,
  [STATUS_RECOMMENDED]: 5,
  [STATUS_IMMEDIATE]: 0,
};

const SHEET_A_ITEMS = [
  { id: "a_engine_oil", label: "Engine oil level & condition", category: "Fluids", inputType: "three_tier" },
  { id: "a_coolant", label: "Coolant level", category: "Fluids", inputType: "three_tier" },
  { id: "a_brake_fluid", label: "Brake fluid level", category: "Fluids", inputType: "three_tier" },
  { id: "a_tyre_pressure_fl", label: "Tyre pressure — Front Left", category: "Tyres", inputType: "measurement", unit: "psi", min: 30, max: 35 },
  { id: "a_tyre_pressure_fr", label: "Tyre pressure — Front Right", category: "Tyres", inputType: "measurement", unit: "psi", min: 30, max: 35 },
  { id: "a_tyre_pressure_rl", label: "Tyre pressure — Rear Left", category: "Tyres", inputType: "measurement", unit: "psi", min: 30, max: 35 },
  { id: "a_tyre_pressure_rr", label: "Tyre pressure — Rear Right", category: "Tyres", inputType: "measurement", unit: "psi", min: 30, max: 35 },
  { id: "a_lights", label: "All lights functional", category: "Electrical", inputType: "three_tier" },
  { id: "a_horn", label: "Horn functional", category: "Electrical", inputType: "three_tier" },
  { id: "a_wipers", label: "Wipers & washer fluid", category: "Electrical", inputType: "three_tier" },
  { id: "a_exterior", label: "Body exterior condition", category: "Body", inputType: "text" },
];

const SHEET_B_ITEMS = [
  // Includes all of Sheet A plus mid-life checks
  ...SHEET_A_ITEMS.map((item) => ({ ...item, id: item.id.replace("a_", "b_") })),
  { id: "b_brake_pad_fl", label: "Brake pad thickness — Front Left", category: "Brakes", inputType: "measurement", unit: "mm", min: 3, max: 12 },
  { id: "b_brake_pad_fr", label: "Brake pad thickness — Front Right", category: "Brakes", inputType: "measurement", unit: "mm", min: 3, max: 12 },
  { id: "b_brake_pad_rl", label: "Brake pad thickness — Rear Left", category: "Brakes", inputType: "measurement", unit: "mm", min: 3, max: 12 },
  { id: "b_brake_pad_rr", label: "Brake pad thickness — Rear Right", category: "Brakes", inputType: "measurement", unit: "mm", min: 3, max: 12 },
  { id: "b_tyre_tread_fl", label: "Tyre tread depth — Front Left", category: "Tyres", inputType: "measurement", unit: "mm", min: 1.6, max: 8 },
  { id: "b_tyre_tread_fr", label: "Tyre tread depth — Front Right", category: "Tyres", inputType: "measurement", unit: "mm", min: 1.6, max: 8 },
  { id: "b_suspension", label: "Suspension — visual check for leaks/damage", category: "Chassis", inputType: "three_tier" },
  { id: "b_exhaust", label: "Exhaust system — leaks or damage", category: "Chassis", inputType: "three_tier" },
  { id: "b_battery", label: "Battery terminal condition", category: "Electrical", inputType: "three_tier" },
  { id: "b_ac", label: "A/C blows cold", category: "Comfort", inputType: "three_tier" },
  { id: "b_belt_condition", label: "Drive belt condition", category: "Engine", inputType: "three_tier" },
];

const SHEET_C_ITEMS = [
  // Includes all of Sheet B plus deep inspections
  ...SHEET_B_ITEMS.map((item) => ({ ...item, id: item.id.replace("b_", "c_") })),
  { id: "c_transmission_fluid", label: "Transmission fluid level & color", category: "Fluids", inputType: "three_tier" },
  { id: "c_power_steering", label: "Power steering fluid", category: "Fluids", inputType: "three_tier" },
  { id: "c_wheel_bearing_fl", label: "Wheel bearing play — Front Left", category: "Chassis", inputType: "three_tier" },
  { id: "c_wheel_bearing_fr", label: "Wheel bearing play — Front Right", category: "Chassis", inputType: "three_tier" },
  { id: "c_cv_joints", label: "CV joint boots — cracks or leaks", category: "Chassis", inputType: "three_tier" },
  { id: "c_radiator", label: "Radiator — leaks, fin condition", category: "Engine", inputType: "three_tier" },
  { id: "c_timing_belt", label: "Timing belt/chain — condition & tension", category: "Engine", inputType: "three_tier" },
  { id: "c_undercarriage", label: "Undercarriage rust/corrosion", category: "Body", inputType: "three_tier" },
  { id: "c_alignment", label: "Wheel alignment — visual pull check", category: "Chassis", inputType: "three_tier" },
];

const SHEET_D_ITEMS = [
  // Major service — all of Sheet C plus engine-internal checks
  ...SHEET_C_ITEMS.map((item) => ({ ...item, id: item.id.replace("c_", "d_") })),
  { id: "d_compression", label: "Engine compression test notes", category: "Engine", inputType: "text" },
  { id: "d_injector", label: "Fuel injector spray pattern", category: "Engine", inputType: "three_tier" },
  { id: "d_egr", label: "EGR valve condition", category: "Engine", inputType: "three_tier" },
  { id: "d_gearbox", label: "Gearbox oil level & condition", category: "Fluids", inputType: "three_tier" },
  { id: "d_diff", label: "Differential oil level", category: "Fluids", inputType: "three_tier" },
  { id: "d_body_corrosion", label: "Structural corrosion (chassis rails)", category: "Body", inputType: "three_tier" },
];

/** Thresholds for sheet selection (PRD 20K/40K/80K) */
const SHEET_B_THRESHOLD = 20000;
const SHEET_C_THRESHOLD = 40000;
const SHEET_D_THRESHOLD = 80000;

/**
 * Determine which inspection sheet applies for a given odometer reading.
 * @param {number} odometerKm
 * @returns {{ sheetId: 'A'|'B'|'C'|'D', sheetLabel: string, items: InspectionItem[] }}
 */
export function getInspectionSheet(odometerKm) {
  const km = Number(odometerKm) || 0;

  if (km > SHEET_D_THRESHOLD) {
    return {
      sheetId: "D",
      sheetLabel: "Major Service (80,001+ km)",
      items: SHEET_D_ITEMS,
    };
  }
  if (km > SHEET_C_THRESHOLD) {
    return {
      sheetId: "C",
      sheetLabel: "Comprehensive Inspection (40,001–80,000 km)",
      items: SHEET_C_ITEMS,
    };
  }
  if (km > SHEET_B_THRESHOLD) {
    return {
      sheetId: "B",
      sheetLabel: "Standard Inspection (20,001–40,000 km)",
      items: SHEET_B_ITEMS,
    };
  }
  return {
    sheetId: "A",
    sheetLabel: "Basic Inspection (0–20,000 km)",
    items: SHEET_A_ITEMS,
  };
}

/**
 * Group inspection items by category for step-based display.
 * @param {InspectionItem[]} items
 * @returns {Map<string, InspectionItem[]>}
 */
export function groupByCategory(items) {
  const groups = new Map();
  for (const item of items) {
    if (!groups.has(item.category)) {
      groups.set(item.category, []);
    }
    groups.get(item.category).push(item);
  }
  return groups;
}
