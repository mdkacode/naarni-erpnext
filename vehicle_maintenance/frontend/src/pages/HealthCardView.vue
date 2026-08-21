<!--
  Vehicle health card — the customer-facing outcome of a PMS.

  Comfortable density and a reading-width column: this is a report someone reads
  once, not a board they work from.
-->
<template>
	<div data-density="comfortable">
		<NPageHeader
			title="Vehicle health card"
			:subtitle="card ? `Generated ${fmt.dateTime(card.generated_on)}` : ''"
			:back="`/service-portal/job-card/${jobCardName}`"
			back-label="Back to job card"
		>
			<template v-if="card" #actions>
				<NButton icon="download" :href="pdfUrl">Download PDF</NButton>
			</template>
		</NPageHeader>

		<div class="mx-auto max-w-3xl space-y-4 p-5">
			<NSkeleton v-if="loading" :count="3" variant="block" height="h-28" :delay="0" />

			<NAlert
				v-else-if="error"
				semantic="critical"
				title="Could not load the health card"
				:body="error"
			/>

			<NEmptyState
				v-else-if="!card"
				icon="badge-check"
				title="No health card yet"
				body="It is created automatically once the service engineer closes this job."
			/>

			<template v-else>
				<NCard>
					<NKeyValue
						:items="[
							{ label: 'Health card', value: card.name, mono: true },
							{ label: 'Job card', value: card.job_card, mono: true },
							{ label: 'Odometer', value: fmt.distance(card.odometer_reading) },
						]"
					>
						<div class="flex items-baseline justify-between gap-6 py-2">
							<dt class="shrink-0 text-body-sm text-muted">Vehicle</dt>
							<dd><NVehicle :value="card.vehicle_number || card.vehicle" /></dd>
						</div>
					</NKeyValue>
				</NCard>

				<HealthScoreCard
					:pre-score="card.overall_pre_score"
					:post-score="card.overall_post_score"
					:improvement="card.improvement"
					:category-scores="card.category_scores || []"
				/>

				<NCard v-if="card.resolved_alerts?.length" title="Alerts resolved">
					<ul class="divide-y divide-hairline">
						<li
							v-for="(a, i) in card.resolved_alerts"
							:key="i"
							class="flex items-start justify-between gap-3 py-2"
						>
							<div class="min-w-0">
								<p class="truncate text-title-sm text-ink">
									{{ fmt.or(a.alert_name, "Alert") }}
								</p>
								<p class="text-caption text-muted">
									{{ fmt.person(a.resolved_by)
									}}<span v-if="a.response"> · {{ a.response }}</span>
								</p>
							</div>
							<div class="shrink-0 text-right">
								<NPriority :value="a.severity" />
								<p class="mt-0.5 text-caption text-muted">{{ fmt.date(a.resolved_on) }}</p>
							</div>
						</li>
					</ul>
				</NCard>

				<NCard v-if="card.summary_notes" title="Summary">
					<p class="whitespace-pre-line text-body text-ink">{{ card.summary_notes }}</p>
				</NCard>
			</template>
		</div>
	</div>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { callAPI } from "../utils/api.js";
import HealthScoreCard from "../components/HealthScoreCard.vue";
import {
	NPageHeader,
	NCard,
	NKeyValue,
	NVehicle,
	NPriority,
	NButton,
	NAlert,
	NEmptyState,
	NSkeleton,
	fmt,
} from "../ui/index.js";

const route = useRoute();
const jobCardName = computed(() => route.params.jobCardName);

const loading = ref(true);
const error = ref("");
const card = ref(null);

const pdfUrl = computed(() =>
	card.value
		? `/api/method/frappe.utils.print_format.download_pdf?doctype=Vehicle+Health+Card&name=${encodeURIComponent(
				card.value.name
		  )}&format=Vehicle+Health+Card+Report&no_letterhead=1`
		: "#"
);

async function load() {
	loading.value = true;
	error.value = "";
	card.value = null;
	try {
		const result = await callAPI(
			"vehicle_maintenance.fleet_service.doctype.vehicle_health_card.vehicle_health_card.get_for_job_card",
			{ job_card_name: jobCardName.value }
		);
		card.value = result?.data || null;
	} catch (e) {
		error.value = e?.messages?.[0] || e?.message || "Please try again, or contact your depot.";
	} finally {
		loading.value = false;
	}
}

watch(jobCardName, load, { immediate: true });
</script>
