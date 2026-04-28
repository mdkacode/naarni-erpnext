<template>
	<!--
    JobCardOnboarding — gated post-creation flow.

      Step 1: Created  ✔ (auto)
      Step 2: Assigned (DM picks SE / Tech)
      Step 3: Inspection Checklist (every value of the resolved sheet)
      Step 4: Before-Images (chassis + odometer)
      Step 5: Done — server transitions Open → WIP

    Each step blocks the next; the API enforces the same gates so Desk and
    direct API callers also have to comply.
  -->
	<div class="max-w-3xl mx-auto px-4 py-6 pb-32">
		<div class="mb-4">
			<router-link
				:to="{ name: 'JobCardDetail', params: { name: jobCard } }"
				class="text-xs text-brand-600 hover:underline"
				>&larr; View Job Card</router-link
			>
			<h1 class="text-xl font-bold text-gray-900 mt-1">Onboarding — {{ jobCard }}</h1>
			<p class="text-xs text-gray-500 mt-0.5" v-if="state">
				{{ state.vehicle_number }} · {{ state.job_card_type }} · {{ state.workflow_state }}
			</p>
		</div>

		<!-- Stepper -->
		<ol class="flex items-center gap-2 mb-6 text-xs">
			<li v-for="(s, idx) in steps" :key="s.key" class="flex-1 flex items-center gap-2">
				<div
					class="h-7 w-7 shrink-0 rounded-full flex items-center justify-center font-semibold border-2"
					:class="
						stepDone(s.key)
							? 'border-green-500 bg-green-50 text-green-700'
							: activeStep === idx
							? 'border-brand-500 bg-brand-50 text-brand-700'
							: 'border-gray-200 text-gray-400'
					"
				>
					{{ stepDone(s.key) ? "✓" : idx + 1 }}
				</div>
				<span
					class="truncate"
					:class="activeStep === idx ? 'font-medium text-gray-900' : 'text-gray-500'"
				>
					{{ s.label }}
				</span>
				<div v-if="idx < steps.length - 1" class="flex-1 h-px bg-gray-200" />
			</li>
		</ol>

		<div v-if="loading" class="text-center py-10 text-sm text-gray-400">Loading…</div>
		<div v-else-if="loadError" class="p-4 rounded-lg bg-red-50 text-sm text-red-700">{{ loadError }}</div>

		<template v-else-if="state">
			<!-- ── Step 1: Created (always done) ── -->
			<section v-if="activeStep === 0" class="bg-white border border-gray-200 rounded-xl p-5">
				<h2 class="text-base font-semibold text-gray-900 mb-1">Job Card Created</h2>
				<p class="text-sm text-gray-600">
					The card <span class="font-mono">{{ jobCard }}</span> exists in
					<span class="font-medium">{{ state.workflow_state }}</span
					>. Move to step 2 to assign it.
				</p>
				<button
					type="button"
					@click="activeStep = 1"
					class="mt-4 px-4 py-2 text-sm font-semibold rounded-lg bg-brand-600 text-white"
				>
					Continue →
				</button>
			</section>

			<!-- ── Step 2: Assign ── -->
			<section v-else-if="activeStep === 1" class="bg-white border border-gray-200 rounded-xl p-5">
				<h2 class="text-base font-semibold text-gray-900 mb-1">Assign</h2>
				<p class="text-sm text-gray-500 mb-4">
					Pick at least one Service Engineer or Technician. Only Depot Manager / N. Maintenance Head
					can assign.
				</p>
				<div class="space-y-3">
					<div>
						<label class="block text-xs font-medium text-gray-700 mb-1">Service Engineer</label>
						<select v-model="assign.se" class="input-field">
							<option value="">— None —</option>
							<option v-for="u in seOptions" :key="u.name" :value="u.name">
								{{ u.full_name || u.name }}
							</option>
						</select>
					</div>
					<div>
						<label class="block text-xs font-medium text-gray-700 mb-1">Technician</label>
						<select v-model="assign.tech" class="input-field">
							<option value="">— None —</option>
							<option v-for="u in techOptions" :key="u.name" :value="u.name">
								{{ u.full_name || u.name }}
							</option>
						</select>
					</div>
					<p v-if="assignError" class="text-xs text-red-600">{{ assignError }}</p>
					<div class="flex gap-2">
						<button
							type="button"
							@click="activeStep = 0"
							class="flex-1 py-2 text-sm rounded-lg border border-gray-300"
						>
							Back
						</button>
						<button
							type="button"
							@click="saveAssignment"
							:disabled="(!assign.se && !assign.tech) || savingAssign"
							class="flex-[2] py-2 text-sm font-semibold rounded-lg bg-brand-600 text-white disabled:opacity-50"
						>
							{{ savingAssign ? "Saving…" : "Save & Continue →" }}
						</button>
					</div>
				</div>
			</section>

			<!-- ── Step 3: Inspection Checklist ── -->
			<section v-else-if="activeStep === 2" class="space-y-3">
				<div
					class="bg-white border border-gray-200 rounded-xl p-4 flex items-center justify-between flex-wrap gap-2"
				>
					<div>
						<h2 class="text-base font-semibold text-gray-900">
							{{ sheet?.title || "Checklist" }}
						</h2>
						<p class="text-xs text-gray-500">
							{{ doneCount }} of {{ sheet?.total_items || 0 }} answered
							<span
								class="ml-2 px-1.5 py-0.5 text-[10px] rounded-full"
								:class="
									completionPct >= 100
										? 'bg-green-100 text-green-700'
										: 'bg-amber-100 text-amber-700'
								"
							>
								{{ completionPct }}%
							</span>
						</p>
					</div>
					<button
						type="button"
						@click="saveAllResponses"
						:disabled="savingResponses"
						class="px-3 py-1.5 text-xs font-medium rounded-lg bg-brand-50 text-brand-700 hover:bg-brand-100 disabled:opacity-50"
					>
						{{ savingResponses ? "Saving…" : "Save Progress" }}
					</button>
				</div>

				<div
					v-if="!sheet?.applicable"
					class="bg-white border border-gray-200 rounded-xl p-4 text-sm text-gray-600"
				>
					{{ sheet?.reason || "No checklist required for this Job Card." }}
				</div>

				<div
					v-else-if="!sheet?.sections?.length"
					class="bg-amber-50 border border-amber-200 rounded-xl p-4 text-sm text-amber-800"
				>
					{{ sheet?.warning || "Template not loaded." }}
				</div>

				<div
					v-for="section in sheet?.sections || []"
					:key="section.section_code"
					class="bg-white border border-gray-200 rounded-xl overflow-hidden"
				>
					<div
						class="px-4 py-3 bg-gray-50 border-b border-gray-200 flex items-center justify-between"
					>
						<div>
							<p class="text-[10px] uppercase tracking-wide text-gray-400">
								Section {{ section.section_code }}
							</p>
							<h3 class="text-sm font-semibold text-gray-900">{{ section.section_title }}</h3>
						</div>
						<span class="text-xs text-gray-400">
							{{ section.items.filter((i) => responses[i.sr_no]?.status).length }} /
							{{ section.items.length }}
						</span>
					</div>

					<ul class="divide-y divide-gray-100">
						<li v-for="item in section.items" :key="item.sr_no" class="px-4 py-3">
							<div class="flex items-start justify-between gap-3 mb-2">
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
									class="py-2 text-xs font-medium rounded-lg border-2 transition-all"
									:class="
										responses[item.sr_no]?.status === opt.value
											? opt.activeClass
											: 'border-gray-200 text-gray-500 hover:border-gray-300'
									"
								>
									{{ opt.label }}
								</button>
							</div>

							<textarea
								v-if="needsRemarks(item.sr_no)"
								v-model="responses[item.sr_no].remarks"
								rows="2"
								placeholder="Remarks (required for RR / RI)"
								class="mt-2 w-full text-sm border border-gray-200 rounded-lg p-2"
							/>
						</li>
					</ul>
				</div>

				<p v-if="responsesError" class="text-xs text-red-600">{{ responsesError }}</p>

				<div class="flex gap-2 sticky bottom-0 bg-white py-3 border-t border-gray-200">
					<button
						type="button"
						@click="activeStep = 1"
						class="flex-1 py-2 text-sm rounded-lg border border-gray-300"
					>
						Back
					</button>
					<button
						type="button"
						@click="proceedFromChecklist"
						:disabled="!checklistComplete || savingResponses"
						class="flex-[2] py-2 text-sm font-semibold rounded-lg bg-brand-600 text-white disabled:opacity-50"
					>
						{{ savingResponses ? "Saving…" : "Save & Continue →" }}
					</button>
				</div>
			</section>

			<!-- ── Step 4: Before-Images ── -->
			<section v-else-if="activeStep === 3" class="bg-white border border-gray-200 rounded-xl p-5">
				<h2 class="text-base font-semibold text-gray-900 mb-1">Before-Images</h2>
				<p class="text-sm text-gray-500 mb-4">
					Upload both photos before proceeding. Required by the workflow gate.
				</p>

				<div class="grid sm:grid-cols-2 gap-4">
					<PhotoSlot
						label="Chassis / VIN plate"
						:existing-url="state.chassis_photo"
						field="chassis_photo"
						:job-card="jobCard"
						@uploaded="onChassisUploaded"
					/>
					<PhotoSlot
						label="Odometer dashboard"
						:existing-url="state.odometer_photo"
						field="odometer_photo"
						:job-card="jobCard"
						@uploaded="onOdometerUploaded"
					/>
				</div>

				<p v-if="photoError" class="mt-3 text-xs text-red-600">{{ photoError }}</p>

				<div class="mt-4 flex gap-2">
					<button
						type="button"
						@click="activeStep = 2"
						class="flex-1 py-2 text-sm rounded-lg border border-gray-300"
					>
						Back
					</button>
					<button
						type="button"
						@click="activeStep = 4"
						:disabled="!photosDone"
						class="flex-[2] py-2 text-sm font-semibold rounded-lg bg-brand-600 text-white disabled:opacity-50"
					>
						Continue →
					</button>
				</div>
			</section>

			<!-- ── Step 5: Proceed ── -->
			<section v-else class="bg-white border border-gray-200 rounded-xl p-5 text-center">
				<h2 class="text-base font-semibold text-gray-900 mb-1">Ready to start work</h2>
				<p class="text-sm text-gray-500 mb-4">
					All four onboarding gates are satisfied. Click below to transition the Job Card to
					<span class="font-medium">WIP</span>.
				</p>

				<ul class="text-left text-sm space-y-1 max-w-sm mx-auto mb-5">
					<li class="flex items-center gap-2"><span class="text-green-600">✓</span> Created</li>
					<li class="flex items-center gap-2">
						<span :class="state.assigned ? 'text-green-600' : 'text-red-600'">{{
							state.assigned ? "✓" : "✗"
						}}</span>
						Assigned
					</li>
					<li class="flex items-center gap-2">
						<span :class="state.inspection_done ? 'text-green-600' : 'text-red-600'">{{
							state.inspection_done ? "✓" : "✗"
						}}</span>
						Inspection ({{ state.inspection_pct }}%)
					</li>
					<li class="flex items-center gap-2">
						<span :class="state.photos_done ? 'text-green-600' : 'text-red-600'">{{
							state.photos_done ? "✓" : "✗"
						}}</span>
						Before-Images
					</li>
				</ul>

				<button
					type="button"
					@click="finalize"
					:disabled="!state.ready_to_proceed || finalizing"
					class="px-6 py-3 text-sm font-semibold rounded-lg bg-green-600 text-white disabled:opacity-50"
				>
					{{ finalizing ? "Starting…" : "Start Work (Move to WIP)" }}
				</button>
				<p v-if="finalizeError" class="mt-3 text-xs text-red-600">{{ finalizeError }}</p>
				<div class="mt-3">
					<button
						type="button"
						@click="activeStep = 3"
						class="text-xs text-gray-500 hover:underline"
					>
						Back
					</button>
				</div>
			</section>
		</template>
	</div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { call } from "frappe-ui";
