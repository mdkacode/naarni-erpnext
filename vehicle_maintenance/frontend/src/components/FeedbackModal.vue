<template>
  <!--
    FeedbackModal — PRD p.2 post-closure feedback.

    Opened either:
      • on-demand via the "Share Feedback" button in a closed Job Card, or
      • via the deep-link in the auto-sent feedback-request notification.

    Pre-loads any existing feedback via get_customer_feedback — customers can
    revise their rating/comments before a hard cutoff (enforced elsewhere).
  -->
  <div
    v-if="open"
    class="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
    @click.self="close"
  >
    <div class="bg-white rounded-xl shadow-xl w-full max-w-md p-6 space-y-4">
      <div class="flex items-start justify-between">
        <div>
          <h3 class="text-lg font-semibold text-gray-900">How did we do?</h3>
          <p class="text-sm text-gray-500">Your feedback helps us improve.</p>
        </div>
        <button type="button" @click="close" class="text-gray-400 hover:text-gray-600 text-xl leading-none">&times;</button>
      </div>

      <!-- Star rating 1-5 -->
      <div>
        <label class="block text-xs font-medium text-gray-500 mb-1">Service Rating</label>
        <div class="flex gap-1">
          <button
            v-for="n in 5"
            :key="n"
            type="button"
            @click="form.rating = n"
            class="w-10 h-10 text-2xl"
            :class="n <= form.rating ? 'text-amber-400' : 'text-gray-300 hover:text-amber-200'"
            aria-label="`Rate ${n} star`"
          >
            &#9733;
          </button>
        </div>
      </div>

      <!-- NPS 0-10 -->
      <div>
        <label class="block text-xs font-medium text-gray-500 mb-1">
          How likely to recommend us? <span class="text-gray-400">(0–10)</span>
        </label>
        <div class="flex gap-1">
          <button
            v-for="n in npsRange"
            :key="n"
            type="button"
            @click="form.nps_score = n"
            class="flex-1 py-1.5 text-xs font-medium rounded border transition-colors"
            :class="
              form.nps_score === n
                ? npsClass(n)
                : 'border-gray-200 text-gray-500 hover:border-gray-300'
            "
          >
            {{ n }}
          </button>
        </div>
      </div>

      <!-- Would recommend -->
      <div>
        <label class="block text-xs font-medium text-gray-500 mb-1">Would recommend?</label>
        <div class="flex gap-2">
          <button
            v-for="v in ['Yes', 'Maybe', 'No']"
            :key="v"
            type="button"
            @click="form.would_recommend = v"
            class="flex-1 py-1.5 text-xs font-medium rounded-lg border-2"
            :class="
              form.would_recommend === v
                ? recommendClass(v)
                : 'border-gray-200 text-gray-500 hover:border-gray-300'
            "
          >
            {{ v }}
          </button>
        </div>
      </div>

      <!-- Comments -->
      <div>
        <label class="block text-xs font-medium text-gray-500 mb-1">Comments</label>
        <textarea
          v-model="form.comments"
          rows="3"
          placeholder="What went well, what could be better…"
          class="input-field"
        />
      </div>

      <div v-if="error" class="text-sm text-red-600">{{ error }}</div>
      <div v-if="successMsg" class="text-sm text-green-600">{{ successMsg }}</div>

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
          :disabled="submitting || !form.rating"
          @click="submit"
          class="px-4 py-2 text-sm font-medium text-white bg-brand-600 hover:bg-brand-700 rounded-lg disabled:opacity-50"
        >
          {{ submitting ? "Submitting…" : "Submit Feedback" }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from "vue";
import { call } from "frappe-ui";

const props = defineProps({
  jobCardName: { type: String, required: true },
  open: { type: Boolean, default: false },
});
const emit = defineEmits(["update:open", "submitted"]);

const npsRange = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

const form = ref({
  rating: 0,
  nps_score: null,
  would_recommend: "Yes",
  comments: "",
});
const submitting = ref(false);
const error = ref("");
const successMsg = ref("");

function close() {
  emit("update:open", false);
  successMsg.value = "";
  error.value = "";
}

async function loadExisting() {
  try {
    const res = await call("vehicle_maintenance.api.job_card.get_customer_feedback", {
      job_card_name: props.jobCardName,
    });
    if (res?.data) {
      form.value = {
        rating: res.data.rating || 0,
        nps_score: res.data.nps_score ?? null,
        would_recommend: res.data.would_recommend || "Yes",
        comments: res.data.comments || "",
      };
    }
  } catch {
    // Silently fall back to the blank form
  }
}

async function submit() {
  submitting.value = true;
  error.value = "";
  successMsg.value = "";
  try {
    await call("vehicle_maintenance.api.job_card.submit_customer_feedback", {
      job_card_name: props.jobCardName,
      rating: form.value.rating,
      nps_score: form.value.nps_score,
      comments: form.value.comments,
      would_recommend: form.value.would_recommend,
    });
    successMsg.value = "Thanks for your feedback!";
    emit("submitted");
    setTimeout(close, 1000);
  } catch (e) {
    error.value = e?.messages?.[0] || "Failed to submit feedback.";
  } finally {
    submitting.value = false;
  }
}

function npsClass(n) {
  if (n <= 6) return "border-red-500 bg-red-50 text-red-700";
  if (n <= 8) return "border-amber-500 bg-amber-50 text-amber-700";
  return "border-green-500 bg-green-50 text-green-700";
}

function recommendClass(v) {
  const m = {
    Yes: "border-green-500 bg-green-50 text-green-700",
    Maybe: "border-amber-500 bg-amber-50 text-amber-700",
    No: "border-red-500 bg-red-50 text-red-700",
  };
  return m[v];
}

watch(
  () => props.open,
  (val) => {
    if (val) loadExisting();
  },
);
</script>

<style scoped>
.input-field {
  @apply w-full px-3 py-2 border border-gray-300 rounded-lg text-sm
         focus:ring-2 focus:ring-brand-500 focus:border-brand-500
         text-gray-900 placeholder-gray-400;
}
</style>
