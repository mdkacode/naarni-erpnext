<template>
	<!--
    SectionInspector — single-section view in the structured PMS checklist flow.

    EAS applied:
      Eliminate — inspection_method shown as a tiny pill, not a tappable field.
      Automate  — section timer auto-starts on mount, auto-stops on Next/Back.
      Simplify  — one section per screen, large OK / RR / RI tap targets per item.
  -->
	<div class="space-y-4">
		<div class="sticky top-0 z-10 bg-white border-b border-gray-200 -mx-4 px-4 py-3">
			<div class="flex items-center justify-between">
				<div>
					<p class="text-xs uppercase tracking-wide text-gray-500">
						Section {{ section.section_code }} · {{ index + 1 }} of {{ total }}
					</p>
					<h2 class="text-base font-semibold text-gray-900 leading-tight">
						{{ section.section_title }}
					</h2>
				</div>
				<div class="text-right">
					<p class="text-[10px] uppercase tracking-wide text-gray-400">Elapsed</p>
					<p class="font-mono text-sm font-semibold text-brand-700 tabular-nums">
						{{ elapsedLabel }}
					</p>
				</div>
			</div>
			<div class="mt-2 h-1 bg-gray-100 rounded-full overflow-hidden">
				<div class="h-full bg-brand-500 transition-all" :style="{ width: `${donePct}%` }"></div>
			</div>
		</div>

		<div class="space-y-3 pb-32">
			<div
				v-for="item in section.items"
				:key="item.sr_no"
				class="bg-white border border-gray-200 rounded-xl p-4"
			>
				<div class="flex items-start justify-between gap-3 mb-3">
					<div class="flex-1">
						<p class="text-sm font-medium text-gray-900 leading-snug">
							<span class="text-xs text-gray-400 mr-1">#{{ item.sr_no }}</span>
							{{ item.parameter }}
						</p>
					</div>
					<span
						class="shrink-0 inline-block px-2 py-0.5 text-[10px] font-medium rounded-full bg-gray-100 text-gray-600 whitespace-nowrap"
					>
						{{ item.inspection_method }}
					</span>
				</div>

				<div class="grid grid-cols-4 gap-2">
					<button
						v-for="opt in statusOptions"
						:key="opt.value"
						type="button"
						@click="setStatus(item.sr_no, opt.value)"
						class="py-2.5 text-xs font-medium rounded-lg border-2 transition-all"
						:class="
							responses[item.sr_no]?.status === opt.value
								? opt.activeClass
								: 'border-gray-200 text-gray-500 hover:border-gray-300'
						"
					>
						{{ opt.label }}
					</button>
				</div>

				<div v-if="needsRemarks(item.sr_no)" class="mt-3">
					<textarea
						v-model="responses[item.sr_no].remarks"
						rows="2"
						placeholder="Add a brief note (required for RR / RI)"
						class="w-full text-sm border border-gray-200 rounded-lg p-2 focus:ring-1 focus:ring-brand-500 focus:border-brand-500"
					/>
				</div>
			</div>
		</div>

		<div class="fixed bottom-0 left-0 right-0 bg-white border-t border-gray-200 px-4 py-3 z-20">
			<div class="max-w-2xl mx-auto flex items-center gap-3">
				<button
					type="button"
					@click="$emit('back')"
					:disabled="index === 0 || saving"
					class="flex-1 py-3 text-sm font-medium rounded-lg border border-gray-300 text-gray-700 disabled:opacity-50"
				>
					Back
				</button>
				<button
					type="button"
					@click="goNext"
					:disabled="!canAdvance || saving"
					class="flex-[2] py-3 text-sm font-semibold rounded-lg bg-brand-600 text-white disabled:opacity-50"
				>
					{{ saving ? "Saving…" : index === total - 1 ? "Finish" : "Next Section" }}
				</button>
			</div>
			<p v-if="error" class="mt-2 text-xs text-red-600 text-center">{{ error }}</p>
		</div>
	</div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
import { checklistApi } from "../composables/useChecklistApi.js";

const props = defineProps({
	jobCard: { type: String, required: true },
	section: { type: Object, required: true },
	index: { type: Number, required: true },
	total: { type: Number, required: true },
	initialResponses: { type: Object, default: () => ({}) },
});
const emit = defineEmits(["next", "back"]);

const statusOptions = [
	{ value: "OK", label: "OK", activeClass: "border-green-500 bg-green-50 text-green-700" },
	{ value: "RR", label: "RR", activeClass: "border-amber-500 bg-amber-50 text-amber-700" },
	{ value: "RI", label: "RI", activeClass: "border-red-500 bg-red-50 text-red-700" },
	{ value: "NA", label: "NA", activeClass: "border-gray-400 bg-gray-100 text-gray-600" },
];

const responses = reactive({ ...props.initialResponses });
for (const item of props.section.items) {
	if (!responses[item.sr_no]) {
		responses[item.sr_no] = { status: null, remarks: "" };
	}
}

function setStatus(sr, value) {
	if (!responses[sr]) responses[sr] = { status: null, remarks: "" };
	responses[sr].status = value;
}
function needsRemarks(sr) {
	const s = responses[sr]?.status;
	return s === "RR" || s === "RI";
}

const elapsed = ref(0);
let tick = null;
let startedAt = null;

const elapsedLabel = computed(() => {
	const m = Math.floor(elapsed.value / 60)
		.toString()
		.padStart(2, "0");
	const s = (elapsed.value % 60).toString().padStart(2, "0");
	return `${m}:${s}`;
});

const doneCount = computed(() => props.section.items.filter((i) => responses[i.sr_no]?.status).length);
const donePct = computed(() => Math.round((doneCount.value / props.section.items.length) * 100));
const canAdvance = computed(() => {
	// Every item needs a status; RR/RI also need remarks
	return props.section.items.every((i) => {
		const r = responses[i.sr_no];
		if (!r?.status) return false;
		if ((r.status === "RR" || r.status === "RI") && !(r.remarks || "").trim()) return false;
		return true;
	});
});

const saving = ref(false);
const error = ref("");

async function goNext() {
	saving.value = true;
	error.value = "";
	try {
		const payload = props.section.items.map((i) => ({
			sr_no: i.sr_no,
			status: responses[i.sr_no]?.status,
			remarks: responses[i.sr_no]?.remarks || "",
		}));
		await checklistApi.endSection(props.jobCard, props.section.section_code, payload);
		stopTimer();
		emit("next", { responses: { ...responses } });
	} catch (e) {
		error.value = e?.message || "Failed to save section";
	} finally {
		saving.value = false;
	}
}

function stopTimer() {
	if (tick) clearInterval(tick);
	tick = null;
}

onMounted(async () => {
	try {
		const res = await checklistApi.startSection(props.jobCard, props.section.section_code);
		startedAt = new Date(res.data?.started_at || Date.now());
	} catch (e) {
		startedAt = new Date();
	}
	tick = setInterval(() => {
		elapsed.value = Math.floor((Date.now() - startedAt.getTime()) / 1000);
	}, 1000);
});

onBeforeUnmount(() => {
	stopTimer();
});
</script>
