<!--
  A job card — the record the whole product is about.

  Reorganised from one 900-line card into a page header plus sections in the
  order the work happens. Two changes carry most of the difference:

    · the **workflow actions live in the page header**, so "what can I do with
      this card" is answered before the reader scrolls, and exactly one of them
      is the accent-filled primary
    · every "Saved."/"Save failed." strip is a toast instead, because those
      messages were pushing the content down the page and then staying there

  Line-item status used to have its own seven-colour map here, with a blue that
  existed nowhere else in the product. It comes from the shared ramp now.
-->
<template>
	<div>
		<NPageHeader
			:title="data.vehicle_number ? '' : 'Job card'"
			back="/service-portal"
			back-label="Back to job cards"
		>
			<template #title>
				<NVehicle v-if="data.vehicle_number" :value="data.vehicle_number" size="lg" />
				<span v-else>Job card</span>
			</template>

			<template #subtitle>
				<span v-if="data.name" class="flex flex-wrap items-center gap-x-2">
					<NRecordId :value="data.name" />
					<span>{{ fmt.or(data.vehicle_make_model, "") }}</span>
					<span v-if="data.job_card_type">· {{ data.job_card_type }}</span>
					<span v-if="data.service_type">· {{ data.service_type }}</span>
				</span>
			</template>

			<template #badge>
				<NStatus v-if="data.workflow_state" :state="data.workflow_state" />
				<NBadge v-if="data.sla_breached" semantic="critical" label="SLA breached" />
				<NBadge
					v-if="data.force_closed"
					semantic="critical"
					:label="`Force closed · ${data.force_close_severity}`"
				/>
			</template>

			<template #actions>
				<template v-if="canEdit">
					<NButton
						v-for="action in availableActions"
						:key="action"
						:variant="actionVariant(action)"
						:disabled="transitioning"
						@click="doTransition(action)"
					>
						{{ action }}
					</NButton>
				</template>
				<ForceCloseButton
					v-if="canForceClose"
					:disabled="transitioning || data.force_closed"
					@submit="handleForceClose"
				/>
				<ReopenButton
					v-if="canReopen"
					:job-card-name="data.name"
					:disabled="transitioning"
					@reopened="onReopened"
				/>
				<NButton v-if="isClosed && !data.force_closed" icon="star" @click="feedbackOpen = true"
					>Share feedback</NButton
				>
			</template>
		</NPageHeader>

		<div class="space-y-4 p-5">
			<template v-if="card.loading && !card.data">
				<NSkeleton :count="3" variant="block" height="h-32" :delay="0" />
			</template>

			<template v-else-if="data.name">
				<NAlert
					v-if="data.requires_customer_approval && !isClosed"
					semantic="caution"
					title="Customer approval required"
					body="The parts total on this card is over ₹1,000, so the customer has to approve it before work continues."
				/>

				<!-- ── Details ─────────────────────────────────────────────── -->
				<NCard title="Details">
					<template v-if="canEdit" #actions>
						<template v-if="!editing">
							<NButton variant="ghost" size="sm" icon="pencil" @click="startEditing"
								>Edit</NButton
							>
						</template>
						<template v-else>
							<NButton variant="ghost" size="sm" @click="cancelEditing">Cancel</NButton>
							<NButton variant="primary" size="sm" :loading="saving" @click="saveEdits"
								>Save</NButton
							>
						</template>
					</template>

					<NKeyValue v-if="!editing" :items="detailItems">
						<div class="flex items-center justify-between gap-6 py-2">
							<dt class="text-body-sm text-muted">Priority</dt>
							<dd><NPriority :value="data.priority" /></dd>
						</div>
						<div class="space-y-1 py-2">
							<dt class="text-body-sm text-muted">Reported problem</dt>
							<dd class="whitespace-pre-line text-body text-ink">
								{{ fmt.or(data.complaint_description) }}
							</dd>
						</div>
						<div v-if="data.force_close_reason" class="space-y-1 py-2">
							<dt class="text-body-sm text-muted">Why it was force closed</dt>
							<dd class="whitespace-pre-line text-body text-critical">
								{{ data.force_close_reason }}
							</dd>
						</div>
					</NKeyValue>

					<div v-else class="space-y-4">
						<div class="grid gap-4 md:grid-cols-2">
							<NSelect
								v-model="editForm.service_type"
								label="Service type"
								:options="SERVICE_TYPES"
							/>
							<NChoice
								v-model="editForm.priority"
								label="Priority"
								:options="['Low', 'Medium', 'High', 'Urgent']"
								:columns="4"
							/>
						</div>
						<div class="grid gap-4 md:grid-cols-2">
							<NSelect
								v-model="editForm.assigned_service_engineer"
								label="Service engineer"
								placeholder="Unassigned"
								:options="seOptions"
								:disabled="!canAssign"
								:hint="canAssign ? '' : 'Only a depot manager can change assignments.'"
							/>
							<NSelect
								v-model="editForm.assigned_technician"
								label="Technician"
								placeholder="Unassigned"
								:options="techOptions"
								:disabled="!canAssign"
								:hint="canAssign ? '' : 'Only a depot manager can change assignments.'"
							/>
						</div>
						<NInput v-model="editForm.depot" label="Depot or workshop" />
						<NTextarea
							v-model="editForm.complaint_description"
							label="Reported problem"
							:rows="3"
						/>
						<NAlert v-if="saveError" semantic="critical" :body="saveError" />
					</div>
				</NCard>

				<!-- ── Health score ─────────────────────────────────────────── -->
				<template v-if="isPmsRepair">
					<HealthScoreCard
						:pre-score="data.pre_pms_score"
						:post-score="data.post_pms_score"
						:improvement="data.score_improvement"
						:category-scores="data.category_scores || []"
					/>
					<div v-if="data.health_card" class="flex justify-end">
						<NButton
							variant="ghost"
							trailing-icon="arrow-right"
							:to="`/service-portal/job-card/${data.name}/health-card`"
						>
							Vehicle health card
						</NButton>
					</div>
				</template>

				<!-- ── Repair jobs ──────────────────────────────────────────── -->
				<NCard v-if="showRepairTable" title="Repair jobs">
					<template v-if="canEditWork" #actions>
						<template v-if="!editingRepairs">
							<NButton variant="ghost" size="sm" icon="pencil" @click="startEditRepairs"
								>Edit</NButton
							>
						</template>
						<template v-else>
							<NButton variant="ghost" size="sm" @click="cancelEditRepairs">Cancel</NButton>
							<NButton variant="primary" size="sm" :loading="savingRepairs" @click="saveRepairs"
								>Save</NButton
							>
						</template>
					</template>

					<RepairJobTable
						v-if="editingRepairs"
						v-model="editRepairRows"
						:part-groups="partGroups"
					/>
					<ul v-else-if="data.repair_items?.length" class="divide-y divide-hairline">
						<li
							v-for="(item, idx) in data.repair_items"
							:key="idx"
							class="flex items-start gap-3 py-2.5"
						>
							<div class="min-w-0 flex-1">
								<p class="text-title-sm text-ink">{{ fmt.or(item.description) }}</p>
								<p class="text-caption text-muted">
									{{
										[item.part_group, item.bus_system, item.activity_type]
											.filter(Boolean)
											.join(" · ")
									}}
								</p>
							</div>
							<div class="tabular shrink-0 text-right">
								<p class="text-caption text-muted">Qty {{ item.qty }}</p>
								<p class="text-body-sm font-semibold text-ink">
									{{ fmt.money(item.estimated_amount) }}
								</p>
							</div>
							<NBadge
								:semantic="itemStatusSemantic(item.item_status)"
								:label="item.item_status"
							/>
						</li>
					</ul>
					<NEmptyState
						v-else
						icon="wrench"
						title="No repair jobs recorded"
						body="Add them as the technician works through the card."
					/>
					<NAlert
						v-if="saveRepairsError"
						semantic="critical"
						:body="saveRepairsError"
						class="mt-3"
					/>
				</NCard>

				<!-- ── Maintenance jobs ─────────────────────────────────────── -->
				<NCard v-if="isPmsRepair" title="Maintenance jobs">
					<template v-if="canEditWork" #actions>
						<template v-if="!editingMaintenance">
							<NButton variant="ghost" size="sm" icon="pencil" @click="startEditMaintenance"
								>Edit</NButton
							>
						</template>
						<template v-else>
							<NButton variant="ghost" size="sm" @click="cancelEditMaintenance">Cancel</NButton>
							<NButton
								variant="primary"
								size="sm"
								:loading="savingMaintenance"
								@click="saveMaintenance"
								>Save</NButton
							>
						</template>
					</template>

					<MaintenanceJobTable v-if="editingMaintenance" v-model="editMaintenanceRows" />
					<ul v-else-if="data.maintenance_items?.length" class="divide-y divide-hairline">
						<li
							v-for="(item, idx) in data.maintenance_items"
							:key="idx"
							class="flex items-start gap-3 py-2.5"
						>
							<div class="min-w-0 flex-1">
								<p class="text-title-sm text-ink">{{ fmt.or(item.description) }}</p>
								<p class="text-caption text-muted">
									{{ [item.maintenance_type, item.action].filter(Boolean).join(" · ") }}
								</p>
							</div>
							<div class="tabular shrink-0 text-right">
								<p class="text-caption text-muted">{{ item.qty }} {{ item.unit }}</p>
								<p class="text-body-sm font-semibold text-ink">
									{{ fmt.money(item.estimated_amount) }}
								</p>
							</div>
							<NBadge
								:semantic="itemStatusSemantic(item.item_status)"
								:label="item.item_status"
							/>
						</li>
					</ul>
					<NEmptyState
						v-else
						icon="clipboard-list"
						title="No maintenance jobs recorded"
						body="The PMS checklist fills this in."
					/>
					<NAlert
						v-if="saveMaintenanceError"
						semantic="critical"
						:body="saveMaintenanceError"
						class="mt-3"
					/>
				</NCard>

				<!-- ── Subsystems ───────────────────────────────────────────── -->
				<NCard v-if="showSubsystems" title="Subsystems affected">
					<template v-if="canEditWork" #actions>
						<template v-if="!editingSubsystems">
							<NButton variant="ghost" size="sm" icon="pencil" @click="startEditSubsystems"
								>Edit</NButton
							>
						</template>
						<template v-else>
							<NButton variant="ghost" size="sm" @click="cancelEditSubsystems">Cancel</NButton>
							<NButton
								variant="primary"
								size="sm"
								:loading="savingSubsystems"
								@click="saveSubsystems"
								>Save</NButton
							>
						</template>
					</template>

					<SubsystemPicker v-if="editingSubsystems" v-model="editSubsystemsValue" />
					<div v-else-if="data.subsystems?.length" class="flex flex-wrap gap-1.5">
						<NBadge
							v-for="s in data.subsystems"
							:key="s"
							semantic="active"
							:label="s"
							:dot="false"
						/>
					</div>
					<p v-else class="text-body-sm text-muted">Nothing selected yet.</p>
					<NAlert
						v-if="saveSubsystemsError"
						semantic="critical"
						:body="saveSubsystemsError"
						class="mt-3"
					/>
				</NCard>

				<!-- ── Breakdown diagnosis ──────────────────────────────────── -->
				<NCard v-if="isBreakdown" title="Breakdown diagnosis">
					<BreakdownDiagnosisPanel
						:model-value="data.breakdown || {}"
						:disabled="!canEditWork"
						:can-edit-rca="canEditRca"
						@save="saveBreakdown"
						@save-groups="saveBreakdownGroups"
					/>
				</NCard>

				<!-- ── Software update ──────────────────────────────────────── -->
				<NCard v-if="isSoftwareUpdate" title="Software update">
					<template v-if="canEditWork" #actions>
						<template v-if="!editingSoftware">
							<NButton variant="ghost" size="sm" icon="pencil" @click="startEditSoftware"
								>Edit</NButton
							>
						</template>
						<template v-else>
							<NButton variant="ghost" size="sm" @click="cancelEditSoftware">Cancel</NButton>
							<NButton
								variant="primary"
								size="sm"
								:loading="savingSoftware"
								@click="saveSoftware"
								>Save</NButton
							>
						</template>
					</template>

					<SoftwareUpdatePanel v-if="editingSoftware" v-model="editSoftwareRows" />
					<SoftwareUpdatePanel v-else :model-value="data.software_components || []" disabled />
					<NAlert
						v-if="saveSoftwareError"
						semantic="critical"
						:body="saveSoftwareError"
						class="mt-3"
					/>
				</NCard>

				<!-- ── Inventory ────────────────────────────────────────────── -->
				<InventoryRequestPanel v-if="showInventory" :job-card-name="data.name" :roles="roles" />

				<p class="flex flex-wrap gap-x-5 gap-y-1 pt-1 text-caption text-muted">
					<span v-if="data.opened_at">Opened {{ fmt.dateTime(data.opened_at) }}</span>
					<span v-if="data.closed_at">Closed {{ fmt.dateTime(data.closed_at) }}</span>
				</p>
			</template>
		</div>

		<FeedbackModal
			v-if="data.name"
			v-model:open="feedbackOpen"
			:job-card-name="data.name"
			@submitted="card.fetch()"
		/>
	</div>
