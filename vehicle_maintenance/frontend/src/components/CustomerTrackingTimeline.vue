<template>
  <!--
    CustomerTrackingTimeline — read-only progress tracker for the Customer role.

    EAS applied:
      Eliminate — No SLA timers, no audit fields, no financial breakdown, no internal
                 assignment details, no technician names, no inspection raw data.
                 Customer sees ONLY: vehicle, status, timeline, and estimate actions.
      Automate — Progress steps auto-derive from workflow_state. Estimate approval
                 actions auto-surface only when the card is in the right state.
      Simplify — Single vertical timeline with plain-language labels. No tables,
                 no grids, no technical jargon. One clear CTA when action is needed.
  -->
  <div class="max-w-lg mx-auto px-4 py-6">
    <!-- Loading -->
    <div v-if="loading" class="text-center py-16">
      <div class="inline-block w-8 h-8 border-3 border-gray-200 border-t-brand-500 rounded-full animate-spin" />
      <p class="mt-3 text-sm text-gray-400">Loading your service status...</p>
    </div>

    <!-- Error -->
    <div v-else-if="error" class="text-center py-16">
      <p class="text-red-600 text-sm">{{ error }}</p>
      <button
        @click="fetchCard"
        class="mt-3 px-4 py-2 text-sm text-brand-600 border border-brand-200 rounded-lg hover:bg-brand-50"
      >
        Try again
      </button>
    </div>

    <template v-else-if="card">
      <!-- ━━━ Header: Vehicle + Status ━━━ -->
      <div class="mb-8">
        <router-link to="/service-portal" class="text-sm text-brand-600 hover:underline">
          &larr; My vehicles
        </router-link>
        <div class="mt-3 flex items-start justify-between">
          <div>
            <h1 class="text-xl font-bold text-gray-900">{{ card.vehicle_number }}</h1>
            <p class="text-sm text-gray-500 mt-0.5">{{ card.vehicle_make_model }}</p>
            <p class="text-xs text-gray-400 mt-1 font-mono">{{ card.name }}</p>
          </div>
          <StatusBadge :state="card.workflow_state" />
        </div>
      </div>

      <!-- ━━━ Customer-friendly status message ━━━ -->
      <div
        class="mb-8 p-4 rounded-xl border-2"
        :class="statusMessageStyle"
      >
        <p class="font-semibold text-base">{{ statusMessage.headline }}</p>
        <p class="text-sm mt-1 opacity-80">{{ statusMessage.body }}</p>
      </div>

      <!-- ━━━ Estimate approval CTA (only when waiting) ━━━ -->
      <div
        v-if="card.workflow_state === 'Awaiting Customer Approval' && estimateData"
        class="mb-8 bg-white border-2 border-violet-200 rounded-xl p-5"
      >
        <h2 class="text-base font-semibold text-gray-900 mb-3">Estimate for your approval</h2>

        <!-- Simplified estimate summary — no line-item breakdown -->
        <div class="bg-violet-50 rounded-lg p-4 mb-4">
          <div class="flex justify-between items-baseline">
            <span class="text-sm text-gray-600">Estimated total</span>
            <span class="text-2xl font-bold text-gray-900">{{ formatCurrency(estimateData.total_amount) }}</span>
          </div>
          <p class="text-xs text-gray-500 mt-2">
            {{ card.service_type }} &middot; {{ card.vehicle_number }}
          </p>
        </div>

        <!-- Remarks -->
        <div class="mb-4">
          <label class="block text-sm font-medium text-gray-700 mb-1">
            Any remarks? (optional)
          </label>
          <textarea
            v-model="customerRemarks"
            rows="2"
            placeholder="Questions or conditions..."
            class="w-full px-3 py-2 text-sm border border-gray-300 rounded-lg focus:ring-2 focus:ring-brand-500"
          />
        </div>

        <!-- Action buttons — large, clear, tap-friendly -->
        <div class="grid grid-cols-2 gap-3">
          <button
            @click="rejectEstimate"
            :disabled="actionLoading"
            class="py-3 text-sm font-semibold rounded-xl border-2 border-red-300 text-red-700 bg-red-50 hover:bg-red-100 transition-colors disabled:opacity-50"
          >
            Decline
          </button>
          <button
            @click="approveEstimate"
            :disabled="actionLoading"
            class="py-3 text-sm font-semibold rounded-xl border-2 border-green-400 text-green-800 bg-green-50 hover:bg-green-100 transition-colors disabled:opacity-50"
          >
            Approve
          </button>
        </div>

        <div v-if="actionError" class="mt-3 text-sm text-red-600">{{ actionError }}</div>
        <div v-if="actionSuccess" class="mt-3 text-sm text-green-600">{{ actionSuccess }}</div>
      </div>

      <!-- ━━━ Visual Timeline ━━━ -->
      <div class="mb-8">
        <h2 class="text-sm font-semibold text-gray-500 uppercase tracking-wider mb-4">Progress</h2>
        <ol class="relative border-l-2 border-gray-200 ml-4 space-y-0">
          <li
            v-for="(step, idx) in timelineSteps"
            :key="step.state"
            class="relative pl-8 pb-8 last:pb-0"
          >
            <!-- Node circle -->
            <div
              class="absolute -left-[11px] top-0.5 flex items-center justify-center w-5 h-5 rounded-full border-2"
              :class="timelineNodeClass(step, idx)"
            >
              <!-- Check icon for completed -->
              <svg v-if="step.completed" class="w-3 h-3 text-white" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd" />
              </svg>
              <!-- Pulse dot for active -->
              <div v-else-if="step.active" class="w-2 h-2 bg-brand-500 rounded-full animate-pulse" />
            </div>

            <!-- Content -->
            <div>
              <p
                class="text-sm font-medium"
                :class="step.completed ? 'text-gray-900' : step.active ? 'text-brand-700' : 'text-gray-400'"
              >
                {{ step.label }}
              </p>
              <p v-if="step.sublabel" class="text-xs mt-0.5" :class="step.active ? 'text-brand-500' : 'text-gray-400'">
                {{ step.sublabel }}
              </p>
            </div>
          </li>
        </ol>
      </div>

      <!-- ━━━ Minimal info footer ━━━ -->
      <div class="text-xs text-gray-400 space-y-1 border-t border-gray-100 pt-4">
        <p>Service type: {{ card.service_type }}</p>
        <p v-if="card.opened_at">Opened: {{ formatDate(card.opened_at) }}</p>

        <!-- Eliminate: SLA, costs, assignments, audit data hidden from customer -->
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from "vue";
import { callAPI } from "../utils/api.js";
import StatusBadge from "./StatusBadge.vue";

