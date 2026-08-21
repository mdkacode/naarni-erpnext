<!--
  NStepper — progress through a multi-step form.

  A completed step is revisitable; a step ahead of the reader is not, because a
  wizard whose later steps are reachable is a long form with extra clicks.

  The current step is an outlined accent circle and a completed one is filled:
  the difference between "you are here" and "this is done" is carried by fill,
  not by two different hues.
-->
<template>
	<nav :aria-label="ariaLabel">
		<ol class="flex items-center">
			<li
				v-for="(step, idx) in steps"
				:key="step.id"
				class="flex items-center"
				:class="idx < steps.length - 1 && 'flex-1'"
			>
				<button
					type="button"
					:disabled="idx > furthest"
					:aria-current="idx === current ? 'step' : undefined"
					:aria-label="`Step ${idx + 1}: ${step.title}`"
					class="flex h-8 w-8 items-center justify-center rounded-full border text-label-sm font-semibold transition-colors duration-instant focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-default"
					:class="circleClass(idx)"
					@click="$emit('go', idx)"
				>
					<NIcon v-if="idx < current" name="check" :size="15" />
					<template v-else>{{ idx + 1 }}</template>
				</button>

				<span
					v-if="idx < steps.length - 1"
					class="mx-2 h-px flex-1 transition-colors duration-base"
					:class="idx < current ? 'bg-accent' : 'bg-hairline'"
				/>
			</li>
		</ol>
	</nav>
</template>

<script setup>
import NIcon from "./NIcon.vue";

const props = defineProps({
	steps: { type: Array, required: true },
	current: { type: Number, default: 0 },
	furthest: { type: Number, default: 0 },
	ariaLabel: { type: String, default: "Progress" },
});
defineEmits(["go"]);

function circleClass(idx) {
	if (idx < props.current) return "border-accent bg-accent text-accent-on";
	if (idx === props.current) return "border-accent bg-raised text-accent";
	return "border-hairline bg-raised text-subtle";
}
</script>
