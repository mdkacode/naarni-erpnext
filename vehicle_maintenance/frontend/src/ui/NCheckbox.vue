<!--
  NCheckbox — a real input, styled, with the whole label as its hit area.

  The description sits under the label inside the same `<label>`, so tapping
  anywhere in the block toggles it. On a handset that is the difference between
  a 16px target and a 300px one.
-->
<template>
	<label
		class="flex cursor-pointer items-start gap-2.5"
		:class="disabled && 'cursor-not-allowed opacity-55'"
	>
		<input
			type="checkbox"
			:checked="modelValue"
			:disabled="disabled"
			class="mt-0.5 h-4 w-4 shrink-0 rounded-xs border-line bg-sunken text-accent focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
			@change="$emit('update:modelValue', $event.target.checked)"
		/>
		<span class="min-w-0">
			<span class="block text-body text-ink">{{ label }}</span>
			<span v-if="description" class="mt-0.5 block text-caption text-muted">{{ description }}</span>
		</span>
	</label>
</template>

<script setup>
defineProps({
	modelValue: { type: Boolean, default: false },
	label: { type: String, required: true },
	description: { type: String, default: "" },
	disabled: { type: Boolean, default: false },
});
defineEmits(["update:modelValue"]);
</script>
