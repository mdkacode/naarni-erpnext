<!-- NStatus — a workflow state, rendered the one way it is rendered everywhere.
     The raw enum never reaches the reader: `WIP` arrives as "In progress". -->
<template>
	<NBadge :semantic="stateSemantic(state)" :label="stateLabel(state)" :dot="dot" />
</template>

<script setup>
import NBadge from "./NBadge.vue";
import { stateSemantic, stateLabel } from "./semantic.js";

defineProps({
	state: { type: String, default: "" },
	dot: { type: Boolean, default: true },
});
</script>
