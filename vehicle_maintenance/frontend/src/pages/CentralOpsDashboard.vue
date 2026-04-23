<template>
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
    <h1 class="text-2xl font-bold text-gray-900 mb-6">Central Ops — Fleet Overview</h1>

    <div v-if="loading" class="text-center py-16 text-gray-400">Loading...</div>
    <template v-else-if="data">
      <!-- Top Stats -->
      <div class="grid grid-cols-2 md:grid-cols-5 gap-4 mb-8">
        <div class="bg-white rounded-xl border p-4 text-center">
          <div class="text-2xl font-extrabold text-brand-700">{{ data.total_buses }}</div>
          <div class="text-2xs text-gray-500 uppercase">Total Fleet</div>
        </div>
        <div class="bg-white rounded-xl border p-4 text-center">
          <div class="text-2xl font-extrabold text-amber-600">{{ data.buses_in_service }}</div>
          <div class="text-2xs text-gray-500 uppercase">In Service</div>
        </div>
        <div class="bg-white rounded-xl border p-4 text-center">
          <div class="text-2xl font-extrabold text-green-600">{{ data.total_buses - data.buses_in_service }}</div>
          <div class="text-2xs text-gray-500 uppercase">Available</div>
        </div>
        <div class="bg-white rounded-xl border p-4 text-center">
          <div class="text-2xl font-extrabold text-red-600">{{ totalBreaches }}</div>
          <div class="text-2xs text-gray-500 uppercase">SLA Breaches</div>
        </div>
        <div class="bg-white rounded-xl border p-4 text-center">
          <div class="text-2xl font-extrabold text-gray-800">{{ formatCurrency(data.cost_summary?.total_estimated) }}</div>
          <div class="text-2xs text-gray-500 uppercase">Total Est. Cost</div>
        </div>
      </div>

      <!-- Depot-wise Grid -->
      <h2 class="text-sm font-semibold text-gray-500 uppercase tracking-wider mb-3">Depot-wise Status</h2>
      <div class="grid md:grid-cols-2 lg:grid-cols-3 gap-4 mb-8">
        <div v-for="depot in depotSummaries" :key="depot.name"
          class="bg-white rounded-xl border p-5 hover:shadow-md transition-shadow">
          <div class="flex items-center justify-between mb-3">
            <h3 class="font-bold text-gray-900">{{ depot.name }}</h3>
            <span v-if="depot.breaches > 0" class="text-xs font-bold text-red-600 bg-red-50 px-2 py-0.5 rounded-full">{{ depot.breaches }} breach{{ depot.breaches > 1 ? "es" : "" }}</span>
          </div>
          <div class="grid grid-cols-3 gap-2 text-center text-xs">
            <div>
              <div class="text-lg font-bold text-brand-600">{{ depot.open }}</div>
              <div class="text-gray-500">Open</div>
            </div>
            <div>
              <div class="text-lg font-bold text-amber-600">{{ depot.wip }}</div>
              <div class="text-gray-500">WIP</div>
            </div>
            <div>
              <div class="text-lg font-bold text-green-600">{{ depot.closed }}</div>
              <div class="text-gray-500">Closed</div>
            </div>
          </div>
          <div v-if="depot.avg_tat" class="mt-3 pt-3 border-t text-xs text-gray-500 flex justify-between">
            <span>Avg TAT</span>
            <span class="font-bold text-gray-800">{{ depot.avg_tat }} hrs</span>
          </div>
        </div>
      </div>

      <!-- Job Type Distribution -->
      <div class="grid md:grid-cols-2 gap-6 mb-8">
        <div class="bg-white rounded-xl border p-5">
          <h2 class="text-sm font-semibold text-gray-500 uppercase mb-4">Job Card Types</h2>
          <div class="space-y-3">
            <div v-for="t in data.by_type" :key="t.job_card_type" class="flex items-center gap-3">
              <div class="w-32 text-sm font-medium text-gray-700 truncate">{{ t.job_card_type }}</div>
              <div class="flex-1 bg-gray-100 rounded-full h-5 overflow-hidden">
                <div class="h-full bg-brand-500 rounded-full flex items-center justify-end pr-2"
                  :style="{ width: `${(t.count / maxTypeCount) * 100}%` }">
                  <span class="text-white text-2xs font-bold">{{ t.count }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div class="bg-white rounded-xl border p-5">
          <h2 class="text-sm font-semibold text-gray-500 uppercase mb-4">TAT Performance by Depot</h2>
          <div class="space-y-3">
            <div v-for="t in data.tat_by_depot" :key="t.depot" class="flex items-center justify-between">
              <span class="text-sm text-gray-700">{{ t.depot }}</span>
              <div class="flex items-center gap-2">
                <span class="text-sm font-bold" :class="t.avg_hours <= 4 ? 'text-green-600' : t.avg_hours <= 8 ? 'text-amber-600' : 'text-red-600'">
                  {{ t.avg_hours }}h
                </span>
                <span class="text-2xs text-gray-400">({{ t.closed_count }} cards)</span>
              </div>
            </div>
            <div v-if="!data.tat_by_depot?.length" class="text-sm text-gray-400">No closed cards yet</div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from "vue";
import { callAPI } from "../utils/api.js";

const data = ref(null);
const loading = ref(true);

onMounted(async () => {
  try {
    const res = await callAPI("dashboard.get_central_ops_dashboard");
    data.value = res.data;
  } finally { loading.value = false; }
});

const totalBreaches = computed(() =>
  (data.value?.breaches_by_depot || []).reduce((s, b) => s + b.count, 0)
);
const maxTypeCount = computed(() =>
  Math.max(...(data.value?.by_type || []).map(t => t.count), 1)
);

const depotSummaries = computed(() => {
  if (!data.value) return [];
  const byDepot = {};
  for (const row of data.value.by_depot || []) {
    if (!byDepot[row.depot]) byDepot[row.depot] = { name: row.depot, open: 0, wip: 0, closed: 0 };
    if (row.workflow_state === "Open") byDepot[row.depot].open = row.count;
    else if (row.workflow_state === "WIP") byDepot[row.depot].wip = row.count;
    else if (row.workflow_state === "Closed") byDepot[row.depot].closed = row.count;
    else byDepot[row.depot].open += row.count;  // other active states count as open
  }
  for (const b of data.value.breaches_by_depot || []) {
    if (byDepot[b.depot]) byDepot[b.depot].breaches = b.count;
  }
  for (const t of data.value.tat_by_depot || []) {
    if (byDepot[t.depot]) byDepot[t.depot].avg_tat = t.avg_hours;
  }
  return Object.values(byDepot);
});

function formatCurrency(v) {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 0, notation: "compact" }).format(v || 0);
}
</script>
