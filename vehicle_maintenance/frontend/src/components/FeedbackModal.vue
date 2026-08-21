<!--
  Post-closure feedback.

  Opened from a closed job card, or from the deep link in the feedback-request
  notification. Any existing answer is loaded first so a customer revising their
  rating sees what they said last time rather than a blank form.
-->
<template>
	<NDialog
		:model-value="open"
		title="How did we do?"
		subtitle="Two questions, and anything else you want to tell us."
		@update:model-value="$emit('update:open', $event)"
	>
		<div class="space-y-5">
			<div class="space-y-1.5">
				<p class="text-label text-ink">Service rating</p>
				<NRating v-model="form.rating" label="Service rating" />
			</div>

			<div class="space-y-1.5">
				<p class="text-label text-ink">How likely are you to recommend us?</p>
				<div class="flex gap-1">
					<button
						v-for="n in NPS_RANGE"
						:key="n"
						type="button"
						role="radio"
						:aria-checked="form.nps_score === n"
						:aria-label="`${n} out of 10`"
						class="tabular h-8 flex-1 rounded-sm border text-label-sm transition-colors duration-instant focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent"
						:class="
							form.nps_score === n
								? `${semanticClasses(npsSemantic(n)).border} ${
										semanticClasses(npsSemantic(n)).tint
								  } ${semanticClasses(npsSemantic(n)).text}`
								: 'border-hairline text-muted hover:bg-sunken'
						"
						@click="form.nps_score = n"
					>
						{{ n }}
					</button>
				</div>
				<p class="flex justify-between text-caption text-muted">
					<span>Not at all</span>
					<span>Definitely</span>
				</p>
			</div>

			<NChoice
				v-model="form.would_recommend"
				label="Would you recommend us?"
				:options="['Yes', 'Maybe', 'No']"
				:columns="3"
			/>

			<NTextarea
				v-model="form.comments"
				label="Anything else?"
				:rows="3"
				placeholder="What went well, what could be better."
			/>

			<NAlert v-if="error" semantic="critical" :body="error" />
		</div>

		<template #actions>
			<NButton @click="close">Cancel</NButton>
			<NButton variant="primary" :loading="submitting" :disabled="!form.rating" @click="submit"
				>Send feedback</NButton
			>
		</template>
	</NDialog>
</template>

<script setup>
import { reactive, ref, watch } from "vue";
import { call } from "frappe-ui";
import {
	NDialog,
	NRating,
	NChoice,
	NTextarea,
	NButton,
	NAlert,
	semanticClasses,
	toast,
} from "../ui/index.js";

const props = defineProps({
	jobCardName: { type: String, required: true },
	open: { type: Boolean, default: false },
});
const emit = defineEmits(["update:open", "submitted"]);

const NPS_RANGE = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

const form = reactive({ rating: 0, nps_score: null, would_recommend: "Yes", comments: "" });
const submitting = ref(false);
const error = ref("");

/* The standard NPS bands, on the shared ramp: 0–6 detractor, 7–8 passive,
   9–10 promoter. */
function npsSemantic(n) {
	if (n <= 6) return "critical";
	if (n <= 8) return "caution";
	return "positive";
}

function close() {
	emit("update:open", false);
	error.value = "";
}

async function loadExisting() {
	try {
		const res = await call("vehicle_maintenance.api.job_card.get_customer_feedback", {
			job_card_name: props.jobCardName,
		});
		if (res?.data) {
			Object.assign(form, {
				rating: res.data.rating || 0,
				nps_score: res.data.nps_score ?? null,
				would_recommend: res.data.would_recommend || "Yes",
				comments: res.data.comments || "",
			});
		}
	} catch {
		// No previous feedback, or it is not readable — the blank form is correct.
	}
}

async function submit() {
	submitting.value = true;
	error.value = "";
	try {
		await call("vehicle_maintenance.api.job_card.submit_customer_feedback", {
			job_card_name: props.jobCardName,
			rating: form.rating,
			nps_score: form.nps_score,
			comments: form.comments,
			would_recommend: form.would_recommend,
		});
		emit("submitted");
		close();
		toast.success("Thanks — your feedback has been recorded.");
	} catch (e) {
		error.value = e?.messages?.[0] || "Could not send your feedback. Please try again.";
	} finally {
		submitting.value = false;
	}
}

watch(
	() => props.open,
	(val) => val && loadExisting()
);
</script>
