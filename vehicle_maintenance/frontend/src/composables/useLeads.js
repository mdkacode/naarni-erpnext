/**
 * Thin composable over the CRM whitelisted endpoints.
 * Pages manage their own local reactive state; this module just centralises
 * method names + envelope unwrapping.
 */
import { callAPI } from "../utils/api.js";

function unwrap(res) {
	return res?.data ?? res;
}

export function useLeads() {
	return {
		async list({ filters = {}, page = 1, pageSize = 20, search = "" } = {}) {
			const res = await callAPI("crm.get_lead_list", {
				filters,
				page,
				page_size: pageSize,
				search,
			});
			return unwrap(res);
		},

		async get(name) {
			const res = await callAPI("crm.get_lead", { name });
			return unwrap(res);
		},

		async create(payload) {
			const res = await callAPI("crm.create_lead", { payload });
			return unwrap(res);
		},

		async updateStatus(name, newStatus, note = "") {
			const res = await callAPI("crm.update_lead_status", {
				name,
				new_status: newStatus,
				note,
			});
			return unwrap(res);
		},

		async addActivity(lead, payload) {
			const res = await callAPI("crm.add_activity", { lead, payload });
			return unwrap(res);
		},

		async scheduleReminder(payload) {
			const res = await callAPI("crm.schedule_reminder", { payload });
			return unwrap(res);
		},

		async cancelReminder(name) {
			const res = await callAPI("crm.cancel_reminder", { name });
			return unwrap(res);
		},

		async convert(name) {
			const res = await callAPI("crm.convert_lead_to_customer", { name });
			return unwrap(res);
		},

		async uploadAttachment(lead, fileUrl, caption = "") {
			const res = await callAPI("crm.upload_lead_attachment", {
				lead,
				file_url: fileUrl,
				caption,
			});
			return unwrap(res);
		},
	};
}
