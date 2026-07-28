<template>
	<div class="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
		<RoleBoundary :roles="['Central Ops', 'Depot Manager', 'System Manager']">
			<h1 class="text-2xl font-bold text-gray-900 mb-1">KM Corrections</h1>
			<p class="text-sm text-gray-500 mb-6">
				Deduct dead (non-billable) kilometres. Billable = Total &minus; Excluded &minus; Dead.
			</p>

			<!-- Pickers -->
			<div class="flex flex-wrap items-end gap-3 mb-6">
				<label class="block">
					<span class="text-xs font-semibold text-gray-500 uppercase">Customer</span>
					<select
						v-model="customer"
						@change="loadBoard"
						class="mt-1 block w-56 rounded-lg border-gray-300 text-sm"
					>
						<option v-for="c in customers" :key="c.name" :value="c.name">
							{{ c.customer_name || c.name }}
						</option>
					</select>
				</label>
				<label class="block">
					<span class="text-xs font-semibold text-gray-500 uppercase">Billing Month</span>
					<select
						v-model="month"
						@change="loadBoard"
						class="mt-1 block w-40 rounded-lg border-gray-300 text-sm"
					>
						<option v-for="m in months" :key="m" :value="m">{{ m }}</option>
					</select>
				</label>
			</div>

			<div v-if="loading" class="text-center py-16 text-gray-400">Loading…</div>

			<template v-else-if="board">
				<!-- The maths, big and clear -->
				<div class="flex flex-wrap items-stretch gap-2 mb-6">
					<div class="flex-1 min-w-[120px] bg-white rounded-xl border p-4 text-center">
						<div class="text-2xl font-extrabold text-gray-800">
							{{ fmt(board.totals.total_distance_km) }}
						</div>
						<div class="text-2xs text-gray-500 uppercase mt-1">Total KM</div>
					</div>
					<div class="self-center text-2xl font-bold text-gray-300">&minus;</div>
					<div class="flex-1 min-w-[120px] bg-white rounded-xl border p-4 text-center">
						<div class="text-2xl font-extrabold text-amber-600">
							{{ fmt(board.totals.excluded_km) }}
						</div>
						<div class="text-2xs text-gray-500 uppercase mt-1">Excluded</div>
					</div>
					<div class="self-center text-2xl font-bold text-gray-300">&minus;</div>
					<div class="flex-1 min-w-[120px] bg-white rounded-xl border p-4 text-center">
						<div class="text-2xl font-extrabold text-red-600">
							{{ fmt(board.totals.dead_km) }}
						</div>
						<div class="text-2xs text-gray-500 uppercase mt-1">Dead KM</div>
					</div>
					<div class="self-center text-2xl font-bold text-gray-300">=</div>
					<div
						class="flex-1 min-w-[120px] bg-brand-50 rounded-xl border border-brand-200 p-4 text-center"
					>
						<div class="text-2xl font-extrabold text-brand-700">
							{{ fmt(board.totals.billable_km) }}
						</div>
						<div class="text-2xs text-brand-600 uppercase mt-1 font-semibold">Billable</div>
					</div>
				</div>

				<!-- Per-vehicle grid -->
				<div class="bg-white rounded-xl border overflow-hidden">
					<table class="w-full text-sm">
						<thead class="bg-gray-50 text-gray-500 text-xs uppercase">
							<tr>
								<th class="text-left px-4 py-3">Vehicle</th>
								<th class="text-right px-3 py-3">Total KM</th>
								<th class="text-right px-3 py-3 hidden sm:table-cell">Excluded</th>
								<th
									class="text-right px-3 py-3 hidden sm:table-cell"
									title="Dead KM logged on individual days"
								>
									Per-day Dead
								</th>
								<th class="text-right px-3 py-3">Monthly Dead</th>
								<th class="text-right px-4 py-3">Billable</th>
							</tr>
						</thead>
						<tbody class="divide-y">
							<tr v-for="v in board.vehicles" :key="v.vehicle" class="hover:bg-gray-50">
								<td class="px-4 py-3 font-medium text-gray-900">{{ v.registration }}</td>
								<td class="px-3 py-3 text-right tabular-nums">
									{{ fmt(v.total_distance_km) }}
								</td>
								<td
									class="px-3 py-3 text-right tabular-nums text-amber-600 hidden sm:table-cell"
								>
									{{ v.excluded_km ? "− " + fmt(v.excluded_km) : "0.0" }}
								</td>
								<td
									class="px-3 py-3 text-right tabular-nums text-gray-400 hidden sm:table-cell"
								>
									{{ v.per_day_dead_km ? "− " + fmt(v.per_day_dead_km) : "0.0" }}
								</td>
								<td class="px-3 py-3 text-right">
									<input
										type="number"
										min="0"
										step="0.1"
										class="w-24 text-right rounded-lg border-gray-300 text-sm tabular-nums"
										:value="v.monthly_dead_km"
										:disabled="savingVehicle === v.vehicle"
										@change="saveMonthly(v, $event.target.value)"
									/>
								</td>
								<td class="px-4 py-3 text-right font-bold text-brand-700 tabular-nums">
									{{ fmt(v.billable_km) }}
								</td>
							</tr>
							<tr v-if="!board.vehicles.length">
								<td colspan="6" class="px-4 py-10 text-center text-gray-400">
									No vehicles for this customer.
								</td>
							</tr>
						</tbody>
					</table>
				</div>
				<p v-if="savedMsg" class="text-xs text-green-600 mt-3">{{ savedMsg }}</p>
			</template>

			<template #fallback>
				<div class="text-center py-20 text-gray-400">You don't have access to KM corrections.</div>
			</template>
		</RoleBoundary>
	</div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { callAPI } from "../utils/api.js";
import RoleBoundary from "../components/RoleBoundary.vue";

const customers = ref([]);
const customer = ref("");
const month = ref("");
const months = ref(recentMonths(18));
const board = ref(null);
const loading = ref(false);
const savingVehicle = ref("");
const savedMsg = ref("");

function recentMonths(n) {
	const out = [];
	const d = new Date();
	d.setDate(1);
	for (let i = 0; i < n; i++) {
		out.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`);
		d.setMonth(d.getMonth() - 1);
	}
	return out;
}

function fmt(n) {
	return Number(n || 0).toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}

async function loadBoard() {
	if (!customer.value || !month.value) return;
	loading.value = true;
	savedMsg.value = "";
	try {
		const res = await callAPI("km_reports.get_km_correction_board", {
			customer: customer.value,
			year_month: month.value,
		});
		board.value = res.data;
	} finally {
		loading.value = false;
	}
}

async function saveMonthly(v, value) {
	const dead = Math.max(parseFloat(value) || 0, 0);
	savingVehicle.value = v.vehicle;
	savedMsg.value = "";
	try {
		await callAPI("km_reports.set_monthly_dead_km", {
			vehicle: v.vehicle,
			year_month: month.value,
			dead_km: dead,
		});
		// Refresh so billable + fleet totals reflect the change everywhere.
		await loadBoard();
		savedMsg.value = `Saved ${v.registration}: ${fmt(dead)} km dead for ${month.value}.`;
	} finally {
		savingVehicle.value = "";
	}
}

onMounted(async () => {
	month.value = months.value[0];
	const res = await callAPI("km_reports.list_km_customers");
	customers.value = res.data || [];
	if (customers.value.length) {
		customer.value = customers.value[0].name;
		await loadBoard();
	}
});
</script>
