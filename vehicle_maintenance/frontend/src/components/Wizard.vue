<!--
  Wizard — a long form, cut into steps of four or five fields.

  Steps of 4–5 fields, one idea per step, and validation on Continue rather than
  on keystroke. Errors surface as one panel above the navigation, where the
  reader is already looking when the step refuses to advance.

  Usage:
    <Wizard :steps="steps" @complete="onSubmit">
      <template #step-vehicle="{ data, updateField }">…</template>
    </Wizard>

  Steps: `{ id, title, description?, validate?(data) → string[] }`
-->
<template>
	<div class="mx-auto w-full max-w-form" data-density="comfortable">
		<NStepper
			:steps="steps"
			:current="currentStep"
			:furthest="furthestStep"
			class="mb-7"
			@go="goToStep"
		/>

		<div class="mb-5">
			<h2 class="text-title-lg text-ink">{{ activeStep.title }}</h2>
			<p v-if="activeStep.description" class="mt-0.5 text-body-sm text-muted">
				{{ activeStep.description }}
			</p>
		</div>

		<div class="min-h-[200px]">
			<slot
				:name="`step-${activeStep.id}`"
				:data="formData"
				:update-field="updateField"
				:errors="stepErrors"
			/>
		</div>

		<NAlert v-if="stepErrors.length" semantic="critical" :title="errorTitle" class="mt-4">
			<ul class="list-inside list-disc space-y-0.5">
				<li v-for="err in stepErrors" :key="err">{{ err }}</li>
			</ul>
		</NAlert>

		<div class="mt-7 flex items-center justify-between border-t border-hairline pt-5">
			<NButton v-if="currentStep > 0" icon="arrow-left" @click="prevStep">Back</NButton>
			<span v-else />

			<div class="flex items-center gap-3">
				<span class="tabular text-caption text-muted"
					>Step {{ currentStep + 1 }} of {{ steps.length }}</span
				>
				<NButton
					v-if="currentStep < steps.length - 1"
					variant="primary"
					size="lg"
					trailing-icon="arrow-right"
					:loading="isValidating"
					@click="nextStep"
				>
					Continue
				</NButton>
				<NButton
					v-else
					variant="primary"
					size="lg"
					icon="check"
					:loading="isSubmitting"
					@click="handleComplete"
				>
					{{ submitLabel }}
				</NButton>
			</div>
		</div>
	</div>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import { NStepper, NButton, NAlert } from "../ui/index.js";

const props = defineProps({
	steps: {
		type: Array,
		required: true,
		validator: (v) => v.length > 0 && v.every((s) => s.id && s.title),
	},
	modelValue: { type: Object, default: () => ({}) },
	submitLabel: { type: String, default: "Submit" },
});

const emit = defineEmits(["update:modelValue", "complete", "step-change"]);

const currentStep = ref(0);
const furthestStep = ref(0);
const formData = ref({ ...props.modelValue });
const stepErrors = ref([]);
const isValidating = ref(false);
const isSubmitting = ref(false);

const activeStep = computed(() => props.steps[currentStep.value]);
const errorTitle = computed(() =>
	stepErrors.value.length === 1 ? "One thing to fix" : `${stepErrors.value.length} things to fix`
);

watch(formData, (val) => emit("update:modelValue", val), { deep: true });

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
	if (!(await validateCurrentStep())) return;
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
	if (!(await validateCurrentStep())) return;
	isSubmitting.value = true;
	try {
		emit("complete", { ...formData.value });
	} finally {
		isSubmitting.value = false;
	}
}

defineExpose({ currentStep, formData, nextStep, prevStep, goToStep, validateCurrentStep });
</script>
