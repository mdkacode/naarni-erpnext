<!--
  A lead.

  This page carried more emoji than any other in the product — 📞 for the phone,
  💰 for the value, and a per-activity-type emoji in the timeline. Every one of
  them drew differently on Android, Windows and macOS, and none could be tinted
  or aligned to the text beside it. They are icons now.
-->
<template>
	<div>
		<NPageHeader
			:title="lead?.lead_name || 'Lead'"
			:subtitle="subtitle"
			back="/service-portal/crm/leads"
			back-label="All leads"
		>
			<template v-if="lead" #badge>
				<NBadge
					v-if="lead.converted_to_customer"
					semantic="positive"
					:label="`Converted → ${lead.converted_to_customer}`"
				/>
			</template>
			<template v-if="lead" #actions>
				<NPriority :value="lead.priority" />
				<select
					:value="lead.status"
					:disabled="changingStatus"
					aria-label="Lead status"
					class="h-control rounded-sm border border-line bg-sunken px-2 text-body-sm text-ink"
					@change="onStatusChange($event.target.value)"
				>
					<option v-for="s in dropdowns.statuses" :key="s.name" :value="s.name">
						{{ s.status_name }}
					</option>
				</select>
			</template>
			<template v-if="lead" #toolbar>
				<NTabs v-model="activeTab" :tabs="tabs" class="border-0" />
			</template>
		</NPageHeader>

		<div class="p-5">
			<NSkeleton v-if="loading" :count="3" variant="block" height="h-24" :delay="0" />

			<template v-else-if="lead">
				<!-- ── Overview ─────────────────────────────────────────────── -->
				<div v-if="activeTab === 'overview'" class="grid gap-4 lg:grid-cols-2">
					<NCard title="Contact">
						<ul class="space-y-2">
							<li
								v-for="c in contactLines"
								:key="c.label"
								class="flex items-center gap-2 text-body text-ink"
							>
								<NIcon :name="c.icon" :size="15" class="text-muted" />
								<span class="sr-only-ndl">{{ c.label }}:</span>
								{{ c.value }}
							</li>
						</ul>
					</NCard>

					<NCard title="Qualification">
						<NKeyValue :items="qualificationItems" />
					</NCard>

					<NCard title="Notes" class="lg:col-span-2">
						<p class="whitespace-pre-wrap text-body text-ink">{{ fmt.or(lead.notes) }}</p>
					</NCard>
				</div>

				<!-- ── Activities ───────────────────────────────────────────── -->
				<div v-else-if="activeTab === 'activities'" class="space-y-4">
					<NCard title="Log an activity">
						<div class="grid gap-3 md:grid-cols-3">
							<NSelect
								v-model="newActivity.activity_type"
								label="What happened?"
								placeholder="Choose"
								:options="ACTIVITY_TYPES"
								required
							/>
							<NSelect
								v-model="newActivity.outcome"
								label="Outcome"
								placeholder="Choose"
								:options="OUTCOMES"
							/>
							<NSelect
								v-model="newActivity.next_action"
								label="Next action"
								placeholder="Choose"
								:options="NEXT_ACTIONS"
							/>
						</div>
						<NTextarea
							v-model="newActivity.summary"
							label="Note"
							:rows="2"
							hint="Optional."
							class="mt-3"
						/>
						<template #footer>
							<div class="flex justify-end">
								<NButton
									variant="primary"
									icon="plus"
									:loading="addingActivity"
									:disabled="!newActivity.activity_type"
									@click="submitActivity"
								>
									Add activity
								</NButton>
							</div>
						</template>
					</NCard>

					<NCard :padded="false">
						<NEmptyState
							v-if="!sortedActivities.length"
							icon="message-square"
							title="Nothing logged yet"
							body="Calls, visits and quotes you record appear here, newest first."
						/>
						<ul v-else class="divide-y divide-hairline">
							<li
								v-for="a in sortedActivities"
								:key="a.name"
								class="flex items-start gap-3 py-3"
							>
								<span
									class="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-sunken text-muted"
								>
									<NIcon :name="activityIcon(a.activity_type)" :size="15" />
								</span>
								<div class="min-w-0 flex-1">
									<p class="text-title-sm text-ink">
										{{ a.activity_type }}
										<span v-if="a.outcome" class="font-normal text-muted"
											>· {{ a.outcome }}</span
										>
									</p>
									<p
										v-if="a.summary"
										class="mt-0.5 whitespace-pre-wrap text-body-sm text-ink"
									>
										{{ a.summary }}
									</p>
									<p class="mt-1 text-caption text-muted">
										{{ fmt.dateTime(a.activity_date) }}
										<span v-if="a.performed_by"> · {{ fmt.person(a.performed_by) }}</span>
										<span v-if="a.next_action && a.next_action !== 'None'">
											· next: {{ a.next_action }}</span
										>
									</p>
								</div>
							</li>
						</ul>
					</NCard>
				</div>

				<!-- ── Reminders ────────────────────────────────────────────── -->
				<div v-else-if="activeTab === 'reminders'" class="space-y-3">
					<div class="flex justify-end">
						<NButton variant="primary" icon="bell" @click="showReminderModal = true"
							>Schedule reminder</NButton
						>
					</div>

					<NCard :padded="false">
						<NEmptyState
							v-if="!reminders.length"
							icon="bell"
							title="No reminders scheduled"
							body="Schedule one so this lead does not go quiet."
						/>
						<ul v-else class="divide-y divide-hairline">
							<li
								v-for="r in reminders"
								:key="r.name"
								class="flex items-start justify-between gap-3 px-4 py-3"
							>
								<div class="min-w-0">
									<p class="truncate text-title-sm text-ink">
										{{ fmt.or(r.subject, "(no subject)") }}
									</p>
									<p class="text-caption text-muted">
										{{ fmt.dateTime(r.reminder_datetime) }} · {{ r.channel }}
										<span v-if="r.recipient_email"> · {{ r.recipient_email }}</span>
									</p>
									<p v-if="r.failure_reason" class="mt-1 text-caption text-critical">
										{{ r.failure_reason }}
									</p>
								</div>
								<div class="flex shrink-0 items-center gap-2">
									<NBadge :semantic="dispatchSemantic(r.status)" :label="r.status" />
									<NButton
										v-if="r.status === 'Scheduled'"
										variant="ghost"
										size="sm"
										@click="cancelReminder(r.name)"
										>Cancel</NButton
									>
								</div>
							</li>
						</ul>
					</NCard>
				</div>

				<!-- ── Attachments ──────────────────────────────────────────── -->
				<div v-else-if="activeTab === 'attachments'" class="space-y-4">
					<NCard title="Add a file">
						<div class="space-y-3">
							<input
								type="file"
								accept="image/jpeg,image/png,image/webp,application/pdf"
								:disabled="uploading"
								aria-label="Choose a file to upload"
								class="block w-full text-body-sm text-muted file:mr-3 file:h-control file:cursor-pointer file:rounded-sm file:border file:border-line file:bg-raised file:px-3 file:text-label file:text-ink hover:file:bg-sunken"
								@change="onFilePick"
							/>
							<NInput
								v-model="newCaption"
								label="Caption"
								hint="Optional. Images and PDFs up to 5 MB."
							/>
							<NAlert v-if="uploadError" semantic="critical" :body="uploadError" />
						</div>
					</NCard>

					<NEmptyState
						v-if="!(lead.attachments || []).length"
						icon="paperclip"
						title="No files yet"
						body="Quotes, site photos and signed documents can live here."
					/>
					<div v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
						<a
							v-for="(att, idx) in lead.attachments"
							:key="idx"
							:href="att.file_url"
							target="_blank"
							rel="noopener noreferrer"
							class="overflow-hidden rounded-md border border-hairline bg-raised transition-colors duration-instant hover:bg-sunken"
						>
							<img
								v-if="isImage(att.file_url)"
								:src="att.file_url"
								:alt="att.caption || fileName(att.file_url)"
								class="h-32 w-full object-cover"
							/>
							<div
								v-else
								class="flex h-32 w-full items-center justify-center bg-sunken text-subtle"
							>
								<NIcon name="file-text" :size="24" />
							</div>
							<p class="truncate px-2.5 py-2 text-caption text-muted">
								{{ att.caption || fileName(att.file_url) }}
							</p>
						</a>
					</div>
				</div>
			</template>
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
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import LeadReminderModal from "../components/LeadReminderModal.vue";
import { useLeads } from "../composables/useLeads.js";
import { useCrmDropdowns } from "../composables/useCrmDropdowns.js";
import {
	NPageHeader,
	NTabs,
	NCard,
	NKeyValue,
	NBadge,
	NPriority,
	NButton,
	NIcon,
	NInput,
	NSelect,
	NTextarea,
	NAlert,
	NEmptyState,
	NSkeleton,
	fmt,
	dispatchSemantic,
	toast,
} from "../ui/index.js";

