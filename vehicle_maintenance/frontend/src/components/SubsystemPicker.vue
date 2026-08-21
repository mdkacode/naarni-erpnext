<!--
  SubsystemPicker — which parts of the bus this job touches.

  Thin wrapper over the kit's NMultiSelect: the only thing specific to
  subsystems is where the options come from and how they group.
-->
<template>
	<div>
		<NMultiSelect
			:model-value="modelValue"
			:options="options"
			:loading="loading"
			:disabled="disabled"
			placeholder="Add subsystems"
			search-placeholder="Search subsystems"
			empty-text="No subsystems configured yet."
			@update:model-value="$emit('update:modelValue', $event)"
		/>

		<NAlert
			v-if="!disabled && !loading && !options.length"
			semantic="caution"
			class="mt-2"
			body="No subsystems are configured. An admin adds them under Setup › Fleet Service › Subsystem."
		/>
	</div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { call } from "frappe-ui";
import { NMultiSelect, NAlert } from "../ui/index.js";

defineProps({
	modelValue: { type: Array, default: () => [] },
	disabled: { type: Boolean, default: false },
});
defineEmits(["update:modelValue"]);

const options = ref([]);
const loading = ref(false);

onMounted(async () => {
	loading.value = true;
	try {
		const res = await call("vehicle_maintenance.api.job_card.list_subsystems");
		options.value = (res?.data || []).map((o) => ({
			value: o.name,
			label: o.subsystem_name,
			group: o.category || "Other",
		}));
	} catch {
		options.value = [];
	} finally {
		loading.value = false;
	}
});
</script>
