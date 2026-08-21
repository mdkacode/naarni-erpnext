<!-- One route, two readers: a customer gets the tracking timeline, an internal
     role gets the working detail page. Neither ever sees the other's version. -->
<template>
	<div v-if="loading" class="p-5">
		<NSkeleton :count="3" variant="block" height="h-24" :delay="0" />
	</div>

	<CustomerTrackingTimeline v-else-if="isCustomerOnly" :job-card-name="name" />

	<JobCardDetail v-else-if="isInternal" :name="name" />

	<NEmptyState
		v-else
		icon="lock"
		title="You cannot view this job card"
		body="It belongs to a depot or customer you are not part of."
	/>
</template>

<script setup>
import { computed } from "vue";
import { useSession } from "../composables/useSession.js";
import { hasAnyRole } from "../utils/permissions.js";
import CustomerTrackingTimeline from "../components/CustomerTrackingTimeline.vue";
import JobCardDetail from "./JobCardDetail.vue";
import { NEmptyState, NSkeleton } from "../ui/index.js";

defineProps({ name: { type: String, required: true } });

const { roles, loading } = useSession();

const isInternal = computed(() =>
	hasAnyRole(roles, [
		"Depot Manager",
		"Service Engineer",
		"Technician",
		"Central Ops",
		"Administrator",
		"System Manager",
	])
);

const isCustomerOnly = computed(() => hasAnyRole(roles, ["Customer"]) && !isInternal.value);
</script>
