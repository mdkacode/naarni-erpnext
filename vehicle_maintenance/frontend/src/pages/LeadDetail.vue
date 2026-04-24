<template>
	<div class="max-w-5xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
		<div class="mb-4">
			<router-link to="/service-portal/crm/leads" class="text-sm text-brand-600 hover:underline"
				>&larr; All leads</router-link
			>
		</div>

		<div v-if="loading" class="text-center py-12 text-gray-400">Loading…</div>

		<div v-else-if="lead" class="space-y-6">
			<!-- Header card -->
			<div class="bg-white border border-gray-200 rounded-xl p-5">
				<div class="flex items-start justify-between gap-4">
					<div>
						<h1 class="text-xl font-bold text-gray-900">{{ lead.lead_name }}</h1>
						<p class="text-sm text-gray-500 mt-0.5">
							{{ lead.company_name || "Individual lead" }}
						</p>
						<div class="flex flex-wrap gap-x-6 gap-y-1 mt-3 text-sm text-gray-700">
							<span>📞 {{ lead.phone }}</span>
							<span v-if="lead.email">✉️ {{ lead.email }}</span>
							<span v-if="lead.city || lead.state">
								📍 {{ [lead.city, lead.state].filter(Boolean).join(", ") }}
							</span>
							<span v-if="lead.estimated_value">
								💰 {{ formatCurrency(lead.estimated_value) }}
							</span>
						</div>
					</div>
					<div class="flex flex-col items-end gap-2">
						<div class="flex items-center gap-2">
							<label class="text-xs text-gray-500">Status</label>
							<select
								:value="lead.status"
								@change="onStatusChange($event.target.value)"
								class="select-field w-44"
								:disabled="changingStatus"
							>
								<option v-for="s in dropdowns.statuses" :key="s.name" :value="s.name">
									{{ s.status_name }}
								</option>
							</select>
						</div>
						<span
							v-if="lead.converted_to_customer"
							class="text-xs text-green-700 bg-green-50 px-2 py-1 rounded"
						>
							Converted → {{ lead.converted_to_customer }}
						</span>
					</div>
				</div>
			</div>

			<!-- Tabs -->
			<div class="border-b border-gray-200">
				<nav class="flex gap-6">
					<button
						v-for="tab in tabs"
						:key="tab.id"
						class="pb-2 text-sm font-medium border-b-2 transition"
						:class="
							activeTab === tab.id
								? 'border-brand-600 text-brand-700'
								: 'border-transparent text-gray-500 hover:text-gray-700'
						"
						@click="activeTab = tab.id"
					>
						{{ tab.label }}
						<span v-if="tab.count != null" class="ml-1 text-xs text-gray-400">{{
							tab.count
						}}</span>
					</button>
				</nav>
			</div>

			<!-- Overview -->
			<div v-if="activeTab === 'overview'" class="grid grid-cols-1 md:grid-cols-2 gap-4">
				<InfoCard label="Source" :value="lead.lead_source" />
				<InfoCard label="Industry" :value="lead.industry" />
				<InfoCard label="Interested In" :value="lead.interested_in" />
				<InfoCard label="Fleet Size" :value="lead.fleet_size_bucket" />
				<InfoCard label="Priority" :value="lead.priority" />
				<InfoCard label="Assigned To" :value="lead.assigned_to" />
				<InfoCard label="Depot" :value="lead.depot" />
				<InfoCard label="Expected Close" :value="lead.expected_close_date" />
				<div class="md:col-span-2 bg-white border border-gray-200 rounded-xl p-4">
					<div class="text-xs text-gray-500 mb-1">Notes</div>
					<div class="text-sm text-gray-800 whitespace-pre-wrap">
						{{ lead.notes || "—" }}
					</div>
				</div>
			</div>

			<!-- Activities -->
			<div v-if="activeTab === 'activities'" class="space-y-4">
				<div class="bg-white border border-gray-200 rounded-xl p-4">
					<h3 class="text-sm font-semibold text-gray-800 mb-3">Log an activity</h3>
					<div class="grid grid-cols-1 md:grid-cols-4 gap-2">
						<select v-model="newActivity.activity_type" class="select-field">
							<option value="">Type</option>
							<option>Call</option>
							<option>Visit</option>
							<option>Demo</option>
							<option>Quote Sent</option>
							<option>Email</option>
							<option>WhatsApp</option>
							<option>Other</option>
						</select>
						<select v-model="newActivity.outcome" class="select-field">
							<option value="">Outcome</option>
							<option>Interested</option>
							<option>Not Interested</option>
							<option>Callback Requested</option>
							<option>Meeting Scheduled</option>
							<option>No Response</option>
						</select>
						<select v-model="newActivity.next_action" class="select-field">
							<option value="">Next action</option>
							<option>Call Back</option>
							<option>Send Quote</option>
							<option>Schedule Demo</option>
							<option>Close as Lost</option>
							<option>None</option>
						</select>
						<button
							class="px-3 py-2 text-sm text-white bg-brand-600 rounded-lg hover:bg-brand-700 disabled:opacity-50"
							:disabled="!newActivity.activity_type || addingActivity"
							@click="submitActivity"
						>
							{{ addingActivity ? "…" : "Add" }}
						</button>
					</div>
					<textarea
						v-model="newActivity.summary"
						rows="2"
						class="input-field mt-2"
						placeholder="Short note (optional)"
					/>
				</div>

				<div class="bg-white border border-gray-200 rounded-xl divide-y divide-gray-100">
					<div v-if="!(lead.activities || []).length" class="p-6 text-center text-sm text-gray-400">
						No activities yet.
					</div>
					<div v-for="a in sortedActivities" :key="a.name" class="p-4 flex items-start gap-3">
						<span class="text-lg">{{ activityIcon(a.activity_type) }}</span>
						<div class="flex-1">
							<div class="text-sm font-medium text-gray-900">
								{{ a.activity_type }}
								<span v-if="a.outcome" class="ml-2 text-xs font-normal text-gray-500"
									>· {{ a.outcome }}</span
								>
							</div>
							<div v-if="a.summary" class="text-sm text-gray-700 mt-0.5 whitespace-pre-wrap">
								{{ a.summary }}
							</div>
							<div class="text-xs text-gray-400 mt-1">
								{{ formatDateTime(a.activity_date) }}
								<span v-if="a.performed_by"> · {{ a.performed_by }}</span>
								<span v-if="a.next_action && a.next_action !== 'None'">
									· next: {{ a.next_action }}
								</span>
							</div>
						</div>
					</div>
				</div>
			</div>

			<!-- Reminders -->
			<div v-if="activeTab === 'reminders'" class="space-y-4">
				<div class="flex justify-end">
					<button
						class="px-3 py-2 text-sm text-white bg-brand-600 rounded-lg hover:bg-brand-700"
						@click="showReminderModal = true"
					>
						+ Schedule reminder
					</button>
				</div>
				<div class="bg-white border border-gray-200 rounded-xl divide-y divide-gray-100">
					<div v-if="!reminders.length" class="p-6 text-center text-sm text-gray-400">
						No reminders scheduled.
					</div>
					<div
						v-for="r in reminders"
						:key="r.name"
						class="p-4 flex items-start justify-between gap-3"
					>
						<div>
							<div class="text-sm font-medium text-gray-900">
								{{ r.subject || "(no subject)" }}
							</div>
							<div class="text-xs text-gray-500 mt-0.5">
								{{ formatDateTime(r.reminder_datetime) }} · {{ r.channel }}
								<span v-if="r.recipient_email"> · {{ r.recipient_email }}</span>
							</div>
							<div v-if="r.failure_reason" class="text-xs text-red-600 mt-1">
								{{ r.failure_reason }}
							</div>
						</div>
						<div class="flex items-center gap-2">
							<span :class="reminderBadge(r.status)">{{ r.status }}</span>
							<button
								v-if="r.status === 'Scheduled'"
								class="text-xs text-red-600 hover:underline"
								@click="cancelReminder(r.name)"
							>
								Cancel
							</button>
						</div>
					</div>
				</div>
			</div>

			<!-- Attachments -->
			<div v-if="activeTab === 'attachments'" class="space-y-4">
				<div class="bg-white border border-gray-200 rounded-xl p-4">
					<label class="label">Upload file (image or PDF, max 5 MB)</label>
					<input
						type="file"
						accept="image/jpeg,image/png,image/webp,application/pdf"
						@change="onFilePick"
						:disabled="uploading"
						class="block text-sm"
					/>
					<input
						v-model="newCaption"
						type="text"
						class="input-field mt-2"
						placeholder="Caption (optional)"
					/>
					<p v-if="uploadError" class="text-sm text-red-600 mt-2">{{ uploadError }}</p>
				</div>
				<div class="grid grid-cols-2 md:grid-cols-3 gap-3">
					<div
						v-for="(att, idx) in lead.attachments || []"
						:key="idx"
						class="bg-white border border-gray-200 rounded-xl overflow-hidden"
					>
						<a :href="att.file_url" target="_blank" class="block">
							<img
								v-if="isImage(att.file_url)"
								:src="att.file_url"
								:alt="att.caption || 'attachment'"
								class="w-full h-40 object-cover"
							/>
							<div
								v-else
								class="w-full h-40 flex items-center justify-center bg-gray-50 text-gray-400"
							>
								📄 {{ fileName(att.file_url) }}
							</div>
						</a>
						<div class="p-2 text-xs text-gray-600 truncate">
							{{ att.caption || fileName(att.file_url) }}
						</div>
					</div>
					<div
						v-if="!(lead.attachments || []).length"
						class="col-span-full text-center text-sm text-gray-400 py-6"
					>
						No attachments yet.
					</div>
				</div>
			</div>
		</div>

		<LeadReminderModal
			v-if="lead"
			v-model="showReminderModal"
			:lead="lead.name"
			:default-recipient="lead.email"
			@scheduled="reload"
		/>
	</div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from "vue";
