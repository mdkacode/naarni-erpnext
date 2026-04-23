<template>
  <RoleBoundary :roles="['Technician', 'Service Engineer', 'Depot Manager']">
    <JobCardCreationWizard />
    <template #fallback>
      <div class="text-center py-16 text-gray-500">
        You do not have permission to perform inspections.
      </div>
    </template>
  </RoleBoundary>
</template>

<script setup>
import RoleBoundary from "../components/RoleBoundary.vue";
import JobCardCreationWizard from "../components/JobCardCreationWizard.vue";
</script>
