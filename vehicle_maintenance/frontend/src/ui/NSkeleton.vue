<!--
  NSkeleton — a placeholder shaped like the thing that is coming.

  A centred grey "Loading…" makes the layout jump when the content lands. A
  skeleton at the real dimensions does not, and it tells the reader what kind of
  thing to expect while they wait.

  It appears only after 200ms: flashing a skeleton for a 60ms response is worse
  than showing nothing at all. That delay is `NSkeleton`'s job, not the caller's.
-->
<template>
	<div v-if="shown" :class="wrapperClass" role="status" aria-live="polite" aria-busy="true">
		<span class="sr-only-ndl">Loading</span>
		<div
			v-for="i in count"
			:key="i"
			class="animate-ndl-pulse bg-sunken"
			:class="[rounded, heightClass]"
			:style="widths[i - 1] ? { width: widths[i - 1] } : undefined"
		/>
	</div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from "vue";

const props = defineProps({
	/** `bar` · `block` · `circle` */
	variant: { type: String, default: "bar" },
	count: { type: Number, default: 1 },
	/** Per-item widths, e.g. `['40%','70%','55%']`. Ragged widths read as text. */
	widths: { type: Array, default: () => [] },
	height: { type: String, default: "" },
	gap: { type: String, default: "gap-2" },
	delay: { type: Number, default: 200 },
});

const shown = ref(props.delay === 0);
let timer = null;

onMounted(() => {
	if (props.delay > 0) timer = setTimeout(() => (shown.value = true), props.delay);
});
onBeforeUnmount(() => timer && clearTimeout(timer));

const rounded = computed(() =>
	props.variant === "circle" ? "rounded-full" : props.variant === "block" ? "rounded-md" : "rounded-sm"
);
const heightClass = computed(
	() =>
		props.height ||
		(props.variant === "block" ? "h-20" : props.variant === "circle" ? "h-8 w-8" : "h-3.5")
);
const wrapperClass = computed(() => ["flex flex-col", props.gap]);
</script>
