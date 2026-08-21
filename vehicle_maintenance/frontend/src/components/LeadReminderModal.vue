<!--
  Schedule a reminder against a lead.

  The message body disappears when a template is chosen, because the two are
  alternatives and showing both invites someone to fill in a body that will
  never be sent.
-->
<template>
	<NDialog
		:model-value="modelValue"
		title="Schedule reminder"
		subtitle="An email goes out at the time you pick."
		persistent
		@update:model-value="$emit('update:modelValue', $event)"
	>
		<div class="space-y-4">
			<NInput v-model="form.reminder_datetime" label="When" type="datetime-local" required />

			<NSelect
				v-model="form.message_template"
				label="Email template"
				placeholder="Write a custom message"
				:options="templateOptions"
				:hint="
					selectedTemplate
						? `Subject: ${selectedTemplate.subject}`
						: 'Leave unset to write your own.'
				"
			/>

			<NInput v-model="form.subject" label="Subject" required />

			<NTextarea
				v-if="!form.message_template"
				v-model="form.message_body"
				label="Message"
				:rows="4"
				required
			/>

			<NInput
				v-model="form.recipient_email"
				label="Send to"
				type="email"
				:placeholder="defaultRecipient || 'lead@example.com'"
				hint="Leave blank to use the lead's own email address."
			/>

			<NAlert v-if="errorMessage" semantic="critical" :body="errorMessage" />
		</div>

		<template #actions>
			<NButton @click="close">Cancel</NButton>
			<NButton variant="primary" :loading="submitting" @click="submit">Schedule</NButton>
		</template>
	</NDialog>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useLeads } from "../composables/useLeads.js";
import { useCrmDropdowns } from "../composables/useCrmDropdowns.js";
import { NDialog, NInput, NSelect, NTextarea, NButton, NAlert, toast } from "../ui/index.js";

const props = defineProps({
	modelValue: { type: Boolean, default: false },
	lead: { type: String, required: true },
	defaultRecipient: { type: String, default: "" },
});
const emit = defineEmits(["update:modelValue", "scheduled"]);

const leads = useLeads();
const { dropdowns, load } = useCrmDropdowns();

const form = reactive({
	reminder_datetime: "",
	message_template: "",
	subject: "Follow-up",
	message_body: "",
	recipient_email: "",
});
const submitting = ref(false);
const errorMessage = ref("");

const templates = computed(() => dropdowns.value?.email_templates || dropdowns.email_templates || []);
const templateOptions = computed(() => templates.value.map((t) => ({ value: t.name, label: t.name })));
const selectedTemplate = computed(() => templates.value.find((t) => t.name === form.message_template));

watch(selectedTemplate, (tpl) => {
	if (tpl?.subject) form.subject = tpl.subject;
});

watch(
	() => props.modelValue,
	(open) => {
		if (!open) return;
		errorMessage.value = "";
		Object.assign(form, {
			reminder_datetime: "",
			message_template: "",
			subject: "Follow-up",
			message_body: "",
			recipient_email: "",
		});
	}
);

function close() {
	emit("update:modelValue", false);
}

async function submit() {
	errorMessage.value = "";
	if (!form.reminder_datetime) {
		errorMessage.value = "Pick a date and time.";
		return;
	}
	if (!form.message_template && !form.message_body.trim()) {
		errorMessage.value = "Write a message, or pick a template.";
		return;
	}
	submitting.value = true;
	try {
		await leads.scheduleReminder({
			lead: props.lead,
			reminder_datetime: new Date(form.reminder_datetime).toISOString(),
			subject: form.subject,
			message_template: form.message_template || null,
			message_body: form.message_template ? null : form.message_body,
			recipient_email: form.recipient_email || null,
			channel: "Email",
		});
		emit("scheduled");
		close();
		toast.success("Reminder scheduled.");
	} catch (e) {
		errorMessage.value = e?.message || "Please try again.";
	} finally {
		submitting.value = false;
	}
}

onMounted(() => load());
</script>