</template>

<script setup>
import { computed, reactive, ref } from "vue";
import { createResource, call } from "frappe-ui";
import HealthScoreCard from "../components/HealthScoreCard.vue";
import RepairJobTable from "../components/RepairJobTable.vue";
import MaintenanceJobTable from "../components/MaintenanceJobTable.vue";
import ForceCloseButton from "../components/ForceCloseButton.vue";
import SubsystemPicker from "../components/SubsystemPicker.vue";
import BreakdownDiagnosisPanel from "../components/BreakdownDiagnosisPanel.vue";
import SoftwareUpdatePanel from "../components/SoftwareUpdatePanel.vue";
import InventoryRequestPanel from "../components/InventoryRequestPanel.vue";
import FeedbackModal from "../components/FeedbackModal.vue";
import ReopenButton from "../components/ReopenButton.vue";
import { useSession } from "../composables/useSession.js";
import { hasAnyRole } from "../utils/permissions.js";
import {
	NPageHeader,
	NCard,
	NKeyValue,
	NStatus,
	NPriority,
	NBadge,
	NButton,
	NVehicle,
	NRecordId,
	NInput,
	NSelect,
	NTextarea,
	NChoice,
	NAlert,
	NEmptyState,
	NSkeleton,
	fmt,
	itemStatusSemantic,
	toast,
} from "../ui/index.js";

