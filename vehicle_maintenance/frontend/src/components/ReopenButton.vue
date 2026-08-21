<!--
  ReopenButton — a customer or central ops can send a closed card back to work.

  Reopening is consequential but not destructive, so it is a secondary button and
  a caution-toned confirm, not a danger one: the reader is asking for more work to
  happen, not for a record to disappear.
-->
<template>
	<div>
		<NButton icon="refresh-cw" :disabled="disabled" @click="open = true">Reopen job card</NButton>

		<NDialog v-model="open" title="Reopen job card" size="sm" persistent @close="reset">
			<div class="space-y-4">
				<p class="text-body-sm text-muted">
					The service engineer will be notified and this card moves back into the work queue.
				</p>

				<NTextarea
					v-model="reason"
					label="Why is it being reopened?"
					required
					:rows="3"
					placeholder="The issue was not fixed, something new came up…"
					hint="Recorded on the card's audit log."
				/>

				<NAlert v-if="error" semantic="critical" :body="error" />
			</div>

			<template #actions>
				<NButton @click="open = false">Cancel</NButton>
				<NButton variant="primary" :loading="submitting" :disabled="!reason.trim()" @click="submit">
					Reopen
				</NButton>
			</template>
		</NDialog>
	</div>
</template>

<script setup>
import { ref } from "vue";
import { call } from "frappe-ui";
import { NButton, NDialog, NTextarea, NAlert, toast } from "../ui/index.js";

const props = defineProps({
	jobCardName: { type: String, required: true },
	disabled: { type: Boolean, default: false },
});
const emit = defineEmits(["reopened"]);

const open = ref(false);
const reason = ref("");
const submitting = ref(false);
const error = ref("");

function reset() {
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
		open.value = false;
		reset();
		toast.success(`${props.jobCardName} reopened.`);
	} catch (e) {
		error.value = e?.messages?.[0] || "Could not reopen this job card.";
	} finally {
		submitting.value = false;
	}
}
</script>
