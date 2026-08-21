<!--
  NDialog — the only floating surface in the product that blocks.

  Focus enters the dialog, is trapped inside it, and returns to whatever opened
  it on close. Escape closes; the scrim closes unless the dialog is `persistent`
  (a half-finished form should not evaporate because of a stray click).

  Destructive confirms name the object — "Delete job card JC-2026-00412?" — and
  the destructive verb sits on the danger button, never on an "OK".
-->
<template>
	<Teleport to="body">
		<Transition
			enter-active-class="transition-opacity duration-base"
			leave-active-class="transition-opacity duration-fast"
			enter-from-class="opacity-0"
			leave-to-class="opacity-0"
		>
			<div
				v-if="modelValue"
				class="fixed inset-0 z-dialog flex items-end justify-center p-0 sm:items-center sm:p-6"
			>
				<div class="absolute inset-0 bg-overlay" @click="onScrim" />

				<div
					ref="panel"
					role="dialog"
					aria-modal="true"
					:aria-labelledby="`${uid}-title`"
					class="animate-ndl-scale-in relative flex max-h-[88vh] w-full flex-col rounded-t-lg border border-hairline bg-raised shadow-e3 sm:rounded-lg"
					:class="WIDTHS[size]"
					@keydown.esc.stop.prevent="close"
					@keydown.tab="trap"
				>
					<header class="flex items-start justify-between gap-4 border-b border-hairline px-4 py-3">
						<div class="min-w-0">
							<h2 :id="`${uid}-title`" class="text-title text-ink">{{ title }}</h2>
							<p v-if="subtitle" class="mt-0.5 text-body-sm text-muted">{{ subtitle }}</p>
						</div>
						<NIconButton icon="x" label="Close" size="sm" @click="close" />
					</header>

					<div class="min-h-0 flex-1 overflow-y-auto px-4 py-4">
						<slot />
					</div>

					<footer
						v-if="$slots.actions"
						class="flex items-center justify-end gap-2 border-t border-hairline px-4 py-3"
					>
						<slot name="actions" />
					</footer>
				</div>
			</div>
		</Transition>
	</Teleport>
</template>

<script setup>
import { nextTick, ref, useId, watch } from "vue";
import NIconButton from "./NIconButton.vue";

const props = defineProps({
	modelValue: { type: Boolean, default: false },
	title: { type: String, default: "" },
	subtitle: { type: String, default: "" },
	size: { type: String, default: "md" }, // sm (confirm) | md (form) | lg
	persistent: { type: Boolean, default: false },
});
const emit = defineEmits(["update:modelValue", "close"]);

const WIDTHS = { sm: "sm:max-w-[420px]", md: "sm:max-w-[560px]", lg: "sm:max-w-[760px]" };
const uid = useId();
const panel = ref(null);
let returnFocusTo = null;

const FOCUSABLE =
	'a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';

function focusables() {
	return Array.from(panel.value?.querySelectorAll(FOCUSABLE) || []).filter(
		(el) => el.offsetParent !== null
	);
}

/* A native `<dialog>` would give this for free but cannot be styled consistently
   across the browsers a depot actually runs, so the trap is explicit. */
function trap(event) {
	const items = focusables();
	if (!items.length) return;
	const first = items[0];
	const last = items[items.length - 1];
	if (event.shiftKey && document.activeElement === first) {
		event.preventDefault();
		last.focus();
	} else if (!event.shiftKey && document.activeElement === last) {
		event.preventDefault();
		first.focus();
	}
}

function close() {
	emit("update:modelValue", false);
	emit("close");
}

function onScrim() {
	if (!props.persistent) close();
}

watch(
	() => props.modelValue,
	async (open) => {
		if (open) {
			returnFocusTo = document.activeElement;
			document.body.style.overflow = "hidden";
			await nextTick();
			// The first focusable, not the close button: the reader should land on
			// the thing they came to do, not on the way out.
			(focusables()[1] || focusables()[0])?.focus();
		} else {
			document.body.style.overflow = "";
			returnFocusTo?.focus?.();
			returnFocusTo = null;
		}
	}
);
</script>
