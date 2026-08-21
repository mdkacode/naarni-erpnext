<!--
  NEmptyState — three flavours of nothing, and they are not the same message.

    nothing-yet   → offer the action that creates the first one
    no-match      → offer to clear the filters
    not-for-you   → explain the permission, offer nothing

  The icon is neutral. Colouring it made "nothing here" the most saturated thing
  on the screen, which is exactly backwards.
-->
<template>
	<div class="flex flex-col items-center justify-center gap-3 px-6 py-12 text-center">
		<span class="flex h-14 w-14 items-center justify-center rounded-lg bg-sunken text-subtle">
			<NIcon :name="icon" :size="24" />
		</span>
		<div class="space-y-1">
			<p class="text-title-sm text-ink">{{ title }}</p>
			<p v-if="body" class="mx-auto max-w-prose text-body-sm text-muted">{{ body }}</p>
		</div>
		<div v-if="$slots.action" class="pt-1">
			<slot name="action" />
		</div>
	</div>
</template>

<script setup>
import NIcon from "./NIcon.vue";

defineProps({
	icon: { type: String, default: "list" },
	title: { type: String, required: true },
	body: { type: String, default: "" },
});
</script>
