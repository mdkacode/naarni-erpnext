<template>
  <div class="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
    <div v-if="card.loading" class="text-center py-12 text-gray-400">Loading...</div>

    <template v-else-if="data.name">
      <!-- Back link -->
      <div class="mb-6">
        <router-link to="/service-portal" class="text-sm text-brand-600 hover:underline">
          &larr; Back to list
        </router-link>
      </div>

      <div class="bg-white border border-gray-200 rounded-xl p-6">
        <!-- ── Header ── -->
        <div class="flex items-start justify-between mb-6">
          <div>
            <p class="text-sm font-mono text-gray-500">{{ data.name }}</p>
            <h1 class="text-xl font-bold text-gray-900">{{ data.vehicle_number }}</h1>
            <p class="text-gray-600">{{ data.vehicle_make_model }}</p>
            <p class="text-xs text-gray-400 mt-1">
              {{ data.job_card_type }} · {{ data.service_type }}
            </p>
          </div>
          <div class="flex flex-col items-end gap-2">
            <StatusBadge :state="data.workflow_state" />
            <div v-if="data.sla_breached" class="text-xs font-medium text-red-600 bg-red-50 px-2 py-1 rounded">
              SLA Breached
            </div>
            <div v-if="data.force_closed" class="text-xs font-medium text-red-700 bg-red-100 px-2 py-1 rounded">
              Force Closed · {{ data.force_close_severity }}
            </div>
          </div>
        </div>

        <!-- ── Workflow Actions ── -->
        <div v-if="(availableActions.length && canEdit) || canForceClose" class="mb-6 flex flex-wrap gap-2">
          <button
            v-for="action in availableActions"
            :key="action"
            @click="doTransition(action)"
            :disabled="transitioning"
            class="px-4 py-2 text-sm font-medium rounded-lg border-2 transition-colors disabled:opacity-50"
            :class="actionClass(action)"
          >
            {{ action }}
          </button>
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
          <button
            v-if="isClosed && !data.force_closed"
            type="button"
            @click="feedbackOpen = true"
            class="px-3 py-2 text-sm font-medium text-amber-700 border border-amber-300 hover:bg-amber-50 rounded-lg"
          >
            Share Feedback
          </button>
        </div>

        <!-- ── Transition feedback ── -->
        <div v-if="transitionMsg" class="mb-4 p-3 rounded-lg text-sm" :class="transitionOk ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'">
          {{ transitionMsg }}
        </div>

        <!-- ── Health Score (PMS + Repair only) ── -->
        <div v-if="isPmsRepair" class="mb-6">
          <HealthScoreCard
            :pre-score="data.pre_pms_score"
            :post-score="data.post_pms_score"
            :improvement="data.score_improvement"
            :category-scores="data.category_scores || []"
          />
          <div v-if="data.health_card" class="mt-2 text-right">
            <router-link
              :to="`/service-portal/job-card/${data.name}/health-card`"
              class="text-sm text-brand-600 hover:underline"
            >
              View Vehicle Health Card &rarr;
            </router-link>
          </div>
        </div>

        <!-- ── Customer approval banner (parts > ₹1,000) ── -->
        <div
          v-if="data.requires_customer_approval && data.workflow_state !== 'Closed'"
          class="mb-6 p-3 bg-amber-50 border border-amber-200 rounded-lg text-sm text-amber-800"
        >
          <span class="font-semibold">Customer approval required</span> — parts total exceeds ₹1,000.
        </div>

        <!-- ── Details (view / edit toggle) ── -->
        <div class="mb-6">
          <div class="flex items-center justify-between mb-3">
            <h3 class="text-sm font-semibold text-gray-500 uppercase tracking-wider">Details</h3>
            <button
              v-if="canEdit && !editing"
              @click="startEditing"
              class="text-sm text-brand-600 hover:underline"
            >
              Edit
            </button>
            <div v-if="editing" class="flex gap-2">
              <button @click="cancelEditing" class="text-sm text-gray-500 hover:underline">Cancel</button>
              <button @click="saveEdits" :disabled="saving" class="text-sm text-brand-600 font-medium hover:underline disabled:opacity-50">
                {{ saving ? 'Saving...' : 'Save' }}
              </button>
            </div>
          </div>

          <!-- View mode -->
          <dl v-if="!editing" class="grid grid-cols-2 gap-x-6 gap-y-4 text-sm">
            <div>
              <dt class="text-gray-500">Customer</dt>
              <dd class="font-medium">{{ data.customer_name }}</dd>
            </div>
            <div>
              <dt class="text-gray-500">Priority</dt>
              <dd class="font-medium" :class="data.priority === 'Urgent' ? 'text-red-600' : ''">
                {{ data.priority }}
              </dd>
            </div>
            <div>
              <dt class="text-gray-500">Estimated Cost</dt>
              <dd class="font-medium">{{ formatCurrency(data.estimated_cost) }}</dd>
            </div>
            <div>
              <dt class="text-gray-500">Actual Cost</dt>
              <dd class="font-medium">{{ formatCurrency(data.actual_cost) }}</dd>
            </div>
            <div v-if="data.assigned_service_engineer">
              <dt class="text-gray-500">Service Engineer</dt>
              <dd class="font-medium">{{ data.assigned_service_engineer }}</dd>
            </div>
            <div v-if="data.assigned_technician">
              <dt class="text-gray-500">Technician</dt>
              <dd class="font-medium">{{ data.assigned_technician }}</dd>
            </div>
            <div v-if="data.depot">
              <dt class="text-gray-500">Depot</dt>
              <dd class="font-medium">{{ data.depot }}</dd>
            </div>
            <div class="col-span-2">
              <dt class="text-gray-500">Complaint / Description</dt>
              <dd class="font-medium whitespace-pre-line">{{ data.complaint_description }}</dd>
            </div>
            <div v-if="data.force_close_reason" class="col-span-2">
              <dt class="text-gray-500">Force Close Reason</dt>
              <dd class="font-medium whitespace-pre-line text-red-700">{{ data.force_close_reason }}</dd>
            </div>
          </dl>

          <!-- Edit mode -->
          <div v-else class="space-y-4">
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">Service Type</label>
                <select v-model="editForm.service_type" class="input-field">
                  <option>Scheduled Maintenance</option>
                  <option>Breakdown Repair</option>
                  <option>Body & Paint</option>
                  <option>Electrical</option>
                  <option>Tyre & Alignment</option>
                  <option>General Inspection</option>
                  <option>Other</option>
                </select>
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">Priority</label>
                <div class="grid grid-cols-4 gap-2">
                  <button
                    v-for="p in ['Low', 'Medium', 'High', 'Urgent']"
                    :key="p"
                    type="button"
                    @click="editForm.priority = p"
                    class="py-2 text-xs font-medium rounded-lg border-2 transition-colors text-center"
                    :class="editForm.priority === p ? priorityClass(p) : 'border-gray-200 text-gray-500'"
                  >
                    {{ p }}
                  </button>
                </div>
              </div>
            </div>
            <div class="grid grid-cols-2 gap-4">
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">
                  Assigned SE
                  <span v-if="!canAssign" class="ml-1 text-xs font-normal text-gray-400">
                    (Depot Manager only)
                  </span>
                </label>
                <select
                  v-model="editForm.assigned_service_engineer"
                  class="input-field"
                  :disabled="!canAssign"
                >
                  <option value="">— Unassigned —</option>
                  <option v-for="u in seOptions" :key="u.user" :value="u.user">
                    {{ u.full_name || u.user }} <span v-if="u.full_name">({{ u.user }})</span>
                  </option>
                </select>
              </div>
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-1">
                  Assigned Technician
                  <span v-if="!canAssign" class="ml-1 text-xs font-normal text-gray-400">
                    (Depot Manager only)
                  </span>
                </label>
                <select
                  v-model="editForm.assigned_technician"
                  class="input-field"
                  :disabled="!canAssign"
                >
                  <option value="">— Unassigned —</option>
                  <option v-for="u in techOptions" :key="u.user" :value="u.user">
                    {{ u.full_name || u.user }} <span v-if="u.full_name">({{ u.user }})</span>
                  </option>
                </select>
              </div>
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">Depot</label>
              <input v-model="editForm.depot" class="input-field" placeholder="Depot / Workshop" />
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">Complaint / Description</label>
              <textarea v-model="editForm.complaint_description" rows="3" class="input-field" />
            </div>
            <div v-if="saveError" class="text-sm text-red-600">{{ saveError }}</div>
          </div>
        </div>

        <!-- ── Repair Jobs table (PMS + Repair, Only Repair) ── -->
        <div v-if="showRepairTable" class="mb-6">
          <div class="flex items-center justify-between mb-3">
            <h3 class="text-sm font-semibold text-gray-500 uppercase tracking-wider">Repair Jobs</h3>
            <div class="flex gap-2">
              <button
                v-if="canEditWork && !editingRepairs"
                @click="startEditRepairs"
                class="text-sm text-brand-600 hover:underline"
              >
                Edit
              </button>
              <div v-if="editingRepairs" class="flex gap-2">
                <button @click="cancelEditRepairs" class="text-sm text-gray-500 hover:underline">Cancel</button>
                <button @click="saveRepairs" :disabled="savingRepairs" class="text-sm text-brand-600 font-medium hover:underline disabled:opacity-50">
                  {{ savingRepairs ? 'Saving…' : 'Save' }}
                </button>
              </div>
            </div>
          </div>

          <RepairJobTable
            v-if="editingRepairs"
            v-model="editRepairRows"
            :part-groups="partGroups"
          />
          <div v-else-if="data.repair_items?.length" class="space-y-2">
            <div
              v-for="(item, idx) in data.repair_items"
              :key="idx"
              class="bg-white border border-gray-100 rounded-lg p-3 text-sm flex items-start gap-3"
            >
              <div class="flex-1">
                <div class="font-medium text-gray-800">{{ item.description || '—' }}</div>
                <div class="text-xs text-gray-500 mt-0.5">
                  {{ item.part_group }} · {{ item.bus_system }} · {{ item.activity_type }}
                </div>
              </div>
              <div class="text-right text-xs text-gray-500">
                <div>Qty {{ item.qty }}</div>
                <div class="font-medium text-gray-800">{{ formatCurrency(item.estimated_amount) }}</div>
              </div>
              <span
                class="text-xs font-medium px-2 py-0.5 rounded-full shrink-0"
                :class="itemStatusClass(item.item_status)"
              >
                {{ item.item_status }}
              </span>
            </div>
          </div>
          <div v-else class="text-sm text-gray-400 text-center py-4 border border-dashed border-gray-200 rounded-xl">
            No repair jobs recorded.
          </div>
          <div v-if="saveRepairsError" class="mt-2 text-sm text-red-600">{{ saveRepairsError }}</div>
        </div>

        <!-- ── Maintenance Jobs table (PMS + Repair only) ── -->
        <div v-if="isPmsRepair" class="mb-6">
          <div class="flex items-center justify-between mb-3">
            <h3 class="text-sm font-semibold text-gray-500 uppercase tracking-wider">Maintenance Jobs</h3>
            <div class="flex gap-2">
              <button
                v-if="canEditWork && !editingMaintenance"
                @click="startEditMaintenance"
                class="text-sm text-brand-600 hover:underline"
              >
                Edit
              </button>
              <div v-if="editingMaintenance" class="flex gap-2">
                <button @click="cancelEditMaintenance" class="text-sm text-gray-500 hover:underline">Cancel</button>
                <button @click="saveMaintenance" :disabled="savingMaintenance" class="text-sm text-brand-600 font-medium hover:underline disabled:opacity-50">
                  {{ savingMaintenance ? 'Saving…' : 'Save' }}
                </button>
              </div>
            </div>
          </div>

          <MaintenanceJobTable
            v-if="editingMaintenance"
            v-model="editMaintenanceRows"
          />
          <div v-else-if="data.maintenance_items?.length" class="space-y-2">
            <div
              v-for="(item, idx) in data.maintenance_items"
              :key="idx"
              class="bg-white border border-gray-100 rounded-lg p-3 text-sm flex items-start gap-3"
            >
              <div class="flex-1">
                <div class="font-medium text-gray-800">{{ item.description || '—' }}</div>
                <div class="text-xs text-gray-500 mt-0.5">
                  {{ item.maintenance_type }} · {{ item.action }}
                </div>
              </div>
              <div class="text-right text-xs text-gray-500">
                <div>{{ item.qty }} {{ item.unit }}</div>
                <div class="font-medium text-gray-800">{{ formatCurrency(item.estimated_amount) }}</div>
              </div>
              <span
                class="text-xs font-medium px-2 py-0.5 rounded-full shrink-0"
                :class="itemStatusClass(item.item_status)"
              >
                {{ item.item_status }}
              </span>
            </div>
          </div>
          <div v-else class="text-sm text-gray-400 text-center py-4 border border-dashed border-gray-200 rounded-xl">
            No maintenance jobs recorded.
          </div>
          <div v-if="saveMaintenanceError" class="mt-2 text-sm text-red-600">{{ saveMaintenanceError }}</div>
        </div>

        <!-- ── Subsystems (Only Repair / Software Update / Breakdown) ── -->
        <div v-if="showSubsystems" class="mb-6">
          <div class="flex items-center justify-between mb-3">
            <h3 class="text-sm font-semibold text-gray-500 uppercase tracking-wider">Subsystems Affected</h3>
            <button
              v-if="canEditWork && !editingSubsystems"
              @click="startEditSubsystems"
              class="text-sm text-brand-600 hover:underline"
            >
              Edit
            </button>
            <div v-if="editingSubsystems" class="flex gap-2">
              <button @click="cancelEditSubsystems" class="text-sm text-gray-500 hover:underline">Cancel</button>
              <button @click="saveSubsystems" :disabled="savingSubsystems" class="text-sm text-brand-600 font-medium hover:underline disabled:opacity-50">
                {{ savingSubsystems ? 'Saving…' : 'Save' }}
              </button>
            </div>
          </div>
          <SubsystemPicker
            v-if="editingSubsystems"
            v-model="editSubsystemsValue"
          />
          <div v-else-if="data.subsystems?.length" class="flex flex-wrap gap-1.5">
            <span
              v-for="s in data.subsystems"
              :key="s"
              class="px-2.5 py-1 text-xs font-medium rounded-full bg-brand-50 text-brand-700"
            >
              {{ s }}
            </span>
          </div>
          <div v-else class="text-sm text-gray-400">No subsystems selected yet.</div>
          <div v-if="saveSubsystemsError" class="mt-2 text-sm text-red-600">
            {{ saveSubsystemsError }}
          </div>
        </div>

        <!-- ── Breakdown Diagnosis Panel ── -->
        <div v-if="isBreakdown" class="mb-6">
          <h3 class="text-sm font-semibold text-gray-500 uppercase tracking-wider mb-3">
            Breakdown Diagnosis
          </h3>
          <BreakdownDiagnosisPanel
            :model-value="data.breakdown || {}"
            :disabled="!canEditWork"
            :can-edit-rca="canEditRca"
            @save="saveBreakdown"
            @save-groups="saveBreakdownGroups"
          />
          <div v-if="saveBreakdownMsg" class="mt-2 text-sm" :class="saveBreakdownOk ? 'text-green-600' : 'text-red-600'">
            {{ saveBreakdownMsg }}
          </div>
        </div>

        <!-- ── Software Update Panel ── -->
        <div v-if="isSoftwareUpdate" class="mb-6">
          <div class="flex items-center justify-between mb-3">
            <h3 class="text-sm font-semibold text-gray-500 uppercase tracking-wider">Software Update</h3>
            <div class="flex gap-2">
              <button
                v-if="canEditWork && !editingSoftware"
                @click="startEditSoftware"
                class="text-sm text-brand-600 hover:underline"
              >
                Edit
              </button>
              <div v-if="editingSoftware" class="flex gap-2">
                <button @click="cancelEditSoftware" class="text-sm text-gray-500 hover:underline">Cancel</button>
                <button @click="saveSoftware" :disabled="savingSoftware" class="text-sm text-brand-600 font-medium hover:underline disabled:opacity-50">
                  {{ savingSoftware ? 'Saving…' : 'Save' }}
                </button>
              </div>
            </div>
          </div>
          <SoftwareUpdatePanel
            v-if="editingSoftware"
            v-model="editSoftwareRows"
          />
          <SoftwareUpdatePanel
            v-else
            :model-value="data.software_components || []"
            :disabled="true"
          />
          <div v-if="saveSoftwareError" class="mt-2 text-sm text-red-600">{{ saveSoftwareError }}</div>
        </div>

        <!-- ── Inventory Requests (internal roles only) ── -->
        <div v-if="showInventory" class="mb-6">
          <InventoryRequestPanel
            :job-card-name="data.name"
            :roles="roles"
          />
        </div>

        <!-- ── Timestamps ── -->
        <div class="text-xs text-gray-400 border-t border-gray-100 pt-4 flex gap-6">
          <span v-if="data.opened_at">Opened: {{ formatDate(data.opened_at) }}</span>
          <span v-if="data.closed_at">Closed: {{ formatDate(data.closed_at) }}</span>
        </div>
      </div>

      <!-- ── Feedback Modal (available post-closure) ── -->
      <FeedbackModal
        v-if="data.name"
        :job-card-name="data.name"
        v-model:open="feedbackOpen"
        @submitted="card.fetch()"
      />
    </template>
  </div>
