<!--
  What the customer sees about their bus.

  Deliberately thin: no SLA timers, no assignment, no cost breakdown, no
  technician names, no inspection data. A vehicle, a plain-language sentence
  about what is happening, a timeline, and — when it is actually their turn —
  one decision to make.

  The status banner used to take a different hue per state: indigo, amber,
  violet, pink, cyan, orange, green. Seven colours across seven states asked a
  customer who visits twice a year to learn a legend. It is the shared ramp now,
  and the sentence carries the meaning.
-->
<template>
	<div data-density="comfortable">
		<NPageHeader
			:title="card?.vehicle_number ? '' : 'Your service'"
			back="/service-portal"
			back-label="My vehicles"
		>
			<template #title>
				<NVehicle v-if="card?.vehicle_number" :value="card.vehicle_number" size="lg" />
				<span v-else>Your service</span>
			</template>
			<template #subtitle>
				<span v-if="card" class="flex flex-wrap items-center gap-x-2">
					{{ fmt.or(card.vehicle_make_model, "") }}
					<NRecordId :value="card.name" />
				</span>
			</template>
			<template #badge>
				<NStatus v-if="card?.workflow_state" :state="card.workflow_state" />
			</template>
		</NPageHeader>

		<div class="mx-auto max-w-lg space-y-6 p-5">
			<NSkeleton v-if="loading" :count="3" variant="block" height="h-24" :delay="0" />

			<NAlert
				v-else-if="error"
				semantic="critical"
				title="Could not load your service status"
				:body="error"
			>
				<template #action
					><NButton size="sm" icon="refresh-cw" @click="fetchCard">Try again</NButton></template
				>
			</NAlert>

			<template v-else-if="card">
				<NAlert
					:semantic="stateSemantic(card.workflow_state)"
					:title="statusMessage.headline"
					:body="statusMessage.body"
				/>

				<!-- The one decision, and only when it is theirs to make. -->
				<NCard v-if="awaitingApproval && estimateData" title="Estimate for your approval">
					<div class="space-y-4">
						<div class="rounded-md bg-sunken p-4">
							<div class="flex items-baseline justify-between gap-3">
								<span class="text-body-sm text-muted">Estimated total</span>
								<span class="tabular text-display text-ink">{{
									fmt.money(estimateData.total_amount)
								}}</span>
							</div>
							<p class="mt-1.5 text-caption text-muted">
								{{
									[card.service_type, fmt.vehicle(card.vehicle_number)]
										.filter(Boolean)
										.join(" · ")
								}}
							</p>
						</div>

						<NTextarea
							v-model="customerRemarks"
							label="Anything you want to add?"
							:rows="2"
							placeholder="Questions or conditions."
							:hint="'Optional to approve — required if you decline, so the depot knows why.'"
						/>

						<NAlert v-if="actionError" semantic="critical" :body="actionError" />

						<div class="grid grid-cols-2 gap-3">
							<NButton size="lg" :loading="actionLoading" @click="rejectEstimate"
								>Decline</NButton
							>
							<NButton
								variant="primary"
								size="lg"
								:loading="actionLoading"
								@click="approveEstimate"
								>Approve</NButton
							>
						</div>
					</div>
				</NCard>

				<NSection title="Progress">
					<NTimeline :steps="timelineSteps" />
				</NSection>

				<p
					class="flex flex-wrap gap-x-5 gap-y-1 border-t border-hairline pt-4 text-caption text-muted"
				>
					<span>{{ fmt.or(card.service_type) }}</span>
					<span v-if="card.opened_at">Opened {{ fmt.date(card.opened_at) }}</span>
				</p>
			</template>
		</div>
	</div>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { callAPI } from "../utils/api.js";
import {
	NPageHeader,
	NCard,
	NSection,
	NStatus,
	NAlert,
	NButton,
	NTextarea,
	NTimeline,
	NVehicle,
	NRecordId,
	NSkeleton,
	fmt,
	stateSemantic,
	toast,
} from "../ui/index.js";

const props = defineProps({ jobCardName: { type: String, required: true } });

const card = ref(null);
const estimateData = ref(null);
const loading = ref(true);
const error = ref("");
const customerRemarks = ref("");
const actionLoading = ref(false);
const actionError = ref("");

const WORKFLOW_ORDER = [
	"Open",
	"WIP",
	"Awaiting Customer Approval",
	"Awaiting Parts",
	"Parts Fitted",
	"Verification Pending",
	"Closed",
];

