<!--
  New lead.

  Four steps — who, what, where, who owns it — each of them four or five fields.
  Every value that comes from a known set is a picker, not a text box; the only
  genuinely free text on the form is the address and the notes.
-->
<template>
	<div>
		<NPageHeader
			title="New lead"
			subtitle="Capture the essentials now — calls and visits are logged from the lead itself."
			back="/service-portal/crm/leads"
			back-label="Back to leads"
		/>

		<div class="p-5">
			<Wizard v-model="formData" :steps="steps" submit-label="Create lead" @complete="handleSubmit">
				<template #step-who="{ data, updateField }">
					<div class="space-y-4">
						<NInput
							:model-value="data.lead_name"
							label="Lead name"
							placeholder="Ravi Kumar"
							required
							@update:model-value="(v) => updateField('lead_name', v)"
						/>
						<NInput
							:model-value="data.phone"
							label="Phone"
							type="tel"
							inputmode="numeric"
							prefix="+91"
							maxlength="10"
							placeholder="10-digit mobile"
							required
							@update:model-value="
								(v) => updateField('phone', String(v).replace(/\D+/g, '').slice(0, 10))
							"
						/>
						<NInput
							:model-value="data.email"
							label="Email"
							type="email"
							hint="Optional."
							@update:model-value="(v) => updateField('email', v)"
						/>
						<NInput
							:model-value="data.company_name"
							label="Company"
							hint="Optional."
							@update:model-value="(v) => updateField('company_name', v)"
						/>
					</div>
				</template>

				<template #step-what="{ data, updateField }">
					<div class="space-y-4">
						<NSelect
							:model-value="data.industry"
							label="Industry"
							placeholder="Choose an industry"
							:options="INDUSTRIES"
							@update:model-value="(v) => updateField('industry', v)"
						/>
						<NSelect
							:model-value="data.interested_in"
							label="Interested in"
							placeholder="Choose"
							:options="INTERESTS"
							@update:model-value="(v) => updateField('interested_in', v)"
						/>
						<NChoice
							:model-value="data.fleet_size_bucket"
							label="Fleet size"
							:options="FLEET_BUCKETS"
							:columns="5"
							@update:model-value="(v) => updateField('fleet_size_bucket', v)"
						/>
						<NInput
							:model-value="data.estimated_value"
							label="Estimated value"
							type="number"
							prefix="₹"
							hint="Optional — a rough figure is fine."
							@update:model-value="(v) => updateField('estimated_value', v)"
						/>
					</div>
				</template>

				<template #step-where="{ data, updateField }">
					<div class="space-y-4">
						<NSelect
							:model-value="data.state"
							label="State"
							placeholder="Choose a state"
							:options="STATES"
							@update:model-value="(v) => updateField('state', v)"
						/>
						<NInput
							:model-value="data.city"
							label="City"
							@update:model-value="(v) => updateField('city', v)"
						/>
						<NSelect
							:model-value="data.depot"
							label="Nearest depot"
							placeholder="Choose a depot"
							:options="depotOptions"
							@update:model-value="(v) => updateField('depot', v)"
						/>
						<NTextarea
							:model-value="data.address_line"
							label="Address"
							:rows="2"
							hint="Optional."
							@update:model-value="(v) => updateField('address_line', v)"
						/>
					</div>
				</template>

				<template #step-assign="{ data, updateField }">
					<div class="space-y-4">
						<NSelect
							:model-value="data.lead_source"
							label="Where did this lead come from?"
							placeholder="Choose a source"
							:options="sourceOptions"
							@update:model-value="(v) => updateField('lead_source', v)"
						/>
						<NSelect
							:model-value="data.assigned_to"
							label="Assigned to"
							placeholder="Choose a person"
							:options="userOptions"
							@update:model-value="(v) => updateField('assigned_to', v)"
						/>
						<NChoice
							:model-value="data.priority"
							label="Priority"
							:options="['Low', 'Medium', 'High']"
							:columns="3"
							required
							@update:model-value="(v) => updateField('priority', v)"
						/>
						<NInput
							:model-value="data.expected_close_date"
							label="Expected close date"
							type="date"
							@update:model-value="(v) => updateField('expected_close_date', v)"
						/>
						<NTextarea
							:model-value="data.notes"
							label="Notes"
							:rows="3"
							placeholder="Context for the next step."
							@update:model-value="(v) => updateField('notes', v)"
						/>
					</div>
				</template>
			</Wizard>

			<NAlert
				v-if="errorMessage"
				semantic="critical"
				title="Could not create the lead"
				:body="errorMessage"
				class="mx-auto mt-4 max-w-form"
			/>
		</div>
	</div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import Wizard from "../components/Wizard.vue";