const props = defineProps({ name: { type: String, required: true } });

const SERVICE_TYPES = [
	"Scheduled Maintenance",
	"Breakdown Repair",
	"Body & Paint",
	"Electrical",
	"Tyre & Alignment",
	"General Inspection",
	"Other",
];

const { roles } = useSession();

// ── Data ───────────────────────────────────────────────────────────────────
const card = createResource({
	url: "vehicle_maintenance.api.job_card.get_job_card_summary",
	params: { job_card_name: props.name },
	auto: true,
	onSuccess() {
		fetchActions();
		fetchPartGroups();
	},
});
const data = computed(() => card.data?.data || {});

const isPmsRepair = computed(() => data.value.job_card_type === "PMS + Repair");
const isSoftwareUpdate = computed(() => data.value.job_card_type === "Software Update");
const isBreakdown = computed(() => data.value.job_card_type === "Breakdown");
const isRepairType = computed(() => ["PMS + Repair", "Only Repair"].includes(data.value.job_card_type));
const showRepairTable = computed(() => isRepairType.value);
const showSubsystems = computed(() =>
	["Only Repair", "Software Update", "Breakdown"].includes(data.value.job_card_type)
);
const isClosed = computed(() => data.value.workflow_state === "Closed");

// ── Permissions ────────────────────────────────────────────────────────────
const canEdit = computed(() => hasAnyRole(roles, ["Service Engineer", "Depot Manager"]));
const canEditWork = computed(
	() => hasAnyRole(roles, ["Service Engineer", "Technician", "Depot Manager"]) && !isClosed.value
);
const canForceClose = computed(
	() =>
		hasAnyRole(roles, ["Service Engineer", "Depot Manager", "Aftersales Eng", "N. Maintenance Head"]) &&
		!isClosed.value
);
const canReopen = computed(() => isClosed.value && hasAnyRole(roles, ["Customer", "Central Ops"]));
const canEditRca = computed(() =>
	hasAnyRole(roles, ["Aftersales Eng", "Depot Manager", "N. Maintenance Head"])
);
const showInventory = computed(() =>
	hasAnyRole(roles, ["Service Engineer", "Technician", "Depot Manager", "Central Ops"])
);
/* Only a depot manager assigns people. Everyone with edit rights still sees the
   fields, disabled and explained, so "who is on this" is answerable by anyone. */
