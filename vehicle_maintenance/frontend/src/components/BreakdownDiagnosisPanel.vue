<!--
  Breakdown diagnosis.

  The thirteen-step breakdown flow, as six sections in the order it actually
  happens: incident → SOP override → remote attempt → travel → resolution →
  trial trip → handover → RCA.

  Every timestamp on this panel is stamped by a button press, never typed. The
  service engineer is standing beside a bus on a highway; asking them to pick a
  datetime is asking for a wrong one.

  Colour here is verdict, not decoration: a permanent fix is `positive`, a
  temporary one `caution`, a force close `critical`, and high risk is the same
  red as a breached SLA.
-->
<template>
	<div class="space-y-4">
		<!-- ── Last PMS context ─────────────────────────────────────────────── -->
		<div
			v-if="breakdown.last_pms_date || breakdown.last_pms_odometer"
			class="grid grid-cols-2 gap-3 rounded-md border border-hairline bg-sunken p-3 sm:grid-cols-4"
		>
			<div>
				<p class="text-caption uppercase tracking-wide text-muted">Last PMS</p>
				<p class="text-body-sm text-ink">{{ fmt.date(breakdown.last_pms_date) }}</p>
			</div>
			<div>
				<p class="text-caption uppercase tracking-wide text-muted">At odometer</p>
				<p class="tabular text-body-sm text-ink">{{ fmt.distance(breakdown.last_pms_odometer) }}</p>
			</div>
			<div v-if="breakdown.last_service_tolerance_level">
				<p class="text-caption uppercase tracking-wide text-muted">Tolerance then</p>
				<p class="text-body-sm text-ink">{{ breakdown.last_service_tolerance_level }}</p>
			</div>
			<div v-if="breakdown.last_serviced_by">
				<p class="text-caption uppercase tracking-wide text-muted">Serviced by</p>
				<p class="truncate text-body-sm text-ink">{{ fmt.person(breakdown.last_serviced_by) }}</p>
			</div>
		</div>

		<!-- ── Incident ─────────────────────────────────────────────────────── -->
		<NCard title="Incident">
			<div class="space-y-4">
				<NChoice
					:model-value="breakdown.incident_place"
					label="Where did it happen?"
					:options="['Depot', 'En Route']"
					:columns="2"
					:disabled="disabled"
					@update:model-value="(v) => emitPatch({ incident_place: v })"
				/>

				<div class="grid grid-cols-3 gap-3">
					<NInput
						v-for="i in [1, 2, 3]"
						:key="i"
						:model-value="breakdown[`fault_code_${i}`]"
						:label="`Fault code ${i}`"
						placeholder="Optional"
						:disabled="disabled"
						@update:model-value="(v) => emitPatch({ [`fault_code_${i}`]: v })"
					/>
				</div>

				<div class="space-y-1.5">
					<p class="text-label text-ink">Groups impacted</p>
					<NMultiSelect
						:model-value="breakdown.groups_impacted || []"
						:options="groupOptions"
						:disabled="disabled"
						placeholder="Add groups"
						search-placeholder="Search part groups"
						@update:model-value="(next) => $emit('save-groups', next)"
					/>
				</div>
			</div>
		</NCard>

		<!-- ── SOP override ─────────────────────────────────────────────────── -->
		<NCard title="SOP override">
			<p class="-mt-1 mb-3 text-body-sm text-muted">
				Tick only if no documented process applies, or the documented process did not resolve it.
			</p>

			<div class="space-y-4">
				<div>
					<NCheckbox
						:model-value="Boolean(breakdown.force_override)"
						label="Force override"
						description="There is no SOP for this group."
						:disabled="disabled"
						@update:model-value="(v) => emitPatch({ force_override: v ? 1 : 0 })"
					/>
					<NTextarea
						v-if="breakdown.force_override"
						class="mt-2"
						:model-value="breakdown.force_override_reason || ''"
						:rows="2"
						placeholder="Why was there no documented process?"
						:disabled="disabled"
						@update:model-value="(v) => emitPatch({ force_override_reason: v })"
					/>
				</div>

				<div>
					<NCheckbox
						:model-value="Boolean(breakdown.process_override)"
						label="Process override"
						description="The SOP was followed but did not resolve it — record what was done instead."
						:disabled="disabled"
						@update:model-value="(v) => emitPatch({ process_override: v ? 1 : 0 })"
					/>
					<NTextarea
						v-if="breakdown.process_override"
						class="mt-2"
						:model-value="breakdown.process_override_steps || ''"
						:rows="3"
						placeholder="Step by step, what troubleshooting was actually performed."
						:disabled="disabled"
						@update:model-value="(v) => emitPatch({ process_override_steps: v })"
					/>
				</div>
			</div>
		</NCard>

		<!-- ── Remote resolution ────────────────────────────────────────────── -->
		<NCard title="Remote resolution">
			<template #actions>
				<NBadge
					:semantic="remoteSemantic"
					:label="breakdown.remote_resolution_status || 'Not started'"
				/>
			</template>

			<div class="space-y-3">
				<div class="grid grid-cols-2 gap-3">
					<NTimestamp label="Started" :value="breakdown.remote_resolution_started_at" />
					<div>
						<p class="text-caption uppercase tracking-wide text-muted">Elapsed of 30 min</p>
						<p class="tabular text-body-sm" :class="slaBreached ? 'text-critical' : 'text-ink'">
							{{ remoteElapsedMinutes }}m<span v-if="slaBreached"> · SLA breached</span>
						</p>
					</div>
				</div>

				<NMeter
					:value="remoteElapsedMinutes"
					:max="30"
					mode="count"
					label="Remote resolution SLA"
					:semantic="slaBreached ? 'critical' : 'active'"
				/>

				<div class="flex gap-2">
					<NButton
						class="flex-1"
						variant="primary"
						:disabled="disabled || breakdown.remote_resolution_status === 'Resolved'"
						@click="emitPatch({ remote_resolution_status: 'Resolved' })"
					>
						Resolved remotely
					</NButton>
					<NButton
						class="flex-1 text-critical"
						:disabled="disabled || breakdown.remote_resolution_status === 'Failed'"
						@click="emitPatch({ remote_resolution_status: 'Failed' })"
					>
						Could not fix remotely
					</NButton>
				</div>
			</div>
		</NCard>

		<!-- ── Travel ───────────────────────────────────────────────────────── -->
		<NCard title="Travel to the breakdown">
			<div class="space-y-3">
				<div class="grid grid-cols-3 gap-3">
					<NTimestamp label="Left" :value="breakdown.travel_started_at" />
					<NTimestamp label="Arrived" :value="breakdown.arrived_at_location" />
					<div>
						<p class="text-caption uppercase tracking-wide text-muted">Took</p>
						<p class="tabular text-body-sm text-ink">
							{{ minutes(breakdown.travel_duration_minutes) }}
						</p>
					</div>
				</div>

				<div class="flex gap-2">
					<NButton
						class="flex-1"
						:disabled="disabled || Boolean(breakdown.travel_started_at)"
						@click="emitPatch({ travel_started_at: nowStamp() })"
					>
						I have set off
					</NButton>
					<NButton
						class="flex-1"
						:disabled="
							disabled || !breakdown.travel_started_at || Boolean(breakdown.arrived_at_location)
						"
						@click="emitPatch({ arrived_at_location: nowStamp() })"
					>
						I have arrived
					</NButton>
				</div>
			</div>
		</NCard>

		<!-- ── Resolution ───────────────────────────────────────────────────── -->
		<NCard title="Resolution">
			<div class="space-y-4">
				<NChoice
					:model-value="breakdown.fix_type"
					label="What kind of fix was it?"
					:options="FIX_TYPES"
					:columns="3"
					:disabled="disabled"
					@update:model-value="(v) => emitPatch({ fix_type: v })"
				/>

				<NInput
					v-if="breakdown.fix_type === 'Temporary'"
					:model-value="breakdown.next_level_engineer"
					label="Escalate to"
					placeholder="User ID of the senior engineer"
					hint="A temporary fix has to belong to somebody."
					:disabled="disabled"
					@update:model-value="(v) => emitPatch({ next_level_engineer: v })"
				/>

				<div class="grid gap-4 sm:grid-cols-2">
					<NChoice
						:model-value="breakdown.recurrence_risk"
						label="Could it happen again on this bus?"
						:options="RISKS"
						:columns="2"
						:disabled="disabled"
						@update:model-value="(v) => emitPatch({ recurrence_risk: v })"
					/>
					<NChoice
						:model-value="breakdown.occurrence_risk"
						label="Could it happen across the fleet?"
						:options="RISKS"
						:columns="2"
						:disabled="disabled"
						@update:model-value="(v) => emitPatch({ occurrence_risk: v })"
					/>
				</div>
			</div>
		</NCard>

		<!-- ── Trial trip ───────────────────────────────────────────────────── -->
		<NCard title="Trial trip">
			<div class="space-y-3">
				<div class="grid grid-cols-2 gap-3 sm:grid-cols-4">
					<NTimestamp label="Started" :value="breakdown.trial_trip_started_at" />
					<NInput
						:model-value="breakdown.trial_trip_start_km"
						label="Start km"
						type="number"
						:disabled="disabled"
						@update:model-value="(v) => emitPatch({ trial_trip_start_km: Number(v) || 0 })"
					/>
					<NTimestamp label="Ended" :value="breakdown.trial_trip_ended_at" />
					<NInput
						:model-value="breakdown.trial_trip_end_km"
						label="End km"
						type="number"
						:disabled="disabled"
						@update:model-value="(v) => emitPatch({ trial_trip_end_km: Number(v) || 0 })"
					/>
				</div>

				<div class="grid grid-cols-2 gap-3">
					<div class="rounded-sm bg-sunken p-2.5">
						<p class="text-caption uppercase tracking-wide text-muted">Dead km</p>
						<p class="tabular text-body-sm text-ink">
							{{ fmt.or(breakdown.trial_trip_distance_km) }}
						</p>
					</div>
					<div class="rounded-sm bg-sunken p-2.5">
						<p class="text-caption uppercase tracking-wide text-muted">Took</p>
						<p class="tabular text-body-sm text-ink">
							{{ minutes(breakdown.trial_trip_duration_minutes) }}
						</p>
					</div>
				</div>

				<div class="flex gap-2">
					<NButton
						class="flex-1"
						:disabled="disabled || Boolean(breakdown.trial_trip_started_at)"
						@click="emitPatch({ trial_trip_started_at: nowStamp() })"
					>
						Start trial trip
					</NButton>
					<NButton
						class="flex-1"
						:disabled="
							disabled ||
							!breakdown.trial_trip_started_at ||
							Boolean(breakdown.trial_trip_ended_at)
						"
						@click="emitPatch({ trial_trip_ended_at: nowStamp() })"
					>
						End trial trip
					</NButton>
				</div>
			</div>
		</NCard>

		<!-- ── Handover ─────────────────────────────────────────────────────── -->
		<NCard title="Handover">
			<div class="space-y-3">
				<div class="grid grid-cols-2 gap-3">
					<NTimestamp label="Handed back" :value="breakdown.vehicle_handover_at" />
					<div>
						<p class="text-caption uppercase tracking-wide text-muted">Total downtime</p>
						<p class="tabular text-body-sm text-ink">
							{{ minutes(breakdown.total_downtime_minutes) }}
						</p>
					</div>
				</div>

				<NButton
					variant="primary"
					block
					:disabled="disabled || Boolean(breakdown.vehicle_handover_at)"
					@click="emitPatch({ vehicle_handover_at: nowStamp() })"
				>
					Bus handed back to the depot
				</NButton>
			</div>
		</NCard>

		<!-- ── RCA ──────────────────────────────────────────────────────────── -->
		<NCard title="Root cause analysis">
			<template v-if="breakdown.rca_received_at" #actions>
				<NBadge semantic="positive" :label="`Received ${fmt.date(breakdown.rca_received_at)}`" />
			</template>

			<NTextarea
				:model-value="breakdown.rca_notes || ''"
				:rows="4"
				placeholder="Root cause, what was done about it, and the summary the customer will read."
				:disabled="disabled || !canEditRca"
				:hint="
					!canEditRca && !disabled
						? 'Only aftersales, the depot manager or the maintenance head can write the RCA.'
						: ''
				"
				@update:model-value="(v) => emitPatch({ rca_notes: v })"
			/>
		</NCard>
	</div>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { call } from "frappe-ui";
