<!--
  NCombobox — type to search a server-side set, pick exactly one.

  The pattern behind "search the vehicle, search the depot, search the part".
  Two things it does that a hand-rolled version in a page never does:

    · **the chosen value is shown as a resolved summary**, not as text left in
      the box. A search box still holding "DL-01" cannot tell the reader whether
      the selection took, and the old vehicle picker needed a whole green
      confirmation panel underneath to answer that.
    · **arrow keys and Enter work**, so the control is usable without a mouse and
      without lifting a gloved hand off the keyboard.
-->
<template>
	<NField
		v-slot="{ id, describedBy, invalid }"
		:label="label"
		:hint="hint"
		:error="error"
		:required="required"
	>
		<div ref="root" class="relative">
			<!-- Resolved selection -->
			<div
				v-if="selected"
				class="flex items-center justify-between gap-2 rounded-sm border border-line bg-sunken px-2.5 py-1.5"
			>
				<div class="min-w-0">
					<slot name="selected" :item="selected">
						<p class="truncate text-body text-ink">{{ display(selected) }}</p>
						<p v-if="describe(selected)" class="truncate text-caption text-muted">
							{{ describe(selected) }}
						</p>
					</slot>
				</div>
				<NButton v-if="!disabled" variant="ghost" size="sm" @click="clear">Change</NButton>
			</div>

			<!-- Search -->
			<template v-else>
				<span class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-subtle">
					<NIcon name="search" :size="15" />
				</span>
				<input
					:id="id"
					v-model="query"
					type="text"
					role="combobox"
					:aria-expanded="isOpen"
					aria-autocomplete="list"
					:aria-controls="`${id}-list`"
					:placeholder="placeholder"
					:disabled="disabled"
					:aria-describedby="describedBy"
					:aria-invalid="invalid || undefined"
					autocomplete="off"
					class="h-control w-full rounded-sm border bg-sunken pl-8 pr-8 text-body text-ink transition-colors duration-instant placeholder:text-subtle focus:bg-raised"
					:class="invalid ? 'border-critical' : 'border-line'"
					@input="onInput"
					@focus="isOpen = true"
					@keydown.down.prevent="move(1)"
					@keydown.up.prevent="move(-1)"
					@keydown.enter.prevent="choose(results[cursor])"
					@keydown.esc="isOpen = false"
				/>
				<span v-if="searching" class="absolute right-2.5 top-1/2 -translate-y-1/2 text-subtle">
					<NSpinner :size="14" />
				</span>

				<ul
					v-if="isOpen && query.length >= minChars"
					:id="`${id}-list`"
					role="listbox"
					class="absolute z-overlay mt-1 max-h-60 w-full overflow-y-auto rounded-md border border-hairline bg-raised p-1 shadow-e2"
				>
					<li v-if="searching && !results.length" class="px-2 py-3">
						<NSkeleton :count="3" :widths="['70%', '50%', '80%']" :delay="0" />
					</li>
					<li v-else-if="!results.length" class="px-2 py-4 text-center text-body-sm text-muted">
						{{ noResultsText }}
					</li>
					<li
						v-for="(item, idx) in results"
						v-else
						:key="keyOf(item)"
						role="option"
						:aria-selected="idx === cursor"
					>
						<button
							type="button"
							class="flex w-full items-center justify-between gap-3 rounded-sm px-2 py-1.5 text-left transition-colors duration-instant"
							:class="idx === cursor ? 'bg-sunken' : 'hover:bg-sunken'"
							@click="choose(item)"
							@mousemove="cursor = idx"
						>
							<slot name="option" :item="item">
								<span class="truncate text-body-sm text-ink">{{ display(item) }}</span>
								<span v-if="describe(item)" class="shrink-0 text-caption text-muted">{{
									describe(item)
								}}</span>
							</slot>
						</button>
					</li>
				</ul>
			</template>
		</div>
	</NField>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import NField from "./NField.vue";
import NIcon from "./NIcon.vue";
import NButton from "./NButton.vue";
import NSpinner from "./NSpinner.vue";
import NSkeleton from "./NSkeleton.vue";

const props = defineProps({
	/** The chosen item, or null. */
	modelValue: { type: Object, default: null },
	/** `async (query) => item[]` */
	search: { type: Function, required: true },
	/** `(item) => string` for the primary line, the secondary line, and the key. */
	display: { type: Function, default: (i) => i?.label ?? i?.name ?? "" },
	describe: { type: Function, default: () => "" },
	keyOf: { type: Function, default: (i) => i?.name ?? i?.value },
	label: { type: String, default: "" },
	placeholder: { type: String, default: "Type to search" },
	hint: { type: String, default: "" },
	error: { type: String, default: "" },
	noResultsText: { type: String, default: "Nothing found." },
	minChars: { type: Number, default: 2 },
	debounce: { type: Number, default: 250 },
	required: { type: Boolean, default: false },
	disabled: { type: Boolean, default: false },
});
const emit = defineEmits(["update:modelValue"]);

const root = ref(null);
const query = ref("");
const results = ref([]);
const searching = ref(false);
const isOpen = ref(false);
const cursor = ref(0);
const selected = ref(props.modelValue);

let timer = null;
/* Every keystroke starts a request; only the newest one is allowed to write the
   results. Without the token a slow early request can land after a fast later
   one and repopulate the list with answers to a query the reader has moved on
   from. */
let token = 0;

watch(
	() => props.modelValue,
	(v) => (selected.value = v)
);

function onInput() {
	isOpen.value = true;
	cursor.value = 0;
	clearTimeout(timer);
	if (query.value.trim().length < props.minChars) {
		results.value = [];
		searching.value = false;
		return;
	}
	searching.value = true;
	const mine = ++token;
	timer = setTimeout(async () => {
		try {
			const found = await props.search(query.value.trim());
			if (mine === token) results.value = found || [];
		} catch {
			if (mine === token) results.value = [];
		} finally {
			if (mine === token) searching.value = false;
		}
	}, props.debounce);
}

function move(delta) {
	if (!results.value.length) return;
	cursor.value = (cursor.value + delta + results.value.length) % results.value.length;
}

function choose(item) {
	if (!item) return;
	selected.value = item;
	emit("update:modelValue", item);
	isOpen.value = false;
	query.value = "";
	results.value = [];
}

function clear() {
	selected.value = null;
	emit("update:modelValue", null);
	query.value = "";
	results.value = [];
}

function onDocumentClick(event) {
	if (isOpen.value && root.value && !root.value.contains(event.target)) isOpen.value = false;
}
onMounted(() => document.addEventListener("click", onDocumentClick));
onBeforeUnmount(() => {
	document.removeEventListener("click", onDocumentClick);
	clearTimeout(timer);
});
</script>
