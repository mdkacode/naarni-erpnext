<!--
  NSegmented — a filter switch: "show me Open / WIP / Closed".

  A sunken track with one raised, accent-tinted selection. Distinct from NTabs,
  which navigates between *views* of a page; this one changes what a single view
  is showing. Using the same control for both leaves the reader unsure whether
  pressing it will lose their place.
-->
<template>
	<div class="inline-flex items-center gap-0.5 rounded-sm bg-sunken p-0.5" role="tablist">
		<button
			v-for="opt in normalised"
			:key="opt.value"
			type="button"
			role="tab"
			:aria-selected="opt.value === modelValue"
			class="inline-flex h-7 items-center gap-1.5 whitespace-nowrap rounded-sm px-2.5 text-label-sm font-label transition-colors duration-instant focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
			:class="opt.value === modelValue ? 'bg-accent-soft text-accent-ink' : 'text-muted hover:text-ink'"
			@click="$emit('update:modelValue', opt.value)"
		>
			{{ opt.label }}
			<span
				v-if="opt.count !== undefined && opt.count !== null"
				class="tabular text-caption"
				:class="opt.value === modelValue ? 'text-accent-ink opacity-70' : 'text-subtle'"
				>{{ opt.count }}</span
			>
		</button>
	</div>
</template>

<script setup>
import { computed } from "vue";

const props = defineProps({
	modelValue: { type: [String, Number], default: "" },
	/** `['All','Open']` or `[{ label, value, count? }]` */
	options: { type: Array, default: () => [] },
});
defineEmits(["update:modelValue"]);

const normalised = computed(() =>
	props.options.map((o) => (typeof o === "object" ? o : { label: o, value: o }))
);
</script>
