<template>
	<div class="bg-white border border-gray-200 rounded-xl p-4">
		<div class="text-xs text-gray-500">{{ label }}</div>
		<div class="text-sm font-medium text-gray-900 mt-1">
			{{ value || "—" }}
		</div>
	</div>
</template>

<script setup>
defineProps({
	label: { type: String, required: true },
	value: { type: [String, Number], default: "" },
});
</script>
