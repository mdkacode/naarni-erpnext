<!-- NSpinner — an in-place busy indicator. For first paint use NSkeleton instead:
     a spinner tells the reader to wait, a skeleton tells them what for. -->
<template>
	<svg
		:width="px"
		:height="px"
		viewBox="0 0 24 24"
		fill="none"
		class="shrink-0 animate-spin"
		:aria-label="label || undefined"
		:role="label ? 'status' : undefined"
		:aria-hidden="label ? undefined : 'true'"
	>
		<circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="2.5" class="opacity-25" />
		<path d="M21 12a9 9 0 0 0-9-9" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" />
	</svg>
</template>

<script setup>
import { computed } from "vue";
const props = defineProps({
	size: { type: [Number, String], default: 16 },
	label: { type: String, default: "" },
});
const px = computed(() => Number(props.size));
</script>
