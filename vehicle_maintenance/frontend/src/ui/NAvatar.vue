<!-- NAvatar — initials on a sunken ground. Not accent-coloured: an avatar is an
     identifier, not an action, and a wall of indigo circles in a list spends the
     one colour the screen has to point with. -->
<template>
	<span class="relative inline-flex shrink-0" :class="box">
		<span
			class="flex h-full w-full items-center justify-center rounded-full bg-sunken font-semibold uppercase text-muted"
			:class="textClass"
			>{{ initials }}</span
		>
		<span
			v-if="online"
			class="absolute bottom-0 right-0 block h-2 w-2 rounded-full bg-online ring-2 ring-raised"
			aria-label="Online"
			role="img"
		/>
	</span>
</template>

<script setup>
import { computed } from "vue";
import { fmt } from "./format.js";

const props = defineProps({
	name: { type: String, default: "" },
	size: { type: String, default: "md" }, // sm | md | lg
	online: { type: Boolean, default: false },
});

const initials = computed(() => fmt.initials(props.name));
const box = computed(() => ({ sm: "h-6 w-6", md: "h-8 w-8", lg: "h-10 w-10" }[props.size]));
const textClass = computed(() => ({ sm: "text-caption", md: "text-label-sm", lg: "text-label" }[props.size]));
</script>
