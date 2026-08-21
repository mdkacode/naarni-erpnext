<!--
  NAlert — an inline panel that explains a state the reader must act on.

  Says what happened and what to do next. Never a raw traceback, never
  "Something went wrong" — that sentence tells a depot manager nothing they can
  use and nothing they can report.
-->
<template>
	<div
		class="flex items-start gap-2.5 rounded-md border px-3 py-2.5"
		:class="[tone.tint, borderClass]"
		:role="semantic === 'critical' ? 'alert' : 'status'"
	>
		<NIcon :name="ICONS[semantic] || 'info'" :size="16" class="mt-0.5 shrink-0" :class="tone.text" />
		<div class="min-w-0 flex-1 space-y-0.5">
			<p v-if="title" class="text-title-sm" :class="tone.text">{{ title }}</p>
			<p class="text-body-sm text-ink">
				<slot>{{ body }}</slot>
			</p>
		</div>
		<div v-if="$slots.action" class="shrink-0"><slot name="action" /></div>
	</div>
</template>

<script setup>
import { computed } from "vue";
import NIcon from "./NIcon.vue";
import { semanticClasses } from "./semantic.js";

const props = defineProps({
	semantic: { type: String, default: "caution" },
	title: { type: String, default: "" },
	body: { type: String, default: "" },
});

const ICONS = {
	positive: "circle-check",
	caution: "alert-triangle",
	critical: "circle-alert",
	active: "info",
	idle: "info",
};

const tone = computed(() => semanticClasses(props.semantic));
const borderClass = computed(() => semanticClasses(props.semantic).border);
</script>