</template>

<script setup>
import { ref, computed, reactive } from "vue";
import { createResource, call } from "frappe-ui";
import StatusBadge from "../components/StatusBadge.vue";
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

const props = defineProps({
  name: { type: String, required: true },
});

const { roles } = useSession();
const canEdit = computed(() =>
  hasAnyRole(roles, ["Service Engineer", "Depot Manager"])
);
const canEditWork = computed(() =>
  hasAnyRole(roles, ["Service Engineer", "Technician", "Depot Manager"]) &&
  !["Closed"].includes(data.value.workflow_state)
);
const canForceClose = computed(() =>
  hasAnyRole(roles, [
    "Service Engineer", "Depot Manager", "Aftersales Eng", "N. Maintenance Head",
  ]) && !["Closed"].includes(data.value.workflow_state)
);
const canReopen = computed(() =>
  data.value.workflow_state === "Closed" &&
  hasAnyRole(roles, ["Customer", "Central Ops"])
);
const isClosed = computed(() => data.value.workflow_state === "Closed");
// Inventory panel is for internal roles only (Customer can't raise parts).
const showInventory = computed(() =>
  hasAnyRole(roles, [
    "Service Engineer", "Technician", "Depot Manager", "Central Ops",
  ])
);

const feedbackOpen = ref(false);

