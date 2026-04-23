<template>
  <!--
    JobCardCreationWizard — Technician-facing Job Card creation + inspection flow.

    EAS applied:
      Eliminate — SLA timers, audit fields, financials are absent from this view entirely.
                 Technicians never see cost fields, deadline timestamps, or internal flags.
      Automate — Inspection check sheet (A/B/C) is auto-selected from odometer reading.
                 Current user auto-assigned as technician. Date/time auto-stamped.
      Simplify — 5-step wizard: Vehicle → Service → Inspection → Findings → Review.
                 Inspection items are grouped by category, one category per sub-screen.
                 Tap-friendly pass/fail toggles and measurement inputs.
  -->
  <div class="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
    <div class="mb-6">
      <router-link to="/service-portal" class="text-sm text-brand-600 hover:underline">
        &larr; Back to dashboard
      </router-link>
      <h1 class="text-2xl font-bold text-gray-900 mt-2">New Inspection</h1>
    </div>

    <Wizard
      ref="wizardRef"
      :steps="wizardSteps"
      v-model="formData"
      submit-label="Submit Job Card"
      @complete="handleSubmit"
      @step-change="onStepChange"
    >
      <!-- ━━━ Step 1: Vehicle ━━━ -->
      <template #step-vehicle="{ data, updateField }">
        <div class="space-y-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">
              Vehicle Registration Number
            </label>
            <input
              :value="data.vehicle_number"
              @input="updateField('vehicle_number', $event.target.value.toUpperCase())"
              type="text"
              placeholder="e.g. KA-01-AB-1234"
              class="input-field uppercase"
              autocapitalize="characters"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">
              Current Odometer Reading
            </label>
            <div class="relative">
              <input
                :value="data.odometer_reading"
                @input="onOdometerChange($event, updateField)"
                type="number"
                inputmode="numeric"
                min="0"
                placeholder="e.g. 45000"
                class="input-field pr-12"
              />
              <span class="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-gray-400">km</span>
            </div>
          </div>

          <!-- Automate: Show the auto-selected sheet immediately -->
          <div
            v-if="data.odometer_reading > 0"
            class="mt-3 p-3 rounded-lg border text-sm"
            :class="sheetBannerClass"
          >
            <span class="font-medium">Auto-selected:</span>
            {{ inspectionSheet.sheetLabel }}
            <span class="text-xs ml-1">({{ inspectionSheet.items.length }} checks)</span>
          </div>
        </div>
      </template>

      <!-- ━━━ Step 2: Service Type ━━━ -->
      <template #step-service="{ data, updateField }">
        <div class="space-y-5">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">
              What type of service?
            </label>
            <div class="grid grid-cols-2 gap-3">
              <button
                v-for="svc in serviceTypes"
                :key="svc.value"
                type="button"
                @click="updateField('service_type', svc.value)"
                class="flex items-center gap-3 p-3 rounded-xl border-2 text-left transition-all"
                :class="
                  data.service_type === svc.value
                    ? 'border-brand-500 bg-brand-50 ring-1 ring-brand-500'
                    : 'border-gray-200 hover:border-gray-300'
                "
              >
                <span class="text-2xl">{{ svc.icon }}</span>
                <span class="text-sm font-medium text-gray-800">{{ svc.label }}</span>
              </button>
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">
              Describe the issue (optional for routine service)
            </label>
            <textarea
              :value="data.complaint_description"
              @input="updateField('complaint_description', $event.target.value)"
              rows="3"
              placeholder="Any specific complaints or observations..."
              class="input-field"
            />
          </div>
        </div>
      </template>

      <!-- ━━━ Step 3: Inspection Checklist ━━━ -->
      <template #step-inspection="{ data, updateField }">
        <div>
          <!-- Category sub-navigation -->
          <div class="flex gap-2 overflow-x-auto pb-3 mb-4 -mx-1 px-1">
            <button
              v-for="(cat, idx) in inspectionCategories"
              :key="cat"
              type="button"
              @click="activeCategory = idx"
              class="shrink-0 px-3 py-1.5 text-xs font-medium rounded-full whitespace-nowrap transition-colors"
              :class="
                activeCategory === idx
                  ? 'bg-brand-600 text-white'
                  : categoryComplete(cat, data) ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'
              "
            >
              {{ cat }}
              <span v-if="categoryComplete(cat, data)" class="ml-1">&#10003;</span>
            </button>
          </div>

          <!-- Items for active category -->
          <div class="space-y-3">
            <div
              v-for="item in activeCategoryItems"
              :key="item.id"
              class="bg-white border border-gray-200 rounded-xl p-4"
            >
              <p class="text-sm font-medium text-gray-800 mb-2">{{ item.label }}</p>

              <!-- Three-tier status per PRD (Good / Recommended / Immediately) -->
              <div v-if="item.inputType === 'three_tier'" class="flex gap-2">
                <button
                  type="button"
                  @click="updateField(`inspection_${item.id}`, STATUS_GOOD)"
                  class="flex-1 py-2.5 text-xs font-medium rounded-lg border-2 transition-all"
                  :class="
                    data[`inspection_${item.id}`] === STATUS_GOOD
                      ? 'border-green-500 bg-green-50 text-green-700'
                      : 'border-gray-200 text-gray-500 hover:border-gray-300'
                  "
                >
                  Good
                </button>
                <button
                  type="button"
                  @click="updateField(`inspection_${item.id}`, STATUS_RECOMMENDED)"
                  class="flex-1 py-2.5 text-xs font-medium rounded-lg border-2 transition-all"
                  :class="
                    data[`inspection_${item.id}`] === STATUS_RECOMMENDED
                      ? 'border-amber-500 bg-amber-50 text-amber-700'
                      : 'border-gray-200 text-gray-500 hover:border-gray-300'
                  "
                >
                  Recommended
                </button>
                <button
                  type="button"
                  @click="updateField(`inspection_${item.id}`, STATUS_IMMEDIATE)"
                  class="flex-1 py-2.5 text-xs font-medium rounded-lg border-2 transition-all"
                  :class="
                    data[`inspection_${item.id}`] === STATUS_IMMEDIATE
                      ? 'border-red-500 bg-red-50 text-red-700'
                      : 'border-gray-200 text-gray-500 hover:border-gray-300'
                  "
                >
                  Immediate
                </button>
              </div>

              <!-- Measurement input -->
              <div v-else-if="item.inputType === 'measurement'" class="flex items-center gap-2">
                <div class="relative flex-1">
                  <input
                    :value="data[`inspection_${item.id}`]"
                    @input="updateField(`inspection_${item.id}`, $event.target.value)"
                    type="number"
                    inputmode="decimal"
                    step="0.1"
                    :placeholder="`${item.min}–${item.max}`"
                    class="input-field pr-12"
                    :class="measurementOutOfRange(item, data[`inspection_${item.id}`]) ? 'border-red-400 bg-red-50' : ''"
                  />
                  <span class="absolute right-3 top-1/2 -translate-y-1/2 text-xs text-gray-400">
                    {{ item.unit }}
                  </span>
                </div>
                <div
                  v-if="data[`inspection_${item.id}`] && measurementOutOfRange(item, data[`inspection_${item.id}`])"
                  class="text-xs text-red-600 font-medium shrink-0"
                >
                  Out of range
                </div>
              </div>

              <!-- Text input -->
              <div v-else-if="item.inputType === 'text'">
                <textarea
                  :value="data[`inspection_${item.id}`]"
                  @input="updateField(`inspection_${item.id}`, $event.target.value)"
                  rows="2"
                  placeholder="Notes..."
                  class="input-field text-sm"
                />
              </div>
            </div>
          </div>

          <!-- Category progress -->
          <div class="mt-4 text-xs text-gray-400 text-center">
            Category {{ activeCategory + 1 }} of {{ inspectionCategories.length }}
            &middot;
            {{ completedCount }} / {{ inspectionSheet.items.length }} checks done
          </div>
        </div>
      </template>

      <!-- ━━━ Step 4: Findings & Parts ━━━ -->
      <template #step-findings="{ data, updateField }">
        <div class="space-y-5">
          <!-- Auto-detected failures (Immediate) -->
          <div v-if="failedItems.length" class="space-y-2">
            <h3 class="text-sm font-semibold text-red-700">
              {{ failedItems.length }} item{{ failedItems.length > 1 ? 's need' : ' needs' }} immediate action
            </h3>
            <div
              v-for="item in failedItems"
              :key="item.id"
              class="flex items-center gap-3 p-3 bg-red-50 border border-red-200 rounded-lg text-sm"
            >
              <span class="text-red-500 font-bold shrink-0">&#10007;</span>
              <span class="text-gray-800">{{ item.label }}</span>
              <span class="text-xs text-red-500 ml-auto">{{ item.category }}</span>
            </div>
          </div>

          <!-- Recommendations (middle tier) -->
          <div v-if="recommendedItems.length" class="space-y-2">
            <h3 class="text-sm font-semibold text-amber-700">
              {{ recommendedItems.length }} repair{{ recommendedItems.length > 1 ? 's' : '' }} recommended
            </h3>
            <div
              v-for="item in recommendedItems"
              :key="item.id"
              class="flex items-center gap-3 p-3 bg-amber-50 border border-amber-200 rounded-lg text-sm"
            >
              <span class="text-amber-500 font-bold shrink-0">&#9888;</span>
              <span class="text-gray-800">{{ item.label }}</span>
              <span class="text-xs text-amber-600 ml-auto">{{ item.category }}</span>
            </div>
          </div>

          <div v-if="outOfRangeItems.length" class="space-y-2">
            <h3 class="text-sm font-semibold text-amber-700">
              {{ outOfRangeItems.length }} measurement{{ outOfRangeItems.length > 1 ? 's' : '' }} out of range
            </h3>
            <div
              v-for="item in outOfRangeItems"
              :key="item.id"
              class="flex items-center justify-between p-3 bg-amber-50 border border-amber-200 rounded-lg text-sm"
            >
              <span class="text-gray-800">{{ item.label }}</span>
              <span class="font-mono text-amber-700">
                {{ data[`inspection_${item.id}`] }} {{ item.unit }}
                <span class="text-xs text-gray-400">({{ item.min }}–{{ item.max }})</span>
              </span>
            </div>
          </div>

          <div
            v-if="!failedItems.length && !recommendedItems.length && !outOfRangeItems.length"
            class="text-center py-6"
          >
            <div class="text-4xl mb-2">&#10003;</div>
            <p class="text-green-700 font-medium">All components rated Good</p>
            <p class="text-sm text-gray-500 mt-1">No repairs recommended at this time</p>
          </div>

          <!-- Technician notes -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">
              Additional technician notes
            </label>
            <textarea
              :value="data.technician_notes"
              @input="updateField('technician_notes', $event.target.value)"
              rows="3"
              placeholder="Any observations, recommendations, or context..."
              class="input-field"
            />
          </div>
        </div>
      </template>

      <!-- ━━━ Step 5: Review & Submit ━━━ -->
      <template #step-review="{ data }">
        <div class="space-y-4">
          <h3 class="text-sm font-semibold text-gray-500 uppercase tracking-wider">Summary</h3>

          <div class="bg-gray-50 rounded-xl p-4 space-y-3 text-sm">
            <div class="flex justify-between">
              <span class="text-gray-500">Vehicle</span>
              <span class="font-medium text-gray-900">{{ data.vehicle_number }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-gray-500">Odometer</span>
              <span class="font-medium text-gray-900">{{ Number(data.odometer_reading).toLocaleString() }} km</span>
            </div>
            <div class="flex justify-between">
              <span class="text-gray-500">Service Type</span>
              <span class="font-medium text-gray-900">{{ data.service_type }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-gray-500">Inspection Sheet</span>
              <span class="font-medium text-gray-900">Sheet {{ inspectionSheet.sheetId }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-gray-500">Checks Completed</span>
              <span class="font-medium text-gray-900">{{ completedCount }} / {{ inspectionSheet.items.length }}</span>
            </div>
            <div class="flex justify-between">
              <span class="text-gray-500">Issues Found</span>
              <span
                class="font-medium"
                :class="failedItems.length + outOfRangeItems.length > 0 ? 'text-red-600' : 'text-green-600'"
              >
                {{ failedItems.length + outOfRangeItems.length }}
              </span>
            </div>
          </div>

          <!-- Eliminate: No SLA, no cost, no audit fields visible here -->

          <div v-if="data.technician_notes" class="bg-gray-50 rounded-xl p-4 text-sm">
            <span class="text-gray-500">Notes:</span>
            <p class="text-gray-900 mt-1">{{ data.technician_notes }}</p>
          </div>

          <!-- Incomplete warning -->
          <div
            v-if="completedCount < inspectionSheet.items.length"
            class="p-3 bg-amber-50 border border-amber-200 rounded-lg text-sm text-amber-800"
          >
            {{ inspectionSheet.items.length - completedCount }} check(s) are incomplete. You can still submit, but it will be flagged.
          </div>
        </div>
      </template>
    </Wizard>

    <!-- Submission feedback -->
    <div v-if="submitError" class="mt-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">
      {{ submitError }}
    </div>
    <div v-if="isSubmitting" class="mt-4 p-3 bg-brand-50 border border-brand-200 rounded-lg text-sm text-brand-700 text-center">
      Submitting inspection...
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from "vue";
import { useRouter } from "vue-router";
import { callAPI } from "../utils/api.js";
import {
  getInspectionSheet,
  groupByCategory,
  STATUS_GOOD,
  STATUS_RECOMMENDED,
  STATUS_IMMEDIATE,
} from "../composables/useInspectionSheet.js";
import Wizard from "./Wizard.vue";

const router = useRouter();
const wizardRef = ref(null);
const submitError = ref("");
const isSubmitting = ref(false);
const activeCategory = ref(0);

// --- Service type options (Simplify: icons + large tap targets) ---
const serviceTypes = [
  { value: "Scheduled Maintenance", label: "Routine Service", icon: "\u{1F527}" },
  { value: "Breakdown Repair", label: "Breakdown", icon: "\u{1F6A8}" },
  { value: "Body & Paint", label: "Body & Paint", icon: "\u{1F3A8}" },
  { value: "Electrical", label: "Electrical", icon: "\u{26A1}" },
  { value: "Tyre & Alignment", label: "Tyres", icon: "\u{2B55}" },
  { value: "General Inspection", label: "Inspection", icon: "\u{1F50D}" },
];

// --- Form data ---
const formData = ref({
  vehicle_number: "",
  odometer_reading: null,
  service_type: "",
  complaint_description: "",
  technician_notes: "",
});

// --- Automate: Inspection sheet derived from odometer ---
const inspectionSheet = computed(() =>
  getInspectionSheet(formData.value.odometer_reading)
);

const inspectionCategories = computed(() =>
  [...groupByCategory(inspectionSheet.value.items).keys()]
);

const activeCategoryItems = computed(() => {
  const groups = groupByCategory(inspectionSheet.value.items);
  const catName = inspectionCategories.value[activeCategory.value];
  return groups.get(catName) || [];
});

// Reset active category when sheet changes
watch(() => inspectionSheet.value.sheetId, () => {
  activeCategory.value = 0;
});

// --- Computed helpers for findings step ---
// "Fail" in PRD 3-tier terms means *immediate* action required; Recommended is
// surfaced separately in the UI so SE can see both tiers distinctly.
const failedItems = computed(() =>
  inspectionSheet.value.items.filter(
    (item) => item.inputType === "three_tier" &&
              formData.value[`inspection_${item.id}`] === STATUS_IMMEDIATE
  )
);

const recommendedItems = computed(() =>
  inspectionSheet.value.items.filter(
    (item) => item.inputType === "three_tier" &&
              formData.value[`inspection_${item.id}`] === STATUS_RECOMMENDED
  )
);

const outOfRangeItems = computed(() =>
  inspectionSheet.value.items.filter((item) => {
    if (item.inputType !== "measurement") return false;
    return measurementOutOfRange(item, formData.value[`inspection_${item.id}`]);
  })
);

const completedCount = computed(() =>
  inspectionSheet.value.items.filter(
    (item) => formData.value[`inspection_${item.id}`] != null && formData.value[`inspection_${item.id}`] !== ""
  ).length
);

// --- Sheet banner color based on severity ---
const sheetBannerClass = computed(() => {
  const map = {
    A: "bg-green-50 border-green-200 text-green-800",
    B: "bg-amber-50 border-amber-200 text-amber-800",
    C: "bg-orange-50 border-orange-200 text-orange-800",
    D: "bg-red-50 border-red-200 text-red-800",
  };
  return map[inspectionSheet.value.sheetId];
});

// --- Helpers ---
function onOdometerChange(event, updateField) {
  const val = Number(event.target.value);
  updateField("odometer_reading", val > 0 ? val : null);
}

function measurementOutOfRange(item, value) {
  if (value == null || value === "") return false;
  const num = Number(value);
  return num < item.min || num > item.max;
}

function categoryComplete(catName, data) {
  const groups = groupByCategory(inspectionSheet.value.items);
  const items = groups.get(catName) || [];
  return items.every(
    (item) => data[`inspection_${item.id}`] != null && data[`inspection_${item.id}`] !== ""
  );
}

// --- Wizard steps ---
const wizardSteps = computed(() => [
  {
    id: "vehicle",
    title: "Vehicle",
    description: "Scan or enter the vehicle registration.",
    validate: (data) => {
      const errors = [];
      if (!data.vehicle_number?.trim()) errors.push("Vehicle number is required.");
      if (!data.odometer_reading || data.odometer_reading <= 0)
        errors.push("Enter a valid odometer reading.");
      return errors;
    },
  },
  {
    id: "service",
    title: "Service Type",
    description: "Tap the type of work needed.",
    validate: (data) => {
      if (!data.service_type) return ["Select a service type."];
      return [];
    },
  },
  {
    id: "inspection",
    title: `Inspection — Sheet ${inspectionSheet.value.sheetId}`,
    description: `${inspectionSheet.value.items.length} checks across ${inspectionCategories.value.length} categories.`,
    // No hard validation — technician can submit partial inspections
  },
  {
    id: "findings",
    title: "Findings",
    description: "Review what the inspection found.",
  },
  {
    id: "review",
    title: "Review & Submit",
    description: "Confirm everything looks right before submitting.",
  },
]);

function onStepChange(stepIdx) {
  // Reset category nav when entering inspection step
  if (wizardSteps.value[stepIdx]?.id === "inspection") {
    activeCategory.value = 0;
  }
}

// --- Submit ---
async function handleSubmit(data) {
  submitError.value = "";
  isSubmitting.value = true;

  try {
    // 1. Create the Job Card via our whitelisted API
    const inspectionResults = {};
    for (const item of inspectionSheet.value.items) {
      const key = `inspection_${item.id}`;
      if (data[key] != null && data[key] !== "") {
        inspectionResults[item.id] = {
          value: data[key],
          label: item.label,
          category: item.category,
          inputType: item.inputType,
        };
      }
    }

    const result = await callAPI("job_card.create_job_card_with_inspection", {
      vehicle_number: data.vehicle_number,
      odometer_reading: data.odometer_reading,
      service_type: data.service_type,
      complaint_description: data.complaint_description || "",
      technician_notes: data.technician_notes || "",
      inspection_sheet_id: inspectionSheet.value.sheetId,
      inspection_results: JSON.stringify(inspectionResults),
    });

    if (result.success) {
      router.push(`/service-portal/job-card/${result.data.name}`);
    } else {
      submitError.value = result.message || "Submission failed.";
    }
  } catch (e) {
    submitError.value =
      e?.exc_type === "ValidationError"
        ? e.message
        : e?.messages?.[0] || "Failed to create job card. Please try again.";
  } finally {
    isSubmitting.value = false;
  }
}
</script>

<style scoped>
.input-field {
  @apply w-full px-3 py-2.5 border border-gray-300 rounded-lg
         focus:ring-2 focus:ring-brand-500 focus:border-brand-500
         text-gray-900 placeholder-gray-400 transition-colors;
}
</style>
