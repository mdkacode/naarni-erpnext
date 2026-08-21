<!--
  NTimestamp — a labelled moment, or the fact that it has not happened yet.

  A breakdown record is mostly a sequence of these: travel started, arrived,
  trial trip ended, vehicle handed over. Rendering each one as a bare
  `toLocaleString()` gave four different date formats on the same card.
-->
<template>
	<div class="min-w-0">
		<p class="text-caption uppercase tracking-wide text-muted">{{ label }}</p>
		<p class="tabular truncate text-body-sm" :class="value ? 'text-ink' : 'text-subtle'">
			{{ value ? fmt.dateTime(value) : EMPTY }}
		</p>
	</div>
</template>

<script setup>
import { fmt, EMPTY } from "./format.js";

defineProps({
	label: { type: String, required: true },
	value: { type: [String, Object, null], default: null },
});
</script>