import { onboardingApi } from "../composables/useOnboardingApi.js";
import PhotoSlot from "../components/PhotoSlot.vue";

const route = useRoute();
const router = useRouter();
const jobCard = route.params.jobCard;

const steps = [
	{ key: "created", label: "Created" },
	{ key: "assigned", label: "Assign" },
	{ key: "inspection", label: "Checklist" },
	{ key: "photos", label: "Before-Images" },
	{ key: "proceed", label: "Proceed" },
];

const activeStep = ref(0);
const loading = ref(true);
const loadError = ref("");

const state = ref(null);
const sheet = ref(null);
const responses = reactive({});

// Step 2
const seOptions = ref([]);
const techOptions = ref([]);
const assign = reactive({ se: "", tech: "" });
const savingAssign = ref(false);
const assignError = ref("");

// Step 3
const savingResponses = ref(false);
const responsesError = ref("");
const statusOptions = [
	{ value: "OK", label: "OK", activeClass: "border-green-500 bg-green-50 text-green-700" },
	{ value: "RR", label: "RR", activeClass: "border-amber-500 bg-amber-50 text-amber-700" },
	{ value: "RI", label: "RI", activeClass: "border-red-500 bg-red-50 text-red-700" },
	{ value: "NA", label: "NA", activeClass: "border-gray-400 bg-gray-100 text-gray-600" },
];

