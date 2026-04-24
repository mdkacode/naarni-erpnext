/**
 * CRM dropdown cache — single round-trip to `crm.get_lead_dropdowns`
 * shared across all Lead pages. Refresh forces a re-fetch.
 */
import { ref } from "vue";
import { callAPI } from "../utils/api.js";

const state = ref({
	sources: [],
	statuses: [],
	sales_users: [],
	depots: [],
	email_templates: [],
});
const loaded = ref(false);
const loading = ref(false);
let inflight = null;

async function load(force = false) {
	if (loaded.value && !force) return state.value;
	if (inflight) return inflight;

	loading.value = true;
	inflight = (async () => {
		try {
			const res = await callAPI("crm.get_lead_dropdowns");
			const data = res?.data || res; // envelope-or-raw tolerance
			state.value = {
				sources: data.sources || [],
				statuses: data.statuses || [],
				sales_users: data.sales_users || [],
				depots: data.depots || [],
				email_templates: data.email_templates || [],
			};
			loaded.value = true;
			return state.value;
		} finally {
			loading.value = false;
			inflight = null;
		}
	})();
	return inflight;
}

export function useCrmDropdowns() {
	return {
		dropdowns: state,
		loaded,
		loading,
		load,
		refresh: () => load(true),
	};
}
