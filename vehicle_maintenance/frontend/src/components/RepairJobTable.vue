<template>
  <!--
    RepairJobTable — PRD p.5 "Repair Job List".

    Each row represents a repair scoped to a Part Group and an Activity Type.
      • Only Repair        → labour only, no part consumed
      • Spare Replacement  → replace part(s), quantity required
      • Both               → replacement + labour

    EAS applied:
      Eliminate — Amount math and approval threshold logic hidden from the
                  technician's view (computed on save, shown as read-only).
      Automate  — Customer-approval flag auto-lights when line total > ₹1,000.
      Simplify  — Tap-friendly Activity Type chips; photos collapse behind
                  a single "Evidence" toggle per row.
  -->
  <div class="space-y-3">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-semibold text-gray-800">Repair Jobs</h3>
      <button
        type="button"
        @click="addRow"
        class="px-3 py-1.5 text-xs font-medium text-brand-700 bg-brand-50 hover:bg-brand-100 rounded-lg transition-colors"
      >
        + Add Repair
      </button>
    </div>

    <div v-if="!rows.length" class="text-center py-6 text-sm text-gray-400 border border-dashed border-gray-200 rounded-xl">
      No repair jobs yet — tap "Add Repair" to start.
    </div>

    <div
      v-for="(row, idx) in rows"
      :key="idx"
      class="bg-white border border-gray-200 rounded-xl p-4 space-y-3"
    >
      <!-- Row header: part group + remove -->
      <div class="flex items-start gap-3">
        <div class="flex-1">
          <label class="block text-xs font-medium text-gray-500 mb-1">Part Group</label>
          <select
            :value="row.part_group"
            @change="updateRow(idx, 'part_group', $event.target.value)"
            class="input-field"
          >
            <option value="">Select part group…</option>
            <option v-for="pg in partGroups" :key="pg.name" :value="pg.name">
              {{ pg.part_group_name || pg.name }} ({{ pg.bus_system }})
            </option>
          </select>
        </div>
        <button
          type="button"
          @click="removeRow(idx)"
          class="mt-5 text-red-500 hover:text-red-600 text-sm px-2 py-1"
          aria-label="Remove row"
        >
          &#10005;
        </button>
      </div>

      <!-- Activity type chips -->
      <div>
        <label class="block text-xs font-medium text-gray-500 mb-1">Activity Type</label>
        <div class="flex gap-2">
          <button
            v-for="t in activityTypes"
            :key="t"
            type="button"
            @click="updateRow(idx, 'activity_type', t)"
            class="flex-1 py-2 text-xs font-medium rounded-lg border-2 transition-all"
            :class="
              row.activity_type === t
                ? 'border-brand-500 bg-brand-50 text-brand-700'
                : 'border-gray-200 text-gray-500 hover:border-gray-300'
            "
          >
            {{ t }}
          </button>
        </div>
      </div>

      <!-- Description -->
      <div>
        <label class="block text-xs font-medium text-gray-500 mb-1">Work Description</label>
        <input
          :value="row.description"
          @input="updateRow(idx, 'description', $event.target.value)"
          type="text"
          placeholder="e.g. Replace worn brake pads"
          class="input-field"
        />
      </div>

      <!-- Qty / Rate / Component status (3-tier) -->
      <div class="grid grid-cols-3 gap-2">
        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">Qty</label>
          <input
            :value="row.qty"
            @input="updateRow(idx, 'qty', Number($event.target.value) || 0)"
            type="number"
            min="0"
            step="0.5"
            class="input-field"
          />
        </div>
        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">Est. Rate (₹)</label>
          <input
            :value="row.rate"
            @input="updateRow(idx, 'rate', Number($event.target.value) || 0)"
            type="number"
            min="0"
            step="1"
            class="input-field"
          />
        </div>
        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">Line Total</label>
          <div class="input-field bg-gray-50 text-gray-500 flex items-center">
            ₹ {{ ((row.qty || 0) * (row.rate || 0)).toLocaleString() }}
          </div>
        </div>
      </div>

      <!-- Customer approval banner — only relevant when parts are being replaced -->
      <div
        v-if="needsApproval(row)"
        class="flex items-center gap-2 p-2 text-xs bg-amber-50 border border-amber-200 rounded-lg text-amber-800"
      >
        <span class="font-bold">!</span>
        Over ₹1,000 — customer approval required before parts are allocated.
      </div>

      <!-- Evidence (collapsed by default) -->
      <details class="text-xs text-gray-600">
        <summary class="cursor-pointer select-none font-medium">Evidence photos</summary>
        <div class="grid grid-cols-2 gap-3 mt-2">
          <div>
            <label class="block text-xs text-gray-500 mb-1">Pre-Repair</label>
            <input
              type="file"
              accept="image/*"
              @change="onPhoto(idx, 'pre_repair_photo', $event)"
              class="text-xs"
            />
            <div v-if="row.pre_repair_photo" class="mt-1 text-green-600">&#10003; Attached</div>
          </div>
          <div>
            <label class="block text-xs text-gray-500 mb-1">Post-Repair</label>
            <input
              type="file"
              accept="image/*"
              @change="onPhoto(idx, 'post_repair_photo', $event)"
              class="text-xs"
            />
            <div v-if="row.post_repair_photo" class="mt-1 text-green-600">&#10003; Attached</div>
          </div>
        </div>
      </details>
    </div>

    <!-- Summary -->
    <div v-if="rows.length" class="text-right text-sm text-gray-600 pt-2 border-t border-gray-100">
      <span class="font-medium">Total Estimate:</span>
      ₹ {{ totalEstimate.toLocaleString() }}
      <span v-if="approvalNeeded" class="ml-3 text-amber-700 font-medium">
        &middot; needs customer approval
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed } from "vue";