const ACTIVITY_TYPES = ["Call", "Visit", "Demo", "Quote Sent", "Email", "WhatsApp", "Other"];
const OUTCOMES = ["Interested", "Not Interested", "Callback Requested", "Meeting Scheduled", "No Response"];
const NEXT_ACTIONS = ["Call Back", "Send Quote", "Schedule Demo", "Close as Lost", "None"];

const ACTIVITY_ICONS = {
	Call: "phone",
	Visit: "map-pin",
	Demo: "play",
	"Quote Sent": "file-text",
	Email: "mail",
	WhatsApp: "message-square",
};

const route = useRoute();
const leads = useLeads();
const { dropdowns, load: loadDropdowns } = useCrmDropdowns();

const lead = ref(null);
const reminders = ref([]);
const loading = ref(true);
const activeTab = ref("overview");
const changingStatus = ref(false);

const newActivity = reactive({ activity_type: "", outcome: "", next_action: "", summary: "" });
const addingActivity = ref(false);
const showReminderModal = ref(false);

const newCaption = ref("");
const uploading = ref(false);
const uploadError = ref("");

const subtitle = computed(() => lead.value?.company_name || "Individual lead");

const tabs = computed(() => [
	{ value: "overview", label: "Overview" },
	{ value: "activities", label: "Activities", count: (lead.value?.activities || []).length },
	{ value: "reminders", label: "Reminders", count: reminders.value.length },
	{ value: "attachments", label: "Files", count: (lead.value?.attachments || []).length },
]);

