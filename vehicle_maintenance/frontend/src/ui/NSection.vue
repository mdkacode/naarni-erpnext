<!-- NSection — a titled band of a page. The heading sits *below* the content in
     the hierarchy (small, uppercase, muted) so it labels without competing. -->
<template>
	<section class="space-y-3">
		<div v-if="title || $slots.actions" class="flex items-center justify-between gap-3">
			<h2 class="text-label-sm uppercase tracking-wide text-muted">{{ title }}</h2>
			<div v-if="$slots.actions" class="flex items-center gap-2"><slot name="actions" /></div>
		</div>
		<slot />
	</section>
</template>

<script setup>
defineProps({ title: { type: String, default: "" } });
</script>
