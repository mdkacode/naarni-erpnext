<template>
  <!--
    ForceCloseButton — PRD p.6 Force-Close Severity Matrix.

    Severity → Authority:
      Minor    → SE / DM / Aftersales Eng / N. Maintenance Head
      Major    → DM / Aftersales Eng / N. Maintenance Head (DM approval mandatory)
      Critical → N. Maintenance Head only

    Reason is mandatory. The backend re-validates severity vs. the logged-in
    user's roles — this button is a UX hint, not the enforcement layer.
  -->
  <div>
    <button
      type="button"
      :disabled="disabled"
      @click="open = true"
      class="px-3 py-2 text-sm font-medium text-red-700 border border-red-300 hover:bg-red-50 rounded-lg disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
    >
      Force Close
    </button>

    <!-- Modal -->
    <div
      v-if="open"
      class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
      @click.self="close"
    >
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6 space-y-4">
        <h3 class="text-lg font-semibold text-gray-900">Force Close Job Card</h3>
        <p class="text-sm text-gray-600">
          Select the severity that best describes why this job card cannot complete normally.
        </p>

        <div class="space-y-2">
          <button
            v-for="s in severities"
            :key="s.value"
            type="button"
            @click="severity = s.value"
            class="w-full text-left p-3 rounded-lg border-2 transition-all"
            :class="
              severity === s.value
                ? `${s.activeClasses}`
                : 'border-gray-200 hover:border-gray-300'
            "
          >
            <div class="flex items-start gap-2">
              <div class="font-semibold text-sm">{{ s.label }}</div>
              <div class="text-xs text-gray-500 ml-auto">{{ s.authority }}</div>
            </div>
            <div class="text-xs text-gray-600 mt-1">{{ s.description }}</div>
          </button>
        </div>

        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">
            Reason <span class="text-red-500">*</span>
          </label>
          <textarea
            v-model="reason"
            rows="3"
            placeholder="What prevented normal closure? Inventory unavailable, issue resolved on its own, etc."
            class="input-field"
          />
        </div>

        <div v-if="error" class="text-sm text-red-600">{{ error }}</div>

        <div class="flex gap-2 justify-end pt-2">
          <button
            type="button"
            @click="close"
            class="px-4 py-2 text-sm text-gray-700 bg-gray-100 hover:bg-gray-200 rounded-lg"
          >
            Cancel
          </button>
          <button
            type="button"
            @click="submit"
            :disabled="submitting || !canSubmit"
            class="px-4 py-2 text-sm font-medium text-white bg-red-600 hover:bg-red-700 rounded-lg disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ submitting ? "Closing…" : "Confirm Force Close" }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from "vue";

const props = defineProps({
  disabled: { type: Boolean, default: false },
});

const emit = defineEmits(["submit"]);

const open = ref(false);
const severity = ref("");
const reason = ref("");
const submitting = ref(false);
const error = ref("");

const severities = [
  {
    value: "Minor",
    label: "Minor",
    description: "Cosmetic or minor functional issue. Safe to defer to next PMS.",
    authority: "SE can force close",
    activeClasses: "border-amber-500 bg-amber-50",
  },
  {
    value: "Major",
    label: "Major",
    description: "Operational but with a significant issue. Needs follow-up within 24h.",
    authority: "DM or Aftersales approval",
    activeClasses: "border-orange-500 bg-orange-50",
  },
  {
    value: "Critical",
    label: "Critical",
    description: "Vehicle non-operational or safety risk. Auto-creates follow-up in 24h.",
    authority: "N. Maintenance Head only",
    activeClasses: "border-red-500 bg-red-50",
  },
];

const canSubmit = computed(() => severity.value && reason.value.trim().length > 0);

function close() {
  open.value = false;
  severity.value = "";
  reason.value = "";
  error.value = "";
  submitting.value = false;
}

async function submit() {
  if (!canSubmit.value) return;
  submitting.value = true;
  error.value = "";
  try {
    await emit("submit", {
      severity: severity.value,
      reason: reason.value.trim(),
    });
    close();
  } catch (e) {
    error.value = e?.message || "Force close failed. Please try again.";
    submitting.value = false;
  }
}
</script>

<style scoped>
.input-field {
  @apply w-full px-3 py-2 border border-gray-300 rounded-lg text-sm
         focus:ring-2 focus:ring-brand-500 focus:border-brand-500
         text-gray-900 placeholder-gray-400 transition-colors;
}
</style>
