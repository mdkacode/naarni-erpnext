<!--
  NKeyValue — the summary block at the top of a detail page.

  Definition rows rather than a grid of little bordered cards: a fact and its
  label are one thing, and boxing each one separately turns eight facts into
  eight objects the eye has to visit.
-->
<template>
	<dl class="divide-y divide-hairline">
		<div v-for="item in visible" :key="item.label" class="flex items-baseline justify-between gap-6 py-2">
			<dt class="shrink-0 text-body-sm text-muted">{{ item.label }}</dt>
			<dd
				class="min-w-0 text-right text-body-sm text-ink"
				:class="[
					item.mono && 'font-mono tabular',
					item.semantic && semanticClasses(item.semantic).text,
				]"
			>
				{{ item.value }}
			</dd>
		</div>
		<slot />
	</dl>
</template>

<script setup>
import { computed } from "vue";
import { semanticClasses } from "./semantic.js";
import { EMPTY } from "./format.js";

const props = defineProps({
	/** `[{ label, value, mono?, semantic?, hideWhenEmpty? }]` */
	items: { type: Array, default: () => [] },
});

const visible = computed(() =>
	props.items
		.filter((i) => !(i.hideWhenEmpty && (i.value === EMPTY || i.value === "" || i.value == null)))
		.map((i) => ({ ...i, value: i.value === "" || i.value == null ? EMPTY : i.value }))
);
</script>