const props = defineProps({
  jobCardName: { type: String, required: true },
});

const card = ref(null);
const estimateData = ref(null);
const loading = ref(true);
const error = ref("");
const customerRemarks = ref("");
const actionLoading = ref(false);
const actionError = ref("");
const actionSuccess = ref("");

// --- Workflow states in customer-visible order ---
const WORKFLOW_ORDER = [
  "Open",
  "WIP",
  "Awaiting Customer Approval",
  "Awaiting Parts",
  "Parts Fitted",
  "Verification Pending",
  "Closed",
];

// Customer-friendly labels (no jargon)
const CUSTOMER_LABELS = {
  Open: "Received",
  WIP: "Work in progress",
  "Awaiting Customer Approval": "Waiting for your approval",
  "Awaiting Parts": "Ordering parts",
  "Parts Fitted": "Parts installed",
  "Verification Pending": "Final quality check",
  Closed: "Complete",
};

// --- Fetch ---
async function fetchCard() {
  loading.value = true;
  error.value = "";
  try {
    const result = await callAPI("job_card.get_job_card_summary", {
      job_card_name: props.jobCardName,
    });
    card.value = result.data;

    // Fetch estimate if in approval state
    if (card.value.workflow_state === "Awaiting Customer Approval") {
      await fetchEstimate();
    }
  } catch (e) {
    error.value = e?.messages?.[0] || "Could not load your service status.";
  } finally {
    loading.value = false;
  }
}

async function fetchEstimate() {
  try {
    if (!card.value?.name) return;
    const estimates = await callAPI(
      "vehicle_maintenance.api.estimate.get_estimate_for_card",
      { job_card_name: card.value.name }
    ).catch(() => null);

    // Fallback: use estimated_cost from card if dedicated endpoint not available
    if (estimates?.data) {
      estimateData.value = estimates.data;
    } else if (card.value.estimated_cost > 0) {
      estimateData.value = { total_amount: card.value.estimated_cost, name: null };
    }
  } catch {
    // Degrade gracefully — show cost from card summary
    if (card.value.estimated_cost > 0) {
      estimateData.value = { total_amount: card.value.estimated_cost, name: null };
    }
  }
}

onMounted(fetchCard);

