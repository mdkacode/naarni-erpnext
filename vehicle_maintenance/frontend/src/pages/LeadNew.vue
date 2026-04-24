<template>
	<div class="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
		<div class="mb-6">
			<router-link to="/service-portal/crm/leads" class="text-sm text-brand-600 hover:underline"
				>&larr; Back to leads</router-link
			>
			<h1 class="text-2xl font-bold text-gray-900 mt-2">New Lead</h1>
			<p class="text-sm text-gray-500 mt-1">
				Capture the essentials now — you can log calls and visits from the detail page.
			</p>
		</div>

		<Wizard :steps="steps" v-model="formData" submit-label="Create Lead" @complete="handleSubmit">
			<!-- ───────────────────── Step 1: Who ───────────────────── -->
			<template #step-who="{ data, updateField }">
				<div class="space-y-4">
					<div>
						<label class="label">Lead Name *</label>
						<input
							:value="data.lead_name"
							@input="updateField('lead_name', $event.target.value)"
							type="text"
							class="input-field"
							placeholder="e.g. Ravi Kumar"
						/>
					</div>
					<div>
						<label class="label">Phone *</label>
						<input
							:value="data.phone"
							@input="updateField('phone', $event.target.value)"
							type="tel"
							inputmode="numeric"
							class="input-field"
							placeholder="10-digit mobile"
						/>
					</div>
					<div>
						<label class="label">Email</label>
						<input
							:value="data.email"
							@input="updateField('email', $event.target.value)"
							type="email"
							class="input-field"
							placeholder="optional"
						/>
					</div>
					<div>
						<label class="label">Company</label>
						<input
							:value="data.company_name"
							@input="updateField('company_name', $event.target.value)"
							type="text"
							class="input-field"
							placeholder="optional"
						/>
					</div>
				</div>
			</template>

			<!-- ───────────────────── Step 2: What ───────────────────── -->
			<template #step-what="{ data, updateField }">
				<div class="space-y-4">
					<div>
						<label class="label">Industry</label>
						<select
							:value="data.industry"
							@change="updateField('industry', $event.target.value)"
							class="select-field"
						>
							<option value="">— Select —</option>
							<option v-for="i in INDUSTRIES" :key="i">{{ i }}</option>
						</select>
					</div>
					<div>
						<label class="label">Interested In</label>
						<select
							:value="data.interested_in"
							@change="updateField('interested_in', $event.target.value)"
							class="select-field"
						>
							<option value="">— Select —</option>
							<option v-for="i in INTERESTS" :key="i">{{ i }}</option>
						</select>
					</div>
					<div>
						<label class="label">Fleet Size</label>
						<div class="grid grid-cols-5 gap-2">
							<button
								type="button"
								v-for="bucket in FLEET_BUCKETS"
								:key="bucket"
								@click="updateField('fleet_size_bucket', bucket)"
								class="px-3 py-2 rounded-lg border text-sm transition"
								:class="
									data.fleet_size_bucket === bucket
										? 'border-brand-500 bg-brand-50 text-brand-700 font-semibold'
										: 'border-gray-200 hover:border-gray-300'
								"
							>
								{{ bucket }}
							</button>
						</div>
					</div>
					<div>
						<label class="label">Estimated Value (₹)</label>
						<input
							:value="data.estimated_value"
							@input="updateField('estimated_value', $event.target.value)"
							type="number"
							class="input-field"
							placeholder="optional"
							min="0"
						/>
					</div>
				</div>
			</template>

			<!-- ───────────────────── Step 3: Where ───────────────────── -->
			<template #step-where="{ data, updateField }">
				<div class="space-y-4">
					<div>
						<label class="label">State</label>
						<select
							:value="data.state"
							@change="updateField('state', $event.target.value)"
							class="select-field"
						>
							<option value="">— Select —</option>
							<option v-for="s in STATES" :key="s">{{ s }}</option>
						</select>
					</div>
					<div>
						<label class="label">City</label>
						<input
							:value="data.city"
							@input="updateField('city', $event.target.value)"
							type="text"
							class="input-field"
							placeholder="City"
						/>
					</div>
					<div>
						<label class="label">Depot</label>
						<select
							:value="data.depot"
							@change="updateField('depot', $event.target.value)"
							class="select-field"
						>
							<option value="">— Select —</option>
							<option v-for="d in dropdowns.depots" :key="d.name" :value="d.name">
								{{ d.depot_name || d.name }}
							</option>
						</select>
					</div>
					<div>
						<label class="label">Address</label>
						<textarea
							:value="data.address_line"
							@input="updateField('address_line', $event.target.value)"
							rows="2"
							class="input-field"
							placeholder="optional"
						/>
					</div>
				</div>
			</template>

			<!-- ───────────────────── Step 4: Assign ───────────────────── -->
			<template #step-assign="{ data, updateField }">
				<div class="space-y-4">
					<div>
						<label class="label">Source</label>
						<select
							:value="data.lead_source"
							@change="updateField('lead_source', $event.target.value)"
							class="select-field"
						>
							<option value="">— Select —</option>
							<option v-for="s in dropdowns.sources" :key="s.name" :value="s.name">
								{{ s.source_name }}
							</option>
						</select>
					</div>
					<div>
						<label class="label">Assigned To</label>
						<select
							:value="data.assigned_to"
							@change="updateField('assigned_to', $event.target.value)"
							class="select-field"
						>
							<option value="">— Select —</option>
							<option v-for="u in dropdowns.sales_users" :key="u.name" :value="u.name">
								{{ u.full_name || u.name }}
							</option>
						</select>
					</div>
					<div>
						<label class="label">Priority</label>
						<div class="grid grid-cols-3 gap-2">
							<button
								type="button"
								v-for="p in ['Low', 'Medium', 'High']"
								:key="p"
								@click="updateField('priority', p)"
								class="px-3 py-2 rounded-lg border text-sm transition"
								:class="
									data.priority === p
										? 'border-brand-500 bg-brand-50 text-brand-700 font-semibold'
										: 'border-gray-200 hover:border-gray-300'
								"
							>
								{{ p }}
							</button>
						</div>
					</div>
					<div>
						<label class="label">Expected Close Date</label>
						<input
							:value="data.expected_close_date"
							@input="updateField('expected_close_date', $event.target.value)"
							type="date"
							class="input-field"
						/>
					</div>
					<div>
						<label class="label">Notes</label>
						<textarea
							:value="data.notes"
							@input="updateField('notes', $event.target.value)"
							rows="3"
							class="input-field"
							placeholder="Context for next steps"
						/>
					</div>
				</div>
			</template>
		</Wizard>

		<div
			v-if="errorMessage"
			class="mt-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700"
		>
			{{ errorMessage }}
		</div>
	</div>
