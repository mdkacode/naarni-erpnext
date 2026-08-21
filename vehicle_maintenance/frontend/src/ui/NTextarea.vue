<!-- NTextarea — free text, for the one place per form where it is genuinely free. -->
<template>
	<NField
		v-slot="{ id, describedBy, invalid }"
		:label="label"
		:hint="hint"
		:error="error"
		:required="required"
	>
		<textarea
			:id="id"
			:value="modelValue"
			:rows="rows"
			:placeholder="placeholder"
			:maxlength="maxlength || undefined"
			:disabled="disabled"
			:required="required"
			:aria-describedby="describedBy"
			:aria-invalid="invalid || undefined"
			class="w-full resize-y rounded-sm border bg-sunken px-2.5 py-2 text-body text-ink transition-colors duration-instant placeholder:text-subtle focus:bg-raised disabled:cursor-not-allowed disabled:opacity-55"
			:class="invalid ? 'border-critical' : 'border-line'"
			@input="$emit('update:modelValue', $event.target.value)"
		/>
	</NField>
</template>

<script setup>
import NField from "./NField.vue";

defineProps({
	modelValue: { type: String, default: "" },
	label: { type: String, default: "" },
	placeholder: { type: String, default: "" },
	hint: { type: String, default: "" },
	error: { type: String, default: "" },
	rows: { type: [String, Number], default: 3 },
	maxlength: { type: [String, Number], default: "" },
	required: { type: Boolean, default: false },
	disabled: { type: Boolean, default: false },
});
defineEmits(["update:modelValue"]);
</script>
