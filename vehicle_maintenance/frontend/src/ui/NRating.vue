<!--
  NRating — one-to-five stars.

  The stars are icons with a fill state, not the `★` character: a text star
  cannot be half-sized, sits on a different baseline in every font, and cannot
  take a hover state. Each star is a real button with its own accessible name,
  so "rate three" is reachable from the keyboard.
-->
<template>
	<div class="flex items-center gap-0.5" role="radiogroup" :aria-label="label">
		<button
			v-for="n in max"
			:key="n"
			type="button"
			role="radio"
			:aria-checked="n === modelValue"
			:aria-label="`${n} out of ${max}`"
			class="touch-target relative flex h-9 w-9 items-center justify-center rounded-sm transition-colors duration-instant focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
			:class="n <= modelValue ? 'text-caution' : 'text-subtle hover:text-caution'"
			@click="$emit('update:modelValue', n)"
		>
			<NIcon name="star" :size="24" :filled="n <= modelValue" />
		</button>
		<span v-if="modelValue" class="tabular ml-1.5 text-body-sm text-muted"
			>{{ modelValue }} / {{ max }}</span
		>
	</div>
</template>

<script setup>
import NIcon from "./NIcon.vue";

defineProps({
	modelValue: { type: Number, default: 0 },
	max: { type: Number, default: 5 },
	label: { type: String, default: "Rating" },
});
defineEmits(["update:modelValue"]);
</script>