// Step 4
const photoError = ref("");

// Step 5
const finalizing = ref(false);
const finalizeError = ref("");

const completionPct = computed(() => {
	if (!sheet.value?.total_items) return 100;
	return Math.round(100 * (doneCount.value / sheet.value.total_items));
});

const doneCount = computed(() => {
	if (!sheet.value?.sections) return 0;
	let n = 0;
	for (const s of sheet.value.sections) for (const i of s.items) if (responses[i.sr_no]?.status) n++;
	return n;
});

const checklistComplete = computed(() => {
	if (!sheet.value?.applicable) return true;
	if (!sheet.value?.sections) return false;
	for (const s of sheet.value.sections) {
		for (const i of s.items) {
			const r = responses[i.sr_no];
			if (!r?.status) return false;
			if ((r.status === "RR" || r.status === "RI") && !(r.remarks || "").trim()) return false;
		}
	}
	return true;
});

const photosDone = computed(() => !!state.value?.chassis_photo && !!state.value?.odometer_photo);

function stepDone(key) {
	if (!state.value) return false;
	if (key === "created") return true;
	if (key === "assigned") return !!state.value.assigned;
	if (key === "inspection") return !!state.value.inspection_done;
	if (key === "photos") return !!state.value.photos_done;
	if (key === "proceed")
		return state.value.workflow_state !== "Open" && state.value.workflow_state !== "Reopened";
	return false;
}

