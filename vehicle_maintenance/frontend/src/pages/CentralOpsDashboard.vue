<!--
  Fleet overview — every depot at once.

  Five figures across the top, then one card per depot. The depot cards are the
  point of the page, so they get the density: three counts, the breach flag, and
  turnaround. Anything else belongs on the depot's own dashboard.
-->
<template>
	<div>
		<NPageHeader title="Fleet overview" subtitle="All depots, live">
			<template #actions>
				<NIconButton icon="refresh-cw" label="Refresh" :loading="loading" @click="load" />
			</template>
		</NPageHeader>

		<div class="space-y-6 p-5">
			<div class="grid grid-cols-2 gap-3 lg:grid-cols-5">
				<template v-if="loading">
					<NSkeleton v-for="i in 5" :key="i" variant="block" height="h-[92px]" :delay="0" />
				</template>
				<template v-else>
					<NStat label="Total fleet" :value="fmt.number(data?.total_buses)" icon="bus" />
					<NStat label="In service" :value="fmt.number(data?.buses_in_service)" icon="wrench" />
					<NStat label="Available" :value="fmt.number(available)" icon="circle-check" />
					<NStat
						label="SLA breaches"
						:value="fmt.number(totalBreaches)"
						icon="shield-alert"
						:semantic="totalBreaches > 0 ? 'critical' : ''"
					/>
					<NStat
						label="Estimated cost"
						:value="fmt.moneyShort(data?.cost_summary?.total_estimated)"
						icon="indian-rupee"
					/>
				</template>
			</div>

			<NSection title="Depots">
				<div v-if="loading" class="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
					<NSkeleton v-for="i in 6" :key="i" variant="block" height="h-32" :delay="0" />
				</div>
				<NEmptyState
					v-else-if="!depotSummaries.length"
					icon="building-2"
					title="No depot activity"
					body="No job cards have been raised at any depot yet."
				/>
				<div v-else class="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
					<NCard v-for="depot in depotSummaries" :key="depot.name" :flagged="depot.breaches > 0">
						<div class="mb-3 flex items-start justify-between gap-2">
							<h3 class="text-title-sm text-ink">{{ depot.name }}</h3>
							<NBadge
								v-if="depot.breaches"
								semantic="critical"
								:label="`${depot.breaches} breach${depot.breaches > 1 ? 'es' : ''}`"
							/>
						</div>

						<dl class="grid grid-cols-3 gap-2 border-t border-hairline pt-3">
							<div v-for="cell in cells(depot)" :key="cell.label">
								<dd class="tabular text-title-lg text-ink">{{ fmt.number(cell.value) }}</dd>
								<dt class="text-caption uppercase tracking-wide text-muted">
									{{ cell.label }}
								</dt>
							</div>
						</dl>

						<div
							v-if="depot.avg_tat"
							class="mt-3 flex items-center justify-between border-t border-hairline pt-2.5"
						>
							<span class="text-body-sm text-muted">Average turnaround</span>
							<span
								class="text-body-sm font-semibold"
								:class="semanticClasses(tatSemantic(depot.avg_tat)).text"
							>
								{{ fmt.duration(depot.avg_tat) }}
							</span>
						</div>
					</NCard>
				</div>
			</NSection>

			<div class="grid gap-4 lg:grid-cols-2">
				<NCard title="Job card types">
					<NSkeleton v-if="loading" :count="4" :widths="['75%', '60%', '85%', '40%']" />
					<NEmptyState v-else-if="!data?.by_type?.length" icon="list" title="No job cards yet" />
					<ul v-else class="space-y-2.5">
						<li v-for="t in data.by_type" :key="t.job_card_type" class="flex items-center gap-3">
							<span class="w-32 shrink-0 truncate text-body-sm text-muted">{{
								t.job_card_type
							}}</span>
							<NMeter
								class="flex-1"
								:value="t.count"
								:max="maxTypeCount"
								semantic="active"
								mode="count"
								:label="t.job_card_type"
							/>
						</li>
					</ul>
				</NCard>

				<NCard title="Turnaround by depot">
					<NSkeleton v-if="loading" :count="4" :widths="['70%', '55%', '80%', '45%']" />
					<NEmptyState
						v-else-if="!data?.tat_by_depot?.length"
						icon="clock"
						title="No closed cards yet"
						body="Turnaround is measured once a job card closes."
					/>
					<NKeyValue v-else :items="tatItems" />
				</NCard>
			</div>
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
	NBadge,
	NMeter,
	NKeyValue,
	NEmptyState,
	NSkeleton,
	fmt,
	tatSemantic,
	semanticClasses,
} from "../ui/index.js";

const data = ref(null);
const loading = ref(true);

async function load() {
	loading.value = true;
	try {
		const res = await callAPI("dashboard.get_central_ops_dashboard");
		data.value = res.data;
	} finally {
		loading.value = false;
	}
}
onMounted(load);

const available = computed(() => (data.value?.total_buses || 0) - (data.value?.buses_in_service || 0));
const totalBreaches = computed(() => (data.value?.breaches_by_depot || []).reduce((s, b) => s + b.count, 0));
const maxTypeCount = computed(() => Math.max(...(data.value?.by_type || []).map((t) => t.count), 1));

const depotSummaries = computed(() => {
	if (!data.value) return [];
	const byDepot = {};
	for (const row of data.value.by_depot || []) {
		if (!byDepot[row.depot])
			byDepot[row.depot] = { name: row.depot, open: 0, wip: 0, closed: 0, breaches: 0 };
		if (row.workflow_state === "Open") byDepot[row.depot].open = row.count;
		else if (row.workflow_state === "WIP") byDepot[row.depot].wip = row.count;
		else if (row.workflow_state === "Closed") byDepot[row.depot].closed = row.count;
		else byDepot[row.depot].open += row.count; // other active states count as open
	}
	for (const b of data.value.breaches_by_depot || [])
		if (byDepot[b.depot]) byDepot[b.depot].breaches = b.count;
	for (const t of data.value.tat_by_depot || [])
		if (byDepot[t.depot]) byDepot[t.depot].avg_tat = t.avg_hours;
	return Object.values(byDepot);
});

const tatItems = computed(() =>
	(data.value?.tat_by_depot || []).map((t) => ({
		label: t.depot,
		value: `${fmt.duration(t.avg_hours)} · ${t.closed_count} cards`,
		semantic: tatSemantic(t.avg_hours),
	}))
);

function cells(depot) {
	return [
		{ label: "Open", value: depot.open },
		{ label: "WIP", value: depot.wip },
		{ label: "Closed", value: depot.closed },
	];
}
</script>
