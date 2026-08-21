<!--
  New job card.

  Four steps: what kind of work, which bus, where and why, then a review. The
  job-card types were rendered with emoji (🔧 🛠 💻 🚨) — which render as a
  different drawing on every phone in the depot and cannot be tinted or aligned.
  They are icons now, from the one set.

  The vehicle and depot pickers were two hand-rolled async dropdowns with their
  own debounce, their own outside-click handler and their own "selected" panel.
  Both are NCombobox now, which also fixes the out-of-order-response bug they
  shared: a slow request for "DL" could land after a fast one for "DL-01" and
  repopulate the list with the wrong buses.
-->
<template>
	<div>
		<NPageHeader title="New job card" back="/service-portal" back-label="Back to job cards" />

		<div class="p-5">
			<Wizard v-model="formData" :steps="steps" submit-label="Create job card" @complete="handleSubmit">
				<template #step-type="{ data, updateField }">
					<NChoice
						:model-value="data.job_card_type"
						:options="jobCardTypes"
						label="What kind of work is this?"
						:columns="2"
						required
						@update:model-value="(v) => selectJobType(v, updateField)"
					/>
				</template>

				<template #step-vehicle="{ data, updateField }">
					<div class="space-y-4">
						<NCombobox
							:model-value="selectedVehicle"
							label="Vehicle"
							placeholder="Registration number or model"
							hint="Type at least two characters."
							required
							:search="searchVehicles"
							:display="(v) => fmt.vehicle(v.registration_number)"
							:describe="(v) => v.make_model || ''"
							@update:model-value="(v) => selectVehicle(v, updateField)"
						/>

						<NInput
							:model-value="data.odometer_reading ?? ''"
							label="Odometer reading"
							type="number"
							inputmode="numeric"
							placeholder="145000"
							required
							@update:model-value="
								(v) => updateField('odometer_reading', v === '' ? null : Number(v))
							"
						>
							<template #suffix><span class="pr-2 text-body-sm text-muted">km</span></template>
						</NInput>

						<NCard v-if="data.customer_name || data.auto_check_sheet" padded>
							<NKeyValue
								:items="[
									{ label: 'Customer', value: data.customer_name, hideWhenEmpty: true },
									{
										label: 'Check sheet',
										value: data.auto_check_sheet,
										hideWhenEmpty: true,
									},
								]"
							/>
						</NCard>
					</div>
				</template>

				<template #step-details="{ data, updateField }">
					<div class="space-y-4">
						<NCombobox
							:model-value="selectedDepot"
							label="Depot or workshop"
							placeholder="Depot name"
							required
							:min-chars="1"
							:search="searchDepots"
							:display="(d) => d.depot_name"
							:describe="(d) => d.city || ''"
							@update:model-value="(d) => selectDepot(d, updateField)"
						/>

						<NTextarea
							:model-value="data.complaint_description"
							label="What did the driver or customer report?"
							placeholder="List every issue they mentioned."
							required
							:rows="3"
							@update:model-value="(v) => updateField('complaint_description', v)"
						/>

						<NTextarea
							:model-value="data.se_observations"
							label="What did you find?"
							placeholder="Anything you saw during the initial inspection."
							hint="Optional — the technician will add to this."
							:rows="3"
							@update:model-value="(v) => updateField('se_observations', v)"
						/>
					</div>
				</template>

				<template #step-review="{ data }">
					<div class="space-y-4">
						<NCard>
							<NKeyValue
								:items="[
									{ label: 'Type', value: data.job_card_type },
									{ label: 'Model', value: data.vehicle_make_model },
									{ label: 'Odometer', value: fmt.distance(data.odometer_reading) },
									{ label: 'Customer', value: data.customer_name },
									{ label: 'Depot', value: data.depot },
								]"
							>
								<div class="flex items-baseline justify-between gap-6 py-2">
									<dt class="text-body-sm text-muted">Vehicle</dt>
									<dd><NVehicle :value="data.vehicle_number" /></dd>
								</div>
								<div class="flex items-center justify-between gap-6 py-2">
									<dt class="text-body-sm text-muted">Priority</dt>
									<dd><NPriority :value="data.priority" /></dd>
								</div>
							</NKeyValue>
						</NCard>

						<NCard v-if="data.complaint_description" title="Reported by the driver">
							<p class="whitespace-pre-line text-body text-ink">
								{{ data.complaint_description }}
							</p>
						</NCard>

						<NCard v-if="data.se_observations" title="Your observations">
							<p class="whitespace-pre-line text-body text-ink">{{ data.se_observations }}</p>
						</NCard>
					</div>
				</template>
			</Wizard>

			<NAlert
				v-if="submitError"
				semantic="critical"
				title="Could not create the job card"
				:body="submitError"
				class="mx-auto mt-4 max-w-form"
			/>
		</div>
	</div>