const props = defineProps({
  modelValue: {
    type: Array,
    default: () => [],
  },
  partGroups: {
    type: Array,
    default: () => [],
  },
});

const emit = defineEmits(["update:modelValue"]);

const activityTypes = ["Only Repair", "Spare Replacement", "Both"];

const APPROVAL_THRESHOLD = 1000;

const rows = computed(() => props.modelValue || []);

const totalEstimate = computed(() =>
  rows.value.reduce((sum, r) => sum + (Number(r.qty) || 0) * (Number(r.rate) || 0), 0)
);

const approvalNeeded = computed(() =>
  rows.value.some((r) => needsApproval(r))
);

function needsApproval(row) {
  if (row.activity_type !== "Spare Replacement" && row.activity_type !== "Both") {
    return false;
  }
  const lineTotal = (Number(row.qty) || 0) * (Number(row.rate) || 0);
  return lineTotal > APPROVAL_THRESHOLD;
}

function emitChange(next) {
  emit("update:modelValue", next);
}

function addRow() {
  emitChange([
    ...rows.value,
    {
      part_group: "",
      activity_type: "Only Repair",
      description: "",
      qty: 1,
      rate: 0,
      pre_repair_photo: null,
      post_repair_photo: null,
    },
  ]);
}

function removeRow(idx) {
  emitChange(rows.value.filter((_, i) => i !== idx));
}

function updateRow(idx, field, value) {
  const copy = rows.value.map((r, i) => (i === idx ? { ...r, [field]: value } : r));
  emitChange(copy);
}

function onPhoto(idx, field, event) {
  const file = event.target.files?.[0];
  if (!file) return;
  // Defer actual upload to submit time; store the File object as a placeholder.
  updateRow(idx, field, file);
}
</script>

<style scoped>
.input-field {
  @apply w-full px-3 py-2 border border-gray-300 rounded-lg text-sm
         focus:ring-2 focus:ring-brand-500 focus:border-brand-500
         text-gray-900 placeholder-gray-400 transition-colors;
}
</style>
