<!--
  Inspection → job card, the technician's flow.

  Field-facing, so the whole screen runs comfortable density: bigger controls,
  more room between them, and 48dp touch boxes. This is filled in standing next
  to a bus, often with gloves on.

  Two things changed beyond the tokens:

    · the service types were emoji (🔧 🚨 🎨 ⚡ ⭕ 🔍). ⭕ in particular is a
      *red circle* on most Android builds, so "Tyres" arrived looking like an
      alarm. They are icons now.
    · the three-tier verdict — Good / Recommended / Immediate — keeps its
      green-amber-red because the colour *is* the message here. It is the shared
      semantic ramp, so it is the same green and the same red as everywhere else.
-->
<template>
	<div class="mx-auto max-w-4xl" data-density="comfortable">
		<Wizard
			ref="wizardRef"
			v-model="formData"
			:steps="wizardSteps"
			submit-label="Submit inspection"
			@complete="handleSubmit"
			@step-change="onStepChange"
		>
			<!-- ── Vehicle ──────────────────────────────────────────────────── -->
			<template #step-vehicle="{ data, updateField }">
				<div class="space-y-4">
					<NInput
						:model-value="data.vehicle_number"
						label="Vehicle registration number"
						placeholder="KA 01 AB 1234"
						required
						@update:model-value="(v) => updateField('vehicle_number', String(v).toUpperCase())"
					/>

					<NInput
						:model-value="data.odometer_reading ?? ''"
						label="Odometer reading now"
						type="number"
						inputmode="numeric"
						placeholder="45000"
						required
						@update:model-value="
							(v) => updateField('odometer_reading', Number(v) > 0 ? Number(v) : null)
						"
					>
						<template #suffix><span class="pr-2 text-body-sm text-muted">km</span></template>
					</NInput>

					<NAlert
						v-if="data.odometer_reading > 0"
						:semantic="sheetSemantic"
						:title="`Sheet ${inspectionSheet.sheetId} — ${inspectionSheet.sheetLabel}`"
						:body="`${inspectionSheet.items.length} checks, chosen from the odometer reading.`"
					/>
				</div>
			</template>

			<!-- ── Service type ─────────────────────────────────────────────── -->
			<template #step-service="{ data, updateField }">
				<div class="space-y-5">
					<NChoice
						:model-value="data.service_type"
						label="What kind of work is this?"
						:options="SERVICE_TYPES"
						:columns="2"
						required
						@update:model-value="(v) => updateField('service_type', v)"
					/>

					<NTextarea
						:model-value="data.complaint_description"
						label="What was reported?"
						placeholder="Any specific complaints or observations."
						hint="Optional for a routine service."
						:rows="3"
						@update:model-value="(v) => updateField('complaint_description', v)"
					/>
				</div>
			</template>

			<!-- ── Inspection ───────────────────────────────────────────────── -->
			<template #step-inspection="{ data, updateField }">
				<div>
					<!-- Category nav. A completed category is marked with a tick as well
					     as a colour, so progress survives a sunlit screen. -->
					<div class="-mx-1 mb-4 flex gap-2 overflow-x-auto px-1 pb-3">
						<button
							v-for="(cat, idx) in inspectionCategories"
							:key="cat"
							type="button"
							class="inline-flex h-8 shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-3 text-label-sm transition-colors duration-instant"
							:class="categoryClass(cat, data, idx)"
							@click="activeCategory = idx"
						>
							{{ cat }}
							<NIcon v-if="categoryComplete(cat, data)" name="check" :size="13" />
						</button>
					</div>

					<div class="space-y-3">
						<NCard v-for="item in activeCategoryItems" :key="item.id">
							<p class="mb-3 text-title-sm text-ink">{{ item.label }}</p>

							<NChoice
								v-if="item.inputType === 'three_tier'"
								:model-value="data[`inspection_${item.id}`]"
								:options="VERDICTS"
								:columns="3"
								@update:model-value="(v) => updateField(`inspection_${item.id}`, v)"
							/>

							<NInput
								v-else-if="item.inputType === 'measurement'"
								:model-value="data[`inspection_${item.id}`] ?? ''"
								type="number"
								inputmode="decimal"
								:placeholder="`${item.min}–${item.max}`"
								:error="
									measurementOutOfRange(item, data[`inspection_${item.id}`])
										? `Outside the expected ${item.min}–${item.max} ${item.unit}.`
										: ''
								"
								@update:model-value="(v) => updateField(`inspection_${item.id}`, v)"
							>
								<template #suffix
									><span class="pr-2 text-body-sm text-muted">{{
										item.unit
									}}</span></template
								>
							</NInput>

							<NTextarea
								v-else-if="item.inputType === 'text'"
								:model-value="data[`inspection_${item.id}`] ?? ''"
								:rows="2"
								placeholder="Notes"
								@update:model-value="(v) => updateField(`inspection_${item.id}`, v)"
							/>
						</NCard>
					</div>

					<p class="tabular mt-4 text-center text-caption text-muted">
						Category {{ activeCategory + 1 }} of {{ inspectionCategories.length }} ·
						{{ completedCount }} of {{ inspectionSheet.items.length }} checks done
					</p>
				</div>
			</template>

			<!-- ── Findings ─────────────────────────────────────────────────── -->
			<template #step-findings="{ data, updateField }">
				<div class="space-y-5">
					<NSection
						v-if="failedItems.length"
						:title="`Needs immediate action (${failedItems.length})`"
					>
						<ul class="space-y-2">
							<li
								v-for="item in failedItems"
								:key="item.id"
								class="flex items-center gap-2.5 rounded-md border border-critical bg-critical-tint px-3 py-2.5"
							>
								<NIcon name="circle-x" :size="16" class="shrink-0 text-critical" />
								<span class="min-w-0 flex-1 text-body text-ink">{{ item.label }}</span>
								<span class="shrink-0 text-caption text-muted">{{ item.category }}</span>
							</li>
						</ul>
					</NSection>

					<NSection
						v-if="recommendedItems.length"
						:title="`Recommended (${recommendedItems.length})`"
					>
						<ul class="space-y-2">
							<li
								v-for="item in recommendedItems"
								:key="item.id"
								class="flex items-center gap-2.5 rounded-md border border-caution bg-caution-tint px-3 py-2.5"
							>
								<NIcon name="alert-triangle" :size="16" class="shrink-0 text-caution" />
								<span class="min-w-0 flex-1 text-body text-ink">{{ item.label }}</span>
								<span class="shrink-0 text-caption text-muted">{{ item.category }}</span>
							</li>
						</ul>
					</NSection>

					<NSection
						v-if="outOfRangeItems.length"
						:title="`Out of range (${outOfRangeItems.length})`"
					>
						<ul class="space-y-2">
							<li
								v-for="item in outOfRangeItems"
								:key="item.id"
								class="flex items-center justify-between gap-3 rounded-md border border-caution bg-caution-tint px-3 py-2.5"
							>
								<span class="min-w-0 text-body text-ink">{{ item.label }}</span>
								<span class="tabular shrink-0 font-mono text-body-sm text-caution">
									{{ data[`inspection_${item.id}`] }} {{ item.unit }}
									<span class="text-muted">({{ item.min }}–{{ item.max }})</span>
								</span>
							</li>
						</ul>
					</NSection>

					<NEmptyState
						v-if="!failedItems.length && !recommendedItems.length && !outOfRangeItems.length"
						icon="circle-check"
						title="Everything rated good"
						body="Nothing needs a repair from this inspection."
					/>

					<NTextarea
						:model-value="data.technician_notes"
						label="Anything else worth recording?"
						placeholder="Observations, recommendations, context for the service engineer."
						:rows="3"
						@update:model-value="(v) => updateField('technician_notes', v)"
					/>
				</div>
			</template>

			<!-- ── Review ───────────────────────────────────────────────────── -->
			<template #step-review="{ data }">
				<div class="space-y-4">
					<NCard>
						<NKeyValue
							:items="[
								{ label: 'Odometer', value: fmt.distance(data.odometer_reading) },
								{ label: 'Service type', value: fmt.or(data.service_type) },
								{ label: 'Inspection sheet', value: `Sheet ${inspectionSheet.sheetId}` },
								{
									label: 'Checks completed',
									value: `${completedCount} of ${inspectionSheet.items.length}`,
								},
							]"
						>
							<div class="flex items-baseline justify-between gap-6 py-2">
								<dt class="text-body-sm text-muted">Vehicle</dt>
								<dd><NVehicle :value="data.vehicle_number" /></dd>
							</div>
							<div class="flex items-baseline justify-between gap-6 py-2">
								<dt class="text-body-sm text-muted">Issues found</dt>
								<dd
									class="tabular text-body-sm font-semibold"
									:class="issueCount > 0 ? 'text-critical' : 'text-positive'"
								>
									{{ issueCount }}
								</dd>
							</div>
						</NKeyValue>
					</NCard>

					<NCard v-if="data.technician_notes" title="Your notes">
						<p class="whitespace-pre-line text-body text-ink">{{ data.technician_notes }}</p>
					</NCard>

					<!-- A gate, not a block: an incomplete sheet still submits. A hard stop
					     on a workshop floor does not produce the missing check, it produces
					     a guess — the technician has a bus in front of them and a queue behind. -->
					<NAlert
						v-if="completedCount < inspectionSheet.items.length"
						semantic="caution"
						:title="`${inspectionSheet.items.length - completedCount} checks are still blank`"
						body="You can submit anyway — the card will be flagged as an incomplete inspection."
					/>
				</div>
			</template>
		</Wizard>

		<NAlert
			v-if="submitError"
			semantic="critical"
			title="Could not submit the inspection"
			:body="submitError"
			class="mx-auto mt-4 max-w-form"
		/>
	</div>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { callAPI } from "../utils/api.js";