import {
	NCard,
	NInput,
	NTextarea,
	NChoice,
	NCheckbox,
	NMultiSelect,
	NButton,
	NBadge,
	NMeter,
	NTimestamp,
	fmt,
	EMPTY,
} from "../ui/index.js";

const props = defineProps({
	modelValue: { type: Object, default: () => ({}) },
	disabled: { type: Boolean, default: false },
	canEditRca: { type: Boolean, default: true },
});
const emit = defineEmits(["save", "save-groups"]);

const FIX_TYPES = [
	{ value: "Permanent", label: "Permanent", semantic: "positive" },
	{ value: "Temporary", label: "Temporary", semantic: "caution" },
	{ value: "Force Closed", label: "Force closed", semantic: "critical" },
];

const RISKS = [
	{ value: "Low", label: "Low", semantic: "positive" },
	{ value: "High", label: "High", semantic: "critical" },
];

/** The remote-resolution SLA, in minutes. */
const REMOTE_SLA = 30;

const breakdown = computed(() => props.modelValue || {});

const groupOptions = ref([]);

onMounted(async () => {
	try {
		const res = await call("vehicle_maintenance.api.job_card.list_part_groups");
		groupOptions.value = (res?.data || []).map((g) => ({
			value: g.name,
			label: g.part_group_name || g.name,
			group: g.bus_system || "Other",
		}));
	} catch {
		groupOptions.value = [];
	}
});

