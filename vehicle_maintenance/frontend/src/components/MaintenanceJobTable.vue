<!--
  Maintenance jobs editor — oil, coolant, grease, filters.

  No cost fields: the volume and the evidence are the record here. The two photo
  slots are always present and their **labels shift with the action**, so a
  technician is never left deciding what picture belongs in an unlabelled box.
-->
<template>
	<div class="space-y-3">
		<div class="flex items-center justify-between">
			<span class="text-label-sm uppercase tracking-wide text-muted"
				>{{ rows.length }} item{{ rows.length === 1 ? "" : "s" }}</span
			>
			<NButton variant="tonal" size="sm" icon="plus" @click="addRow">Add item</NButton>
		</div>

		<NEmptyState
			v-if="!rows.length"
			icon="fuel"
			title="Nothing recorded yet"
			body="Add an item for each oil, coolant, grease or filter job."
		>
			<template #action><NButton icon="plus" @click="addRow">Add item</NButton></template>
		</NEmptyState>

		<NCard v-for="(row, idx) in rows" :key="idx">
			<template #header>
				<span class="text-label-sm uppercase tracking-wide text-muted">Item {{ idx + 1 }}</span>
			</template>
			<template #actions>
				<NIconButton
					icon="trash-2"
					label="Remove this item"
					variant="danger"
					size="sm"
					@click="removeRow(idx)"
				/>
			</template>

			<div class="space-y-4">
				<NSelect
					:model-value="row.maintenance_type"
					label="Type"
					placeholder="Choose a type"
					:options="MAINTENANCE_TYPES"
					@update:model-value="(v) => updateRow(idx, 'maintenance_type', v)"
				/>

				<NChoice
					:model-value="row.action"
					label="Action"
					:options="availableActions(row)"
					:columns="3"
					@update:model-value="(v) => updateRow(idx, 'action', v)"
				/>

				<div class="grid gap-3 sm:grid-cols-2">
					<NInput
						:model-value="row.description"
						label="Item"
						placeholder="15W-40 engine oil"
						@update:model-value="(v) => updateRow(idx, 'description', v)"
					/>
					<div class="flex items-end gap-2">
						<NInput
							class="flex-1"
							:model-value="row.qty"
							:label="`Quantity${row.unit ? ` (${row.unit})` : ''}`"
							type="number"
							@update:model-value="(v) => updateRow(idx, 'qty', Number(v) || 0)"
						/>
						<NSelect
							class="w-28"
							:model-value="row.unit"
							label="Unit"
							:options="UNITS"
							@update:model-value="(v) => updateRow(idx, 'unit', v)"
						/>
					</div>
				</div>

				<div class="grid gap-4 sm:grid-cols-2">
					<NFileField
						:model-value="row.pre_photo"
						:label="prePhotoLabel(row)"
						@update:model-value="(f) => updateRow(idx, 'pre_photo', f)"
					/>
					<NFileField
						:model-value="row.post_photo"
						:label="postPhotoLabel(row)"
						@update:model-value="(f) => updateRow(idx, 'post_photo', f)"
					/>
				</div>
			</div>
		</NCard>
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
	NEmptyState,
} from "../ui/index.js";

const props = defineProps({ modelValue: { type: Array, default: () => [] } });
const emit = defineEmits(["update:modelValue"]);

const MAINTENANCE_TYPES = ["Oil/Lubricant", "Coolant", "Grease", "Filter", "Consumable"];
const UNITS = ["Litres", "Kg", "Pcs", "Set"];

const rows = computed(() => props.modelValue || []);

function availableActions(row) {
	return row.maintenance_type === "Filter"
		? ["Top-up", "Cleaning", "Replacement"]
		: ["Top-up", "Replacement"];
}

function prePhotoLabel(row) {
	if (row.action === "Cleaning") return "Dirty, before cleaning";
	if (row.action === "Replacement")
		return row.maintenance_type === "Filter" ? "Old filter" : "Drained fluid";
	return "Before top-up";
}

function postPhotoLabel(row) {
	if (row.action === "Cleaning") return "Cleaned";
	if (row.action === "Replacement")
		return row.maintenance_type === "Filter" ? "New filter installed" : "New fluid, showing volume";
	return "After top-up";
}

function emitChange(next) {
	emit("update:modelValue", next);
}

function addRow() {
	emitChange([
		...rows.value,
		{
			maintenance_type: "",
			action: "Replacement",
			description: "",
			qty: 1,
			unit: "Litres",
			pre_photo: null,
			post_photo: null,
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
