<template>
  <!--
    InventoryRequestPanel — PRD p.3 Inventory Request & Allocation Flow.

    Role-based actions per row status:
      Requested       → Technician/SE sees "Waiting"; DM/Central Ops sees "Allocate"
      Parts Allocated → DM/Central Ops sees "Issue Parts"
      Parts Issued    → Technician/SE sees "Acknowledge Receipt"
      Received        → locked

    EAS applied:
      Eliminate — Amounts/costs/notes collapsed by default; only the essential
                  status chip + action button is visible.
      Automate  — Timestamps stamp server-side; UI never asks for datetime input.
      Simplify  — One primary button per row per role; chip colors mirror status.
  -->
  <div class="space-y-3">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-semibold text-gray-800">Inventory Requests</h3>
      <button
        v-if="canRequest"
        type="button"
        @click="openRequestForm"
        class="px-3 py-1.5 text-xs font-medium text-brand-700 bg-brand-50 hover:bg-brand-100 rounded-lg"
      >
        + New Request
      </button>
    </div>

    <!-- Error / info feedback -->
    <div v-if="feedback" class="p-2 text-xs rounded-lg" :class="feedback.ok ? 'bg-green-50 text-green-700' : 'bg-red-50 text-red-700'">
      {{ feedback.msg }}
    </div>

    <!-- Empty state -->
    <div v-if="!loading && !rows.length" class="text-center py-6 text-sm text-gray-400 border border-dashed border-gray-200 rounded-xl">
      No inventory requests yet.
    </div>

    <div v-if="loading" class="text-center py-4 text-sm text-gray-400">Loading…</div>

    <!-- Rows -->
    <div
      v-for="row in rows"
      :key="row.name"
      class="bg-white border border-gray-200 rounded-xl p-3 flex items-center gap-3 text-sm"
    >
      <div class="flex-1">
        <div class="font-medium text-gray-800">{{ row.part_name || row.part }}</div>
        <div class="text-xs text-gray-500">
          {{ row.part_group }} · {{ row.quantity }} {{ row.uom || "" }} ·
          <span :class="urgencyTextClass(row.urgency_level)">{{ row.urgency_level }}</span>
        </div>
      </div>
      <span
        class="text-xs font-medium px-2 py-0.5 rounded-full"
        :class="statusClass(row.status)"
      >
        {{ row.status }}
      </span>
      <button
        v-if="nextAction(row)"
        type="button"
        :disabled="actingOn === row.name"
        @click="advance(row)"
        class="px-3 py-1.5 text-xs font-medium rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50 disabled:opacity-50"
      >
        {{ nextAction(row).label }}
      </button>
    </div>

    <!-- New-request modal -->
    <div
      v-if="requestOpen"
      class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
      @click.self="closeRequestForm"
    >
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6 space-y-4">
        <h3 class="text-lg font-semibold text-gray-900">Raise Inventory Request</h3>

        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">Part</label>
          <div class="relative">
            <input
              v-model="partSearch"
              type="text"
              placeholder="Search parts…"
              class="input-field"
              @input="searchParts"
              @focus="showPartDropdown = true"
            />
            <div
              v-if="showPartDropdown && partResults.length"
              class="absolute z-10 mt-1 w-full bg-white border border-gray-200 rounded-lg shadow-lg max-h-48 overflow-y-auto"
            >
              <button
                v-for="p in partResults"
                :key="p.part_code"
                type="button"
                @click="selectPart(p)"
                class="w-full text-left px-3 py-2 text-sm hover:bg-brand-50 border-b border-gray-50 last:border-0"
              >
                <div class="font-medium text-gray-800">{{ p.part_name }}</div>
                <div class="text-xs text-gray-500">{{ p.part_code }} · {{ p.part_group }}</div>
              </button>
            </div>
          </div>
          <div v-if="form.part" class="text-xs text-green-700 mt-1">
            Selected: {{ form.part }}
          </div>
        </div>

        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-xs font-medium text-gray-500 mb-1">Quantity</label>
            <input
              v-model.number="form.quantity"
              type="number"
              min="0.001"
              step="0.5"
              class="input-field"
            />
          </div>
          <div>
            <label class="block text-xs font-medium text-gray-500 mb-1">Urgency</label>
            <select v-model="form.urgency_level" class="input-field">
              <option v-for="u in urgencies" :key="u" :value="u">{{ u }}</option>
            </select>
          </div>
        </div>

        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">Notes</label>
          <textarea v-model="form.notes" rows="2" class="input-field" />
        </div>

        <div v-if="requestError" class="text-sm text-red-600">{{ requestError }}</div>

        <div class="flex gap-2 justify-end pt-2">
          <button
            type="button"
            @click="closeRequestForm"
            class="px-4 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg"
          >
            Cancel
          </button>
          <button
            type="button"
            :disabled="submitting || !form.part || !form.quantity"
            @click="submitRequest"
            class="px-4 py-2 text-sm font-medium text-white bg-brand-600 hover:bg-brand-700 rounded-lg disabled:opacity-50"
          >
            {{ submitting ? "Creating…" : "Raise Request" }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from "vue";
import { call } from "frappe-ui";

const props = defineProps({
  jobCardName: { type: String, required: true },
  roles: { type: Array, default: () => [] },
});

const rows = ref([]);
const loading = ref(false);
const feedback = ref(null);
const actingOn = ref(null);

const requestOpen = ref(false);
const submitting = ref(false);
const requestError = ref("");
const partSearch = ref("");
const partResults = ref([]);
const showPartDropdown = ref(false);
const form = ref({
  part: "",
  quantity: 1,
  urgency_level: "Medium",
  notes: "",
});
const urgencies = ["Low", "Medium", "High", "Critical"];

const canRequest = computed(() =>
  props.roles.some((r) => ["Service Engineer", "Technician", "Depot Manager"].includes(r))
);

const isAllocator = computed(() =>
  props.roles.some((r) => ["Depot Manager", "Central Ops"].includes(r))
);
const isReceiver = computed(() =>
  props.roles.some((r) => ["Technician", "Service Engineer"].includes(r))
);

async function load() {
  loading.value = true;
  try {
    const res = await call(
      "vehicle_maintenance.fleet_service.doctype.inventory_request.inventory_request.list_for_job_card",
      { job_card_ref: props.jobCardName },
    );
    rows.value = res?.data || [];
  } catch (e) {
    rows.value = [];
  } finally {
    loading.value = false;
  }
}

function nextAction(row) {
  if (row.status === "Requested" && isAllocator.value) {
    return { label: "Allocate", target: "Parts Allocated" };
  }
  if (row.status === "Parts Allocated" && isAllocator.value) {
    return { label: "Mark Issued", target: "Parts Issued" };
  }
  if (row.status === "Parts Issued" && isReceiver.value) {
    return { label: "Acknowledge Receipt", target: "Received" };
  }
  return null;
}

async function advance(row) {
  const action = nextAction(row);
  if (!action) return;
  actingOn.value = row.name;
  feedback.value = null;
  try {
    await call("vehicle_maintenance.api.job_card.advance_inventory_status", {
      inventory_request_name: row.name,
      next_status: action.target,
    });
    feedback.value = { ok: true, msg: `Moved to ${action.target}.` };
    await load();
  } catch (e) {
    feedback.value = {
      ok: false,
      msg: e?.messages?.[0] || `Failed to move to ${action.target}.`,
    };
  } finally {
    actingOn.value = null;
  }
}

// ── New-request form ──

function openRequestForm() {
  form.value = { part: "", quantity: 1, urgency_level: "Medium", notes: "" };
  partSearch.value = "";
  partResults.value = [];
  requestError.value = "";
  requestOpen.value = true;
}

function closeRequestForm() {
  requestOpen.value = false;
}

let partTimer = null;
function searchParts() {
  clearTimeout(partTimer);
  if (partSearch.value.length < 2) {
    partResults.value = [];
    return;
  }
  partTimer = setTimeout(async () => {
    try {
      const res = await call(
        "vehicle_maintenance.fleet_service.doctype.part.part.search_parts",
        { query: partSearch.value, limit: 10 },
      );
      partResults.value = res?.data || [];
    } catch {
      partResults.value = [];
    }
  }, 250);
}

function selectPart(p) {
  form.value.part = p.part_code;
  partSearch.value = `${p.part_code} — ${p.part_name}`;
  showPartDropdown.value = false;
  partResults.value = [];
}

async function submitRequest() {
  submitting.value = true;
  requestError.value = "";
  try {
    await call("vehicle_maintenance.api.job_card.create_inventory_request", {
      job_card_name: props.jobCardName,
      part: form.value.part,
      quantity: form.value.quantity,
      urgency_level: form.value.urgency_level,
      notes: form.value.notes,
    });
    feedback.value = { ok: true, msg: "Inventory request raised." };
    closeRequestForm();
    await load();
  } catch (e) {
    requestError.value = e?.messages?.[0] || "Failed to raise request.";
  } finally {
    submitting.value = false;
  }
}

// ── Classes ──

function statusClass(status) {
  const m = {
    Requested: "bg-amber-100 text-amber-700",
    "Parts Allocated": "bg-blue-100 text-blue-700",
    "Parts Issued": "bg-indigo-100 text-indigo-700",
    Received: "bg-green-100 text-green-700",
  };
  return m[status] || "bg-gray-100 text-gray-600";
}

function urgencyTextClass(u) {
  const m = {
    Low: "text-gray-500",
    Medium: "text-amber-600",
    High: "text-orange-600",
    Critical: "text-red-600 font-medium",
  };
  return m[u];
}

onMounted(load);
</script>

<style scoped>
.input-field {
  @apply w-full px-3 py-2 border border-gray-300 rounded-lg text-sm
         focus:ring-2 focus:ring-brand-500 focus:border-brand-500
         text-gray-900 placeholder-gray-400;
}
</style>