const remoteElapsedMinutes = computed(() => {
	if (!breakdown.value.remote_resolution_started_at) return 0;
	const start = new Date(String(breakdown.value.remote_resolution_started_at).replace(" ", "T"));
	const end = breakdown.value.remote_resolution_failed_at
		? new Date(String(breakdown.value.remote_resolution_failed_at).replace(" ", "T"))
		: new Date();
	const mins = Math.round((end - start) / 60000);
	return Number.isFinite(mins) && mins > 0 ? mins : 0;
});

const slaBreached = computed(() => remoteElapsedMinutes.value >= REMOTE_SLA);

const remoteSemantic = computed(
	() =>
		({ "In Progress": "active", Resolved: "positive", Failed: "critical" }[
			breakdown.value.remote_resolution_status
		] || "idle")
);

function minutes(value) {
	return Number.isFinite(Number(value)) && value ? `${Math.round(Number(value))} min` : EMPTY;
}

function emitPatch(patch) {
	emit("save", patch);
}

/* Frappe stores Datetime as "YYYY-MM-DD HH:MM:SS" in site-local time, so the
   stamp is built from the local clock rather than an ISO string in UTC — an
   ISO stamp arrives five and a half hours off in an Indian depot. */
function nowStamp() {
	const pad = (n) => String(n).padStart(2, "0");
	const d = new Date();
	return (
		`${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
		`${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
	);
}
</script>
