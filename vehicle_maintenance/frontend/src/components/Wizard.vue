<template>
  <!--
    Wizard — generic multi-step form component.
    Replaces long scrolling Frappe forms with a guided, step-by-step flow
    following the EAS (Eliminate, Automate, Simplify) framework.

    Usage:
      <Wizard :steps="steps" @complete="onSubmit">
        <template #step-vehicle="{ data, updateField }">
          <input :value="data.vehicle_number" @input="updateField('vehicle_number', $event.target.value)" />
        </template>
        <template #step-service="{ data, updateField }">
          ...
        </template>
      </Wizard>

    Props:
      steps: Array of { id, title, description?, validate? }
      modelValue: Optional initial form data object
  -->
  <div class="w-full max-w-2xl mx-auto">
    <!-- Step indicator -->
    <nav class="mb-8" aria-label="Progress">
      <ol class="flex items-center">
        <li
          v-for="(step, idx) in steps"
          :key="step.id"
          class="flex items-center"
          :class="{ 'flex-1': idx < steps.length - 1 }"
        >
          <!-- Step circle -->
          <button
            type="button"
            class="flex items-center justify-center w-10 h-10 rounded-full border-2 text-sm font-semibold transition-colors focus:outline-none focus:ring-2 focus:ring-brand-500 focus:ring-offset-2"
            :class="stepCircleClass(idx)"
            :disabled="idx > furthestStep"
            @click="goToStep(idx)"
            :aria-current="idx === currentStep ? 'step' : undefined"
          >
            <template v-if="idx < currentStep">
              <!-- Checkmark for completed steps -->
              <svg class="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd" />
              </svg>
            </template>
            <template v-else>
              {{ idx + 1 }}
            </template>
          </button>

          <!-- Connector line -->
          <div
            v-if="idx < steps.length - 1"
            class="flex-1 h-0.5 mx-3 transition-colors"
            :class="idx < currentStep ? 'bg-brand-500' : 'bg-gray-200'"
          />
        </li>
      </ol>
    </nav>

    <!-- Step header -->
    <div class="mb-6">
      <h2 class="text-xl font-semibold text-gray-900">
        {{ activeStep.title }}
      </h2>
      <p v-if="activeStep.description" class="mt-1 text-sm text-gray-500">
        {{ activeStep.description }}
      </p>
    </div>

    <!-- Step content (named slot) -->
    <div class="min-h-[200px]">
      <slot
        :name="`step-${activeStep.id}`"
        :data="formData"
        :updateField="updateField"
        :errors="stepErrors"
      />
    </div>

    <!-- Validation error banner -->
    <div
      v-if="stepErrors.length"
      class="mt-4 p-3 bg-red-50 border border-red-200 rounded-lg"
    >
      <ul class="list-disc list-inside text-sm text-red-700">
        <li v-for="err in stepErrors" :key="err">{{ err }}</li>
      </ul>
    </div>

    <!-- Navigation buttons -->
    <div class="mt-8 flex items-center justify-between border-t border-gray-200 pt-6">
      <button
        v-if="currentStep > 0"
        type="button"
        class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
        @click="prevStep"
      >
        Back
      </button>
      <div v-else />

      <div class="flex items-center gap-3">
        <span class="text-xs text-gray-400">
          Step {{ currentStep + 1 }} of {{ steps.length }}
        </span>
        <button
          v-if="currentStep < steps.length - 1"
          type="button"
          class="px-6 py-2 text-sm font-medium text-white bg-brand-600 rounded-lg hover:bg-brand-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="isValidating"
          @click="nextStep"
        >
          Continue
        </button>
        <button
          v-else
          type="button"
          class="px-6 py-2 text-sm font-medium text-white bg-green-600 rounded-lg hover:bg-green-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="isSubmitting"
          @click="handleComplete"
        >
          {{ submitLabel }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch } from "vue";

const props = defineProps({
  /**
   * Step definitions.
   * Each: { id: string, title: string, description?: string, validate?: (data) => string[] }
   * validate() should return an array of error messages (empty = valid).
   */
  steps: {
    type: Array,
    required: true,
    validator: (v) =>
      v.length > 0 && v.every((s) => s.id && s.title),
  },
  /** Initial form data. Wizard manages a reactive copy. */
  modelValue: {
    type: Object,
    default: () => ({}),
  },
  /** Label for the final submit button. */
  submitLabel: {
    type: String,
    default: "Submit",
  },
});

const emit = defineEmits(["update:modelValue", "complete", "step-change"]);

// --- State ---
const currentStep = ref(0);
const furthestStep = ref(0);
const formData = ref({ ...props.modelValue });
const stepErrors = ref([]);
const isValidating = ref(false);
const isSubmitting = ref(false);

const activeStep = computed(() => props.steps[currentStep.value]);

// Sync formData back to parent via v-model
watch(formData, (val) => emit("update:modelValue", val), { deep: true });

// --- Methods ---

function updateField(field, value) {
  formData.value[field] = value;
}

async function validateCurrentStep() {
  stepErrors.value = [];
  const step = activeStep.value;
  if (!step.validate) return true;

  isValidating.value = true;
  try {
    const errors = await step.validate(formData.value);
    stepErrors.value = errors || [];
    return stepErrors.value.length === 0;
  } finally {
    isValidating.value = false;
  }
}

async function nextStep() {
  const valid = await validateCurrentStep();
  if (!valid) return;

  if (currentStep.value < props.steps.length - 1) {
    currentStep.value++;
    furthestStep.value = Math.max(furthestStep.value, currentStep.value);
    emit("step-change", currentStep.value);
  }
}

function prevStep() {
  stepErrors.value = [];
  if (currentStep.value > 0) {
    currentStep.value--;
    emit("step-change", currentStep.value);
  }
}

function goToStep(idx) {
  if (idx <= furthestStep.value) {
    stepErrors.value = [];
    currentStep.value = idx;
    emit("step-change", currentStep.value);
  }
}

async function handleComplete() {
  const valid = await validateCurrentStep();
  if (!valid) return;

  isSubmitting.value = true;
  try {
    emit("complete", { ...formData.value });
  } finally {
    isSubmitting.value = false;
  }
}

function stepCircleClass(idx) {
  if (idx < currentStep.value) {
    return "border-brand-600 bg-brand-600 text-white";
  }
  if (idx === currentStep.value) {
    return "border-brand-600 bg-white text-brand-600";
  }
  return "border-gray-300 bg-white text-gray-400";
}

// Expose for parent access via template ref
defineExpose({
  currentStep,
  formData,
  nextStep,
  prevStep,
  goToStep,
  validateCurrentStep,
});
</script>