function onReopened() {
  transitionOk.value = true;
  transitionMsg.value = "Job Card reopened.";
  card.fetch();
}

// ── Data fetch ──
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
const isOnlyRepair = computed(() => data.value.job_card_type === "Only Repair");
const isSoftwareUpdate = computed(() => data.value.job_card_type === "Software Update");
const isBreakdown = computed(() => data.value.job_card_type === "Breakdown");
const isRepairType = computed(() =>
  ["PMS + Repair", "Only Repair"].includes(data.value.job_card_type)
);
// Subsystems multi-select is relevant for all non-PMS types per PRD.
const showSubsystems = computed(() =>
  ["Only Repair", "Software Update", "Breakdown"].includes(data.value.job_card_type)
);
const showRepairTable = computed(() => isRepairType.value);
const canEditRca = computed(() =>
  hasAnyRole(roles, ["Aftersales Eng", "Depot Manager", "N. Maintenance Head"])
);

// ── Workflow actions ──
const availableActions = ref([]);
const transitioning = ref(false);
const transitionMsg = ref("");
const transitionOk = ref(true);

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
  transitionMsg.value = "";
  try {
    const res = await call("vehicle_maintenance.api.job_card.transition_job_card", {
      job_card_name: props.name,
      action,
    });
    transitionOk.value = true;
    transitionMsg.value = res?.message || `Moved to ${res?.data?.workflow_state}`;
    card.fetch();
  } catch (e) {
    transitionOk.value = false;
    transitionMsg.value = e?.messages?.[0] || "Transition failed.";
  } finally {
    transitioning.value = false;
  }
}

