<!--
  Depot dashboard.

  Four figures, two breakdowns, and the live list. The stats are plain cards with
  the number carrying the weight — the version this replaces coloured each figure
  by category (brand / green / red / amber), which meant the SLA breach count and
  the closed count competed for attention even when there were zero breaches.
  Now only the breach figure takes colour, and only when it is above zero.
-->
<template>
	<div>
		<NPageHeader title="Depot" subtitle="Job cards raised at your depot">
			<template #actions>
				<NIconButton icon="refresh-cw" label="Refresh" :loading="loading" @click="load" />
			</template>
		</NPageHeader>

		<div class="space-y-6 p-5">
			<div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
				<template v-if="loading">
					<NSkeleton v-for="i in 4" :key="i" variant="block" height="h-[92px]" :delay="0" />
				</template>
				<template v-else>
					<NStat label="Open cards" :value="fmt.number(data?.total_open)" icon="clipboard-list" />
					<NStat label="Closed" :value="fmt.number(data?.total_closed)" icon="circle-check" />
					<NStat
						label="SLA breaches"
						:value="fmt.number(data?.sla_breached)"
						icon="shield-alert"
						:semantic="data?.sla_breached > 0 ? 'critical' : ''"
						:hint="data?.sla_breached > 0 ? 'Needs attention today' : 'All within target'"
					/>
					<NStat label="Urgent or high" :value="fmt.number(urgentCount)" icon="alert-triangle" />
				</template>
			</div>

			<div class="grid gap-4 lg:grid-cols-2">
				<NCard title="Cards by status">
					<NSkeleton v-if="loading" :count="5" :widths="['70%', '55%', '80%', '45%', '65%']" />
					<ul v-else class="divide-y divide-hairline">
						<li
							v-for="s in data?.states || []"
							:key="s.workflow_state"
							class="flex items-center justify-between py-2"
						>
							<NStatus :state="s.workflow_state" />
							<span class="tabular text-title-sm text-ink">{{ fmt.number(s.count) }}</span>
						</li>
					</ul>
				</NCard>

				<NCard title="Priority">
					<NSkeleton v-if="loading" :count="4" :widths="['80%', '60%', '70%', '40%']" />
					<ul v-else class="space-y-2.5">
						<li
							v-for="p in data?.priorities || []"
							:key="p.priority"
							class="flex items-center gap-3"
						>
							<span class="w-20 shrink-0 text-body-sm text-muted">{{ p.priority }}</span>
							<NMeter
								class="flex-1"
								:value="p.count"
								:max="maxPriorityCount"
								:semantic="prioritySemantic(p.priority)"
								mode="count"
								:label="`${p.priority} priority`"
							/>
						</li>
					</ul>
				</NCard>
			</div>

			<NSection title="Active job cards">
				<NTable
					:columns="columns"
					:rows="data?.recent_cards || []"
					:loading="loading"
					:row-to="(row) => `/service-portal/job-card/${row.name}`"
					empty-icon="clipboard-list"
					empty-title="No active job cards"
					empty-body="Everything raised at this depot has been closed."
				>
					<template #cell:vehicle_number="{ row }"
						><NVehicle :value="row.vehicle_number"
					/></template>
					<template #cell:priority="{ row }"><NPriority :value="row.priority" /></template>
					<template #cell:workflow_state="{ row }"
						><NStatus :state="row.workflow_state"
					/></template>
					<template #cell:sla_breached="{ row }">
						<NBadge
							:semantic="slaSemantic(row.sla_breached)"
							:label="row.sla_breached ? 'Breached' : 'On track'"
						/>
					</template>
				</NTable>
			</NSection>
		</div>
	</div>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { callAPI } from "../utils/api.js";
import {
	NPageHeader,
	NIconButton,
	NStat,
	NCard,
	NSection,
	NTable,
	NStatus,
	NPriority,
	NBadge,
	NVehicle,
	NMeter,
	NSkeleton,
	fmt,
	prioritySemantic,
	slaSemantic,
} from "../ui/index.js";

const columns = [
	{ key: "name", label: "Job card", mono: true, width: "150px", link: true },
	{ key: "vehicle_number", label: "Vehicle", width: "140px" },
	{ key: "job_card_type", label: "Type", width: "150px" },
	{ key: "customer_name", label: "Customer" },
	{ key: "priority", label: "Priority", width: "110px" },
	{ key: "workflow_state", label: "Status", width: "180px" },
	{ key: "sla_breached", label: "SLA", width: "120px" },
];

const data = ref(null);
const loading = ref(true);

async function load() {
	loading.value = true;
	try {
		const res = await callAPI("dashboard.get_depot_manager_dashboard");
		data.value = res.data;
	} finally {
		loading.value = false;
	}
}
onMounted(load);

const urgentCount = computed(() =>
	(data.value?.priorities || [])
		.filter((p) => p.priority === "Urgent" || p.priority === "High")
		.reduce((sum, p) => sum + p.count, 0)
);

const maxPriorityCount = computed(() => Math.max(...(data.value?.priorities || []).map((p) => p.count), 1));
</script>
