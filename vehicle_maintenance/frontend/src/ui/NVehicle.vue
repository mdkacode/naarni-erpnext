<!--
  NVehicle — a registration number, rendered the one way it is rendered anywhere.

  Grouped into its four parts, and the **serial is heavier and accent-coloured**
  because that is the part a depot actually says out loud. Everyone at a site
  shares a state code and usually a district code; nobody identifies a bus by
  "TN 01".
-->
<template>
	<span class="whitespace-nowrap font-mono tabular" :class="sizeClass" :title="full">
		<span class="text-muted">{{ parts.prefix }}</span
		><span class="font-bold tracking-wide" :class="accent ? 'text-accent' : 'text-ink'">{{
			parts.serial
		}}</span>
	</span>
</template>

<script setup>
import { computed } from "vue";
import { fmt } from "./format.js";

const props = defineProps({
	value: { type: String, default: "" },
	size: { type: String, default: "md" }, // sm | md | lg
	accent: { type: Boolean, default: true },
});

const parts = computed(() => fmt.vehicleParts(props.value));
const full = computed(() => fmt.vehicle(props.value));
const sizeClass = computed(() => ({ sm: "text-label-sm", md: "text-body-sm", lg: "text-title" }[props.size]));
</script>