import {
	getInspectionSheet,
	groupByCategory,
	STATUS_GOOD,
	STATUS_RECOMMENDED,
	STATUS_IMMEDIATE,
} from "../composables/useInspectionSheet.js";
import Wizard from "./Wizard.vue";
import {
	NCard,
	NSection,
	NInput,
	NTextarea,
	NChoice,
	NIcon,
	NAlert,
	NKeyValue,
	NVehicle,
	NEmptyState,
	fmt,
} from "../ui/index.js";

const router = useRouter();
const wizardRef = ref(null);
const submitError = ref("");
const activeCategory = ref(0);

const SERVICE_TYPES = [
	{ value: "Scheduled Maintenance", label: "Routine service", icon: "wrench" },
	{ value: "Breakdown Repair", label: "Breakdown", icon: "alert-triangle" },
	{ value: "Body & Paint", label: "Body and paint", icon: "pencil" },
	{ value: "Electrical", label: "Electrical", icon: "battery-charging" },
	{ value: "Tyre & Alignment", label: "Tyres", icon: "route" },
	{ value: "General Inspection", label: "Inspection", icon: "search" },
];

const VERDICTS = [
	{ value: STATUS_GOOD, label: "Good", semantic: "positive" },
	{ value: STATUS_RECOMMENDED, label: "Recommended", semantic: "caution" },
	{ value: STATUS_IMMEDIATE, label: "Immediate", semantic: "critical" },
];

