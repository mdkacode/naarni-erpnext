<!--
  NIconButton — a square, icon-only control.

  `label` is required, not optional: an icon-only control with no accessible
  name is invisible to a screen reader and ambiguous to everyone else. It
  becomes both the `aria-label` and the native tooltip.

  The painted box is 28–32px, but the *touch* box is padded out to 44px on
  coarse pointers. In a depot the input device is a gloved thumb.
-->
<template>
	<button
		:type="type"
		:disabled="disabled"
		:aria-label="label"
		:title="label"
		class="touch-target relative inline-flex items-center justify-center rounded-sm transition-colors duration-instant focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:pointer-events-none disabled:opacity-45"
		:class="[SIZES[size], VARIANTS[variant]]"
	>
		<NSpinner v-if="loading" :size="iconSize" />
		<NIcon v-else :name="icon" :size="iconSize" />
	</button>
</template>

<script setup>
import { computed } from "vue";
import NIcon from "./NIcon.vue";
import NSpinner from "./NSpinner.vue";

const props = defineProps({
	icon: { type: String, required: true },
	label: { type: String, required: true },
	variant: { type: String, default: "ghost" }, // ghost | secondary | tonal | danger
	size: { type: String, default: "md" }, // sm | md
	type: { type: String, default: "button" },
	loading: { type: Boolean, default: false },
	disabled: { type: Boolean, default: false },
});

const VARIANTS = {
	ghost: "text-muted hover:bg-sunken hover:text-ink",
	secondary: "bg-raised text-ink border border-line hover:bg-sunken",
	tonal: "bg-accent-soft text-accent-ink hover:brightness-95",
	danger: "text-critical hover:bg-critical-tint",
};

const SIZES = { sm: "h-6 w-6", md: "h-8 w-8" };
const iconSize = computed(() => (props.size === "sm" ? 14 : 16));
</script>
