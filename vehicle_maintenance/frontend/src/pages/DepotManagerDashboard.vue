<template>
	<div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
		<div class="flex items-center justify-between mb-6 flex-wrap gap-3">
			<h1 class="text-2xl font-bold text-gray-900">Depot Manager Dashboard</h1>
			<router-link
				:to="{ name: 'ChecklistTemplatesAdmin' }"
				class="inline-flex items-center gap-2 px-3 py-2 text-xs font-medium rounded-lg bg-brand-50 text-brand-700 hover:bg-brand-100"
			>
				PMS Checklist Templates &rarr;
			</router-link>
		</div>

		<div v-if="loading" class="text-center py-16 text-gray-400">Loading...</div>
		<template v-else-if="data">
			<!-- Stats Row -->
			<div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
				<div class="bg-white rounded-xl border p-5 text-center">
					<div class="text-3xl font-extrabold text-brand-600">{{ data.total_open }}</div>
					<div class="text-xs text-gray-500 mt-1 uppercase tracking-wider">Open Cards</div>
				</div>
				<div class="bg-white rounded-xl border p-5 text-center">
					<div class="text-3xl font-extrabold text-green-600">{{ data.total_closed }}</div>
					<div class="text-xs text-gray-500 mt-1 uppercase tracking-wider">Closed</div>
				</div>
				<div
					class="bg-white rounded-xl border p-5 text-center"
					:class="data.sla_breached > 0 ? 'border-red-300 bg-red-50' : ''"
				>
					<div
						class="text-3xl font-extrabold"
						:class="data.sla_breached > 0 ? 'text-red-600' : 'text-gray-400'"
					>
						{{ data.sla_breached }}
					</div>
					<div class="text-xs text-gray-500 mt-1 uppercase tracking-wider">SLA Breaches</div>
				</div>
				<div class="bg-white rounded-xl border p-5 text-center">
					<div class="text-3xl font-extrabold text-amber-600">{{ urgentCount }}</div>
					<div class="text-xs text-gray-500 mt-1 uppercase tracking-wider">Urgent/High</div>
				</div>
			</div>

			<!-- Status Breakdown -->
			<div class="grid md:grid-cols-2 gap-6 mb-8">
				<div class="bg-white rounded-xl border p-5">
					<h2 class="text-sm font-semibold text-gray-500 uppercase mb-4">Cards by Status</h2>
					<div class="space-y-2">
						<div
							v-for="s in data.states"
							:key="s.workflow_state"
							class="flex items-center justify-between"
						>
							<StatusBadge :state="s.workflow_state" />
							<span class="text-lg font-bold text-gray-900">{{ s.count }}</span>
						</div>
					</div>
				</div>
				<div class="bg-white rounded-xl border p-5">
					<h2 class="text-sm font-semibold text-gray-500 uppercase mb-4">Priority Breakdown</h2>
					<div class="space-y-3">
						<div v-for="p in data.priorities" :key="p.priority" class="flex items-center gap-3">
							<div
								class="w-24 text-sm font-medium"
								:class="
									p.priority === 'Urgent'
										? 'text-red-600'
										: p.priority === 'High'
										? 'text-orange-600'
										: 'text-gray-700'
								"
							>
								{{ p.priority }}
							</div>
							<div class="flex-1 bg-gray-100 rounded-full h-4 overflow-hidden">
								<div
									class="h-full rounded-full"
									:class="priorityBarColor(p.priority)"
									:style="{ width: barWidth(p.count) }"
								/>
							</div>
							<span class="text-sm font-bold w-8 text-right">{{ p.count }}</span>
						</div>
					</div>
				</div>
			</div>

			<!-- Recent Cards -->
			<div class="bg-white rounded-xl border">
				<div class="px-5 py-4 border-b flex items-center justify-between">
					<h2 class="text-sm font-semibold text-gray-500 uppercase">Active Job Cards</h2>
				</div>
				<div class="overflow-x-auto">
					<table class="min-w-full text-sm">
						<thead class="bg-gray-50">
							<tr>
								<th class="px-4 py-3 text-left text-gray-600">Job Card</th>
								<th class="px-4 py-3 text-left text-gray-600">Vehicle</th>
								<th class="px-4 py-3 text-left text-gray-600">Type</th>
								<th class="px-4 py-3 text-left text-gray-600">Customer</th>
								<th class="px-4 py-3 text-left text-gray-600">Priority</th>
								<th class="px-4 py-3 text-left text-gray-600">Status</th>
								<th class="px-4 py-3 text-center text-gray-600">SLA</th>
							</tr>
						</thead>
						<tbody class="divide-y">
							<tr
								v-for="card in data.recent_cards"
								:key="card.name"
								class="hover:bg-gray-50 cursor-pointer"
								@click="openCard(card.name)"
							>
								<td class="px-4 py-3 font-mono text-brand-600">{{ card.name }}</td>
								<td class="px-4 py-3 font-semibold">
									{{ formatVehicle(card.vehicle_number) }}
								</td>
								<td class="px-4 py-3">{{ card.job_card_type }}</td>
								<td class="px-4 py-3 text-gray-600">{{ card.customer_name }}</td>
								<td class="px-4 py-3">
									<span
										class="text-xs font-semibold px-2 py-0.5 rounded-full"
										:class="priorityBadge(card.priority)"
										>{{ card.priority }}</span
									>
								</td>
								<td class="px-4 py-3"><StatusBadge :state="card.workflow_state" /></td>
								<td class="px-4 py-3 text-center">
									<span v-if="card.sla_breached" class="text-red-600 font-bold text-xs"
										>BREACH</span
									>
									<span v-else class="text-green-500 text-xs">OK</span>
								</td>
							</tr>
						</tbody>
					</table>
				</div>
			</div>
		</template>
	</div>
</template>

<script setup>
import { ref, computed, onMounted } from "vue";
import { callAPI } from "../utils/api.js";
import StatusBadge from "../components/StatusBadge.vue";

const data = ref(null);
const loading = ref(true);

onMounted(async () => {
	try {
		const res = await callAPI("dashboard.get_depot_manager_dashboard");
		data.value = res.data;
	} finally {
		loading.value = false;
	}
});

const urgentCount = computed(() => {
	if (!data.value?.priorities) return 0;
	return data.value.priorities
		.filter((p) => p.priority === "Urgent" || p.priority === "High")
		.reduce((sum, p) => sum + p.count, 0);
});

const maxCount = computed(() => Math.max(...(data.value?.priorities || []).map((p) => p.count), 1));
function barWidth(count) {
	return `${(count / maxCount.value) * 100}%`;
}
function priorityBarColor(p) {
	return (
		{ Urgent: "bg-red-500", High: "bg-orange-400", Medium: "bg-amber-400", Low: "bg-green-400" }[p] ||
		"bg-gray-300"
	);
}
function priorityBadge(p) {
	return {
		Urgent: "bg-red-100 text-red-700",
		High: "bg-orange-100 text-orange-700",
		Medium: "bg-amber-100 text-amber-700",
		Low: "bg-green-100 text-green-700",
	}[p];
}
function formatVehicle(num) {
	if (!num) return "";
	let c = num.replace(/[-\s]/g, "").toUpperCase();
	let m = c.match(/^([A-Z]{2})(\d{1,2})([A-Z]{1,3})(\d{1,4})$/);
	return m ? `${m[1]} ${m[2].padStart(2, "0")} ${m[3]} ${m[4]}` : num;
}
function openCard(name) {
	window.open(`/app/job-card/${name}`, "_blank");
}
</script>
