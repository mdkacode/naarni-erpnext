<!--
  NMultiSelect — pick several values from a known set.

  Exists because the product rule is that anything with a known set of answers is
  a picker, never a text box. It searches, it groups, and it shows what is chosen
  as removable chips above the control so the answer is legible without opening
  anything.

  The chips are tonal, not filled: a list of eight selected subsystems in solid
  accent would be eight things claiming to be the screen's primary action.
-->
<template>
	<div ref="root" class="relative">
		<div v-if="selected.length" class="mb-2 flex flex-wrap gap-1.5">
			<span
				v-for="value in selected"
				:key="value"
				class="inline-flex items-center gap-1 rounded-full bg-accent-soft py-0.5 pl-2.5 pr-1 text-label-sm text-accent-ink"
			>
				{{ labelFor(value) }}
				<button
					v-if="!disabled"
					type="button"
					class="flex h-4 w-4 items-center justify-center rounded-full hover:bg-hairline"
					:aria-label="`Remove ${labelFor(value)}`"
					@click="toggle(value)"
				>
					<NIcon name="x" :size="11" />
				</button>
			</span>
		</div>

		<button
			v-if="!disabled"
			type="button"
			class="flex h-control w-full items-center justify-between gap-2 rounded-sm border border-line bg-sunken px-2.5 text-body-sm text-ink transition-colors duration-instant hover:bg-raised"
			:aria-expanded="open"
			aria-haspopup="listbox"
			@click="open = !open"
		>
			<span :class="!selected.length && 'text-subtle'">{{ placeholder }}</span>
			<span class="flex items-center gap-1.5 text-caption text-muted">
				<span v-if="selected.length" class="tabular">{{ selected.length }} selected</span>
				<NIcon name="chevron-down" :size="14" />
			</span>
		</button>

		<div
			v-if="open"
			class="absolute z-overlay mt-1 max-h-72 w-full overflow-y-auto rounded-md border border-hairline bg-raised p-1.5 shadow-e2"
			role="listbox"
		>
			<NSearch v-model="search" :placeholder="searchPlaceholder" class="mb-1.5" />

			<NSkeleton
				v-if="loading"
				:count="4"
				:widths="['70%', '55%', '80%', '45%']"
				class="p-2"
				:delay="0"
			/>
			<p v-else-if="!grouped.size" class="px-2 py-4 text-center text-body-sm text-muted">
				{{ search ? `Nothing matches “${search}”.` : emptyText }}
			</p>

			<template v-for="[group, items] in grouped" :key="group">
				<p v-if="showGroups" class="px-2 pb-1 pt-2 text-caption uppercase tracking-wide text-subtle">
					{{ group }}
				</p>
				<button
					v-for="item in items"
					:key="item.value"
					type="button"
					role="option"
					:aria-selected="selected.includes(item.value)"
					class="flex w-full items-center justify-between gap-2 rounded-sm px-2 py-1.5 text-left text-body-sm transition-colors duration-instant hover:bg-sunken"
					:class="selected.includes(item.value) ? 'text-accent-ink' : 'text-ink'"
					@click="toggle(item.value)"
				>
					<span class="truncate">{{ item.label }}</span>
					<NIcon v-if="selected.includes(item.value)" name="check" :size="14" class="text-accent" />
				</button>
			</template>
		</div>
	</div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import NIcon from "./NIcon.vue";
import NSearch from "./NSearch.vue";
import NSkeleton from "./NSkeleton.vue";

const props = defineProps({
	modelValue: { type: Array, default: () => [] },
	/** `[{ value, label, group? }]` */
	options: { type: Array, default: () => [] },
	placeholder: { type: String, default: "Choose" },
	searchPlaceholder: { type: String, default: "Search" },
	emptyText: { type: String, default: "Nothing to choose from yet." },
	loading: { type: Boolean, default: false },
	disabled: { type: Boolean, default: false },
	showGroups: { type: Boolean, default: true },
});
const emit = defineEmits(["update:modelValue"]);

const root = ref(null);
const open = ref(false);
const search = ref("");

const selected = computed(() => props.modelValue || []);

function labelFor(value) {
	return props.options.find((o) => o.value === value)?.label || value;
}

const grouped = computed(() => {
	const q = search.value.trim().toLowerCase();
	const map = new Map();
	for (const opt of props.options) {
		if (q && !String(opt.label).toLowerCase().includes(q)) continue;
		const key = opt.group || "Other";
		if (!map.has(key)) map.set(key, []);
		map.get(key).push(opt);
	}
	return map;
});

function toggle(value) {
	const next = selected.value.includes(value)
		? selected.value.filter((v) => v !== value)
		: [...selected.value, value];
	emit("update:modelValue", next);
}

/* Closing on an outside click rather than on blur: blur fires before the option's
   click handler, so a blur-close swallows the first selection every time. */
function onDocumentClick(event) {
	if (open.value && root.value && !root.value.contains(event.target)) open.value = false;
}
onMounted(() => document.addEventListener("click", onDocumentClick));
onBeforeUnmount(() => document.removeEventListener("click", onDocumentClick));

watch(open, (v) => {
	if (!v) search.value = "";
});
</script>