async function handleForceClose(payload) {
  transitioning.value = true;
  transitionMsg.value = "";
  try {
    const res = await call("vehicle_maintenance.api.job_card.force_close_job_card", {
      job_card_name: props.name,
      severity: payload.severity,
      reason: payload.reason,
    });
    transitionOk.value = true;
    transitionMsg.value = res?.message || "Job Card force-closed.";
    card.fetch();
  } catch (e) {
    transitionOk.value = false;
    transitionMsg.value = e?.messages?.[0] || "Force close failed.";
    throw e;
  } finally {
    transitioning.value = false;
  }
}

function actionClass(action) {
  if (action === "Close Job Card") return "border-green-400 bg-green-50 text-green-700 hover:bg-green-100";
  if (action === "Verification Failed" || action === "Customer Rejects") return "border-red-300 bg-red-50 text-red-700 hover:bg-red-100";
  return "border-brand-300 bg-brand-50 text-brand-700 hover:bg-brand-100";
}

function itemStatusClass(status) {
  const m = {
    "Completed": "bg-green-100 text-green-700",
    "Parts Issued": "bg-blue-100 text-blue-700",
    "Parts Allocated": "bg-indigo-100 text-indigo-700",
    "Parts Requested": "bg-amber-100 text-amber-700",
    "In Progress": "bg-amber-100 text-amber-700",
    "Pending": "bg-gray-100 text-gray-600",
    "Customer Rejected": "bg-red-100 text-red-700",
  };
  return m[status] || "bg-gray-100 text-gray-600";
}