/** No jargon reaches this page. "WIP" is a column value, not a sentence. */
const CUSTOMER_LABELS = {
	Open: "Received",
	WIP: "Being worked on",
	"Awaiting Customer Approval": "Waiting for your approval",
	"Awaiting Parts": "Ordering parts",
	"Parts Fitted": "Parts installed",
	"Verification Pending": "Final quality check",
	Closed: "Complete",
};

const STATUS_MESSAGES = {
	Open: {
		headline: "We have your bus",
		body: "The inspection starts shortly.",
	},
	WIP: {
		headline: "Your bus is being serviced",
		body: "Our technicians are working on it. We will update you as things change.",
	},
	"Awaiting Customer Approval": {
		headline: "We need your approval",
		body: "Review the estimate below, then approve or decline so work can continue.",
	},
	"Awaiting Parts": {
		headline: "Parts are on order",
		body: "Work resumes as soon as they arrive.",
	},
	"Parts Fitted": {
		headline: "The new parts are in",
		body: "Work is continuing on your bus.",
	},
	"Verification Pending": {
		headline: "Almost done",
		body: "Your bus is going through a final quality check before handover.",
	},
	Closed: {
		headline: "Your bus is ready",
		body: "Service is complete — you can collect it from the workshop.",
	},
};

const awaitingApproval = computed(() => card.value?.workflow_state === "Awaiting Customer Approval");

const statusMessage = computed(
	() =>
		STATUS_MESSAGES[card.value?.workflow_state] || {
			headline: card.value?.workflow_state || "",
			body: "",
		}
);

const timelineSteps = computed(() => {
	if (!card.value) return [];
	const currentIdx = WORKFLOW_ORDER.indexOf(card.value.workflow_state);
	return WORKFLOW_ORDER.map((state, idx) => ({
		key: state,
		label: CUSTOMER_LABELS[state] || state,
		sublabel: idx === currentIdx ? "Where it is now" : "",
		state: idx < currentIdx ? "done" : idx === currentIdx ? "current" : "todo",
	}));
});

async function fetchCard() {
	loading.value = true;
	error.value = "";
	try {
		const result = await callAPI("job_card.get_job_card_summary", { job_card_name: props.jobCardName });
		card.value = result.data;
		if (awaitingApproval.value) await fetchEstimate();
	} catch (e) {
		error.value = e?.messages?.[0] || "Please try again, or call your depot.";
	} finally {
		loading.value = false;
	}
}

async function fetchEstimate() {
	try {
		if (!card.value?.name) return;
		const estimates = await callAPI("vehicle_maintenance.api.estimate.get_estimate_for_card", {
			job_card_name: card.value.name,
		}).catch(() => null);

		// Fall back to the figure already on the card, so the customer always sees
		// a number even when the dedicated endpoint is unavailable.
		if (estimates?.data) estimateData.value = estimates.data;
		else if (card.value.estimated_cost > 0)
			estimateData.value = { total_amount: card.value.estimated_cost, name: null };
	} catch {
		if (card.value.estimated_cost > 0)
			estimateData.value = { total_amount: card.value.estimated_cost, name: null };
	}
}

async function approveEstimate() {
	if (!estimateData.value?.name) {
		actionError.value = "We could not find the estimate to approve. Please call your depot.";
		return;
	}
	actionLoading.value = true;
	actionError.value = "";
	try {
		await callAPI("estimate.approve_estimate", {
			estimate_name: estimateData.value.name,
			remarks: customerRemarks.value,
		});
		toast.success("Approved — work will continue.");
		await fetchCard();
	} catch (e) {
		actionError.value = e?.messages?.[0] || "Could not approve. Please try again.";
	} finally {
		actionLoading.value = false;
	}
}

async function rejectEstimate() {
	if (!customerRemarks.value.trim()) {
		actionError.value = "Tell us why you are declining, so the depot can act on it.";
		return;
	}
	if (!estimateData.value?.name) {
		actionError.value = "We could not find the estimate to decline. Please call your depot.";
		return;
	}
	actionLoading.value = true;
	actionError.value = "";
	try {
		await callAPI("estimate.reject_estimate", {
			estimate_name: estimateData.value.name,
			remarks: customerRemarks.value,
		});
		toast.success("Declined — the team will be in touch.");
		await fetchCard();
	} catch (e) {
		actionError.value = e?.messages?.[0] || "Could not decline. Please try again.";
	} finally {
		actionLoading.value = false;
	}
}

onMounted(fetchCard);
</script>