// --- Timeline computation ---
const timelineSteps = computed(() => {
  if (!card.value) return [];
  const currentIdx = WORKFLOW_ORDER.indexOf(card.value.workflow_state);

  return WORKFLOW_ORDER.map((state, idx) => ({
    state,
    label: CUSTOMER_LABELS[state] || state,
    sublabel: idx === currentIdx ? "Current step" : "",
    completed: idx < currentIdx,
    active: idx === currentIdx,
    future: idx > currentIdx,
  }));
});

// --- Customer-friendly status message ---
const statusMessage = computed(() => {
  if (!card.value) return { headline: "", body: "" };

  const messages = {
    Open: {
      headline: "We've received your vehicle",
      body: "Our team will begin the inspection shortly.",
    },
    WIP: {
      headline: "Your vehicle is being serviced",
      body: "Our technicians are working on it. We'll update you as soon as there's news.",
    },
    "Awaiting Customer Approval": {
      headline: "We need your approval",
      body: "Please review the estimate below and approve or decline to continue.",
    },
    "Awaiting Parts": {
      headline: "Parts have been ordered",
      body: "We're waiting for the required parts to arrive. Work will resume once they're in.",
    },
    "Parts Fitted": {
      headline: "Parts have been installed",
      body: "The new parts are in place. Work is continuing on your vehicle.",
    },
    "Verification Pending": {
      headline: "Almost done!",
      body: "Your vehicle is undergoing a final quality check before handover.",
    },
    Closed: {
      headline: "Your vehicle is ready!",
      body: "Service is complete. You can pick up your vehicle at the workshop.",
    },
  };

  return messages[card.value.workflow_state] || {
    headline: card.value.workflow_state,
    body: "",
  };
});

const statusMessageStyle = computed(() => {
  if (!card.value) return "";
  const styles = {
    Open: "border-indigo-200 bg-indigo-50 text-indigo-900",
    WIP: "border-amber-200 bg-amber-50 text-amber-900",
    "Awaiting Customer Approval": "border-violet-300 bg-violet-50 text-violet-900",
    "Awaiting Parts": "border-pink-200 bg-pink-50 text-pink-900",
    "Parts Fitted": "border-cyan-200 bg-cyan-50 text-cyan-900",
    "Verification Pending": "border-orange-200 bg-orange-50 text-orange-900",
    Closed: "border-green-300 bg-green-50 text-green-900",
  };
  return styles[card.value.workflow_state] || "border-gray-200 bg-gray-50 text-gray-900";
});

// --- Timeline node styling ---
function timelineNodeClass(step) {
  if (step.completed) return "bg-green-500 border-green-500";
  if (step.active) return "bg-white border-brand-500";
  return "bg-white border-gray-300";
}

// --- Estimate actions ---
async function approveEstimate() {
  if (!estimateData.value?.name) {
    actionError.value = "No estimate reference found. Please contact the workshop.";
    return;
  }
  actionLoading.value = true;
  actionError.value = "";
  actionSuccess.value = "";
  try {
    await callAPI("estimate.approve_estimate", {
      estimate_name: estimateData.value.name,
      remarks: customerRemarks.value,
    });
    actionSuccess.value = "Estimate approved! Work will continue.";
    // Refresh to update timeline
    setTimeout(() => fetchCard(), 1500);
  } catch (e) {
    actionError.value = e?.messages?.[0] || "Could not approve. Please try again.";
  } finally {
    actionLoading.value = false;
  }
}

async function rejectEstimate() {
  if (!customerRemarks.value.trim()) {
    actionError.value = "Please add a reason for declining.";
    return;
  }
  if (!estimateData.value?.name) {
    actionError.value = "No estimate reference found. Please contact the workshop.";
    return;
  }
  actionLoading.value = true;
  actionError.value = "";
  actionSuccess.value = "";
  try {
    await callAPI("estimate.reject_estimate", {
      estimate_name: estimateData.value.name,
      remarks: customerRemarks.value,
    });
    actionSuccess.value = "Estimate declined. The team will contact you.";
    setTimeout(() => fetchCard(), 1500);
  } catch (e) {
    actionError.value = e?.messages?.[0] || "Could not decline. Please try again.";
  } finally {
    actionLoading.value = false;
  }
}

// --- Formatting ---
function formatCurrency(val) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 0,
  }).format(val || 0);
}

function formatDate(dateStr) {
  if (!dateStr) return "";
  const d = new Date(dateStr);
  return d.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}
</script>
