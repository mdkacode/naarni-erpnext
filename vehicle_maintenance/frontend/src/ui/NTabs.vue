<!-- NTabs — navigation between views of one record. Underline, not a pill track:
     a tab bar is part of the page's structure, a segmented control is a filter. -->
<template>
	<div class="flex items-center gap-4 border-b border-hairline" role="tablist">
		<button
			v-for="tab in tabs"
			:key="tab.value"
			type="button"
			role="tab"
			:aria-selected="tab.value === modelValue"
			class="relative -mb-px flex items-center gap-1.5 border-b-2 px-0.5 py-2 text-label transition-colors duration-instant focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
			:class="
				tab.value === modelValue
					? 'border-accent text-ink'
					: 'border-transparent text-muted hover:text-ink'
			"
			@click="$emit('update:modelValue', tab.value)"
		>
			<NIcon v-if="tab.icon" :name="tab.icon" :size="15" />
			{{ tab.label }}
			<span v-if="tab.count" class="tabular rounded-full bg-sunken px-1.5 text-caption text-muted">{{
				tab.count
			}}</span>
		</button>
	</div>
</template>

<script setup>
import NIcon from "./NIcon.vue";

defineProps({
	modelValue: { type: [String, Number], default: "" },
	tabs: { type: Array, default: () => [] },
});
defineEmits(["update:modelValue"]);
</script>
