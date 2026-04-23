<template>
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
    <h1 class="text-2xl font-bold text-gray-900 mb-6">My Job Cards</h1>

    <!-- Status filter tabs -->
    <div class="flex flex-wrap gap-2 mb-6">
      <button
        v-for="tab in tabs"
        :key="tab.value"
        @click="activeFilter = tab.value"
        class="px-3 py-1.5 text-sm font-medium rounded-full transition-colors"
        :class="
          activeFilter === tab.value
            ? 'bg-brand-600 text-white'
            : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
        "
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- Loading state -->
    <div v-if="jobCards.loading" class="text-center py-12 text-gray-400">
      Loading...
    </div>

    <!-- Empty state -->
    <div
      v-else-if="!jobCards.data?.data?.length"
      class="text-center py-12 text-gray-400"
    >
      No job cards found.
    </div>

    <!-- Card grid -->
    <div v-else class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <router-link
        v-for="card in jobCards.data.data"
        :key="card.name"
        :to="`/service-portal/job-card/${card.name}`"
        class="bg-white border border-gray-200 rounded-xl p-4 hover:shadow-md transition-shadow"
      >
        <div class="flex items-start justify-between mb-3">
          <div>
            <p class="text-sm font-mono text-gray-500">{{ card.name }}</p>
            <p class="font-semibold text-gray-900">{{ card.vehicle_number }}</p>
          </div>
          <StatusBadge :state="card.workflow_state" />
        </div>
        <div class="space-y-1 text-sm text-gray-600">
          <p>{{ card.customer_name }}</p>
          <p>{{ card.service_type }}</p>
        </div>
        <div class="mt-3 flex items-center justify-between text-xs text-gray-400">
          <span :class="card.priority === 'Urgent' ? 'text-red-500 font-medium' : ''">
            {{ card.priority }}
          </span>
        </div>
      </router-link>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from "vue";
import { createResource } from "frappe-ui";
import StatusBadge from "../components/StatusBadge.vue";

const tabs = [
  { label: "All", value: "" },
  { label: "Open", value: "Open" },
  { label: "In Progress", value: "WIP" },
  { label: "Awaiting Approval", value: "Awaiting Customer Approval" },
  { label: "Awaiting Parts", value: "Awaiting Parts" },
  { label: "Verification", value: "Verification Pending" },
  { label: "Closed", value: "Closed" },
];

const activeFilter = ref("");

const jobCards = createResource({
  url: "vehicle_maintenance.api.job_card.get_my_job_cards",
  params: { status: "", limit: 50, offset: 0 },
  auto: true,
});

watch(activeFilter, (status) => {
  jobCards.update({ params: { status, limit: 50, offset: 0 } });
  jobCards.fetch();
});
</script>
