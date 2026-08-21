<!--
  My fleet — the customer's view.

  Customer-facing, so it runs comfortable density and a centred column: this
  reader visits occasionally and is not scanning forty rows.

  The four gradient tiles that opened this page are gone. They spent the screen's
  entire colour budget on decoration, which left the one thing that actually
  needed to shout — "your approval is needed on this bus" — competing with a
  violet box that meant nothing.
-->
<template>
	<div class="mx-auto max-w-6xl" data-density="comfortable">
		<NPageHeader
			:title="loading ? 'My fleet' : `Welcome, ${data?.customer_name || ''}`"
			subtitle="Your NaArNi electric fleet"
		/>

		<div class="space-y-8 p-5">
			<!-- Anything waiting on the customer comes first, before the summary. -->
			<NAlert
				v-if="awaitingApproval.length"
				semantic="caution"
				title="Your approval is needed"
				:body="`${awaitingApproval.length} service estimate${
					awaitingApproval.length > 1 ? 's are' : ' is'
				} waiting for you to review.`"
			>
				<template #action>
					<NButton
						variant="primary"
						size="sm"
						:to="`/service-portal/job-card/${awaitingApproval[0].name}`"
					>
						Review estimate
					</NButton>
				</template>
			</NAlert>

			<div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
				<template v-if="loading">
					<NSkeleton v-for="i in 4" :key="i" variant="block" height="h-[92px]" :delay="0" />
				</template>
				<template v-else>
					<NStat label="Buses" :value="fmt.number(data?.vehicles?.length)" icon="bus" />
					<NStat label="In service" :value="fmt.number(data?.active_cards?.length)" icon="wrench" />
					<NStat label="Completed" :value="fmt.number(data?.history?.length)" icon="circle-check" />
					<NStat
						label="Total spent"
						:value="fmt.moneyShort(data?.cost_totals?.total_actual)"
						icon="indian-rupee"
					/>
				</template>
			</div>

			<NSection v-if="data?.active_cards?.length" title="In service now">
				<div class="grid gap-3 md:grid-cols-2">
					<NCard
						v-for="card in data.active_cards"
						:key="card.name"
						:to="`/service-portal/job-card/${card.name}`"
						:flagged="Boolean(card.sla_breached)"
					>
						<div class="flex items-start justify-between gap-3">
							<div class="min-w-0">
								<NRecordId :value="card.name" />
								<div class="mt-1"><NVehicle :value="card.vehicle_number" size="lg" /></div>
								<p class="text-body-sm text-muted">
									{{ fmt.or(card.vehicle_make_model, "") }}
								</p>
							</div>
							<NStatus :state="card.workflow_state" />
						</div>

						<div
							class="mt-3 flex items-center justify-between border-t border-hairline pt-2.5 text-body-sm"
						>
							<span class="text-muted">{{ fmt.or(card.job_card_type) }}</span>
							<NPriority :value="card.priority" />
						</div>

						<div
							v-if="card.estimated_cost"
							class="mt-2 flex items-center justify-between text-body-sm"
						>
							<span class="text-muted">Estimate</span>
							<span class="tabular font-semibold text-ink">{{
								fmt.money(card.estimated_cost)
							}}</span>
						</div>

						<p
							v-if="card.sla_breached"
							class="mt-2.5 flex items-center gap-1.5 text-caption text-critical"
						>
							<NIcon name="shield-alert" :size="13" />
							Running past the agreed turnaround — your depot has been notified.
						</p>
					</NCard>
				</div>
			</NSection>

			<NSection v-if="data?.fleet_health?.length" title="Fleet health">
				<NCard>
					<ul class="divide-y divide-hairline">
						<li
							v-for="bus in data.fleet_health"
							:key="bus.vehicle_number"
							class="flex items-center gap-4 py-2.5"
						>
							<div class="w-40 shrink-0">
								<NVehicle :value="bus.vehicle_number" />
								<p class="text-caption text-muted">
									{{ fmt.or(bus.vehicle_make_model, "") }}
								</p>
							</div>
							<NMeter
								class="flex-1"
								:value="bus.latest_health_score"
								:label="`Health of ${fmt.vehicle(bus.vehicle_number)}`"
							/>
							<span class="w-24 shrink-0 text-right text-caption text-muted">
								Last {{ fmt.date(bus.last_service_date) }}
							</span>
						</li>
					</ul>
				</NCard>
			</NSection>

			<NSection title="Your fleet">
				<div v-if="loading" class="grid gap-3 md:grid-cols-3">
					<NSkeleton v-for="i in 3" :key="i" variant="block" height="h-24" :delay="0" />
				</div>
				<NEmptyState
					v-else-if="!data?.vehicles?.length"
					icon="bus"
					title="No buses registered yet"
					body="Your depot will add them as they come into service."
				/>
				<div v-else class="grid gap-3 md:grid-cols-3">
					<NCard v-for="v in data.vehicles" :key="v.name">
						<NVehicle :value="v.registration_number" size="lg" />
						<p class="mt-0.5 text-body-sm text-muted">{{ fmt.or(v.make_model, "") }}</p>
						<div class="mt-2.5 flex items-center gap-2 text-caption text-muted">
							<NBadge
								v-if="v.fuel_type"
								semantic="positive"
								:label="v.fuel_type"
								:dot="false"
							/>
							<span v-if="v.year_of_manufacture">{{ v.year_of_manufacture }}</span>
							<span v-if="v.color">{{ v.color }}</span>
						</div>
					</NCard>
				</div>
			</NSection>

			<NSection v-if="data?.history?.length" title="Service history">
				<NTable :columns="columns" :rows="data.history" empty-title="No completed services yet">
					<template #cell:name="{ row }"
						><NRecordId :value="row.name" :to="`/service-portal/job-card/${row.name}`"
					/></template>
					<template #cell:vehicle_number="{ row }"
						><NVehicle :value="row.vehicle_number"
					/></template>
					<template #cell:pre_pms_score="{ row }">{{ fmt.percent(row.pre_pms_score) }}</template>
					<template #cell:post_pms_score="{ row }">
						<span
							:class="
								row.post_pms_score
									? semanticClasses(scoreSemantic(row.post_pms_score)).text
									: ''
							"
						>
							{{ fmt.percent(row.post_pms_score) }}
						</span>
					</template>
					<template #cell:cost="{ row }">{{
						fmt.money(row.actual_cost || row.estimated_cost)
					}}</template>
					<template #cell:closed_at="{ row }">{{ fmt.date(row.closed_at) }}</template>
					<template #rowActions="{ row }">
						<NButton
							variant="ghost"
							size="sm"
							icon="file-text"
							:href="`/printview?doctype=Job Card&name=${encodeURIComponent(
								row.name
							)}&format=PMS Report`"
						>
							Report
						</NButton>
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
	NStat,
	NCard,
	NSection,
	NTable,
	NStatus,
	NPriority,
	NBadge,
	NAlert,
	NButton,
	NIcon,
	NVehicle,
	NRecordId,
	NMeter,
	NEmptyState,
	NSkeleton,
	fmt,
	scoreSemantic,
	semanticClasses,
} from "../ui/index.js";

const columns = [
	{ key: "name", label: "Job card", mono: true, width: "150px" },
	{ key: "vehicle_number", label: "Vehicle", width: "140px" },
	{ key: "job_card_type", label: "Type" },
	{ key: "pre_pms_score", label: "Before", align: "right", width: "90px" },
	{ key: "post_pms_score", label: "After", align: "right", width: "90px" },
	{ key: "cost", label: "Cost", align: "right", width: "120px" },
	{ key: "closed_at", label: "Closed", align: "right", width: "110px" },
];

const data = ref(null);
const loading = ref(true);

onMounted(async () => {
	try {
		const res = await callAPI("dashboard.get_customer_dashboard");
		data.value = res.data;
	} finally {
		loading.value = false;
	}
});

const awaitingApproval = computed(() =>
	(data.value?.active_cards || []).filter((c) => c.workflow_state === "Awaiting Customer Approval")
);
</script>
