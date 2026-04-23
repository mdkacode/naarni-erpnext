<template>
  <!--
    SoftwareUpdatePanel — PRD p.13-14 Software Update lifecycle.

    Each row represents one ECU/component being updated:
      • Component + Reason (Performance / Regular / Emergency)
      • Pre-version + photo, Post-version + photo
      • Calibration values (hidden from customer per PRD)
      • Status: Pending / In Progress / Success / Failed — with a Retry button
        that increments retry_count when the previous attempt failed.

    Calibration values + photo are rendered behind a "Calibration" toggle so
    the customer view (read-only) doesn't surface them.
  -->
  <div class="space-y-3">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-semibold text-gray-800">Software Components</h3>
      <button
        v-if="!disabled"
        type="button"
        @click="addRow"
        class="px-3 py-1.5 text-xs font-medium text-brand-700 bg-brand-50 hover:bg-brand-100 rounded-lg"
      >
        + Add Component
      </button>
    </div>

    <div
      v-if="!rows.length"
      class="text-center py-6 text-sm text-gray-400 border border-dashed border-gray-200 rounded-xl"
    >
      No components added yet — tap "+ Add Component" to start.
    </div>

    <div
      v-for="(row, idx) in rows"
      :key="idx"
      class="bg-white border border-gray-200 rounded-xl p-4 space-y-3"
    >
      <!-- Component + status + remove -->
      <div class="flex items-start gap-3">
        <div class="flex-1">
          <label class="block text-xs font-medium text-gray-500 mb-1">Component</label>
          <input
            :value="row.component"
            :disabled="disabled"
            @change="updateRow(idx, 'component', $event.target.value)"
            type="text"
            placeholder="e.g. BMS, VCU, HVAC controller"
            class="input-field"
          />
        </div>
        <div class="w-36">
          <label class="block text-xs font-medium text-gray-500 mb-1">Status</label>
          <span
            class="inline-flex items-center px-2 py-1.5 text-xs font-medium rounded-lg w-full justify-center"
            :class="statusClass(row.status)"
          >
            {{ row.status || "Pending" }}
            <span v-if="row.retry_count" class="ml-1 text-gray-500">
              (retry {{ row.retry_count }})
            </span>
          </span>
        </div>
        <button
          v-if="!disabled"
          type="button"
          @click="removeRow(idx)"
          class="mt-6 text-red-500 hover:text-red-600 text-sm px-2"
          aria-label="Remove"
        >
          &#10005;
        </button>
      </div>

      <!-- Reason -->
      <div>
        <label class="block text-xs font-medium text-gray-500 mb-1">Reason for Update</label>
        <div class="flex gap-2">
          <button
            v-for="r in reasons"
            :key="r"
            type="button"
            :disabled="disabled"
            @click="updateRow(idx, 'reason', r)"
            class="flex-1 py-1.5 text-[11px] font-medium rounded-lg border-2 transition-all disabled:opacity-50"
            :class="
              row.reason === r
                ? 'border-brand-500 bg-brand-50 text-brand-700'
                : 'border-gray-200 text-gray-500 hover:border-gray-300'
            "
          >
            {{ r }}
          </button>
        </div>
      </div>

      <!-- Pre / Post version -->
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="block text-xs text-gray-500 mb-1">Pre Version</label>
          <input
            :value="row.pre_version"
            :disabled="disabled"
            @change="updateRow(idx, 'pre_version', $event.target.value)"
            type="text"
            placeholder="e.g. 2.4.1"
            class="input-field"
          />
          <input
            v-if="!disabled"
            type="file"
            accept="image/*"
            @change="onPhoto(idx, 'pre_version_photo', $event)"
            class="mt-1 text-xs"
          />
          <div v-if="row.pre_version_photo" class="text-xs text-green-600 mt-0.5">
            &#10003; Photo attached
          </div>
        </div>
        <div>
          <label class="block text-xs text-gray-500 mb-1">Post Version</label>
          <input
            :value="row.post_version"
            :disabled="disabled"
            @change="updateRow(idx, 'post_version', $event.target.value)"
            type="text"
            placeholder="e.g. 2.4.2"
            class="input-field"
          />
          <input
            v-if="!disabled"
            type="file"
            accept="image/*"
            @change="onPhoto(idx, 'post_version_photo', $event)"
            class="mt-1 text-xs"
          />
          <div v-if="row.post_version_photo" class="text-xs text-green-600 mt-0.5">
            &#10003; Photo attached
          </div>
        </div>
      </div>

      <!-- Calibration (collapsed — not surfaced to customer) -->
      <details v-if="!customerView" class="text-xs text-gray-600">
        <summary class="cursor-pointer font-medium">
          Calibration (internal only)
        </summary>
        <div class="mt-2 space-y-2">
          <textarea
            :value="row.calibration_values"
            :disabled="disabled"
            @change="updateRow(idx, 'calibration_values', $event.target.value)"
            rows="2"
            placeholder="Calibration values per SOP…"
            class="input-field"
          />
        </div>
      </details>

      <!-- Failure context -->
      <div v-if="row.status === 'Failed'">
        <label class="block text-xs text-red-600 mb-1">Failure Notes</label>
        <textarea
          :value="row.failure_notes"
          :disabled="disabled"
          @change="updateRow(idx, 'failure_notes', $event.target.value)"
          rows="2"
          placeholder="What failed? (log messages, error codes, tool feedback)"
          class="input-field"
        />
      </div>

      <!-- Action buttons -->
      <div v-if="!disabled" class="flex flex-wrap gap-2 pt-1">
        <button
          type="button"
          :disabled="row.status === 'In Progress'"
          @click="updateRow(idx, 'status', 'In Progress')"
          class="px-3 py-1.5 text-xs rounded-lg border-2 border-amber-400 text-amber-700 hover:bg-amber-50 disabled:opacity-50"
        >
          Start
        </button>
        <button
          type="button"
          :disabled="row.status === 'Success'"
          @click="markSuccess(idx)"
          class="px-3 py-1.5 text-xs rounded-lg border-2 border-green-500 text-green-700 hover:bg-green-50 disabled:opacity-50"
        >
          Mark Success
        </button>
        <button
          type="button"
          :disabled="row.status === 'Failed'"
          @click="markFailed(idx)"
          class="px-3 py-1.5 text-xs rounded-lg border-2 border-red-500 text-red-700 hover:bg-red-50 disabled:opacity-50"
        >
          Mark Failed
        </button>
        <button
          v-if="row.status === 'Failed'"
          type="button"
          @click="retry(idx)"
          class="px-3 py-1.5 text-xs rounded-lg border-2 border-brand-500 text-brand-700 hover:bg-brand-50"
        >
          Retry
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from "vue";

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
  customerView: { type: Boolean, default: false },
});
const emit = defineEmits(["update:modelValue"]);

