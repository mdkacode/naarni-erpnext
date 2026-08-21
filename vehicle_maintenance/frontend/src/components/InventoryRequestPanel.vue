<!--
  Inventory requests against a job card.

  Each row offers **one** action, and which one depends on the reader's role:
  an allocator sees "Allocate", the person who asked for the part sees
  "Acknowledge receipt". Everyone else sees the status and no button — a
  disabled button they will never be able to press is an unanswered question in
  the corner of the screen.
-->
<template>
	<NCard title="Inventory requests">
		<template v-if="canRequest" #actions>
			<NButton variant="tonal" size="sm" icon="plus" @click="openRequestForm">New request</NButton>
		</template>

		<NSkeleton v-if="loading" :count="3" :widths="['70%', '55%', '80%']" />

		<NEmptyState
			v-else-if="!rows.length"
			icon="package"
			title="No parts requested"
			:body="
				canRequest
					? 'Raise a request when a repair needs a part from stores.'
					: 'Nothing has been requested for this card.'
			"
		/>

		<ul v-else class="divide-y divide-hairline">
			<li v-for="row in rows" :key="row.name" class="flex items-center gap-3 py-2.5">
				<div class="min-w-0 flex-1">
					<p class="truncate text-title-sm text-ink">{{ row.part_name || row.part }}</p>
					<p class="text-caption text-muted">
						{{
							[row.part_group, `${row.quantity} ${row.uom || ""}`.trim()]
								.filter(Boolean)
								.join(" · ")
						}}
						·
						<span :class="semanticClasses(prioritySemantic(row.urgency_level)).text">{{
							row.urgency_level
						}}</span>
					</p>
				</div>
				<NBadge :semantic="itemStatusSemantic(row.status)" :label="row.status" />
				<NButton
					v-if="nextAction(row)"
					variant="secondary"
					size="sm"
					:loading="actingOn === row.name"
					@click="advance(row)"
				>
					{{ nextAction(row).label }}
				</NButton>
			</li>
		</ul>

		<NDialog v-model="requestOpen" title="Raise an inventory request" size="sm" persistent>
			<div class="space-y-4">
				<NCombobox
					v-model="selectedPart"
					label="Part"
					placeholder="Part name or code"
					required
					:search="searchParts"
					:display="(p) => p.part_name"
					:describe="(p) => [p.part_code, p.part_group].filter(Boolean).join(' · ')"
					:key-of="(p) => p.part_code"
				/>

				<div class="grid grid-cols-2 gap-3">
					<NInput v-model="form.quantity" label="Quantity" type="number" required />
					<NSelect v-model="form.urgency_level" label="Urgency" :options="URGENCIES" />
				</div>

				<NTextarea
					v-model="form.notes"
					label="Notes"
					:rows="2"
					hint="Optional — anything stores should know."
				/>

				<NAlert v-if="requestError" semantic="critical" :body="requestError" />
			</div>

			<template #actions>
				<NButton @click="requestOpen = false">Cancel</NButton>
				<NButton
					variant="primary"
					:loading="submitting"
					:disabled="!selectedPart || !form.quantity"
					@click="submitRequest"
				>
					Raise request
				</NButton>
			</template>
		</NDialog>
	</NCard>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { call } from "frappe-ui";
import {
	NCard,
	NButton,
	NBadge,
	NDialog,
	NCombobox,
	NInput,
	NSelect,
	NTextarea,
	NAlert,
	NEmptyState,
	NSkeleton,
	semanticClasses,
	prioritySemantic,
	itemStatusSemantic,
	toast,
} from "../ui/index.js";

const props = defineProps({
	jobCardName: { type: String, required: true },
	roles: { type: Array, default: () => [] },
});

const URGENCIES = ["Low", "Medium", "High", "Critical"];

const rows = ref([]);
const loading = ref(false);
const actingOn = ref(null);

const requestOpen = ref(false);
const submitting = ref(false);
const requestError = ref("");
const selectedPart = ref(null);
const form = reactive({ quantity: 1, urgency_level: "Medium", notes: "" });

const canRequest = computed(() =>
	props.roles.some((r) => ["Service Engineer", "Technician", "Depot Manager"].includes(r))
);
const isAllocator = computed(() => props.roles.some((r) => ["Depot Manager", "Central Ops"].includes(r)));
const isReceiver = computed(() => props.roles.some((r) => ["Technician", "Service Engineer"].includes(r)));

async function load() {
	loading.value = true;
	try {
		const res = await call(
			"vehicle_maintenance.fleet_service.doctype.inventory_request.inventory_request.list_for_job_card",
			{ job_card_ref: props.jobCardName }
		);
		rows.value = res?.data || [];
	} catch {
		rows.value = [];
	} finally {
		loading.value = false;
	}
}

function nextAction(row) {
	if (row.status === "Requested" && isAllocator.value)
		return { label: "Allocate", target: "Parts Allocated" };
	if (row.status === "Parts Allocated" && isAllocator.value)
		return { label: "Mark issued", target: "Parts Issued" };
	if (row.status === "Parts Issued" && isReceiver.value)
		return { label: "Acknowledge receipt", target: "Received" };
	return null;
}

async function advance(row) {
	const action = nextAction(row);
	if (!action) return;
	actingOn.value = row.name;
	try {
		await call("vehicle_maintenance.api.job_card.advance_inventory_status", {
			inventory_request_name: row.name,
			next_status: action.target,
		});
		toast.success(`${row.part_name || row.part} moved to ${action.target}.`);
		await load();
	} catch (e) {
		toast.error(e?.messages?.[0] || `Could not move it to ${action.target}.`);
	} finally {
		actingOn.value = null;
	}
}

function openRequestForm() {
	selectedPart.value = null;
	Object.assign(form, { quantity: 1, urgency_level: "Medium", notes: "" });
	requestError.value = "";
	requestOpen.value = true;
}

async function searchParts(query) {
	const res = await call("vehicle_maintenance.fleet_service.doctype.part.part.search_parts", {
		query,
		limit: 10,
	});
	return res?.data || [];
}

async function submitRequest() {
	submitting.value = true;
	requestError.value = "";
	try {
		await call("vehicle_maintenance.api.job_card.create_inventory_request", {
			job_card_name: props.jobCardName,
			part: selectedPart.value.part_code,
			quantity: form.quantity,
			urgency_level: form.urgency_level,
			notes: form.notes,
		});
		requestOpen.value = false;
		await load();
		toast.success("Inventory request raised.");
	} catch (e) {
		requestError.value = e?.messages?.[0] || "Could not raise the request.";
	} finally {
		submitting.value = false;
	}
}

onMounted(load);
</script>
