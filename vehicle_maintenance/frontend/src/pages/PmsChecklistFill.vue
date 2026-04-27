<template>
	<!--
    PmsChecklistFill — section-by-section structured PMS inspection.

    Loads the 103-item template from the server, paginates the technician through
    sections I → XVI, captures per-section timing automatically, and submits.
  -->
	<div class="max-w-2xl mx-auto px-4 py-6">
		<div class="mb-4">
			<router-link
				:to="{ name: 'JobCardDetail', params: { name: jobCard } }"
				class="text-xs text-brand-600 hover:underline"
				>&larr; Back to Job Card</router-link
			>
			<h1 class="text-xl font-bold text-gray-900 mt-1">{{ template?.title || "PMS Inspection" }}</h1>
			<p class="text-xs text-gray-500 mt-0.5">
				Job Card: <span class="font-mono">{{ jobCard }}</span>
				<span v-if="template">
					· {{ template.total_items }} checks · {{ template.sections.length }} sections</span
				>
			</p>
		</div>

		<div v-if="loading" class="text-center py-10 text-sm text-gray-400">Loading checklist…</div>
		<div v-else-if="loadError" class="p-4 rounded-lg bg-red-50 text-sm text-red-700">{{ loadError }}</div>

		<SectionInspector
			v-else-if="!finished && currentSection"
			:key="currentSection.section_code"
			:job-card="jobCard"
			:section="currentSection"
			:index="activeIndex"
			:total="template.sections.length"
			:initial-responses="savedBySection[currentSection.section_code] || {}"
			@next="onNext"
			@back="onBack"
		/>

		<div v-else-if="finished" class="bg-white border border-gray-200 rounded-xl p-6 text-center">
			<h2 class="text-lg font-semibold text-gray-900">All sections complete</h2>
			<p class="text-sm text-gray-500 mt-1">
				Review the submitted responses on the Job Card. Per-section timing has been recorded.
			</p>
			<div class="mt-4 flex flex-col gap-2 max-w-xs mx-auto">
				<button
					type="button"
					@click="finalize"
					:disabled="submitting"
					class="py-3 text-sm font-semibold rounded-lg bg-brand-600 text-white disabled:opacity-50"
				>
					{{ submitting ? "Submitting…" : "Finalize Inspection" }}
				</button>
				<p v-if="submitError" class="text-xs text-red-600">{{ submitError }}</p>
				<p v-if="finalized" class="text-xs text-green-600">
					✔ Inspection finalized at {{ finalized }}
				</p>
			</div>
		</div>
	</div>
</template>

<script setup>
import { ref, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import SectionInspector from "../components/SectionInspector.vue";
import { checklistApi } from "../composables/useChecklistApi.js";

const route = useRoute();
const jobCard = route.params.jobCard;

const template = ref(null);
const loading = ref(true);
const loadError = ref("");
const activeIndex = ref(0);
const finished = ref(false);
const savedBySection = ref({});
const submitting = ref(false);
const submitError = ref("");
const finalized = ref("");

const currentSection = computed(() => template.value?.sections?.[activeIndex.value] || null);

function onNext({ responses }) {
	if (currentSection.value) {
		savedBySection.value[currentSection.value.section_code] = responses;
	}
	if (activeIndex.value < template.value.sections.length - 1) {
		activeIndex.value += 1;
	} else {
		finished.value = true;
	}
}
function onBack() {
	if (activeIndex.value > 0) activeIndex.value -= 1;
}

async function finalize() {
	submitting.value = true;
	submitError.value = "";
	try {
		const res = await checklistApi.submit(jobCard);
		finalized.value = res.data?.inspection_completed_at || "now";
	} catch (e) {
		submitError.value = e?.message || "Submission failed";
	} finally {
		submitting.value = false;
	}
}

onMounted(async () => {
	try {
		const res = await checklistApi.getTemplate({ job_card: jobCard });
		template.value = res.data;
	} catch (e) {
		loadError.value = e?.message || "Could not load checklist template";
	} finally {
		loading.value = false;
	}
});
</script>
