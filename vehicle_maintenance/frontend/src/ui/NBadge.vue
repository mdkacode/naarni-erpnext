<!--
  NBadge — the one shape a state is allowed to take.

  Colour is never the only signal (DESIGN_LANGUAGE.md §8): the pill carries a
  leading dot in the same hue *and* the word itself, so it survives a
  colour-blind reader, a monochrome print, and direct sunlight on a handset.

  Callers usually want NStatus or NPriority, which know how to turn a domain
  value into a semantic. Reach for NBadge directly only for a one-off.
-->
<template>
	<span
		class="inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-0.5 text-label-sm font-label"
		:class="[tone.tint, tone.text]"
	>
		<span v-if="dot" class="h-1.5 w-1.5 shrink-0 rounded-full" :class="tone.bg" aria-hidden="true" />
		<slot>{{ label }}</slot>
	</span>
</template>

<script setup>
import { computed } from "vue";
import { semanticClasses } from "./semantic.js";

const props = defineProps({
	semantic: { type: String, default: "idle" },
	label: { type: String, default: "" },
	dot: { type: Boolean, default: true },
});

const tone = computed(() => semanticClasses(props.semantic));
</script>