// ── Header-level edit mode ──
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

// ── Role-filtered user pickers (Assigned SE / Technician) ──
const seOptions = ref([]);
const techOptions = ref([]);

// PRD p.3: only Depot Manager assigns SE / Technician. Any other role can
// still view the edit form, but the dropdowns are disabled for them.
const canAssign = computed(() => !!data.value?.viewer_is_depot_manager);

async function loadAssignmentOptions() {
  try {
    const [se, tech] = await Promise.all([
      call("vehicle_maintenance.api.job_card.list_users_by_role", {
        role: "Service Engineer",
      }),
      call("vehicle_maintenance.api.job_card.list_users_by_role", {
        role: "Technician",
      }),
    ]);
    seOptions.value = se?.data || [];
    techOptions.value = tech?.data || [];
  } catch {
    // Non-fatal — user can still save without changing assignments.
    seOptions.value = [];
    techOptions.value = [];
  }
}

function startEditing() {
  editForm.service_type = data.value.service_type || "";
  editForm.priority = data.value.priority || "";
  editForm.assigned_service_engineer = data.value.assigned_service_engineer || "";
  editForm.assigned_technician = data.value.assigned_technician || "";
  editForm.depot = data.value.depot || "";
  editForm.complaint_description = data.value.complaint_description || "";
  editing.value = true;
  saveError.value = "";
  // Fetch role-filtered user lists once on first edit; cached for re-edits.
  if (!seOptions.value.length && !techOptions.value.length) {
    loadAssignmentOptions();
  }
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
  } catch (e) {
    saveError.value = e?.messages?.[0] || "Save failed.";
  } finally {
    saving.value = false;
  }
}

