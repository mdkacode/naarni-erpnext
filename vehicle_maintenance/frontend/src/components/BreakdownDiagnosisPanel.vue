<template>
  <!--
    BreakdownDiagnosisPanel — PRD p.15-16 Breakdown lifecycle.

    Collapses the 13-step Breakdown flow into a single inline panel that tracks:
      1. Last PMS context (auto-filled from prior closed PMS card)
      2. Incident place + Fault codes
      3. Remote resolution SLA timer (30-min)
      4. Travel start/arrive timestamps (Mark Start Travel / Mark Arrived)
      5. Fix type + Recurrence/Occurrence risk + Next-level engineer
      6. Trial trip start/end with dead-KM auto-compute
      7. Vehicle handover
      8. Total downtime (server-computed)
      9. RCA notes (Aftersales Eng permlevel 1 — UI allows all internal roles)

    EAS applied:
      Eliminate — No raw timestamps in ISO format shown; friendly labels only.
      Automate  — Travel duration, trial-trip distance/duration, total downtime
                 are all auto-computed server-side; UI just renders them.
      Simplify  — One primary action per row ("Mark Start Travel"), so SE never
                 has to pick between datetime pickers.
  -->
  <div class="space-y-4">
    <!-- ─── Last PMS context ─── -->
    <div
      v-if="breakdown.last_pms_date || breakdown.last_pms_odometer"
      class="bg-gray-50 border border-gray-200 rounded-xl p-3 text-xs grid grid-cols-2 gap-2"
    >
      <div>
        <span class="text-gray-500">Last PMS</span>
        <div class="font-medium text-gray-800">
          {{ formatDate(breakdown.last_pms_date) }}
        </div>
      </div>
      <div>
        <span class="text-gray-500">Last Serviced Odometer</span>
        <div class="font-medium text-gray-800">
          {{ (breakdown.last_pms_odometer || 0).toLocaleString() }} km
        </div>
      </div>
      <div v-if="breakdown.last_service_tolerance_level">
        <span class="text-gray-500">Last Tolerance</span>
        <div class="font-medium text-gray-800">{{ breakdown.last_service_tolerance_level }}</div>
      </div>
      <div v-if="breakdown.last_serviced_by">
        <span class="text-gray-500">Last Serviced By</span>
        <div class="font-medium text-gray-800">{{ breakdown.last_serviced_by }}</div>
      </div>
    </div>

    <!-- ─── Incident + Fault codes ─── -->
    <section class="bg-white border border-gray-200 rounded-xl p-4 space-y-3">
      <h4 class="text-sm font-semibold text-gray-800">Incident</h4>

      <div>
        <label class="block text-xs font-medium text-gray-500 mb-1">Incident Place</label>
        <div class="flex gap-2">
          <button
            v-for="p in ['Depot', 'En Route']"
            :key="p"
            type="button"
            :disabled="disabled"
            @click="emitPatch({ incident_place: p })"
            class="flex-1 py-2 text-xs font-medium rounded-lg border-2 transition-all disabled:opacity-50"
            :class="
              breakdown.incident_place === p
                ? 'border-brand-500 bg-brand-50 text-brand-700'
                : 'border-gray-200 text-gray-500 hover:border-gray-300'
            "
          >
            {{ p }}
          </button>
        </div>
      </div>

      <div class="grid grid-cols-3 gap-2">
        <div v-for="i in [1, 2, 3]" :key="i">
          <label class="block text-xs font-medium text-gray-500 mb-1">
            Fault Code {{ i }}
          </label>
          <input
            :value="breakdown[`fault_code_${i}`]"
            :disabled="disabled"
            @change="emitPatch({ [`fault_code_${i}`]: $event.target.value })"
            type="text"
            placeholder="optional"
            class="input-field"
          />
        </div>
      </div>

      <div class="pt-1">
        <label class="block text-xs font-medium text-gray-500 mb-1">
          Groups Impacted
          <span class="text-gray-400 font-normal">(multi-select)</span>
        </label>
        <div v-if="breakdown.groups_impacted?.length" class="flex flex-wrap gap-1.5 mb-1">
          <span
            v-for="g in breakdown.groups_impacted"
            :key="g"
            class="inline-flex items-center gap-1 px-2 py-0.5 text-xs font-medium rounded-full bg-brand-50 text-brand-700"
          >
            {{ g }}
            <button
              v-if="!disabled"
              type="button"
              @click="toggleGroup(g)"
              class="text-brand-400 hover:text-red-500"
              aria-label="Remove"
            >
              &times;
            </button>
          </span>
        </div>
        <div v-else class="text-xs text-gray-400 mb-1">None selected</div>
        <div v-if="!disabled" class="relative">
          <button
            type="button"
            @click="groupOpen = !groupOpen"
            class="text-xs text-brand-600 hover:underline"
          >
            {{ groupOpen ? "Close" : "+ Add groups" }}
          </button>
          <div
            v-if="groupOpen"
            class="absolute z-20 left-0 mt-1 w-64 bg-white border border-gray-200 rounded-lg shadow-lg max-h-56 overflow-y-auto p-2"
          >
            <input
              v-model="groupSearch"
              type="text"
              placeholder="Search groups…"
              class="w-full px-2 py-1.5 text-xs border border-gray-200 rounded mb-2 focus:outline-none focus:border-brand-500"
            />
            <div v-if="!filteredGroupOptions.length" class="text-xs text-gray-400 text-center py-2">
              No groups match.
            </div>
            <button
              v-for="opt in filteredGroupOptions"
              :key="opt.name"
              type="button"
              @click="toggleGroup(opt.name)"
              class="w-full text-left px-2 py-1.5 text-xs rounded hover:bg-brand-50 flex justify-between"
            >
              <span>{{ opt.part_group_name || opt.name }}</span>
              <span class="text-gray-400">{{ opt.bus_system }}</span>
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- ─── Force Override / Process Override ─── -->
    <section class="bg-white border border-gray-200 rounded-xl p-4 space-y-3">
      <h4 class="text-sm font-semibold text-gray-800">SOP Override</h4>
      <p class="text-xs text-gray-500 -mt-1">
        PRD p.15 step 8 — tick only if no documented process applies or the
        documented process didn't resolve the issue.
      </p>

      <!-- Force Override -->
      <div>
        <label class="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            :checked="!!breakdown.force_override"
            :disabled="disabled"
            @change="emitPatch({ force_override: $event.target.checked ? 1 : 0 })"
            class="mt-0.5"
          />
          <span>
            <span class="font-medium">Force Override</span>
            <span class="block text-xs text-gray-500">No SOP available for this group</span>
          </span>
        </label>
        <textarea
          v-if="breakdown.force_override"
          :value="breakdown.force_override_reason || ''"
          :disabled="disabled"
          @change="emitPatch({ force_override_reason: $event.target.value })"
          rows="2"
          placeholder="Why was there no documented process?"
          class="input-field mt-2"
        />
      </div>

      <!-- Process Override -->
      <div>
        <label class="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            :checked="!!breakdown.process_override"
            :disabled="disabled"
            @change="emitPatch({ process_override: $event.target.checked ? 1 : 0 })"
            class="mt-0.5"
          />
          <span>
            <span class="font-medium">Process Override</span>
            <span class="block text-xs text-gray-500">SOP was attempted but didn't resolve — record alternate steps taken</span>
          </span>
        </label>
        <textarea
          v-if="breakdown.process_override"
          :value="breakdown.process_override_steps || ''"
          :disabled="disabled"
          @change="emitPatch({ process_override_steps: $event.target.value })"
          rows="3"
          placeholder="Step-by-step alternate troubleshooting actions the SE performed"
          class="input-field mt-2"
        />
      </div>
    </section>

    <!-- ─── Remote Resolution Timer ─── -->
    <section class="bg-white border border-gray-200 rounded-xl p-4 space-y-3">
      <div class="flex items-center justify-between">
        <h4 class="text-sm font-semibold text-gray-800">Remote Resolution (30-min SLA)</h4>
        <span
          class="text-xs font-medium px-2 py-0.5 rounded-full"
          :class="remoteStatusClass"
        >
          {{ breakdown.remote_resolution_status || "Not started" }}
        </span>
      </div>
      <div class="text-xs text-gray-500 grid grid-cols-2 gap-2">
        <div>
          <span>Started</span>
          <div class="font-medium text-gray-800">
            {{ formatDateTime(breakdown.remote_resolution_started_at) }}
          </div>
        </div>
        <div>
          <span>Elapsed</span>
          <div
            class="font-medium"
            :class="remoteElapsedMinutes >= 30 ? 'text-red-700' : 'text-gray-800'"
          >
            {{ remoteElapsedMinutes }}m
            {{ remoteElapsedMinutes >= 30 ? "· SLA breached" : "" }}
          </div>
        </div>
      </div>
      <div class="flex gap-2">
        <button
          type="button"
          :disabled="disabled || breakdown.remote_resolution_status === 'Resolved'"
          @click="emitPatch({ remote_resolution_status: 'Resolved' })"
          class="flex-1 py-2 text-xs font-medium rounded-lg border-2 border-green-500 text-green-700 hover:bg-green-50 disabled:opacity-50"
        >
          Mark Resolved
        </button>
        <button
          type="button"
          :disabled="disabled || breakdown.remote_resolution_status === 'Failed'"
          @click="emitPatch({ remote_resolution_status: 'Failed' })"
          class="flex-1 py-2 text-xs font-medium rounded-lg border-2 border-red-500 text-red-700 hover:bg-red-50 disabled:opacity-50"
        >
          Mark Failed
        </button>
      </div>
    </section>

    <!-- ─── Travel to BD location ─── -->
    <section class="bg-white border border-gray-200 rounded-xl p-4 space-y-3">
      <h4 class="text-sm font-semibold text-gray-800">Travel to BD Location</h4>
      <div class="grid grid-cols-3 gap-2 text-xs">
        <div>
          <span class="text-gray-500">Start Travel</span>
          <div class="font-medium text-gray-800">
            {{ formatDateTime(breakdown.travel_started_at) || "—" }}
          </div>
        </div>
        <div>
          <span class="text-gray-500">Arrived</span>
          <div class="font-medium text-gray-800">
            {{ formatDateTime(breakdown.arrived_at_location) || "—" }}
          </div>
        </div>
        <div>
          <span class="text-gray-500">Duration</span>
          <div class="font-medium text-gray-800">
            {{ breakdown.travel_duration_minutes
              ? `${breakdown.travel_duration_minutes.toFixed(0)} min`
              : "—" }}
          </div>
        </div>
      </div>
      <div class="flex gap-2">
        <button
          type="button"
          :disabled="disabled || breakdown.travel_started_at"
          @click="emitPatch({ travel_started_at: nowISO() })"
          class="flex-1 py-2 text-xs font-medium rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50 disabled:opacity-50"
        >
          Mark Start Travel
        </button>
        <button
          type="button"
          :disabled="disabled || !breakdown.travel_started_at || breakdown.arrived_at_location"
          @click="emitPatch({ arrived_at_location: nowISO() })"
          class="flex-1 py-2 text-xs font-medium rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50 disabled:opacity-50"
        >
          Mark Arrived
        </button>
      </div>
    </section>

    <!-- ─── Fix Type + Risk ─── -->
    <section class="bg-white border border-gray-200 rounded-xl p-4 space-y-3">
      <h4 class="text-sm font-semibold text-gray-800">Resolution</h4>

      <div>
        <label class="block text-xs font-medium text-gray-500 mb-1">Fix Type</label>
        <div class="flex gap-2">
          <button
            v-for="t in ['Permanent', 'Temporary', 'Force Closed']"
            :key="t"
            type="button"
            :disabled="disabled"
            @click="emitPatch({ fix_type: t })"
            class="flex-1 py-2 text-xs font-medium rounded-lg border-2 transition-all disabled:opacity-50"
            :class="
              breakdown.fix_type === t
                ? fixTypeClass(t)
                : 'border-gray-200 text-gray-500 hover:border-gray-300'
            "
          >
            {{ t }}
          </button>
        </div>
      </div>

      <div v-if="breakdown.fix_type === 'Temporary'">
        <label class="block text-xs font-medium text-gray-500 mb-1">Escalate to Next-Level Engineer</label>
        <input
          :value="breakdown.next_level_engineer"
          :disabled="disabled"
          @change="emitPatch({ next_level_engineer: $event.target.value })"
          type="text"
          placeholder="User ID of senior engineer"
          class="input-field"
        />
      </div>

      <div class="grid grid-cols-2 gap-2">
        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">Recurrence Risk (this vehicle)</label>
          <div class="flex gap-2">
            <button
              v-for="r in ['Low', 'High']"
              :key="r"
              type="button"
              :disabled="disabled"
              @click="emitPatch({ recurrence_risk: r })"
              class="flex-1 py-1.5 text-xs font-medium rounded-lg border-2 disabled:opacity-50"
              :class="
                breakdown.recurrence_risk === r
                  ? riskClass(r)
                  : 'border-gray-200 text-gray-500 hover:border-gray-300'
              "
            >
              {{ r }}
            </button>
          </div>
        </div>
        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">Occurrence Risk (fleet-wide)</label>
          <div class="flex gap-2">
            <button
              v-for="r in ['Low', 'High']"
              :key="r"
              type="button"
              :disabled="disabled"
              @click="emitPatch({ occurrence_risk: r })"
              class="flex-1 py-1.5 text-xs font-medium rounded-lg border-2 disabled:opacity-50"
              :class="
                breakdown.occurrence_risk === r
                  ? riskClass(r)
                  : 'border-gray-200 text-gray-500 hover:border-gray-300'
              "
            >
              {{ r }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- ─── Trial Trip ─── -->
    <section class="bg-white border border-gray-200 rounded-xl p-4 space-y-3">
      <h4 class="text-sm font-semibold text-gray-800">Trial Trip</h4>
      <div class="grid grid-cols-4 gap-2 text-xs">
        <div>
          <span class="text-gray-500">Start</span>
          <div class="font-medium text-gray-800">
            {{ formatDateTime(breakdown.trial_trip_started_at) || "—" }}
          </div>
        </div>
        <div>
          <label class="block text-gray-500 mb-1">Start km</label>
          <input
            :value="breakdown.trial_trip_start_km"
            :disabled="disabled"
            @change="emitPatch({ trial_trip_start_km: Number($event.target.value) || 0 })"
            type="number"
            min="0"
            class="input-field"
          />
        </div>
        <div>
          <span class="text-gray-500">End</span>
          <div class="font-medium text-gray-800">
            {{ formatDateTime(breakdown.trial_trip_ended_at) || "—" }}
          </div>
        </div>
        <div>
          <label class="block text-gray-500 mb-1">End km</label>
          <input
            :value="breakdown.trial_trip_end_km"
            :disabled="disabled"
            @change="emitPatch({ trial_trip_end_km: Number($event.target.value) || 0 })"
            type="number"
            min="0"
            class="input-field"
          />
        </div>
      </div>
      <div class="grid grid-cols-2 gap-2 text-xs">
        <div class="bg-gray-50 p-2 rounded">
          <span class="text-gray-500">Dead km</span>
          <div class="font-medium text-gray-800">
            {{ breakdown.trial_trip_distance_km ?? "—" }}
          </div>
        </div>
        <div class="bg-gray-50 p-2 rounded">
          <span class="text-gray-500">Duration</span>
          <div class="font-medium text-gray-800">
            {{ breakdown.trial_trip_duration_minutes
              ? `${breakdown.trial_trip_duration_minutes.toFixed(0)} min`
              : "—" }}
          </div>
        </div>
      </div>
      <div class="flex gap-2">
        <button
          type="button"
          :disabled="disabled || breakdown.trial_trip_started_at"
          @click="emitPatch({ trial_trip_started_at: nowISO() })"
          class="flex-1 py-2 text-xs font-medium rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50 disabled:opacity-50"
        >
          Start Trial Trip
        </button>
        <button
          type="button"
          :disabled="disabled || !breakdown.trial_trip_started_at || breakdown.trial_trip_ended_at"
          @click="emitPatch({ trial_trip_ended_at: nowISO() })"
          class="flex-1 py-2 text-xs font-medium rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50 disabled:opacity-50"
        >
          End Trial Trip
        </button>
      </div>
    </section>

    <!-- ─── Vehicle Handover + Downtime ─── -->
    <section class="bg-white border border-gray-200 rounded-xl p-4 space-y-3">
      <h4 class="text-sm font-semibold text-gray-800">Handover</h4>
      <div class="grid grid-cols-2 gap-2 text-xs">
        <div>
          <span class="text-gray-500">Vehicle Handed Over</span>
          <div class="font-medium text-gray-800">
            {{ formatDateTime(breakdown.vehicle_handover_at) || "—" }}
          </div>
        </div>
        <div>
          <span class="text-gray-500">Total Downtime</span>
          <div class="font-medium text-gray-800">
            {{ breakdown.total_downtime_minutes
              ? `${breakdown.total_downtime_minutes.toFixed(0)} min`
              : "—" }}
          </div>
        </div>
      </div>
      <button
        type="button"
        :disabled="disabled || breakdown.vehicle_handover_at"
        @click="emitPatch({ vehicle_handover_at: nowISO() })"
        class="w-full py-2 text-xs font-medium rounded-lg border-2 border-green-500 text-green-700 hover:bg-green-50 disabled:opacity-50"
      >
        Mark Vehicle Handed Over
      </button>
    </section>

    <!-- ─── RCA ─── -->
    <section class="bg-white border border-gray-200 rounded-xl p-4 space-y-2">
      <div class="flex items-center justify-between">
        <h4 class="text-sm font-semibold text-gray-800">Root Cause Analysis</h4>
        <span v-if="breakdown.rca_received_at" class="text-xs text-green-700">
          Received {{ formatDateTime(breakdown.rca_received_at) }}
        </span>
      </div>
      <textarea
        :value="breakdown.rca_notes || ''"
        :disabled="disabled || !canEditRca"
        @change="emitPatch({ rca_notes: $event.target.value })"
        rows="4"
        placeholder="Aftersales Engineer — root cause, fix narrative, customer-facing summary…"
        class="input-field"
      />
      <div v-if="!canEditRca && !disabled" class="text-xs text-gray-400">
        Only Aftersales Eng / DM / N. Maint. Head can edit the RCA.
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from "vue";
import { call } from "frappe-ui";

const props = defineProps({
  modelValue: { type: Object, default: () => ({}) },
  disabled: { type: Boolean, default: false },
  canEditRca: { type: Boolean, default: true },
});
const emit = defineEmits(["save", "save-groups"]);

const breakdown = computed(() => props.modelValue || {});

// ── Groups Impacted picker state ──
const groupOpen = ref(false);
const groupSearch = ref("");
const groupOptions = ref([]);

const filteredGroupOptions = computed(() => {
  const q = groupSearch.value.trim().toLowerCase();
  if (!q) return groupOptions.value;
  return groupOptions.value.filter((o) =>
    (o.part_group_name || o.name || "").toLowerCase().includes(q),
  );
});

async function loadGroups() {
  try {
    const res = await call("vehicle_maintenance.api.job_card.list_part_groups");
    groupOptions.value = res?.data || [];
  } catch {
    groupOptions.value = [];
  }
}

function toggleGroup(name) {
  const current = breakdown.value.groups_impacted || [];
  const next = current.includes(name)
    ? current.filter((n) => n !== name)
    : [...current, name];
  emit("save-groups", next);
}

onMounted(loadGroups);

const remoteElapsedMinutes = computed(() => {
  if (!breakdown.value.remote_resolution_started_at) return 0;
  const start = new Date(breakdown.value.remote_resolution_started_at);
  const end = breakdown.value.remote_resolution_failed_at
    ? new Date(breakdown.value.remote_resolution_failed_at)
    : new Date();
  const mins = Math.round((end - start) / 60000);
  return Number.isFinite(mins) && mins > 0 ? mins : 0;
});

const remoteStatusClass = computed(() => {
  const map = {
    "In Progress": "bg-amber-100 text-amber-700",
    Resolved: "bg-green-100 text-green-700",
    Failed: "bg-red-100 text-red-700",
  };
  return map[breakdown.value.remote_resolution_status] || "bg-gray-100 text-gray-600";
});

function emitPatch(patch) {
  emit("save", patch);
}

function nowISO() {
  // Frappe accepts "YYYY-MM-DD HH:MM:SS" for Datetime fields.
  const pad = (n) => String(n).padStart(2, "0");
  const d = new Date();
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  );
}

function formatDate(val) {
  if (!val) return "—";
  try {
    return new Date(val).toLocaleDateString("en-IN", {
      day: "numeric", month: "short", year: "numeric",
    });
  } catch {
    return val;
  }
}

function formatDateTime(val) {
  if (!val) return "";
  try {
    return new Date(val).toLocaleString("en-IN", {
      day: "numeric", month: "short", hour: "2-digit", minute: "2-digit",
    });
  } catch {
    return val;
  }
}

function fixTypeClass(t) {
  const m = {
    Permanent: "border-green-500 bg-green-50 text-green-700",
    Temporary: "border-amber-500 bg-amber-50 text-amber-700",
    "Force Closed": "border-red-500 bg-red-50 text-red-700",
  };
  return m[t];
}

function riskClass(r) {
  return r === "High"
    ? "border-red-500 bg-red-50 text-red-700"
    : "border-green-500 bg-green-50 text-green-700";
}
</script>

<style scoped>
.input-field {
  @apply w-full px-3 py-2 border border-gray-300 rounded-lg text-sm
         focus:ring-2 focus:ring-brand-500 focus:border-brand-500
         text-gray-900 placeholder-gray-400 transition-colors
         disabled:opacity-60 disabled:cursor-not-allowed;
}
</style>
