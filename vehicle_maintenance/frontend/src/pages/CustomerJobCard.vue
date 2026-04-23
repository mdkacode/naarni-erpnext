<template>
  <!-- Show loading while session/roles are being fetched -->
  <div v-if="loading" class="text-center py-16 text-gray-400">
    Loading...
  </div>

  <!-- Customer role: show simplified timeline -->
  <CustomerTrackingTimeline
    v-else-if="isCustomerOnly"
    :job-card-name="name"
  />

  <!-- Internal roles: show full detail page -->
  <JobCardDetail v-else-if="isInternal" :name="name" />

  <!-- No matching role -->
  <div v-else class="text-center py-16 text-gray-500">
    You do not have permission to view this job card.
  </div>
</template>

<script setup>
import { computed } from "vue";
import { useSession } from "../composables/useSession.js";
import { hasAnyRole } from "../utils/permissions.js";
import CustomerTrackingTimeline from "../components/CustomerTrackingTimeline.vue";
import JobCardDetail from "./JobCardDetail.vue";

const props = defineProps({
  name: { type: String, required: true },
});

const { roles, loading } = useSession();

const isInternal = computed(() =>
  hasAnyRole(roles, ["Depot Manager", "Service Engineer", "Technician", "Central Ops", "Administrator", "System Manager"])
);

const isCustomerOnly = computed(() =>
  hasAnyRole(roles, ["Customer"]) && !isInternal.value
);
</script>