import { useLeads } from "../composables/useLeads.js";
import { useCrmDropdowns } from "../composables/useCrmDropdowns.js";
import { NPageHeader, NInput, NSelect, NTextarea, NChoice, NAlert } from "../ui/index.js";

const router = useRouter();
const leads = useLeads();
const { dropdowns, load: loadDropdowns } = useCrmDropdowns();

const INDUSTRIES = [
	"Logistics",
	"Mining",
	"Construction",
	"Municipal",
	"Agriculture",
	"Private Fleet",
	"Other",
];
const INTERESTS = ["AMC", "Spot Repair", "Full Maintenance Contract", "Parts Only", "Inspection Only"];
const FLEET_BUCKETS = ["1-5", "6-20", "21-50", "51-100", "100+"];
const STATES = [
	"Andhra Pradesh",
	"Arunachal Pradesh",
	"Assam",
	"Bihar",
	"Chhattisgarh",
	"Goa",
	"Gujarat",
	"Haryana",
	"Himachal Pradesh",
	"Jharkhand",
	"Karnataka",
	"Kerala",
	"Madhya Pradesh",
	"Maharashtra",
	"Manipur",
	"Meghalaya",
	"Mizoram",
	"Nagaland",
	"Odisha",
	"Punjab",
	"Rajasthan",
	"Sikkim",
	"Tamil Nadu",
	"Telangana",
	"Tripura",
	"Uttar Pradesh",
	"Uttarakhand",
	"West Bengal",
	"Andaman and Nicobar Islands",
	"Chandigarh",
	"Dadra and Nagar Haveli and Daman and Diu",
	"Delhi",
	"Jammu and Kashmir",
	"Ladakh",
	"Lakshadweep",
	"Puducherry",
];

const depotOptions = computed(() =>
	(dropdowns.depots || []).map((d) => ({ value: d.name, label: d.depot_name || d.name }))
);
const sourceOptions = computed(() =>
	(dropdowns.sources || []).map((s) => ({ value: s.name, label: s.source_name }))
);
const userOptions = computed(() =>
	(dropdowns.sales_users || []).map((u) => ({ value: u.name, label: u.full_name || u.name }))
);

const formData = reactive({
	lead_name: "",
	phone: "",
	email: "",
	company_name: "",
	industry: "",
	interested_in: "",
	fleet_size_bucket: "",
	estimated_value: "",
	state: "",
	city: "",
	depot: "",
	address_line: "",
	lead_source: "",
	assigned_to: "",
	priority: "Medium",
	expected_close_date: "",
	notes: "",
});
const errorMessage = ref("");

const steps = [
	{
		id: "who",
		title: "Who is this lead?",
		description: "Name and the number you will actually call.",
		validate: (d) => {
			const errs = [];
			if (!d.lead_name?.trim()) errs.push("A lead needs a name.");
			if ((d.phone || "").replace(/\D+/g, "").length < 10) errs.push("Enter a 10-digit mobile number.");
			if (d.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(d.email))
				errs.push("That email address does not look right — or leave it blank.");
			return errs;
		},
	},
	{
		id: "what",
		title: "What do they need?",
		description: "Enough to qualify them later.",
		validate: () => [],
	},
	{
		id: "where",
		title: "Where are they?",
		description: "Location and the depot that would serve them.",
		validate: () => [],
	},
	{
		id: "assign",
		title: "Who owns it?",
		description: "Source, owner and how hard to chase it.",
		validate: (d) => (d.priority ? [] : ["Pick a priority."]),
	},
];

async function handleSubmit(data) {
	errorMessage.value = "";
	try {
		const payload = { ...data };
		if (payload.estimated_value === "") delete payload.estimated_value;
		if (!payload.expected_close_date) delete payload.expected_close_date;
		const res = await leads.create(payload);
		router.push(`/service-portal/crm/leads/${encodeURIComponent(res.name)}`);
	} catch (e) {
		errorMessage.value = e?.message || "Please try again.";
	}
}

onMounted(() => loadDropdowns());
</script>
