<!--
  NMeter — a proportion: a health score, a share of a total, progress.

  The value is printed *beside* the bar, never only inside it. A number inside a
  bar disappears as soon as the bar is short — which is precisely when the number
  matters most, because a 12% health score is the one a depot needs to read.
-->
<template>
	<div class="flex items-center gap-3">
		<div
			class="h-2 flex-1 overflow-hidden rounded-full bg-sunken"
			role="meter"
			:aria-valuenow="clamped"
			aria-valuemin="0"
			:aria-valuemax="max"
			:aria-label="label || undefined"
		>
			<div
				class="h-full rounded-full transition-[width] duration-base"
				:class="tone.bg"
				:style="{ width: `${pct}%` }"
			/>
		</div>
		<span
			v-if="showValue"
			class="tabular w-10 shrink-0 text-right text-body-sm font-semibold"
			:class="tone.text"
		>
			{{ readout }}
		</span>
	</div>
</template>

<script setup>
import { computed } from "vue";
import { semanticClasses, scoreSemantic } from "./semantic.js";
import { fmt } from "./format.js";

const props = defineProps({
	value: { type: [Number, String], default: 0 },
	max: { type: Number, default: 100 },
	label: { type: String, default: "" },
	showValue: { type: Boolean, default: true },
	/** Omit to grade the value on the shared score thresholds. */
	semantic: { type: String, default: "" },
	/** `percent` prints 82%, `count` prints the raw figure. */
	mode: { type: String, default: "percent" },
});

const clamped = computed(() => Math.max(0, Math.min(Number(props.value) || 0, props.max)));
const pct = computed(() => (props.max ? (clamped.value / props.max) * 100 : 0));
const tone = computed(() =>
	semanticClasses(props.semantic || scoreSemantic((clamped.value / props.max) * 100))
);
const readout = computed(() =>
	props.mode === "percent" ? fmt.percent(pct.value) : fmt.number(clamped.value)
);
</script>
