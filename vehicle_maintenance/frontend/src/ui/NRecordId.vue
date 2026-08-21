<!-- NRecordId — a document name (JC-2026-00412). Monospaced, muted, and a link
     when there is somewhere to go. It is an identifier, not a headline. -->
<template>
	<component
		:is="to ? 'router-link' : 'span'"
		v-bind="to ? { to } : {}"
		class="whitespace-nowrap font-mono tabular text-label-sm"
		:class="to ? 'text-accent hover:underline' : 'text-muted'"
	>
		{{ value || "—" }}
	</component>
</template>

<script setup>
defineProps({
	value: { type: String, default: "" },
	to: { type: [String, Object], default: null },
});
</script>
