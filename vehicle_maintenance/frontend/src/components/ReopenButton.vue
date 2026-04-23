<template>
  <!--
    ReopenButton — PRD p.3: Customer & Central Ops can reopen a Closed card.
    Takes a free-text reason; the backend records it in the audit log.
  -->
  <div>
    <button
      type="button"
      :disabled="disabled"
      @click="open = true"
      class="px-3 py-2 text-sm font-medium text-amber-700 border border-amber-300 hover:bg-amber-50 rounded-lg disabled:opacity-50 disabled:cursor-not-allowed"
    >
      Reopen Job Card
    </button>

    <div
      v-if="open"
      class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
      @click.self="close"
    >
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6 space-y-4">
        <h3 class="text-lg font-semibold text-gray-900">Reopen Job Card</h3>
        <p class="text-sm text-gray-600">
          The Service Engineer will be notified and the card will move back into the work queue.
        </p>

        <div>
          <label class="block text-xs font-medium text-gray-500 mb-1">
            Reason <span class="text-red-500">*</span>
          </label>
          <textarea
            v-model="reason"
            rows="3"
            placeholder="Why are you reopening? The issue wasn't fixed, something new came up, etc."
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
            :disabled="submitting || !reason.trim()"
            @click="submit"
            class="px-4 py-2 text-sm font-medium text-white bg-amber-600 hover:bg-amber-700 rounded-lg disabled:opacity-50"
          >
            {{ submitting ? "Reopening…" : "Confirm Reopen" }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from "vue";
import { call } from "frappe-ui";

const props = defineProps({
  jobCardName: { type: String, required: true },
  disabled: { type: Boolean, default: false },
});
const emit = defineEmits(["reopened"]);

const open = ref(false);
const reason = ref("");
const submitting = ref(false);
const error = ref("");

function close() {
  open.value = false;
  reason.value = "";
  error.value = "";
}

async function submit() {
  submitting.value = true;
  error.value = "";
  try {
    await call("vehicle_maintenance.api.job_card.reopen_job_card", {
      job_card_name: props.jobCardName,
      reason: reason.value,
    });
    emit("reopened");
    close();
  } catch (e) {
    error.value = e?.messages?.[0] || "Failed to reopen.";
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.input-field {
  @apply w-full px-3 py-2 border border-gray-300 rounded-lg text-sm
         focus:ring-2 focus:ring-brand-500 focus:border-brand-500;
}
</style>
