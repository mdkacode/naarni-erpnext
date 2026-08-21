<!--
  NCard — a raised ground with a hairline. No shadow.

  Structure is communicated by a border and a change of ground, not by depth.
  A `shadow-lg` on a static card is decoration, and on a list of twenty cards it
  is also twenty extra paint layers. An interactive card responds by changing its
  *ground*, which costs nothing and reads more clearly on the dark canvas — where
  a drop shadow has almost nothing to fall on.

  See DESIGN_LANGUAGE.md §5.3.
-->
<template>
	<component
		:is="tag"
		v-bind="linkAttrs"
		class="block rounded-md border border-hairline bg-raised text-left transition-colors duration-instant"
		:class="[
			interactive &&
				'cursor-pointer hover:bg-sunken focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent',
			flagged && 'border-critical',
		]"
	>
		<header
			v-if="title || $slots.header || $slots.actions"
			class="flex items-center justify-between gap-3 border-b border-hairline px-4 py-2.5"
		>
			<slot name="header">
				<h3 class="text-title-sm text-ink">{{ title }}</h3>
			</slot>
			<div v-if="$slots.actions" class="flex shrink-0 items-center gap-1">
				<slot name="actions" />
			</div>
		</header>

		<div :class="padded ? 'p-4' : ''">
			<slot />
		</div>

		<footer v-if="$slots.footer" class="border-t border-hairline px-4 py-2.5">
			<slot name="footer" />
		</footer>
	</component>
</template>

<script setup>
import { computed } from "vue";

const props = defineProps({
	title: { type: String, default: "" },
	to: { type: [String, Object], default: null },
	/** Draw the border in `critical` — an SLA breach, a failed check. Pair with a
	 *  visible reason inside the card; a red edge alone is not a message. */
	flagged: { type: Boolean, default: false },
	padded: { type: Boolean, default: true },
});

const interactive = computed(() => Boolean(props.to));
const tag = computed(() => (props.to ? "router-link" : "div"));
const linkAttrs = computed(() => (props.to ? { to: props.to } : {}));
</script>
