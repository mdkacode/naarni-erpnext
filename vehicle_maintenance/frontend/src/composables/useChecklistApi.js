import { call } from "frappe-ui";

const NS = "vehicle_maintenance.api.checklist";

export const checklistApi = {
	getTemplate: (params) => call(`${NS}.get_check_sheet_template`, params),
	listTemplates: () => call(`${NS}.list_templates`),
	startSection: (jobCard, sectionCode) =>
		call(`${NS}.start_section`, { job_card: jobCard, section_code: sectionCode }),
	endSection: (jobCard, sectionCode, responses) =>
		call(`${NS}.end_section`, {
			job_card: jobCard,
			section_code: sectionCode,
			responses: JSON.stringify(responses || []),
		}),
	submit: (jobCard) => call(`${NS}.submit_checklist`, { job_card: jobCard }),
	timingSummary: (filters) => call(`${NS}.section_timing_summary`, filters || {}),
};