const formData = ref({
	vehicle_number: "",
	odometer_reading: null,
	service_type: "",
	complaint_description: "",
	technician_notes: "",
});

// The sheet is chosen from the odometer — nobody is asked which one to use.
const inspectionSheet = computed(() => getInspectionSheet(formData.value.odometer_reading));

const inspectionCategories = computed(() => [...groupByCategory(inspectionSheet.value.items).keys()]);

const activeCategoryItems = computed(() => {
	const groups = groupByCategory(inspectionSheet.value.items);
	return groups.get(inspectionCategories.value[activeCategory.value]) || [];
});

watch(
	() => inspectionSheet.value.sheetId,
	() => (activeCategory.value = 0)
);

/* Sheets escalate A → D with the odometer, so they map onto the same ramp as
   everything else: A is routine, D is the one that needs a hard look. */
const SHEET_SEMANTIC = { A: "positive", B: "caution", C: "caution", D: "critical" };
const sheetSemantic = computed(() => SHEET_SEMANTIC[inspectionSheet.value.sheetId] || "active");

const failedItems = computed(() =>
	inspectionSheet.value.items.filter(
		(item) =>
			item.inputType === "three_tier" && formData.value[`inspection_${item.id}`] === STATUS_IMMEDIATE
	)
);

const recommendedItems = computed(() =>
	inspectionSheet.value.items.filter(
		(item) =>
			item.inputType === "three_tier" && formData.value[`inspection_${item.id}`] === STATUS_RECOMMENDED
	)
);

