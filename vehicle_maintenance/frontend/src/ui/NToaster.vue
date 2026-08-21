<!-- NToaster — mounted once in the shell. Never stacks more than three: a fourth
     message nobody has read yet is not communication, it is a queue. -->
<template>
	<Teleport to="body">
		<div
			class="pointer-events-none fixed bottom-4 right-4 z-toast flex w-[min(22rem,calc(100vw-2rem))] flex-col gap-2"
		>
			<TransitionGroup
				enter-active-class="transition duration-base"
				leave-active-class="transition duration-fast absolute"
				enter-from-class="opacity-0 translate-y-2"
				leave-to-class="opacity-0"
			>
				<div
					v-for="t in toasts"
					:key="t.id"
					class="pointer-events-auto flex items-start gap-2.5 rounded-md border border-hairline bg-raised px-3 py-2.5 shadow-e3"
					role="status"
					aria-live="polite"
				>
					<NIcon
						:name="ICONS[t.semantic]"
						:size="16"
						class="mt-0.5 shrink-0"
						:class="semanticClasses(t.semantic).text"
					/>
					<p class="min-w-0 flex-1 text-body-sm text-ink">{{ t.message }}</p>
					<button
						v-if="t.action"
						type="button"
						class="shrink-0 text-label-sm font-label text-accent hover:underline"
						@click="run(t)"
					>
						{{ t.action.label }}
					</button>
					<NIconButton icon="x" label="Dismiss" size="sm" @click="dismiss(t.id)" />
				</div>
			</TransitionGroup>
		</div>
	</Teleport>
</template>

<script setup>
import NIcon from "./NIcon.vue";
import NIconButton from "./NIconButton.vue";
import { semanticClasses } from "./semantic.js";
import { toasts, dismiss } from "./toast.js";

const ICONS = {
	positive: "circle-check",
	caution: "alert-triangle",
	critical: "circle-alert",
	active: "info",
	idle: "info",
};

function run(t) {
	t.action.onClick?.();
	dismiss(t.id);
}
</script>
