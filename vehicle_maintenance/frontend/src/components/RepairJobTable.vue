<!--
  Repair jobs editor.

  One card per repair. The line total is computed and read-only, and the
  approval warning fires per line as soon as it crosses the threshold — the
  technician should learn that a customer has to approve this *while choosing
  the part*, not when the card refuses to move states an hour later.
-->
<template>
	<div class="space-y-3">
		<div class="flex items-center justify-between">
			<span class="text-label-sm uppercase tracking-wide text-muted"
				>{{ rows.length }} repair{{ rows.length === 1 ? "" : "s" }}</span
			>
			<NButton variant="tonal" size="sm" icon="plus" @click="addRow">Add repair</NButton>
		</div>

		<NEmptyState
			v-if="!rows.length"
			icon="wrench"
			title="No repairs yet"
			body="Add one for each thing being fixed on this card."
		>
			<template #action><NButton icon="plus" @click="addRow">Add repair</NButton></template>
		</NEmptyState>

		<NCard v-for="(row, idx) in rows" :key="idx">
			<template #header>
				<span class="text-label-sm uppercase tracking-wide text-muted">Repair {{ idx + 1 }}</span>
			</template>
			<template #actions>
				<NIconButton
					icon="trash-2"
					label="Remove this repair"
					variant="danger"
					size="sm"
					@click="removeRow(idx)"
				/>
			</template>

			<div class="space-y-4">
				<NSelect
					:model-value="row.part_group"
					label="Part group"
					placeholder="Choose a part group"
					:options="partGroupOptions"
					@update:model-value="(v) => updateRow(idx, 'part_group', v)"
				/>

				<NChoice
					:model-value="row.activity_type"
					label="What kind of work?"
					:options="ACTIVITY_TYPES"
					:columns="3"
					@update:model-value="(v) => updateRow(idx, 'activity_type', v)"
				/>

				<NInput
					:model-value="row.description"
					label="What is being done?"
					placeholder="Replace worn brake pads"
					@update:model-value="(v) => updateRow(idx, 'description', v)"
				/>

				<div class="grid grid-cols-3 gap-3">
					<NInput
						:model-value="row.qty"
						label="Quantity"
						type="number"
						@update:model-value="(v) => updateRow(idx, 'qty', Number(v) || 0)"
					/>
					<NInput
						:model-value="row.rate"
						label="Rate"
						type="number"
						prefix="₹"
						@update:model-value="(v) => updateRow(idx, 'rate', Number(v) || 0)"
					/>
					<div class="flex flex-col gap-1.5">
						<span class="text-label text-ink">Line total</span>
						<div
							class="tabular flex h-control items-center rounded-sm border border-hairline bg-sunken px-2.5 text-body text-muted"
						>
							{{ fmt.money(lineTotal(row)) }}
						</div>
					</div>
				</div>

				<NAlert
					v-if="needsApproval(row)"
					semantic="caution"
					title="Customer approval needed"
					:body="`This line is over ${fmt.money(
						APPROVAL_THRESHOLD
					)}, so parts cannot be allocated until the customer approves it.`"
				/>

				<details class="border-t border-hairline pt-3">
					<summary class="cursor-pointer select-none text-body-sm text-muted hover:text-ink">
						Evidence photos
					</summary>
					<div class="mt-3 grid gap-4 sm:grid-cols-2">
						<NFileField
							:model-value="row.pre_repair_photo"
							label="Before the repair"
							@update:model-value="(f) => updateRow(idx, 'pre_repair_photo', f)"
						/>
						<NFileField
							:model-value="row.post_repair_photo"
							label="After the repair"
							@update:model-value="(f) => updateRow(idx, 'post_repair_photo', f)"
						/>
					</div>
				</details>
			</div>
		</NCard>

		<div v-if="rows.length" class="flex items-center justify-end gap-3 border-t border-hairline pt-3">
			<span class="text-body-sm text-muted">Total estimate</span>
			<span class="tabular text-title text-ink">{{ fmt.money(totalEstimate) }}</span>
			<NBadge v-if="approvalNeeded" semantic="caution" label="Needs customer approval" />
		</div>
	</div>
</template>

<script setup>
import { computed } from "vue";
import {
	NCard,
	NSelect,
	NInput,
	NChoice,
	NFileField,
	NButton,
	NIconButton,
	NAlert,
	NBadge,
	NEmptyState,
	fmt,
} from "../ui/index.js";

const props = defineProps({
	modelValue: { type: Array, default: () => [] },
	partGroups: { type: Array, default: () => [] },
});
const emit = defineEmits(["update:modelValue"]);

const ACTIVITY_TYPES = [
	{ value: "Only Repair", label: "Labour only" },
	{ value: "Spare Replacement", label: "Part replaced" },
	{ value: "Both", label: "Both" },
];

/** Above this, a customer has to say yes before parts move. */
const APPROVAL_THRESHOLD = 1000;

const rows = computed(() => props.modelValue || []);

const partGroupOptions = computed(() =>
	props.partGroups.map((pg) => ({
		value: pg.name,
		label: pg.bus_system
			? `${pg.part_group_name || pg.name} (${pg.bus_system})`
			: pg.part_group_name || pg.name,
	}))
);

function lineTotal(row) {
	return (Number(row.qty) || 0) * (Number(row.rate) || 0);
}

const totalEstimate = computed(() => rows.value.reduce((sum, r) => sum + lineTotal(r), 0));
const approvalNeeded = computed(() => rows.value.some(needsApproval));

function needsApproval(row) {
	if (row.activity_type !== "Spare Replacement" && row.activity_type !== "Both") return false;
	return lineTotal(row) > APPROVAL_THRESHOLD;
}

function emitChange(next) {
	emit("update:modelValue", next);
}

function addRow() {
	emitChange([
		...rows.value,
		{
			part_group: "",
			activity_type: "Only Repair",
			description: "",
			qty: 1,
			rate: 0,
			pre_repair_photo: null,
			post_repair_photo: null,
		},
	]);
}

function removeRow(idx) {
	emitChange(rows.value.filter((_, i) => i !== idx));
}

function updateRow(idx, field, value) {
	emitChange(rows.value.map((r, i) => (i === idx ? { ...r, [field]: value } : r)));
}
</script>