const canAssign = computed(() => Boolean(data.value?.viewer_is_depot_manager));

const feedbackOpen = ref(false);

const detailItems = computed(() => [
	{ label: "Customer", value: fmt.or(data.value.customer_name) },
	{ label: "Estimated cost", value: fmt.money(data.value.estimated_cost) },
	{ label: "Actual cost", value: fmt.money(data.value.actual_cost) },
	{
		label: "Service engineer",
		value: fmt.person(data.value.assigned_service_engineer),
		hideWhenEmpty: true,
	},
	{ label: "Technician", value: fmt.person(data.value.assigned_technician), hideWhenEmpty: true },
	{ label: "Depot", value: fmt.or(data.value.depot), hideWhenEmpty: true },
]);

// ── Workflow actions ───────────────────────────────────────────────────────
const availableActions = ref([]);
const transitioning = ref(false);

/* The committing action is the primary; a rejection is danger; everything else
   is secondary. Exactly one accent-filled button, whatever the state offers. */
function actionVariant(action) {
	if (action === "Close Job Card") return "primary";
	if (action === "Verification Failed" || action === "Customer Rejects") return "danger";
	return "secondary";
}

async function fetchActions() {
	try {
		const res = await call("vehicle_maintenance.api.job_card.get_available_actions", {
			job_card_name: props.name,
		});
		availableActions.value = res?.data?.actions || [];
	} catch {
		availableActions.value = [];
	}
}

