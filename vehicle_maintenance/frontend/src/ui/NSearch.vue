<!-- NSearch — a toolbar search box. Bare (no NField wrapper) because a toolbar
     control with a label above it makes the toolbar two rows tall. -->
<template>
	<div class="relative">
		<span class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-subtle">
			<NIcon name="search" :size="15" />
		</span>
		<input
			:value="modelValue"
			type="search"
			:placeholder="placeholder"
			:aria-label="placeholder"
			class="h-control w-full rounded-sm border border-line bg-sunken pl-8 pr-8 text-body-sm text-ink transition-colors duration-instant placeholder:text-subtle focus:bg-raised"
			@input="$emit('update:modelValue', $event.target.value)"
		/>
		<button
			v-if="modelValue"
			type="button"
			aria-label="Clear search"
			class="absolute right-1.5 top-1/2 flex h-5 w-5 -translate-y-1/2 items-center justify-center rounded-full text-subtle hover:bg-hairline hover:text-ink"
			@click="$emit('update:modelValue', '')"
		>
			<NIcon name="x" :size="13" />
		</button>
	</div>
</template>

<script setup>
import NIcon from "./NIcon.vue";

defineProps({
	modelValue: { type: String, default: "" },
	placeholder: { type: String, default: "Search" },
});
defineEmits(["update:modelValue"]);
</script>
