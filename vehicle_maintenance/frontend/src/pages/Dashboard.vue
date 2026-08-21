<!--
  Job cards — the list every internal role lands on.

  Was a grid of cards, three across, showing five facts each. A table shows the
  same five facts for forty records in the same space, and a depot manager
  looking for "the urgent one on the Salem bus" is scanning, not browsing.
-->
<template>
	<div>
		<NPageHeader title="Job cards" :subtitle="subtitle">
			<template #actions>
				<RoleBoundary :roles="['Service Engineer', 'Depot Manager']">
					<NButton variant="primary" icon="plus" to="/service-portal/job-card/new"
						>New job card</NButton
					>
				</RoleBoundary>
			</template>

			<template #toolbar>
				<NSearch v-model="search" placeholder="Vehicle, customer or job card" class="w-72" />
				<NSegmented v-model="activeFilter" :options="tabs" />
				<span class="flex-1" />
				<NIconButton
					icon="refresh-cw"
					label="Refresh"
					:loading="jobCards.loading"
					@click="jobCards.reload()"
				/>
			</template>
		</NPageHeader>

		<div class="p-5">
			<NTable
				:columns="columns"
				:rows="visibleRows"
				:loading="jobCards.loading && !jobCards.data"
				:row-to="(row) => `/service-portal/job-card/${row.name}`"
				:row-class="(row) => (row.sla_breached ? 'bg-critical-tint' : '')"
				empty-icon="clipboard-list"
				:empty-title="search ? 'No job cards match your search' : 'No job cards here'"
				:empty-body="
					search
						? 'Try a different vehicle number, customer or job card ID.'
						: 'Job cards raised for this depot will appear here.'
				"
			>
				<template #emptyAction>
					<NButton v-if="search" icon="x" @click="search = ''">Clear search</NButton>
				</template>

				<template #cell:vehicle_number="{ row }">
					<NVehicle :value="row.vehicle_number" />
				</template>

				<template #cell:workflow_state="{ row }">
					<div class="flex items-center gap-1.5">
						<NStatus :state="row.workflow_state" />
						<NBadge v-if="row.sla_breached" semantic="critical" label="SLA" />
					</div>
				</template>

				<template #cell:priority="{ row }">
					<NPriority :value="row.priority" />
				</template>
			</NTable>
		</div>
	</div>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { createResource } from "frappe-ui";
import RoleBoundary from "../components/RoleBoundary.vue";
import {
	NPageHeader,
	NTable,
	NSearch,
	NSegmented,
	NButton,
	NIconButton,
	NStatus,
	NPriority,
	NBadge,
	NVehicle,
} from "../ui/index.js";

const tabs = [
	{ label: "All", value: "" },
	{ label: "Open", value: "Open" },
	{ label: "In progress", value: "WIP" },
	{ label: "Awaiting approval", value: "Awaiting Customer Approval" },
	{ label: "Awaiting parts", value: "Awaiting Parts" },
	{ label: "Verification", value: "Verification Pending" },
	{ label: "Closed", value: "Closed" },
];

const columns = [
	{ key: "name", label: "Job card", mono: true, width: "150px", link: true },
	{ key: "vehicle_number", label: "Vehicle", width: "140px" },
	{ key: "customer_name", label: "Customer" },
	{ key: "service_type", label: "Type", width: "160px" },
	{ key: "priority", label: "Priority", width: "110px" },
	{ key: "workflow_state", label: "Status", width: "190px" },
];

const activeFilter = ref("");
const search = ref("");

const jobCards = createResource({
	url: "vehicle_maintenance.api.job_card.get_my_job_cards",
	params: { status: "", limit: 50, offset: 0 },
	auto: true,
});

watch(activeFilter, (status) => {
	jobCards.update({ params: { status, limit: 50, offset: 0 } });
	jobCards.fetch();
});

const rows = computed(() => jobCards.data?.data || []);

/* Filtering client-side over the page already fetched: the server call is the
   status filter, and typing should not cost a round trip per keystroke. */
const visibleRows = computed(() => {
	const q = search.value.trim().toLowerCase();
	if (!q) return rows.value;
	return rows.value.filter((r) =>
		[r.name, r.vehicle_number, r.customer_name, r.service_type]
			.filter(Boolean)
			.some((v) => String(v).toLowerCase().includes(q))
	);
});

const subtitle = computed(() => {
	const total = rows.value.length;
	const shown = visibleRows.value.length;
	if (!total) return "";
	return shown === total ? `${total} job cards` : `${shown} of ${total} job cards`;
});
</script>