const outOfRangeItems = computed(() =>
	inspectionSheet.value.items.filter(
		(item) =>
			item.inputType === "measurement" &&
			measurementOutOfRange(item, formData.value[`inspection_${item.id}`])
	)
);

const completedCount = computed(
	() =>
		inspectionSheet.value.items.filter((item) => {
			const v = formData.value[`inspection_${item.id}`];
			return v != null && v !== "";
		}).length
);

const issueCount = computed(() => failedItems.value.length + outOfRangeItems.value.length);

function measurementOutOfRange(item, value) {
	if (value == null || value === "") return false;
	const num = Number(value);
	return num < item.min || num > item.max;
}

function categoryComplete(catName, data) {
	const items = groupByCategory(inspectionSheet.value.items).get(catName) || [];
	return items.every((item) => {
		const v = data[`inspection_${item.id}`];
		return v != null && v !== "";
	});
}

function categoryClass(cat, data, idx) {
	if (activeCategory.value === idx) return "bg-accent text-accent-on";
	if (categoryComplete(cat, data)) return "bg-positive-tint text-positive";
	return "bg-sunken text-muted hover:text-ink";
}

const wizardSteps = computed(() => [
	{
		id: "vehicle",
		title: "Which bus?",
		description: "Registration and the odometer as it reads now.",
		validate: (data) => {
			const errors = [];
			if (!data.vehicle_number?.trim()) errors.push("Enter the registration number.");
			if (!data.odometer_reading || data.odometer_reading <= 0)
				errors.push("Enter the odometer reading.");
			return errors;
		},
	},
	{
		id: "service",
		title: "What kind of work?",
		description: "Pick the closest one.",
		validate: (data) => (data.service_type ? [] : ["Choose a service type."]),
	},
	{
		id: "inspection",
		title: `Inspection — sheet ${inspectionSheet.value.sheetId}`,
		description: `${inspectionSheet.value.items.length} checks across ${inspectionCategories.value.length} categories.`,
		// No hard validation — a partial inspection is still worth recording.
	},
	{ id: "findings", title: "What the inspection found", description: "Everything that is not rated good." },
	{ id: "review", title: "Check it over", description: "Nothing is saved until you submit." },
]);

function onStepChange(stepIdx) {
	if (wizardSteps.value[stepIdx]?.id === "inspection") activeCategory.value = 0;
}

async function handleSubmit(data) {
	submitError.value = "";
	try {
		const inspectionResults = {};
		for (const item of inspectionSheet.value.items) {
			const value = data[`inspection_${item.id}`];
			if (value != null && value !== "") {
				inspectionResults[item.id] = {
					value,
					label: item.label,
					category: item.category,
					inputType: item.inputType,
				};
			}
		}

		const result = await callAPI("job_card.create_job_card_with_inspection", {
			vehicle_number: data.vehicle_number,
			odometer_reading: data.odometer_reading,
			service_type: data.service_type,
			complaint_description: data.complaint_description || "",
			technician_notes: data.technician_notes || "",
			inspection_sheet_id: inspectionSheet.value.sheetId,
			inspection_results: JSON.stringify(inspectionResults),
		});

		if (result.success) {
			router.push(`/service-portal/job-card/${result.data.name}`);
		} else {
			submitError.value = result.message || "Please try again.";
		}
	} catch (e) {
		submitError.value =
			e?.exc_type === "ValidationError" ? e.message : e?.messages?.[0] || "Please try again.";
	}
}
</script>
