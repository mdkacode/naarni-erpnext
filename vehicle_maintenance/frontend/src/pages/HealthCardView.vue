<template>
	<!--
    HealthCardView — customer-facing Vehicle Health Card page.
    Fetches the Health Card linked to a given Job Card via the whitelisted
    API added in Milestone 2. Used by the customer & DM to view scores after
    SE closure of a PMS + Repair job.
  -->
	<div class="max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
		<div class="mb-6">
			<router-link
				:to="`/service-portal/job-card/${jobCardName}`"
				class="text-sm text-brand-600 hover:underline"
			>
				&larr; Back to Job Card
			</router-link>
			<h1 class="text-2xl font-bold text-gray-900 mt-2">Vehicle Health Card</h1>
		</div>

		<div v-if="loading" class="text-center py-12 text-gray-500">Loading…</div>

		<div v-else-if="error" class="p-4 bg-red-50 border border-red-200 rounded-xl text-sm text-red-700">
			{{ error }}
		</div>

		<div
			v-else-if="!card"
			class="p-6 text-center text-sm text-gray-500 bg-gray-50 border border-gray-200 rounded-xl"
		>
			A Health Card has not yet been generated for this Job Card. It will be created automatically once
			the Service Engineer closes the job.
		</div>

		<div v-else class="space-y-4">
			<!-- Header band -->
			<div class="bg-brand-50 border border-brand-200 rounded-xl p-4">
				<div class="flex justify-between items-start">
					<div>
						<div class="text-xs text-brand-700 uppercase tracking-wide font-medium">
							Health Card
						</div>
						<div class="text-lg font-bold text-brand-900 mt-1">{{ card.name }}</div>
					</div>
					<div class="text-right text-xs text-brand-700">
						<div>Generated</div>
						<div class="font-medium text-brand-900">
							{{ formatDate(card.generated_on) }}
						</div>
					</div>
				</div>
			</div>

			<!-- Vehicle + odometer meta -->
			<div class="grid grid-cols-3 gap-3">
				<div class="bg-white border border-gray-200 rounded-xl p-3">
					<div class="text-xs text-gray-500 uppercase">Vehicle</div>
					<div class="font-semibold text-gray-900 mt-1">
						{{ card.vehicle_number || card.vehicle }}
					</div>
				</div>
				<div class="bg-white border border-gray-200 rounded-xl p-3">
					<div class="text-xs text-gray-500 uppercase">Odometer</div>
					<div class="font-semibold text-gray-900 mt-1">
						{{ (card.odometer_reading || 0).toLocaleString() }} km
					</div>
				</div>
				<div class="bg-white border border-gray-200 rounded-xl p-3">
					<div class="text-xs text-gray-500 uppercase">Job Card</div>
					<div class="font-semibold text-gray-900 mt-1">{{ card.job_card }}</div>
				</div>
			</div>

			<!-- Scores -->
			<HealthScoreCard
				:pre-score="card.overall_pre_score"
				:post-score="card.overall_post_score"
				:improvement="card.improvement"
				:category-scores="card.category_scores || []"
			/>

			<!-- Resolved alerts -->
			<div
				v-if="card.resolved_alerts && card.resolved_alerts.length"
				class="bg-white border border-gray-200 rounded-xl p-4"
			>
				<h3 class="text-sm font-semibold text-gray-800 mb-3">Alerts Resolved</h3>
				<div class="space-y-2">
					<div
						v-for="(a, i) in card.resolved_alerts"
						:key="i"
						class="flex items-start justify-between gap-3 border-b border-gray-100 last:border-0 pb-2 last:pb-0"
					>
						<div class="min-w-0">
							<p class="text-sm font-medium text-gray-900 truncate">
								{{ a.alert_name || "Alert" }}
							</p>
							<p class="text-xs text-gray-500">
								{{ a.resolved_by || "—" }}<span v-if="a.response"> · {{ a.response }}</span>
							</p>
						</div>
						<div class="shrink-0 text-right">
							<span
								class="inline-block px-2 py-0.5 rounded-full text-xs font-semibold"
								:class="severityClass(a.severity)"
								>{{ a.severity || "—" }}</span
							>
							<p class="text-xs text-gray-400 mt-0.5">{{ formatDate(a.resolved_on) }}</p>
						</div>
					</div>
				</div>
			</div>

			<!-- Summary text -->
			<div v-if="card.summary_notes" class="bg-white border border-gray-200 rounded-xl p-4">
				<h3 class="text-sm font-semibold text-gray-800 mb-2">Summary</h3>
				<p class="text-sm text-gray-700 whitespace-pre-line">{{ card.summary_notes }}</p>
			</div>

			<!-- Download PDF link -->
			<div class="text-right">
				<a
					:href="pdfUrl"
					target="_blank"
					rel="noopener"
					class="inline-block px-4 py-2 text-sm font-medium text-brand-700 bg-brand-50 hover:bg-brand-100 rounded-lg"
				>
					Download PDF
				</a>
			</div>
		</div>
	</div>
</template>

<script setup>
import { ref, computed, watch } from "vue";
import { useRoute } from "vue-router";
import { callAPI } from "../utils/api.js";
import HealthScoreCard from "../components/HealthScoreCard.vue";

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
		error.value = e?.messages?.[0] || e?.message || "Failed to load health card.";
	} finally {
		loading.value = false;
	}
}

function formatDate(val) {
	if (!val) return "—";
	try {
		return new Date(val).toLocaleString();
	} catch {
		return val;
	}
}

function severityClass(sev) {
	const s = (sev || "").toLowerCase();
	if (s === "critical") return "bg-red-100 text-red-700";
	if (s === "high") return "bg-orange-100 text-orange-700";
	if (s === "medium") return "bg-amber-100 text-amber-700";
	if (s === "low") return "bg-green-100 text-green-700";
	return "bg-gray-100 text-gray-600";
}

watch(jobCardName, load, { immediate: true });
</script>
