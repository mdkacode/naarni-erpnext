<!--
  HealthScoreCard — before, after, and the difference between them.

  Three tiles that used to be amber, green and blue regardless of what the
  numbers said, so a vehicle that got *worse* still showed a green "post" tile.
  The scores now take their colour from the shared score thresholds, and the
  improvement takes its colour from its sign — which is the only thing about an
  improvement figure that matters at a glance.
-->
<template>
	<NCard title="Vehicle health score">
		<div class="grid grid-cols-3 gap-2">
			<div v-for="tile in tiles" :key="tile.label" class="rounded-sm bg-sunken p-3 text-center">
				<p class="tabular text-title-lg" :class="tile.class">{{ tile.value }}</p>
				<p class="mt-0.5 text-caption uppercase tracking-wide text-muted">{{ tile.label }}</p>
			</div>
		</div>

		<details v-if="categoryScores.length" class="mt-3 border-t border-hairline pt-3">
			<summary class="cursor-pointer select-none text-body-sm text-muted hover:text-ink">
				Per-category breakdown ({{ categoryScores.length }})
			</summary>
			<ul class="mt-2 divide-y divide-hairline">
				<li
					v-for="row in categoryScores"
					:key="row.category"
					class="flex items-center justify-between gap-3 py-1.5"
				>
					<span class="min-w-0 truncate text-body-sm text-ink">{{ row.category }}</span>
					<div class="tabular flex shrink-0 items-center gap-2 text-body-sm text-muted">
						<span>{{ pct(row.pre_pms_score) }}</span>
						<NIcon name="arrow-right" :size="12" class="text-subtle" />
						<span :class="semanticClasses(scoreSemantic(row.post_pms_score)).text">{{
							pct(row.post_pms_score)
						}}</span>
						<span class="w-14 text-right font-semibold" :class="deltaClass(row.improvement)">
							{{ delta(row.improvement) }}
						</span>
					</div>
				</li>
			</ul>
		</details>
	</NCard>
</template>

<script setup>
import { computed } from "vue";
import { NCard, NIcon, semanticClasses, scoreSemantic, EMPTY } from "../ui/index.js";

const props = defineProps({
	preScore: { type: [Number, String, null], default: null },
	postScore: { type: [Number, String, null], default: null },
	improvement: { type: [Number, String, null], default: null },
	categoryScores: { type: Array, default: () => [] },
});

/* A health score keeps one decimal — it is computed from a weighted checklist and
   rounding it to a whole number makes two genuinely different vehicles tie. */
function pct(val) {
	const n = Number(val);
	return val === null || val === undefined || val === "" || Number.isNaN(n) ? EMPTY : `${n.toFixed(1)}%`;
}

function delta(val) {
	const n = Number(val);
	if (val === null || val === undefined || val === "" || Number.isNaN(n)) return EMPTY;
	return `${n > 0 ? "+" : ""}${n.toFixed(1)}%`;
}

function deltaClass(val) {
	const n = Number(val);
	if (!Number.isFinite(n) || n === 0) return "text-muted";
	return n > 0 ? semanticClasses("positive").text : semanticClasses("critical").text;
}

const tiles = computed(() => [
	{
		label: "Pre-PMS",
		value: pct(props.preScore),
		class: semanticClasses(scoreSemantic(props.preScore)).text,
	},
	{
		label: "Post-PMS",
		value: pct(props.postScore),
		class: semanticClasses(scoreSemantic(props.postScore)).text,
	},
	{ label: "Improvement", value: delta(props.improvement), class: deltaClass(props.improvement) },
]);
</script>
