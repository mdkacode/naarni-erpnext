<template>
	<div
		v-if="modelValue"
		class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
		@click.self="close"
	>
		<div class="bg-white rounded-2xl shadow-xl w-full max-w-md p-6">
			<div class="flex items-center justify-between mb-4">
				<h2 class="text-lg font-semibold text-gray-900">Schedule reminder</h2>
				<button class="text-gray-400 hover:text-gray-600" @click="close">✕</button>
			</div>

			<div class="space-y-3">
				<div>
					<label class="label">When</label>
					<input v-model="form.reminder_datetime" type="datetime-local" class="input-field" />
				</div>

				<div>
					<label class="label">Email Template</label>
					<select v-model="form.message_template" class="select-field">
						<option value="">— None (write a custom message) —</option>
						<option v-for="t in dropdowns.email_templates" :key="t.name" :value="t.name">
							{{ t.name }}
						</option>
					</select>
					<p v-if="selectedTemplate" class="text-xs text-gray-500 mt-1">
						Subject: {{ selectedTemplate.subject }}
					</p>
				</div>

				<div>
					<label class="label">Subject</label>
					<input v-model="form.subject" type="text" class="input-field" />
				</div>

				<div v-if="!form.message_template">
					<label class="label">Message</label>
					<textarea v-model="form.message_body" rows="4" class="input-field" />
				</div>

				<div>
					<label class="label">Recipient email</label>
					<input
						v-model="form.recipient_email"
						type="email"
						class="input-field"
						:placeholder="defaultRecipient || 'lead@example.com'"
					/>
					<p class="text-xs text-gray-400 mt-1">
						Defaults to the lead's email on dispatch if left blank.
					</p>
				</div>
			</div>

			<div
				v-if="errorMessage"
				class="mt-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700"
			>
				{{ errorMessage }}
			</div>

			<div class="mt-6 flex justify-end gap-2">
				<button
					class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50"
					@click="close"
				>
					Cancel
				</button>
				<button
					class="px-4 py-2 text-sm font-medium text-white bg-brand-600 rounded-lg hover:bg-brand-700 disabled:opacity-50"
					:disabled="submitting"
					@click="submit"
				>
					{{ submitting ? "Scheduling…" : "Schedule" }}
				</button>
			</div>
		</div>
	</div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from "vue";
import { useLeads } from "../composables/useLeads.js";
import { useCrmDropdowns } from "../composables/useCrmDropdowns.js";

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

const selectedTemplate = computed(() =>
	dropdowns.value.email_templates.find((t) => t.name === form.message_template)
);

watch(selectedTemplate, (tpl) => {
	if (tpl?.subject) form.subject = tpl.subject;
});

watch(
	() => props.modelValue,
	(open) => {
		if (open) {
			errorMessage.value = "";
			form.reminder_datetime = "";
			form.message_template = "";
			form.subject = "Follow-up";
			form.message_body = "";
			form.recipient_email = "";
		}
	}
);

function close() {
	emit("update:modelValue", false);
}

async function submit() {
	errorMessage.value = "";
	if (!form.reminder_datetime) {
		errorMessage.value = "Pick a date & time.";
		return;
	}
	if (!form.message_template && !form.message_body.trim()) {
		errorMessage.value = "Provide a message or pick a template.";
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
	} catch (e) {
		errorMessage.value = e?.message || "Failed to schedule reminder.";
	} finally {
		submitting.value = false;
	}
}

onMounted(() => load());
</script>

<style scoped>
.label {
	@apply block text-sm font-medium text-gray-700 mb-1;
}
.input-field {
	@apply w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:border-brand-500 focus:ring-1 focus:ring-brand-500 outline-none;
}
.select-field {
	@apply w-full px-3 py-2 text-sm border border-gray-200 rounded-lg bg-white focus:border-brand-500 focus:ring-1 focus:ring-brand-500 outline-none;
}
</style>
