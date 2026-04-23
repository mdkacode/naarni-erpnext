<template>
  <!--
    HealthScoreCard — compact Pre/Post PMS score display per PRD p.7.

    Shows three tiles (Pre, Post, Improvement) and, when category-level data
    is available, an expandable per-category breakdown. Safe to render before
    any scoring has happened: the tiles gracefully show "—".
  -->
  <div class="bg-white border border-gray-200 rounded-xl p-4">
    <h3 class="text-sm font-semibold text-gray-800 mb-3">Vehicle Health Score</h3>

    <div class="grid grid-cols-3 gap-2">
      <div class="text-center p-3 rounded-lg bg-amber-50">
        <div class="text-xl font-bold text-amber-800">
          {{ formatPct(preScore) }}
        </div>
        <div class="text-xs text-amber-700 uppercase tracking-wide mt-1">Pre-PMS</div>
      </div>
      <div class="text-center p-3 rounded-lg bg-green-50">
        <div class="text-xl font-bold text-green-800">
          {{ formatPct(postScore) }}
        </div>
        <div class="text-xs text-green-700 uppercase tracking-wide mt-1">Post-PMS</div>
      </div>
      <div class="text-center p-3 rounded-lg bg-blue-50">
        <div class="text-xl font-bold text-blue-800">
          {{ formatImprovement(improvement) }}
        </div>
        <div class="text-xs text-blue-700 uppercase tracking-wide mt-1">Improvement</div>
      </div>
    </div>

    <details v-if="categoryScores.length" class="mt-3">
      <summary class="cursor-pointer text-xs text-gray-500 select-none">
        Per-category breakdown ({{ categoryScores.length }})
      </summary>
      <div class="mt-2 space-y-1">
        <div
          v-for="row in categoryScores"
          :key="row.category"
          class="flex items-center justify-between text-xs border-b border-gray-100 py-1.5"
        >
          <span class="font-medium text-gray-700">{{ row.category }}</span>
          <div class="flex items-center gap-2 text-gray-600">
            <span>{{ formatPct(row.pre_pms_score) }}</span>
            <span class="text-gray-300">→</span>
            <span>{{ formatPct(row.post_pms_score) }}</span>
            <span
              class="w-16 text-right font-medium"
              :class="improvementColor(row.improvement)"
            >
              {{ formatImprovement(row.improvement) }}
            </span>
          </div>
        </div>
      </div>
    </details>
  </div>
</template>

<script setup>
const props = defineProps({
  preScore: { type: [Number, String, null], default: null },
  postScore: { type: [Number, String, null], default: null },
  improvement: { type: [Number, String, null], default: null },
  categoryScores: { type: Array, default: () => [] },
});

function formatPct(val) {
  if (val === null || val === undefined || val === "") return "—";
  const n = Number(val);
  if (Number.isNaN(n)) return "—";
  return `${n.toFixed(1)}%`;
}

function formatImprovement(val) {
  if (val === null || val === undefined || val === "") return "—";
  const n = Number(val);
  if (Number.isNaN(n)) return "—";
  const sign = n > 0 ? "+" : "";
  return `${sign}${n.toFixed(1)}%`;
}

function improvementColor(val) {
  if (val == null || val === "") return "text-gray-500";
  const n = Number(val);
  if (n > 0) return "text-green-700";
  if (n < 0) return "text-red-700";
  return "text-gray-500";
}
</script>
