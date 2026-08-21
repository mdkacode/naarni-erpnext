<!--
  NButton — five variants, and only one of them is filled with the accent.

  The rule the colour system rests on is that anything accent-coloured is
  something to press. It follows that a screen with four accent buttons has told
  the reader nothing. Use `primary` for the one committing action on the screen
  and `secondary` for everything else.

  Loading keeps the button's measured width and swaps the label for a spinner —
  a button that shrinks mid-click moves the thing under the cursor.

  See DESIGN_LANGUAGE.md §5.1.
-->
<template>
	<component
		:is="tag"
		v-bind="linkAttrs"
		:type="tag === 'button' ? type : undefined"
		:disabled="isDisabled"
		:aria-disabled="isDisabled || undefined"
		:aria-busy="loading || undefined"
		class="inline-flex items-center justify-center gap-1.5 whitespace-nowrap rounded-sm font-label transition-colors duration-instant focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:pointer-events-none disabled:opacity-45"
		:class="[SIZES[size], VARIANTS[variant], block && 'w-full']"
	>
		<NSpinner v-if="loading" :size="iconSize" />
		<NIcon v-else-if="icon" :name="icon" :size="iconSize" />
		<span v-if="$slots.default" :class="loading && 'opacity-90'"><slot /></span>
		<NIcon v-if="trailingIcon && !loading" :name="trailingIcon" :size="iconSize" />
	</component>
</template>

<script setup>
import { computed } from "vue";
import NIcon from "./NIcon.vue";
import NSpinner from "./NSpinner.vue";

const props = defineProps({
	variant: { type: String, default: "secondary" }, // primary | secondary | tonal | ghost | danger
	size: { type: String, default: "md" }, // sm | md | lg
	icon: { type: String, default: "" },
	trailingIcon: { type: String, default: "" },
	type: { type: String, default: "button" },
	to: { type: [String, Object], default: null },
	href: { type: String, default: "" },
	loading: { type: Boolean, default: false },
	disabled: { type: Boolean, default: false },
	block: { type: Boolean, default: false },
});

const VARIANTS = {
	primary: "bg-accent text-accent-on hover:bg-accent-hover",
	secondary: "bg-raised text-ink border border-line hover:bg-sunken",
	tonal: "bg-accent-soft text-accent-ink hover:brightness-95",
	ghost: "text-muted hover:bg-sunken hover:text-ink",
	danger: "bg-critical text-white hover:brightness-110",
};

/* Heights come from the density variables, so the same button is 30px on an ops
   table toolbar and 36px on a field form without the call site knowing. */
const SIZES = {
	sm: "h-6 px-2 text-label-sm",
	md: "h-control px-3 text-label",
	lg: "h-11 px-4 text-body font-semibold",
};

const iconSize = computed(() => (props.size === "lg" ? 18 : props.size === "sm" ? 14 : 16));
const isDisabled = computed(() => props.disabled || props.loading);

const tag = computed(() => {
	if (props.to) return "router-link";
	if (props.href) return "a";
	return "button";
});

const linkAttrs = computed(() => {
	if (props.to) return { to: props.to };
	if (props.href) {
		const external = /^https?:|^\//.test(props.href) && !props.href.startsWith("/service-portal");
		return { href: props.href, ...(external ? { target: "_blank", rel: "noopener noreferrer" } : {}) };
	}
	return {};
});
</script>
