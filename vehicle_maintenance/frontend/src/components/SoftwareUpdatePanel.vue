<!--
  Software update — one card per ECU or component being flashed.

  Status colour comes from the shared ramp, so "Failed" here is the same red as
  a breached SLA and a failed inspection. It previously had its own four-colour
  map with an indigo that appeared nowhere else in the product.

  Calibration values stay behind a disclosure and are omitted entirely from the
  customer's copy — they are internal SOP data, not a service record.
-->
<template>
	<div class="space-y-3">
		<div class="flex items-center justify-between">
			<span class="text-label-sm uppercase tracking-wide text-muted">
				{{ rows.length }} component{{ rows.length === 1 ? "" : "s" }}
			</span>
			<NButton v-if="!disabled" variant="tonal" size="sm" icon="plus" @click="addRow"
				>Add component</NButton
			>
		</div>

		<NEmptyState
			v-if="!rows.length"
			icon="upload"
			title="No components yet"
			:body="
				disabled ? 'Nothing has been flashed on this card.' : 'Add one for each ECU being updated.'
			"
		>
			<template v-if="!disabled" #action
				><NButton icon="plus" @click="addRow">Add component</NButton></template
			>
		</NEmptyState>

		<NCard v-for="(row, idx) in rows" :key="idx">
			<template #header>
				<div class="flex items-center gap-2">
					<span class="text-title-sm text-ink">{{ row.component || `Component ${idx + 1}` }}</span>
					<NBadge :semantic="statusSemantic(row.status)" :label="row.status || 'Pending'" />
					<span v-if="row.retry_count" class="tabular text-caption text-muted"
						>retry {{ row.retry_count }}</span
					>
				</div>
			</template>
			<template v-if="!disabled" #actions>
				<NIconButton
					icon="trash-2"
					label="Remove this component"
					variant="danger"
					size="sm"
					@click="removeRow(idx)"
				/>
			</template>

			<div class="space-y-4">
				<NInput
					:model-value="row.component"
					label="Component"
					placeholder="BMS, VCU, HVAC controller…"
					:disabled="disabled"
					@update:model-value="(v) => updateRow(idx, 'component', v)"
				/>

				<NChoice
					:model-value="row.reason"
					label="Why is it being updated?"
					:options="REASONS"
					:columns="3"
					:disabled="disabled"
					@update:model-value="(v) => updateRow(idx, 'reason', v)"
				/>

				<div class="grid gap-4 sm:grid-cols-2">
					<div class="space-y-3">
						<NInput
							:model-value="row.pre_version"
							label="Version before"
							placeholder="2.4.1"
							:disabled="disabled"
							@update:model-value="(v) => updateRow(idx, 'pre_version', v)"
						/>
						<NFileField
							v-if="!disabled"
							:model-value="row.pre_version_photo"
							label="Photo of the version before"
							icon="camera"
							@update:model-value="(f) => updateRow(idx, 'pre_version_photo', f)"
						/>
					</div>
					<div class="space-y-3">
						<NInput
							:model-value="row.post_version"
							label="Version after"
							placeholder="2.4.2"
							:disabled="disabled"
							@update:model-value="(v) => updateRow(idx, 'post_version', v)"
						/>
						<NFileField
							v-if="!disabled"
							:model-value="row.post_version_photo"
							label="Photo of the version after"
							icon="camera"
							@update:model-value="(f) => updateRow(idx, 'post_version_photo', f)"
						/>
					</div>
				</div>

				<details v-if="!customerView" class="border-t border-hairline pt-3">
					<summary class="cursor-pointer select-none text-body-sm text-muted hover:text-ink">
						Calibration — internal only
					</summary>
					<NTextarea
						class="mt-3"
						:model-value="row.calibration_values"
						:rows="2"
						placeholder="Calibration values per SOP."
						:disabled="disabled"
						@update:model-value="(v) => updateRow(idx, 'calibration_values', v)"
					/>
				</details>

				<NTextarea
					v-if="row.status === 'Failed'"
					:model-value="row.failure_notes"
					label="What failed?"
					:rows="2"
					placeholder="Log messages, error codes, what the tool said."
					:disabled="disabled"
					@update:model-value="(v) => updateRow(idx, 'failure_notes', v)"
				/>

				<div v-if="!disabled" class="flex flex-wrap gap-2 border-t border-hairline pt-3">
					<NButton
						:disabled="row.status === 'In Progress'"
						@click="updateRow(idx, 'status', 'In Progress')"
						>Start</NButton
					>
					<NButton
						variant="primary"
						:disabled="row.status === 'Success'"
						@click="updateRow(idx, 'status', 'Success')"
					>
						Mark success
					</NButton>
					<NButton
						variant="secondary"
						class="text-critical"
						:disabled="row.status === 'Failed'"
						@click="updateRow(idx, 'status', 'Failed')"
					>
						Mark failed
					</NButton>
					<NButton v-if="row.status === 'Failed'" icon="refresh-cw" @click="retry(idx)"
						>Retry</NButton
					>
				</div>
			</div>
		</NCard>
	</div>
</template>

<script setup>
import { computed } from "vue";
import {
	NCard,
	NInput,
	NTextarea,
	NChoice,
	NFileField,
	NButton,
	NIconButton,
	NBadge,
	NEmptyState,
} from "../ui/index.js";

const props = defineProps({
	modelValue: { type: Array, default: () => [] },
	disabled: { type: Boolean, default: false },
	customerView: { type: Boolean, default: false },
});
const emit = defineEmits(["update:modelValue"]);

const REASONS = [
	{ value: "Performance Improvement", label: "Performance" },
	{ value: "Regular Update", label: "Regular" },
	{ value: "Emergency Update (Bug Fix)", label: "Emergency fix" },
];

const rows = computed(() => props.modelValue || []);

function statusSemantic(status) {
	return { "In Progress": "active", Success: "positive", Failed: "critical" }[status] || "idle";
}

function emitChange(next) {
	emit("update:modelValue", next);
}

function addRow() {
	emitChange([
		...rows.value,
		{
			component: "",
			reason: "Regular Update",
			status: "Pending",
			retry_count: 0,
			pre_version: "",
			post_version: "",
			pre_version_photo: null,
			post_version_photo: null,
			calibration_values: "",
			failure_notes: "",
		},
	]);
}

function removeRow(idx) {
	emitChange(rows.value.filter((_, i) => i !== idx));
}

function updateRow(idx, field, value) {
	emitChange(rows.value.map((r, i) => (i === idx ? { ...r, [field]: value } : r)));
}

/* A retry is a new attempt, not a reset — the count is what tells a maintenance
   head that an ECU has fought back three times. */
function retry(idx) {
	emitChange(
		rows.value.map((r, i) =>
			i === idx ? { ...r, status: "In Progress", retry_count: (r.retry_count || 0) + 1 } : r
		)
	);
}
</script>