// ── Repair items editor ──
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
    // Photos are File objects in the wizard; send only URL/string placeholders
    // for now (full upload flow lands in Milestone 5).
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
  } catch (e) {
    saveRepairsError.value = e?.messages?.[0] || "Failed to save repair jobs.";
  } finally {
    savingRepairs.value = false;
  }
}

// ── Maintenance items editor ──
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
  } catch (e) {
    saveMaintenanceError.value = e?.messages?.[0] || "Failed to save maintenance jobs.";
  } finally {
    savingMaintenance.value = false;
  }
}

// ── Subsystems editor ──
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
  } catch (e) {
    saveSubsystemsError.value = e?.messages?.[0] || "Failed to save subsystems.";
  } finally {
    savingSubsystems.value = false;
  }
}

// ── Breakdown panel save handler — merges patches into current snapshot ──
const saveBreakdownMsg = ref("");
const saveBreakdownOk = ref(true);

async function saveBreakdown(patch) {
  saveBreakdownMsg.value = "";
  try {
    await call("vehicle_maintenance.api.job_card.update_breakdown_diagnosis", {
      job_card_name: props.name,
      updates: JSON.stringify(patch),
    });
    saveBreakdownOk.value = true;
    saveBreakdownMsg.value = "Saved.";
    card.fetch();
  } catch (e) {
    saveBreakdownOk.value = false;
    saveBreakdownMsg.value = e?.messages?.[0] || "Save failed.";
  }
}

async function saveBreakdownGroups(nextGroups) {
  saveBreakdownMsg.value = "";
  try {
    await call("vehicle_maintenance.api.job_card.save_groups_impacted", {
      job_card_name: props.name,
      part_groups: JSON.stringify(nextGroups),
    });
    saveBreakdownOk.value = true;
    saveBreakdownMsg.value = "Groups updated.";
    card.fetch();
  } catch (e) {
    saveBreakdownOk.value = false;
    saveBreakdownMsg.value = e?.messages?.[0] || "Save failed.";
  }
}

// ── Software Update editor ──
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
    // Strip File objects (the uploaded URL round-trip lands in M5).
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
  } catch (e) {
    saveSoftwareError.value = e?.messages?.[0] || "Failed to save software components.";
  } finally {
    savingSoftware.value = false;
  }
}

// ── Helpers ──
function priorityClass(p) {
  const m = {
    Low: "border-green-500 bg-green-50 text-green-700",
    Medium: "border-amber-500 bg-amber-50 text-amber-700",
    High: "border-orange-500 bg-orange-50 text-orange-700",
    Urgent: "border-red-500 bg-red-50 text-red-700",
  };
  return m[p];
}

function formatCurrency(val) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency", currency: "INR", minimumFractionDigits: 0,
  }).format(val || 0);
}

function formatDate(d) {
  if (!d) return "";
  return new Date(d).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}
</script>

<style scoped>
.input-field {
  @apply w-full px-3 py-2 border border-gray-300 rounded-lg text-sm
         focus:ring-2 focus:ring-brand-500 focus:border-brand-500;
}
</style>
