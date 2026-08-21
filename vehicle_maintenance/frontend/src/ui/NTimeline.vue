<!--
  NTimeline — a sequence of steps and where the reader currently is in it.

  Three states, and the difference between them is carried by *shape* as well as
  colour: done is a filled dot with a tick, current is a ringed dot, still to
  come is a hollow outline. On a customer's phone in daylight that distinction
  survives; three shades of the same circle does not.

  Steps: `{ key, label, sublabel?, state: 'done' | 'current' | 'todo' }`
-->
<template>
	<ol class="relative ml-2.5 border-l border-hairline">
		<li v-for="step in steps" :key="step.key" class="relative pb-6 pl-7 last:pb-0">
			<span
				class="absolute -left-[9px] top-0.5 flex h-[18px] w-[18px] items-center justify-center rounded-full border-2"
				:class="nodeClass(step)"
			>
				<NIcon v-if="step.state === 'done'" name="check" :size="10" class="text-white" />
				<span v-else-if="step.state === 'current'" class="h-1.5 w-1.5 rounded-full bg-accent" />
			</span>

			<p class="text-body-sm" :class="labelClass(step)">{{ step.label }}</p>
			<p
				v-if="step.sublabel"
				class="mt-0.5 text-caption"
				:class="step.state === 'current' ? 'text-accent' : 'text-muted'"
			>
				{{ step.sublabel }}
			</p>
		</li>
	</ol>
</template>

<script setup>
import NIcon from "./NIcon.vue";

defineProps({ steps: { type: Array, default: () => [] } });

function nodeClass(step) {
	if (step.state === "done") return "border-positive bg-positive";
	if (step.state === "current") return "border-accent bg-raised";
	return "border-hairline bg-raised";
}

function labelClass(step) {
	if (step.state === "done") return "text-ink";
	if (step.state === "current") return "font-semibold text-ink";
	return "text-subtle";
}
</script>