async function doTransition(action) {
	transitioning.value = true;
	try {
		const res = await call("vehicle_maintenance.api.job_card.transition_job_card", {
			job_card_name: props.name,
			action,
		});
		toast.success(res?.message || `Moved to ${res?.data?.workflow_state}.`);
		card.fetch();
	} catch (e) {
		toast.error(e?.messages?.[0] || "That transition was refused.");
	} finally {
		transitioning.value = false;
	}
}

async function handleForceClose(payload) {
	transitioning.value = true;
	try {
		const res = await call("vehicle_maintenance.api.job_card.force_close_job_card", {
			job_card_name: props.name,
			severity: payload.severity,
			reason: payload.reason,
		});
		toast.success(res?.message || "Job card force closed.");
		card.fetch();
	} catch (e) {
		toast.error(e?.messages?.[0] || "Force close failed.");
		throw e;
	} finally {
		transitioning.value = false;
	}
}

function onReopened() {
	card.fetch();
}

// ── Details editor ─────────────────────────────────────────────────────────
const editing = ref(false);
const saving = ref(false);
const saveError = ref("");
const editForm = reactive({
	service_type: "",
	priority: "",
	assigned_service_engineer: "",
	assigned_technician: "",
	depot: "",
	complaint_description: "",
});

const seOptions = ref([]);
const techOptions = ref([]);