const contactLines = computed(() => {
	const l = lead.value;
	if (!l) return [];
	return [
		l.phone && { icon: "phone", label: "Phone", value: l.phone },
		l.email && { icon: "mail", label: "Email", value: l.email },
		(l.city || l.state) && {
			icon: "map-pin",
			label: "Location",
			value: [l.city, l.state].filter(Boolean).join(", "),
		},
		l.estimated_value && {
			icon: "indian-rupee",
			label: "Estimated value",
			value: fmt.money(l.estimated_value),
		},
	].filter(Boolean);
});

const qualificationItems = computed(() => {
	const l = lead.value;
	if (!l) return [];
	return [
		{ label: "Source", value: fmt.or(l.lead_source) },
		{ label: "Industry", value: fmt.or(l.industry) },
		{ label: "Interested in", value: fmt.or(l.interested_in) },
		{ label: "Fleet size", value: fmt.or(l.fleet_size_bucket) },
		{ label: "Assigned to", value: fmt.person(l.assigned_to) },
		{ label: "Depot", value: fmt.or(l.depot) },
		{ label: "Expected close", value: fmt.date(l.expected_close_date) },
	];
});

const sortedActivities = computed(() =>
	[...(lead.value?.activities || [])].sort((a, b) => new Date(b.activity_date) - new Date(a.activity_date))
);

function activityIcon(type) {
	return ACTIVITY_ICONS[type] || "pencil";
}

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
		toast.success(`Status set to ${newStatus}.`);
	} catch (e) {
		toast.error(e?.message || "Could not change the status.");
	} finally {
		changingStatus.value = false;
	}
}

async function submitActivity() {
	if (!newActivity.activity_type) return;
	addingActivity.value = true;
	try {
		await leads.addActivity(lead.value.name, { ...newActivity });
		Object.assign(newActivity, { activity_type: "", outcome: "", next_action: "", summary: "" });
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
		uploadError.value = "That file is over 5 MB. Compress it, or attach a smaller one.";
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
		if (!fileUrl) throw new Error("The server did not return a file location.");

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

function isImage(url) {
	return /\.(jpe?g|png|webp|gif)$/i.test(url || "");
}

function fileName(url) {
	return (url || "").split("/").pop();
}

onMounted(async () => {
	await loadDropdowns();
	await reload();
});
</script>
