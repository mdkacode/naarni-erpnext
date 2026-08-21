<!--
  NIcon — the only way an icon appears in this product.

  One set, one stroke weight, four sizes. Icons take a *text* colour token, not
  a semantic, unless the icon is itself the status indicator.

  A decorative icon is `aria-hidden`; an icon that carries meaning takes a
  `label` and becomes an `img` with an accessible name. There is no third case:
  an icon nobody can name is an icon nobody can use.
-->
<template>
	<svg
		:width="px"
		:height="px"
		viewBox="0 0 24 24"
		:fill="filled ? 'currentColor' : 'none'"
		stroke="currentColor"
		:stroke-width="stroke"
		stroke-linecap="round"
		stroke-linejoin="round"
		class="shrink-0"
		:class="spin && 'animate-spin'"
		:role="label ? 'img' : undefined"
		:aria-label="label || undefined"
		:aria-hidden="label ? undefined : 'true'"
		v-html="body"
	/>
</template>

<script setup>
import { computed } from "vue";
import { ICONS } from "./icons.js";

const props = defineProps({
	/** A Lucide icon name present in `icons.js`. */
	name: { type: String, required: true },
	/** 14 inline with body-sm · 16 default · 20 nav and page header · 24 empty states. */
	size: { type: [Number, String], default: 16 },
	/** Accessible name. Omit for decorative icons — they are hidden from AT. */
	label: { type: String, default: "" },
	spin: { type: Boolean, default: false },
	/** Fill the glyph. Only for icons whose *state* is the message — the filled
	 *  half of a star rating, a selected bookmark. Never for decoration. */
	filled: { type: Boolean, default: false },
});

const px = computed(() => Number(props.size));

/* Lucide draws at stroke 2 in a 24 viewBox, which reads heavy next to a 550-weight
   label. 1.75 renders at ~1.2px on a 16px icon and ~1.5px on a 20px one — level
   with the type rather than shouting over it. */
const stroke = 1.75;

const body = computed(() => {
	const found = ICONS[props.name];
	if (!found && import.meta.env.DEV) {
		console.warn(`[NIcon] unknown icon "${props.name}" — add it to src/ui/icons.js`);
	}
	return found || "";
});
</script>