</template>

<script setup>
import { ref } from "vue";
import { useRouter } from "vue-router";
import { call } from "frappe-ui";
import Wizard from "../components/Wizard.vue";
import {
	NPageHeader,
	NChoice,
	NCombobox,
	NInput,
	NTextarea,
	NCard,
	NKeyValue,
	NVehicle,
	NPriority,
	NAlert,
	fmt,
} from "../ui/index.js";

const router = useRouter();
const submitError = ref("");

const jobCardTypes = [
	{
		value: "PMS + Repair",
		label: "PMS + repair",
		icon: "wrench",
		description: "Scheduled maintenance plus any repairs found",
	},
	{
		value: "Only Repair",
		label: "Only repair",
		icon: "settings",
		description: "A specific repair; the bus still runs",
	},
	{
		value: "Software Update",
		label: "Software update",
		icon: "upload",
		description: "Firmware or software only",
	},
	{
		value: "Breakdown",
		label: "Breakdown",
		icon: "alert-triangle",
		description: "The bus is off the road",
	},
];

const formData = ref({
	job_card_type: "",
	vehicle: "",
	vehicle_number: "",
	vehicle_make_model: "",
	odometer_reading: null,
	customer: "",
	customer_name: "",
	depot: "",
	priority: "Medium",
	service_type: "",
	complaint_description: "",
	se_observations: "",
	auto_check_sheet: "",
});

const selectedVehicle = ref(null);
const selectedDepot = ref(null);

async function searchVehicles(txt) {
	const res = await call("vehicle_maintenance.api.job_card.search_vehicles", { txt, limit: 10 });
	return res?.data || [];
}

async function searchDepots(txt) {
	const res = await call("vehicle_maintenance.api.job_card.search_depots", { txt, limit: 10 });
	return res?.data || [];
}

function selectVehicle(v, updateField) {
	selectedVehicle.value = v;
	updateField("vehicle", v?.name || "");
	updateField("vehicle_number", v?.registration_number || "");
	updateField("vehicle_make_model", v?.make_model || "");
	updateField("customer", v?.customer || "");
	updateField("customer_name", v?.customer || "");
	if (v?.customer) {
		call("vehicle_maintenance.api.job_card.get_customer_name", { customer: v.customer }).then((r) => {
			if (r?.data?.customer_name) updateField("customer_name", r.data.customer_name);
		});
	}
}

function selectDepot(d, updateField) {
	selectedDepot.value = d;
	updateField("depot", d?.name || "");
}

/* Choosing the kind of work fills in the service type and priority. A breakdown
   is urgent by definition and nobody should have to say so twice. */
function selectJobType(type, updateField) {
	updateField("job_card_type", type);
	const map = {
		"PMS + Repair": { service_type: "Scheduled Maintenance", priority: "Medium" },
		"Only Repair": { service_type: "General Inspection", priority: "Medium" },
		"Software Update": { service_type: "Software Update", priority: "Low" },
		Breakdown: { service_type: "Breakdown Repair", priority: "Urgent" },
	};
	const cfg = map[type];
	if (cfg) {
		updateField("service_type", cfg.service_type);
		updateField("priority", cfg.priority);
	}
}

const steps = [
	{
		id: "type",
		title: "What kind of job card?",
		description: "This sets the checklist and the default priority.",
		validate: (d) => (d.job_card_type ? [] : ["Choose a job card type."]),
	},
	{
		id: "vehicle",
		title: "Which bus?",
		description: "Search for it, then record the odometer.",
		validate: (d) => {
			const errors = [];
			if (!d.vehicle) errors.push("Search for and select a vehicle.");
			if (!d.odometer_reading || d.odometer_reading <= 0) errors.push("Enter the odometer reading.");
			return errors;
		},
	},
	{
		id: "details",
		title: "Where, and what is wrong?",
		description: "The depot doing the work, and what was reported.",
		validate: (d) => {
			const errors = [];
			if (!d.depot) errors.push("Select a depot.");
			if (!d.complaint_description?.trim()) errors.push("Record what the driver or customer reported.");
			return errors;
		},
	},
	{ id: "review", title: "Check it over", description: "Nothing is saved until you create the card." },
];

async function handleSubmit(data) {
	submitError.value = "";
	try {
		const result = await call("frappe.client.insert", {
			doc: {
				doctype: "Job Card",
				job_card_type: data.job_card_type,
				vehicle: data.vehicle,
				odometer_reading: data.odometer_reading,
				customer: data.customer,
				service_type: data.service_type,
				priority: data.priority,
				depot: data.depot,
				complaint_description: data.complaint_description,
				se_observations: data.se_observations,
			},
		});
		router.push(`/service-portal/job-card/${result.name}`);
	} catch (e) {
		submitError.value = e?.messages?.[0] || e?.message || "Please try again.";
	}
}
</script>
