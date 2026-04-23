<template>
  <div class="max-w-5xl mx-auto px-4 py-6">
    <div v-if="loading" class="text-center py-16 text-gray-400">Loading your fleet...</div>

    <template v-else-if="data">
      <!-- Welcome Header -->
      <div class="mb-8">
        <h1 class="text-2xl font-bold text-gray-900">Welcome, {{ data.customer_name }}</h1>
        <p class="text-gray-500 mt-1">Your NaArNi Electric Fleet at a glance</p>
      </div>

      <!-- Stats Cards -->
      <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
        <div class="bg-gradient-to-br from-brand-500 to-brand-700 text-white rounded-2xl p-5">
          <div class="text-4xl font-extrabold">{{ data.vehicles?.length || 0 }}</div>
          <div class="text-sm opacity-80 mt-1">Total Buses</div>
        </div>
        <div class="bg-gradient-to-br from-amber-400 to-amber-600 text-white rounded-2xl p-5">
          <div class="text-4xl font-extrabold">{{ data.active_cards?.length || 0 }}</div>
          <div class="text-sm opacity-80 mt-1">Active Service</div>
        </div>
        <div class="bg-gradient-to-br from-green-400 to-green-600 text-white rounded-2xl p-5">
          <div class="text-4xl font-extrabold">{{ data.history?.length || 0 }}</div>
          <div class="text-sm opacity-80 mt-1">Completed</div>
        </div>
        <div class="bg-gradient-to-br from-violet-400 to-violet-600 text-white rounded-2xl p-5">
          <div class="text-4xl font-extrabold">{{ formatCurrencyShort(data.cost_totals?.total_actual) }}</div>
          <div class="text-sm opacity-80 mt-1">Total Spent</div>
        </div>
      </div>

      <!-- Active Job Cards -->
      <div v-if="data.active_cards?.length" class="mb-8">
        <h2 class="text-lg font-bold text-gray-900 mb-4">Active Service Cards</h2>
        <div class="grid gap-4 md:grid-cols-2">
          <div v-for="card in data.active_cards" :key="card.name"
            @click="$router.push(`/service-portal/job-card/${card.name}`)"
            class="bg-white rounded-2xl border-2 p-5 cursor-pointer hover:shadow-lg transition-all"
            :class="card.sla_breached ? 'border-red-300' : 'border-gray-100'">
            <div class="flex items-start justify-between mb-3">
              <div>
                <p class="text-xs font-mono text-gray-400">{{ card.name }}</p>
                <p class="text-lg font-bold text-gray-900">{{ formatVehicle(card.vehicle_number) }}</p>
                <p class="text-sm text-gray-500">{{ card.vehicle_make_model }}</p>
              </div>
              <StatusBadge :state="card.workflow_state" />
            </div>
            <div class="flex items-center justify-between text-sm">
              <span class="text-gray-500">{{ card.job_card_type }}</span>
              <span class="font-semibold" :class="card.priority === 'Urgent' ? 'text-red-600' : 'text-gray-700'">{{ card.priority }}</span>
            </div>
            <div v-if="card.estimated_cost" class="mt-2 text-sm text-gray-500">
              Est: <span class="font-semibold text-gray-800">{{ formatCurrency(card.estimated_cost) }}</span>
            </div>
            <div v-if="card.sla_breached" class="mt-2 text-xs text-red-600 font-semibold bg-red-50 rounded-lg px-3 py-1 text-center">
              SLA Breached — Please contact depot
            </div>
            <!-- Approval CTA -->
            <div v-if="card.workflow_state === 'Awaiting Customer Approval'"
              class="mt-3 p-3 bg-violet-50 rounded-xl text-center">
              <p class="text-sm font-semibold text-violet-800">Your approval is needed</p>
              <button class="mt-2 px-4 py-1.5 bg-violet-600 text-white text-sm rounded-lg font-medium hover:bg-violet-700">
                Review Estimate
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Fleet Health (only buses with PMS history) -->
      <div v-if="data.fleet_health?.length" class="mb-8">
        <h2 class="text-lg font-bold text-gray-900 mb-4">Fleet Health Scores</h2>
        <div class="bg-white rounded-2xl border p-5">
          <div class="space-y-4">
            <div v-for="bus in data.fleet_health" :key="bus.vehicle_number" class="flex items-center gap-4">
              <div class="w-36 shrink-0">
                <p class="font-bold text-sm text-gray-900">{{ formatVehicle(bus.vehicle_number) }}</p>
                <p class="text-2xs text-gray-500">{{ bus.vehicle_make_model }}</p>
              </div>
              <!-- Health bar -->
              <div class="flex-1">
                <div class="bg-gray-100 rounded-full h-6 overflow-hidden relative">
                  <div class="h-full rounded-full flex items-center pl-3 transition-all"
                    :class="healthBarColor(bus.latest_health_score)"
                    :style="{ width: `${bus.latest_health_score || 0}%` }">
                    <span class="text-white text-xs font-bold">{{ Math.round(bus.latest_health_score || 0) }}%</span>
                  </div>
                </div>
              </div>
              <div class="text-2xs text-gray-400 w-24 text-right shrink-0">
                Last: {{ formatDateShort(bus.last_service_date) }}
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Vehicles List -->
      <div class="mb-8">
        <h2 class="text-lg font-bold text-gray-900 mb-4">Your Fleet</h2>
        <div class="grid gap-3 md:grid-cols-3">
          <div v-for="v in data.vehicles" :key="v.name"
            class="bg-white rounded-xl border p-4 hover:shadow-md transition-shadow">
            <p class="font-bold text-gray-900">{{ formatVehicle(v.registration_number) }}</p>
            <p class="text-sm text-gray-500">{{ v.make_model }}</p>
            <div class="mt-2 flex items-center gap-2 text-xs text-gray-400">
              <span class="px-2 py-0.5 bg-green-50 text-green-700 rounded-full font-medium">{{ v.fuel_type }}</span>
              <span>{{ v.year_of_manufacture }}</span>
              <span>{{ v.color }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Service History -->
      <div v-if="data.history?.length" class="mb-8">
        <h2 class="text-lg font-bold text-gray-900 mb-4">Service History</h2>
        <div class="bg-white rounded-2xl border overflow-x-auto">
          <table class="min-w-full text-sm">
            <thead class="bg-gray-50">
              <tr>
                <th class="px-4 py-3 text-left text-gray-600">Job Card</th>
                <th class="px-4 py-3 text-left text-gray-600">Vehicle</th>
                <th class="px-4 py-3 text-left text-gray-600">Type</th>
                <th class="px-4 py-3 text-right text-gray-600">Pre-PMS</th>
                <th class="px-4 py-3 text-right text-gray-600">Post-PMS</th>
                <th class="px-4 py-3 text-right text-gray-600">Cost</th>
                <th class="px-4 py-3 text-left text-gray-600">Closed</th>
                <th class="px-4 py-3 text-center text-gray-600">Report</th>
              </tr>
            </thead>
            <tbody class="divide-y">
              <tr v-for="h in data.history" :key="h.name">
                <td class="px-4 py-3 font-mono text-brand-600">{{ h.name }}</td>
                <td class="px-4 py-3 font-semibold">{{ formatVehicle(h.vehicle_number) }}</td>
                <td class="px-4 py-3">{{ h.job_card_type }}</td>
                <td class="px-4 py-3 text-right">
                  <span v-if="h.pre_pms_score" class="font-semibold">{{ Math.round(h.pre_pms_score) }}%</span>
                  <span v-else class="text-gray-300">—</span>
                </td>
                <td class="px-4 py-3 text-right">
                  <span v-if="h.post_pms_score" class="font-semibold text-green-600">{{ Math.round(h.post_pms_score) }}%</span>
                  <span v-else class="text-gray-300">—</span>
                </td>
                <td class="px-4 py-3 text-right font-medium">{{ formatCurrency(h.actual_cost || h.estimated_cost) }}</td>
                <td class="px-4 py-3 text-gray-500">{{ formatDateShort(h.closed_at) }}</td>
                <td class="px-4 py-3 text-center">
                  <a :href="`/printview?doctype=Job Card&name=${h.name}&format=PMS Report`" target="_blank"
                    class="text-brand-600 hover:underline text-xs font-medium">PDF</a>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { callAPI } from "../utils/api.js";
import StatusBadge from "../components/StatusBadge.vue";

const data = ref(null);
const loading = ref(true);

onMounted(async () => {
  try {
    const res = await callAPI("dashboard.get_customer_dashboard");
    data.value = res.data;
  } finally { loading.value = false; }
});

function formatVehicle(num) {
  if (!num) return "";
  let c = num.replace(/[-\s]/g, "").toUpperCase();
  let m = c.match(/^([A-Z]{2})(\d{1,2})([A-Z]{1,3})(\d{1,4})$/);
  return m ? `${m[1]} ${m[2].padStart(2,"0")} ${m[3]} ${m[4]}` : num;
}
function formatCurrency(v) {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", minimumFractionDigits: 0 }).format(v || 0);
}
function formatCurrencyShort(v) {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", notation: "compact", minimumFractionDigits: 0 }).format(v || 0);
}
function formatDateShort(d) {
  if (!d) return "—";
  return new Date(d).toLocaleDateString("en-IN", { day: "numeric", month: "short" });
}
function healthBarColor(score) {
  if (score >= 80) return "bg-green-500";
  if (score >= 60) return "bg-amber-500";
  return "bg-red-500";
}
</script>