const reasons = [
  "Performance Improvement",
  "Regular Update",
  "Emergency Update (Bug Fix)",
];

const rows = computed(() => props.modelValue || []);

function emitChange(next) {
  emit("update:modelValue", next);
}

function addRow() {
  emitChange([
    ...rows.value,
    {
      component: "",
      reason: "Regular Update",
      status: "Pending",
      retry_count: 0,
      pre_version: "",
      post_version: "",
      pre_version_photo: null,
      post_version_photo: null,
      calibration_values: "",
      failure_notes: "",
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

function markSuccess(idx) {
  updateRow(idx, "status", "Success");
}

function markFailed(idx) {
  updateRow(idx, "status", "Failed");
}

function retry(idx) {
  const row = rows.value[idx];
  const copy = rows.value.map((r, i) =>
    i === idx
      ? { ...r, status: "In Progress", retry_count: (r.retry_count || 0) + 1 }
      : r,
  );
  emitChange(copy);
}

function onPhoto(idx, field, event) {
  const file = event.target.files?.[0];
  if (!file) return;
  updateRow(idx, field, file);
}

function statusClass(status) {
  const m = {
    Pending: "bg-gray-100 text-gray-600",
    "In Progress": "bg-amber-100 text-amber-700",
    Success: "bg-green-100 text-green-700",
    Failed: "bg-red-100 text-red-700",
  };
  return m[status || "Pending"];
}
</script>

<style scoped>
.input-field {
  @apply w-full px-3 py-2 border border-gray-300 rounded-lg text-sm
         focus:ring-2 focus:ring-brand-500 focus:border-brand-500
         text-gray-900 placeholder-gray-400 transition-colors
         disabled:opacity-60;
}
</style>
