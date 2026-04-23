<template>
  <span :class="badgeClass">{{ label }}</span>
</template>

<script setup>
import { computed } from "vue";
import { stateBadgeClass } from "../utils/api.js";

const props = defineProps({
  state: { type: String, required: true },
});

const badgeClass = computed(() => stateBadgeClass(props.state));

const label = computed(() => {
  const map = {
    Open: "Open",
    WIP: "In Progress",
    "Awaiting Customer Approval": "Awaiting Approval",
    "Awaiting Parts": "Awaiting Parts",
    "Parts Fitted": "Parts Fitted",
    "Verification Pending": "Verification",
    Closed: "Closed",
  };
  return map[props.state] || props.state;
});
</script>
