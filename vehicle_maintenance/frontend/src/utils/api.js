/**
 * Thin wrapper around frappe-ui's createResource for our custom API methods.
 *
 * Usage:
 *   import { callAPI } from "@/utils/api.js";
 *   const result = await callAPI("vehicle_maintenance.api.job_card.get_my_job_cards", { status: "WIP" });
 */
import { call } from "frappe-ui";

const APP_PREFIX = "vehicle_maintenance.api";

/**
 * Call a whitelisted API method.
 * @param {string} method - Full dotted path or short path (e.g., "job_card.get_my_job_cards")
 * @param {Record<string, any>} params
 * @returns {Promise<any>} The `message` field from Frappe's response (our {success, data, message} envelope)
 */
export async function callAPI(method, params = {}) {
  const fullPath = method.includes("vehicle_maintenance")
    ? method
    : `${APP_PREFIX}.${method}`;

  return call(fullPath, params);
}

/**
 * Map workflow_state to a CSS badge class name.
 * @param {string} state
 * @returns {string}
 */
export function stateBadgeClass(state) {
  const map = {
    Open: "badge-open",
    WIP: "badge-wip",
    "Awaiting Customer Approval": "badge-approval",
    "Awaiting Parts": "badge-parts",
    "Parts Fitted": "badge-fitted",
    "Verification Pending": "badge-verify",
    Closed: "badge-closed",
  };
  return `badge-status ${map[state] || "badge-open"}`;
}
