<!-- NPriority — priority, criticality and alert severity all render here, on the
     same ramp. "Urgent" on a ticket and "Critical" on an alert mean the same
     thing to the person reading them and must not arrive in two different reds. -->
<template>
	<NBadge v-if="value" :semantic="prioritySemantic(value)" :label="value" :dot="dot" />
</template>

<script setup>
import NBadge from "./NBadge.vue";
import { prioritySemantic } from "./semantic.js";

defineProps({
	value: { type: String, default: "" },
	dot: { type: Boolean, default: true },
});
</script>