function setStatus(sr, value) {
	if (!responses[sr]) responses[sr] = { status: null, remarks: "", photo: "" };
	responses[sr].status = value;
}
function needsRemarks(sr) {
	const s = responses[sr]?.status;
	return s === "RR" || s === "RI";
}

async function loadAll() {
	loading.value = true;
	loadError.value = "";
	try {
		const [stateRes, sheetRes] = await Promise.all([
			onboardingApi.state(jobCard),
			onboardingApi.sheet(jobCard),
		]);
		state.value = stateRes.data;
		sheet.value = sheetRes.data;
		// Hydrate responses from server
		if (sheet.value?.responses) {
			for (const [sr, r] of Object.entries(sheet.value.responses)) {
				responses[Number(sr)] = {
					status: r.status || null,
					remarks: r.remarks || "",
					photo: r.photo || "",
				};
			}
		}
		// Pre-fill assignment dropdowns
		assign.se = state.value.assigned_service_engineer || "";
		assign.tech = state.value.assigned_technician || "";
		// Decide which step the user lands on
		if (!state.value.assigned) activeStep.value = 1;
		else if (!state.value.inspection_done) activeStep.value = 2;
		else if (!state.value.photos_done) activeStep.value = 3;
		else activeStep.value = 4;
	} catch (e) {
		loadError.value = e?.messages?.[0] || e?.message || "Failed to load onboarding state";
	} finally {
		loading.value = false;
	}
}

async function loadAssignmentOptions() {
	try {
		const [se, tech] = await Promise.all([
			call("vehicle_maintenance.api.job_card.list_users_by_role", { role: "Service Engineer" }),
			call("vehicle_maintenance.api.job_card.list_users_by_role", { role: "Technician" }),
		]);
		seOptions.value = se?.data || [];
		techOptions.value = tech?.data || [];
	} catch {
		seOptions.value = [];
		techOptions.value = [];
	}
}

async function saveAssignment() {
	savingAssign.value = true;
	assignError.value = "";
	try {
		const res = await onboardingApi.assign(jobCard, assign.se || null, assign.tech || null);
		state.value = { ...state.value, ...res.data };
		activeStep.value = 2;
	} catch (e) {
		assignError.value = e?.messages?.[0] || e?.message || "Assignment failed";
	} finally {
		savingAssign.value = false;
	}
}

async function saveAllResponses() {
	savingResponses.value = true;
	responsesError.value = "";
	try {
		const payload = [];
		for (const [sr, r] of Object.entries(responses)) {
			if (!r?.status) continue;
			payload.push({
				sr_no: Number(sr),
				status: r.status,
				remarks: r.remarks || "",
				photo: r.photo || "",
			});
		}
		const res = await onboardingApi.saveResponses(jobCard, payload);
		state.value = {
			...state.value,
			inspection_pct: res.data.completion_pct,
			inspection_done: res.data.completion_pct >= 100,
		};
	} catch (e) {
		responsesError.value = e?.messages?.[0] || e?.message || "Save failed";
	} finally {
		savingResponses.value = false;
	}
}

async function proceedFromChecklist() {
	await saveAllResponses();
	if (!responsesError.value && checklistComplete.value) activeStep.value = 3;
}

function onChassisUploaded(url) {
	state.value.chassis_photo = url;
	finalizePhotoStateRefresh();
}
function onOdometerUploaded(url) {
	state.value.odometer_photo = url;
	finalizePhotoStateRefresh();
}
async function finalizePhotoStateRefresh() {
	// Pull fresh gate status; the PhotoSlot already POST'd attach_before_photos.
	try {
		const res = await onboardingApi.state(jobCard);
		state.value = res.data;
	} catch {
		/* non-fatal — local mutations above already reflected the upload */
	}
}

async function finalize() {
	finalizing.value = true;
	finalizeError.value = "";
	try {
		await onboardingApi.complete(jobCard);
		router.push({ name: "JobCardDetail", params: { name: jobCard } });
	} catch (e) {
		finalizeError.value = e?.messages?.[0] || e?.message || "Could not start work";
	} finally {
		finalizing.value = false;
	}
}

onMounted(async () => {
	await loadAll();
	loadAssignmentOptions();
});
</script>
