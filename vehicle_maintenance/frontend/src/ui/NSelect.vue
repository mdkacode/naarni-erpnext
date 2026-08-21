<!--
  NSelect — a value from a known set.

  The product rule is that anything with a known set of answers is a select, not
  a text box: the people using this are not typists, and a free-text depot name
  becomes four spellings of the same depot by Thursday.
-->
<template>
	<NField
		v-slot="{ id, describedBy, invalid }"
		:label="label"
		:hint="hint"
		:error="error"
		:required="required"
	>
		<div class="relative">
			<select
				:id="id"
				:value="modelValue"
				:disabled="disabled"
				:required="required"
				:aria-describedby="describedBy"
				:aria-invalid="invalid || undefined"
				class="h-control w-full appearance-none rounded-sm border bg-sunken pl-2.5 pr-8 text-body text-ink transition-colors duration-instant focus:bg-raised disabled:cursor-not-allowed disabled:opacity-55"
				:class="invalid ? 'border-critical' : 'border-line'"
				@change="$emit('update:modelValue', $event.target.value)"
			>
				<option v-if="placeholder" value="" disabled>{{ placeholder }}</option>
				<option v-for="opt in normalised" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
			</select>
			<span class="pointer-events-none absolute right-2.5 top-1/2 -translate-y-1/2 text-subtle">
				<NIcon name="chevron-down" :size="15" />
			</span>
		</div>
	</NField>
</template>

<script setup>
import { computed } from "vue";
import NField from "./NField.vue";
import NIcon from "./NIcon.vue";

const props = defineProps({
	modelValue: { type: [String, Number], default: "" },
	/** `['Open','Closed']` or `[{ label, value }]` */
	options: { type: Array, default: () => [] },
	label: { type: String, default: "" },
	placeholder: { type: String, default: "" },
	hint: { type: String, default: "" },
	error: { type: String, default: "" },
	required: { type: Boolean, default: false },
	disabled: { type: Boolean, default: false },
});
defineEmits(["update:modelValue"]);

const normalised = computed(() =>
	props.options.map((o) => (typeof o === "object" ? o : { label: o, value: o }))
);
</script>