import { useRoute } from "vue-router";
import InfoCard from "../components/InfoCard.vue";
import LeadReminderModal from "../components/LeadReminderModal.vue";
import { useLeads } from "../composables/useLeads.js";
import { useCrmDropdowns } from "../composables/useCrmDropdowns.js";
import { call } from "frappe-ui";

const route = useRoute();
const leads = useLeads();
const { dropdowns, load: loadDropdowns } = useCrmDropdowns();

const lead = ref(null);
const reminders = ref([]);
const loading = ref(true);
const activeTab = ref("overview");
const changingStatus = ref(false);

const newActivity = reactive({
	activity_type: "",
	outcome: "",
	next_action: "",
	summary: "",
});
const addingActivity = ref(false);

const showReminderModal = ref(false);

const newCaption = ref("");
const uploading = ref(false);
const uploadError = ref("");

const tabs = computed(() => [
	{ id: "overview", label: "Overview" },
	{ id: "activities", label: "Activities", count: (lead.value?.activities || []).length },
	{ id: "reminders", label: "Reminders", count: reminders.value.length },
	{ id: "attachments", label: "Attachments", count: (lead.value?.attachments || []).length },
]);

const sortedActivities = computed(() =>
	[...(lead.value?.activities || [])].sort((a, b) => new Date(b.activity_date) - new Date(a.activity_date))
);