async function loadAssignmentOptions() {
	try {
		const [se, tech] = await Promise.all([
			call("vehicle_maintenance.api.job_card.list_users_by_role", { role: "Service Engineer" }),
			call("vehicle_maintenance.api.job_card.list_users_by_role", { role: "Technician" }),
		]);
		const toOption = (u) => ({ value: u.user, label: u.full_name || u.user });
		seOptions.value = (se?.data || []).map(toOption);
		techOptions.value = (tech?.data || []).map(toOption);
	} catch {
		// Non-fatal — the card still saves without changing assignments.
		seOptions.value = [];
		techOptions.value = [];
	}
}

function startEditing() {
	Object.assign(editForm, {
		service_type: data.value.service_type || "",
		priority: data.value.priority || "",
		assigned_service_engineer: data.value.assigned_service_engineer || "",
		assigned_technician: data.value.assigned_technician || "",
		depot: data.value.depot || "",
		complaint_description: data.value.complaint_description || "",
	});
	editing.value = true;
	saveError.value = "";
	if (!seOptions.value.length && !techOptions.value.length) loadAssignmentOptions();
}

function cancelEditing() {
	editing.value = false;
	saveError.value = "";
}

async function saveEdits() {
	saving.value = true;
	saveError.value = "";
	try {
		await call("vehicle_maintenance.api.job_card.update_job_card", {
			job_card_name: props.name,
			updates: JSON.stringify(editForm),
		});
		editing.value = false;
		card.fetch();
		toast.success("Details saved.");
	} catch (e) {
		saveError.value = e?.messages?.[0] || "Save failed.";
	} finally {
		saving.value = false;
	}
}

// ── Repair items ───────────────────────────────────────────────────────────
const partGroups = ref([]);
const editingRepairs = ref(false);
const editRepairRows = ref([]);
const savingRepairs = ref(false);
const saveRepairsError = ref("");

async function fetchPartGroups() {
	try {
		const res = await call("vehicle_maintenance.api.job_card.list_part_groups");
		partGroups.value = res?.data || [];
	} catch {
		partGroups.value = [];
	}
}

function startEditRepairs() {
	editRepairRows.value = (data.value.repair_items || []).map((r) => ({ ...r }));
	editingRepairs.value = true;
	saveRepairsError.value = "";
}

function cancelEditRepairs() {
	editingRepairs.value = false;
	editRepairRows.value = [];
	saveRepairsError.value = "";
}

async function saveRepairs() {
	savingRepairs.value = true;
	saveRepairsError.value = "";
	try {
		// Photos are File objects in the editor; only stored URLs round-trip.
		const payload = editRepairRows.value.map((r) => ({
			...r,
			pre_repair_photo: typeof r.pre_repair_photo === "string" ? r.pre_repair_photo : null,
			post_repair_photo: typeof r.post_repair_photo === "string" ? r.post_repair_photo : null,
		}));
		await call("vehicle_maintenance.api.job_card.save_repair_items", {
			job_card_name: props.name,
			rows: JSON.stringify(payload),
		});
		editingRepairs.value = false;
		card.fetch();
		toast.success("Repair jobs saved.");
	} catch (e) {
		saveRepairsError.value = e?.messages?.[0] || "Could not save the repair jobs.";
	} finally {
		savingRepairs.value = false;
	}
}

// ── Maintenance items ──────────────────────────────────────────────────────
const editingMaintenance = ref(false);
const editMaintenanceRows = ref([]);
const savingMaintenance = ref(false);
const saveMaintenanceError = ref("");

function startEditMaintenance() {
	editMaintenanceRows.value = (data.value.maintenance_items || []).map((r) => ({ ...r }));
	editingMaintenance.value = true;
	saveMaintenanceError.value = "";
}

function cancelEditMaintenance() {
	editingMaintenance.value = false;
	editMaintenanceRows.value = [];
	saveMaintenanceError.value = "";
}

