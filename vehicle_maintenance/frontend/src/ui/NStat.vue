<!--
  NStat — a figure and what it is a figure of.

  The number is the content; the icon is a label for it. So the number carries
  the weight and the icon sits quiet in a sunken slot. The version this replaces
  put a saturated gradient behind every tile, which meant a row of four stats
  gave the screen four focal points and rendered the figures themselves — in
  white, on a busy ground — the least legible thing on the card.

  A stat's number is coloured only when the number is itself the alarm (a breach
  count above zero), never by category. See DESIGN_LANGUAGE.md §5.4.
-->
<template>
	<component
		:is="to ? 'router-link' : 'div'"
		v-bind="to ? { to } : {}"
		class="flex flex-col gap-2 rounded-md border border-hairline bg-raised p-3.5 transition-colors duration-instant"
		:class="
			to &&
			'hover:bg-sunken focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'
		"
	>
		<div class="flex items-start justify-between gap-2">
			<span class="text-label-sm uppercase tracking-wide text-muted">{{ label }}</span>
			<span
				v-if="icon"
				class="flex h-7 w-7 shrink-0 items-center justify-center rounded-sm bg-sunken text-muted"
			>
				<NIcon :name="icon" :size="15" />
			</span>
		</div>

		<div class="flex items-baseline gap-2">
			<span class="tabular text-display leading-none" :class="valueClass">{{ value }}</span>
			<span v-if="unit" class="text-body-sm text-muted">{{ unit }}</span>
		</div>

		<p v-if="hint || $slots.default" class="text-caption text-muted">
			<slot>{{ hint }}</slot>
		</p>
	</component>
</template>

<script setup>
import { computed } from "vue";
import NIcon from "./NIcon.vue";
import { semanticClasses } from "./semantic.js";

const props = defineProps({
	label: { type: String, required: true },
	value: { type: [String, Number], default: "—" },
	unit: { type: String, default: "" },
	hint: { type: String, default: "" },
	icon: { type: String, default: "" },
	/** Only when the figure itself is the alarm. Leave empty for a plain count. */
	semantic: { type: String, default: "" },
	to: { type: [String, Object], default: null },
});

const valueClass = computed(() => (props.semantic ? semanticClasses(props.semantic).text : "text-ink"));
</script>
