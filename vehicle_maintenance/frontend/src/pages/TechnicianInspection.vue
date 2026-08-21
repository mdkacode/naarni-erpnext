<template>
	<RoleBoundary :roles="['Technician', 'Service Engineer', 'Depot Manager']">
		<div class="p-5">
			<JobCardCreationWizard />
		</div>
		<template #fallback>
			<NEmptyState
				icon="lock"
				title="You cannot start an inspection"
				body="Inspections are raised by technicians, service engineers and depot managers."
			/>
		</template>
	</RoleBoundary>
</template>

<script setup>
import RoleBoundary from "../components/RoleBoundary.vue";
import JobCardCreationWizard from "../components/JobCardCreationWizard.vue";
import { NEmptyState } from "../ui/index.js";
</script>