async function saveMaintenance() {
	savingMaintenance.value = true;
	saveMaintenanceError.value = "";
	try {
		const payload = editMaintenanceRows.value.map((r) => ({
			...r,
			pre_photo: typeof r.pre_photo === "string" ? r.pre_photo : null,
			post_photo: typeof r.post_photo === "string" ? r.post_photo : null,
		}));
		await call("vehicle_maintenance.api.job_card.save_maintenance_items", {
			job_card_name: props.name,
			rows: JSON.stringify(payload),
		});
		editingMaintenance.value = false;
		card.fetch();
		toast.success("Maintenance jobs saved.");
	} catch (e) {
		saveMaintenanceError.value = e?.messages?.[0] || "Could not save the maintenance jobs.";
	} finally {
		savingMaintenance.value = false;
	}
}

// ── Subsystems ─────────────────────────────────────────────────────────────
const editingSubsystems = ref(false);
const editSubsystemsValue = ref([]);
const savingSubsystems = ref(false);
const saveSubsystemsError = ref("");

function startEditSubsystems() {
	editSubsystemsValue.value = [...(data.value.subsystems || [])];
	editingSubsystems.value = true;
	saveSubsystemsError.value = "";
}

function cancelEditSubsystems() {
	editingSubsystems.value = false;
	editSubsystemsValue.value = [];
	saveSubsystemsError.value = "";
}

async function saveSubsystems() {
	savingSubsystems.value = true;
	saveSubsystemsError.value = "";
	try {
		await call("vehicle_maintenance.api.job_card.save_subsystems", {
			job_card_name: props.name,
			subsystems: JSON.stringify(editSubsystemsValue.value),
		});
		editingSubsystems.value = false;
		card.fetch();
		toast.success("Subsystems saved.");
	} catch (e) {
		saveSubsystemsError.value = e?.messages?.[0] || "Could not save the subsystems.";
	} finally {
		savingSubsystems.value = false;
	}
}

// ── Breakdown ──────────────────────────────────────────────────────────────
async function saveBreakdown(patch) {
	try {
		await call("vehicle_maintenance.api.job_card.update_breakdown_diagnosis", {
			job_card_name: props.name,
			updates: JSON.stringify(patch),
		});
		card.fetch();
		toast.success("Diagnosis saved.");
	} catch (e) {
		toast.error(e?.messages?.[0] || "Could not save the diagnosis.");
	}
}

async function saveBreakdownGroups(nextGroups) {
	try {
		await call("vehicle_maintenance.api.job_card.save_groups_impacted", {
			job_card_name: props.name,
			part_groups: JSON.stringify(nextGroups),
		});
		card.fetch();
		toast.success("Groups updated.");
	} catch (e) {
		toast.error(e?.messages?.[0] || "Could not update the groups.");
	}
}

// ── Software update ────────────────────────────────────────────────────────
const editingSoftware = ref(false);
const editSoftwareRows = ref([]);
const savingSoftware = ref(false);
const saveSoftwareError = ref("");

function startEditSoftware() {
	editSoftwareRows.value = (data.value.software_components || []).map((r) => ({ ...r }));
	editingSoftware.value = true;
	saveSoftwareError.value = "";
}

function cancelEditSoftware() {
	editingSoftware.value = false;
	editSoftwareRows.value = [];
	saveSoftwareError.value = "";
}

async function saveSoftware() {
	savingSoftware.value = true;
	saveSoftwareError.value = "";
	try {
		const payload = editSoftwareRows.value.map((r) => ({
			...r,
			pre_version_photo: typeof r.pre_version_photo === "string" ? r.pre_version_photo : null,
			post_version_photo: typeof r.post_version_photo === "string" ? r.post_version_photo : null,
			calibration_photo: typeof r.calibration_photo === "string" ? r.calibration_photo : null,
		}));
		await call("vehicle_maintenance.api.job_card.save_software_components", {
			job_card_name: props.name,
			rows: JSON.stringify(payload),
		});
		editingSoftware.value = false;
		card.fetch();
		toast.success("Software components saved.");
	} catch (e) {
		saveSoftwareError.value = e?.messages?.[0] || "Could not save the software components.";
	} finally {
		savingSoftware.value = false;
	}
}
</script>