async function reload() {
	loading.value = true;
	try {
		const res = await leads.get(route.params.name);
		lead.value = res.lead;
		reminders.value = res.reminders || [];
	} finally {
		loading.value = false;
	}
}

async function onStatusChange(newStatus) {
	if (!newStatus || newStatus === lead.value.status) return;
	changingStatus.value = true;
	try {
		await leads.updateStatus(lead.value.name, newStatus);
		await reload();
	} finally {
		changingStatus.value = false;
	}
}

async function submitActivity() {
	if (!newActivity.activity_type) return;
	addingActivity.value = true;
	try {
		await leads.addActivity(lead.value.name, { ...newActivity });
		Object.assign(newActivity, {
			activity_type: "",
			outcome: "",
			next_action: "",
			summary: "",
		});
		await reload();
	} finally {
		addingActivity.value = false;
	}
}

async function cancelReminder(name) {
	await leads.cancelReminder(name);
	await reload();
}

async function onFilePick(event) {
	const file = event.target.files?.[0];
	if (!file) return;
	uploadError.value = "";
	if (file.size > 5 * 1024 * 1024) {
		uploadError.value = "File exceeds 5 MB.";
		event.target.value = "";
		return;
	}
	uploading.value = true;
	try {
		const form = new FormData();
		form.append("file", file);
		form.append("is_private", "1");
		form.append("doctype", "Lead");
		form.append("docname", lead.value.name);

		const headers = {};
		if (window.csrf_token) headers["X-Frappe-CSRF-Token"] = window.csrf_token;

		const resp = await fetch("/api/method/upload_file", {
			method: "POST",
			credentials: "include",
			headers,
			body: form,
		});
		if (!resp.ok) throw new Error(`Upload failed (${resp.status})`);
		const body = await resp.json();
		const fileUrl = body.message?.file_url || body.file_url;
		if (!fileUrl) throw new Error("No file_url in upload response.");

		await leads.uploadAttachment(lead.value.name, fileUrl, newCaption.value);
		newCaption.value = "";
		event.target.value = "";
		await reload();
	} catch (e) {
		uploadError.value = e?.message || "Upload failed.";
	} finally {
		uploading.value = false;
	}
}

function reminderBadge(status) {
	const base = "inline-flex px-2 py-0.5 rounded-full text-xs font-medium";
	if (status === "Scheduled") return `${base} bg-blue-50 text-blue-700`;
	if (status === "Sent") return `${base} bg-green-50 text-green-700`;
	if (status === "Failed") return `${base} bg-red-50 text-red-700`;
	return `${base} bg-gray-100 text-gray-600`;
}

function activityIcon(t) {
	return (
		{
			Call: "📞",
			Visit: "🚗",
			Demo: "💻",
			"Quote Sent": "📄",
			Email: "✉️",
			WhatsApp: "💬",
		}[t] || "📝"
	);
}

function isImage(url) {
	return /\.(jpe?g|png|webp|gif)$/i.test(url || "");
}

function fileName(url) {
	return (url || "").split("/").pop();
}

function formatCurrency(v) {
	return new Intl.NumberFormat("en-IN", {
		style: "currency",
		currency: "INR",
		maximumFractionDigits: 0,
	}).format(v);
}

function formatDateTime(s) {
	if (!s) return "";
	return new Date(s).toLocaleString("en-IN", {
		day: "2-digit",
		month: "short",
		year: "2-digit",
		hour: "2-digit",
		minute: "2-digit",
	});
}

onMounted(async () => {
	await loadDropdowns();
	await reload();
});
</script>

<style scoped>
.label {
	@apply block text-sm font-medium text-gray-700 mb-1;
}
.input-field {
	@apply w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:border-brand-500 focus:ring-1 focus:ring-brand-500 outline-none;
}
.select-field {
	@apply px-3 py-2 text-sm border border-gray-200 rounded-lg bg-white focus:border-brand-500 focus:ring-1 focus:ring-brand-500 outline-none;
}
</style>
