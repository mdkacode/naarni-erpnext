<!--
  NChoice — pick one from a short, visible set.

  A radio group that looks like what it is. Used where a `<select>` would hide
  the options behind a click and where there are few enough of them to show:
  job card type, priority, fleet size.

  Selection is an accent-tinted card with an accent border. Not a filled accent
  card — that would put a large block of the one action colour on a screen whose
  actual action is "Continue" at the bottom.
-->
<template>
	<fieldset class="min-w-0">
		<legend v-if="label" class="mb-1.5 flex items-center gap-1 text-label text-ink">
			{{ label }}
			<span v-if="required" class="text-critical" aria-hidden="true">*</span>
		</legend>

		<div class="grid gap-2" :class="gridClass">
			<button
				v-for="opt in normalised"
				:key="opt.value"
				type="button"
				role="radio"
				:aria-checked="opt.value === modelValue"
				:disabled="disabled"
				class="flex items-start gap-2.5 rounded-md border p-3 text-left transition-colors duration-instant focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:opacity-50"
				:class="selectedClass(opt)"
				@click="$emit('update:modelValue', opt.value)"
			>
				<span
					v-if="opt.icon"
					class="flex h-8 w-8 shrink-0 items-center justify-center rounded-sm"
					:class="opt.value === modelValue ? 'bg-raised text-accent' : 'bg-sunken text-muted'"
				>
					<NIcon :name="opt.icon" :size="17" />
				</span>
				<span class="min-w-0">
					<span class="block text-title-sm" :class="labelClass(opt)">
						{{ opt.label }}
					</span>
					<span v-if="opt.description" class="mt-0.5 block text-caption text-muted">{{
						opt.description
					}}</span>
				</span>
			</button>
		</div>

		<p v-if="hint" class="mt-1.5 text-caption text-muted">{{ hint }}</p>
	</fieldset>
</template>

<script setup>
import { computed } from "vue";
import NIcon from "./NIcon.vue";
import { semanticClasses } from "./semantic.js";

const props = defineProps({
	modelValue: { type: [String, Number], default: "" },
	/** `['Low','High']` or `[{ value, label, description?, icon? }]` */
	options: { type: Array, default: () => [] },
	label: { type: String, default: "" },
	hint: { type: String, default: "" },
	columns: { type: Number, default: 2 },
	required: { type: Boolean, default: false },
	disabled: { type: Boolean, default: false },
});
defineEmits(["update:modelValue"]);

const normalised = computed(() =>
	props.options.map((o) => (typeof o === "object" ? o : { value: o, label: o }))
);

/* An option may carry its own semantic — "Good / Recommended / Immediate" on an
   inspection is a verdict, and colouring all three accent would throw away the
   one thing the technician is actually communicating. Options without a semantic
   fall back to the accent, which is the normal case. */
function selectedClass(opt) {
	if (opt.value !== props.modelValue) return "border-hairline bg-raised hover:bg-sunken";
	if (opt.semantic) {
		const tone = semanticClasses(opt.semantic);
		return `${tone.border} ${tone.tint}`;
	}
	return "border-accent bg-accent-soft";
}

function labelClass(opt) {
	if (opt.value !== props.modelValue) return "text-ink";
	return opt.semantic ? semanticClasses(opt.semantic).text : "text-accent-ink";
}

const GRID = {
	1: "grid-cols-1",
	2: "sm:grid-cols-2",
	3: "grid-cols-3",
	4: "grid-cols-2 sm:grid-cols-4",
	5: "grid-cols-3 sm:grid-cols-5",
};
const gridClass = computed(() => GRID[props.columns] || GRID[2]);
</script>
