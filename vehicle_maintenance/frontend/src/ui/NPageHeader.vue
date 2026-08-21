<!--
  NPageHeader — the top of every page, so no page invents its own.

  Anatomy: optional back link, title, optional subtitle and status, then
  right-aligned actions. There is exactly **one** primary action and it lives
  here; it never also appears as a floating button lower down the page.

  The optional toolbar row beneath carries search, filters and view controls —
  the things that change what the page shows, as opposed to the things that
  change the data.
-->
<template>
	<header class="sticky top-0 z-sticky border-b border-hairline bg-canvas">
		<div class="flex min-h-header items-center gap-3 px-5 py-2.5">
			<router-link
				v-if="back"
				:to="back"
				class="-ml-1 flex h-8 w-8 shrink-0 items-center justify-center rounded-sm text-muted transition-colors duration-instant hover:bg-sunken hover:text-ink"
				:aria-label="backLabel"
			>
				<NIcon name="arrow-left" :size="18" />
			</router-link>

			<div class="min-w-0 flex-1">
				<div class="flex items-center gap-2">
					<h1 class="truncate text-title-lg text-ink">
						<slot name="title">{{ title }}</slot>
					</h1>
					<slot name="badge" />
				</div>
				<p v-if="subtitle || $slots.subtitle" class="truncate text-body-sm text-muted">
					<slot name="subtitle">{{ subtitle }}</slot>
				</p>
			</div>

			<div v-if="$slots.actions" class="flex shrink-0 items-center gap-2">
				<slot name="actions" />
			</div>
		</div>

		<div
			v-if="$slots.toolbar"
			class="flex flex-wrap items-center gap-2 border-t border-hairline px-5 py-2"
		>
			<slot name="toolbar" />
		</div>
	</header>
</template>

<script setup>
import NIcon from "./NIcon.vue";

defineProps({
	title: { type: String, default: "" },
	subtitle: { type: String, default: "" },
	back: { type: [String, Object], default: null },
	backLabel: { type: String, default: "Back" },
});
</script>
