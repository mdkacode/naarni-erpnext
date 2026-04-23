<template>
  <!--
    MaintenanceJobTable — PRD p.5 "Maintenance Job List".

    Captures oil / coolant / grease / filter top-ups or replacements with
    evidence photos per PRD:
      • Oil/Coolant Replacement  → drained photo + new-fluid photo (with volume)
      • Filter Cleaning          → dirty photo + cleaned photo
      • Filter Replacement       → old photo + new photo
      • Top-up                   → single photo of the new fluid

    EAS applied:
      Eliminate — No cost fields surfaced; only the volume + evidence.
      Automate  — Photo slots are auto-labelled based on selected Action
                  (drained vs dirty vs pre vs post).
      Simplify  — Two photo slots per row, always. Labels shift to fit the
                  action, so technicians never wonder "what photo goes here?".
  -->
  <div class="space-y-3">
    <div class="flex items-center justify-between">
      <h3 class="text-sm font-semibold text-gray-800">Maintenance Jobs</h3>
      <button
        type="button"
        @click="addRow"
        class="px-3 py-1.5 text-xs font-medium text-brand-700 bg-brand-50 hover:bg-brand-100 rounded-lg transition-colors"
      >
        + Add Maintenance
      </button>
    </div>

    <div v-if="!rows.length" class="text-center py-6 text-sm text-gray-400 border border-dashed border-gray-200 rounded-xl">
      No maintenance items yet — tap "Add Maintenance" for oil, coolant, or filters.
    </div>

    <div
      v-for="(row, idx) in rows"
      :key="idx"
      class="bg-white border border-gray-200 rounded-xl p-4 space-y-3"
    >
      <!-- Type + Remove -->
      <div class="flex items-start gap-3">
        <div class="flex-1">
          <label class="block text-xs font-medium text-gray-500 mb-1">Type</label>
          <select
            :value="row.maintenance_type"
            @change="updateRow(idx, 'maintenance_type', $event.target.value)"
            class="input-field"
          >
            <option value="">Select…</option>
            <option v-for="t in maintenanceTypes" :key="t" :value="t">{{ t }}</option>
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

      <!-- Action chips -->
      <div>
        <label class="block text-xs font-medium text-gray-500 mb-1">Action</label>
        <div class="flex gap-2">
          <button
            v-for="a in availableActions(row)"
            :key="a"
            type="button"
            @click="updateRow(idx, 'action', a)"
            class="flex-1 py-2 text-xs font-medium rounded-lg border-2 transition-all"
            :class="
              row.action === a
                ? 'border-brand-500 bg-brand-50 text-brand-700'
                : 'border-gray-200 text-gray-500 hover:border-gray-300'
            "
          >
            {{ a }}
          </button>
        </div>
      </div>

      <!-- Description + Qty -->
      <div class="grid grid-cols-2 gap-2">
        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">Item</label>
          <input
            :value="row.description"
            @input="updateRow(idx, 'description', $event.target.value)"
            type="text"
            placeholder="e.g. 15W-40 Engine Oil"
            class="input-field"
          />
        </div>
        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">
            Quantity{{ row.unit ? ` (${row.unit})` : '' }}
          </label>
          <div class="flex gap-2">
            <input
              :value="row.qty"
              @input="updateRow(idx, 'qty', Number($event.target.value) || 0)"
              type="number"
              min="0"
              step="0.1"
              class="input-field flex-1"
            />
            <select
              :value="row.unit"
              @change="updateRow(idx, 'unit', $event.target.value)"
              class="input-field w-20"
            >
              <option v-for="u in units" :key="u" :value="u">{{ u }}</option>
            </select>
          </div>
        </div>
      </div>

      <!-- Evidence photos — labels shift with Action -->
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label class="block text-xs text-gray-500 mb-1">{{ prePhotoLabel(row) }}</label>
          <input
            type="file"
            accept="image/*"
            @change="onPhoto(idx, 'pre_photo', $event)"
            class="text-xs"
          />
          <div v-if="row.pre_photo" class="mt-1 text-xs text-green-600">&#10003; Attached</div>
        </div>
        <div>
          <label class="block text-xs text-gray-500 mb-1">{{ postPhotoLabel(row) }}</label>
          <input
            type="file"
            accept="image/*"
            @change="onPhoto(idx, 'post_photo', $event)"
            class="text-xs"
          />
          <div v-if="row.post_photo" class="mt-1 text-xs text-green-600">&#10003; Attached</div>
        </div>
      </div>
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
});

const emit = defineEmits(["update:modelValue"]);

const maintenanceTypes = ["Oil/Lubricant", "Coolant", "Grease", "Filter", "Consumable"];
const units = ["Litres", "Kg", "Pcs", "Set"];

const rows = computed(() => props.modelValue || []);

function availableActions(row) {
  if (row.maintenance_type === "Filter") return ["Top-up", "Cleaning", "Replacement"];
  return ["Top-up", "Replacement"];
}

function prePhotoLabel(row) {
  if (row.action === "Cleaning") return "Dirty (before cleaning)";
  if (row.action === "Replacement") {
    return row.maintenance_type === "Filter" ? "Old filter" : "Drained fluid";
  }
  return "Before"; // Top-up
}

function postPhotoLabel(row) {
  if (row.action === "Cleaning") return "Cleaned";
  if (row.action === "Replacement") {
    return row.maintenance_type === "Filter" ? "New filter installed" : "New fluid + volume";
  }
  return "After top-up";
}

function emitChange(next) {
  emit("update:modelValue", next);
}

function addRow() {
  emitChange([
    ...rows.value,
    {
      maintenance_type: "",
      action: "Replacement",
      description: "",
      qty: 1,
      unit: "Litres",
      pre_photo: null,
      post_photo: null,
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