</template>

<script setup>
import { ref, reactive, onMounted } from "vue";
import { useRouter } from "vue-router";
import Wizard from "../components/Wizard.vue";
import { useLeads } from "../composables/useLeads.js";
import { useCrmDropdowns } from "../composables/useCrmDropdowns.js";

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
		description: "Name and primary contact.",
		validate: (d) => {
			const errs = [];
			if (!d.lead_name?.trim()) errs.push("Lead name is required.");
			const digits = (d.phone || "").replace(/\D+/g, "");
			if (digits.length < 10) errs.push("Phone must contain at least 10 digits.");
			if (d.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(d.email))
				errs.push("Enter a valid email or leave it blank.");
			return errs;
		},
	},
	{
		id: "what",
		title: "What do they need?",
		description: "Qualification snapshot.",
		validate: () => [],
	},
	{
		id: "where",
		title: "Where are they?",
		description: "Location and depot.",
		validate: () => [],
	},
	{
		id: "assign",
		title: "Who owns it?",
		description: "Source, owner, priority.",
		validate: (d) => {
			const errs = [];
			if (!d.priority) errs.push("Pick a priority.");
			return errs;
		},
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
		errorMessage.value = e?.message || "Failed to create lead.";
	}
}

onMounted(() => loadDropdowns());
</script>

<style scoped>
.label {
	@apply block text-sm font-medium text-gray-700 mb-1;
}
.input-field {
	@apply w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:border-brand-500 focus:ring-1 focus:ring-brand-500 outline-none;
}
.select-field {
	@apply w-full px-3 py-2 text-sm border border-gray-200 rounded-lg bg-white focus:border-brand-500 focus:ring-1 focus:ring-brand-500 outline-none;
}
</style>
