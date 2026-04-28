import { call } from "frappe-ui";

const NS = "vehicle_maintenance.api.onboarding";

export const onboardingApi = {
	state: (jc) => call(`${NS}.get_onboarding_state`, { job_card_name: jc }),
	sheet: (jc) => call(`${NS}.get_check_sheet_for_jc`, { job_card_name: jc }),
	assign: (jc, se, tech) =>
		call(`${NS}.assign_job_card`, {
			job_card_name: jc,
			assigned_service_engineer: se || null,
			assigned_technician: tech || null,
		}),
	saveResponses: (jc, responses) =>
		call(`${NS}.save_inspection_responses`, {
			job_card_name: jc,
			responses: JSON.stringify(responses || []),
		}),
	attachPhotos: (jc, chassis, odometer) =>
		call(`${NS}.attach_before_photos`, {
			job_card_name: jc,
			chassis_photo: chassis ?? null,
			odometer_photo: odometer ?? null,
		}),
	complete: (jc) => call(`${NS}.complete_onboarding`, { job_card_name: jc }),
};
