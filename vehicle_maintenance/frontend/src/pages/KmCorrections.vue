<!--
  KM corrections.

  The page is one equation — Total − Excluded − Dead = Billable — and then the
  per-vehicle grid that feeds it. The equation is rendered *as* an equation, with
  real operators between the terms, because "why is the invoice this number" is
  the only question this page exists to answer.
-->
<template>
	<div>
		<NPageHeader title="KM corrections" subtitle="Deduct dead (non-billable) kilometres before billing">
			<template #toolbar>
				<label class="flex items-center gap-2 text-label text-muted">
					Customer
					<select
						v-model="customer"
						class="h-control rounded-sm border border-line bg-sunken px-2 text-body-sm text-ink"
						@change="loadBoard"
					>
						<option v-for="c in customers" :key="c.name" :value="c.name">
							{{ c.customer_name || c.name }}
						</option>
					</select>
				</label>
				<label class="flex items-center gap-2 text-label text-muted">
					Month
					<select
						v-model="month"
						class="h-control rounded-sm border border-line bg-sunken px-2 text-body-sm text-ink tabular"
						@change="loadBoard"
					>
						<option v-for="m in months" :key="m" :value="m">{{ m }}</option>
					</select>
				</label>
			</template>
		</NPageHeader>

		<RoleBoundary :roles="['Central Ops', 'Depot Manager', 'System Manager']">
			<div class="space-y-5 p-5">
				<div v-if="loading" class="grid grid-cols-4 gap-3">
					<NSkeleton v-for="i in 4" :key="i" variant="block" height="h-[92px]" :delay="0" />
				</div>

				<template v-else-if="board">
					<div class="flex flex-wrap items-stretch gap-2">
						<NStat
							class="min-w-[140px] flex-1"
							label="Total KM"
							:value="km(board.totals.total_distance_km)"
						/>
						<span class="self-center text-title-lg text-subtle" aria-hidden="true">−</span>
						<NStat
							class="min-w-[140px] flex-1"
							label="Excluded"
							:value="km(board.totals.excluded_km)"
							semantic="caution"
						/>
						<span class="self-center text-title-lg text-subtle" aria-hidden="true">−</span>
						<NStat
							class="min-w-[140px] flex-1"
							label="Dead KM"
							:value="km(board.totals.dead_km)"
							semantic="critical"
						/>
						<span class="self-center text-title-lg text-subtle" aria-hidden="true">=</span>
						<NStat
							class="min-w-[140px] flex-1"
							label="Billable"
							:value="km(board.totals.billable_km)"
							semantic="active"
							hint="Goes on the invoice"
						/>
					</div>

					<NTable
						:columns="columns"
						:rows="board.vehicles"
						row-key="vehicle"
						empty-icon="bus"
						empty-title="No vehicles for this customer"
						empty-body="Nothing was recorded against this customer for the selected month."
					>
						<template #cell:registration="{ row }"
							><NVehicle :value="row.registration"
						/></template>
						<template #cell:total_distance_km="{ row }">{{ km(row.total_distance_km) }}</template>
						<template #cell:excluded_km="{ row }">
							<span :class="row.excluded_km ? 'text-caution' : 'text-subtle'">
								{{ row.excluded_km ? `− ${km(row.excluded_km)}` : "0.0" }}
							</span>
						</template>
						<template #cell:per_day_dead_km="{ row }">
							<span class="text-muted">{{
								row.per_day_dead_km ? `− ${km(row.per_day_dead_km)}` : "0.0"
							}}</span>
						</template>
						<template #cell:monthly_dead_km="{ row }">
							<input
								type="number"
								min="0"
								step="0.1"
								:value="row.monthly_dead_km"
								:disabled="savingVehicle === row.vehicle"
								:aria-label="`Monthly dead kilometres for ${row.registration}`"
								class="tabular h-7 w-24 rounded-sm border border-line bg-sunken px-2 text-right text-body-sm text-ink focus:bg-raised disabled:opacity-50"
								@change="saveMonthly(row, $event.target.value)"
							/>
						</template>
						<template #cell:billable_km="{ row }">
							<span class="font-semibold text-ink">{{ km(row.billable_km) }}</span>
						</template>
					</NTable>
				</template>
			</div>

			<template #fallback>
				<NEmptyState
					icon="lock"
					title="You do not have access to KM corrections"
					body="Ask central ops if you need to adjust billable kilometres."
				/>
			</template>
		</RoleBoundary>
	</div>
</template>

<script setup>
import { onMounted, ref } from "vue";
import { callAPI } from "../utils/api.js";
import RoleBoundary from "../components/RoleBoundary.vue";
import { NPageHeader, NStat, NTable, NVehicle, NEmptyState, NSkeleton, toast } from "../ui/index.js";

const columns = [
	{ key: "registration", label: "Vehicle", width: "150px" },
	{ key: "total_distance_km", label: "Total KM", align: "right", width: "110px" },
	{ key: "excluded_km", label: "Excluded", align: "right", width: "110px" },
	{ key: "per_day_dead_km", label: "Per-day dead", align: "right", width: "130px" },
	{ key: "monthly_dead_km", label: "Monthly dead", align: "right", width: "130px" },
	{ key: "billable_km", label: "Billable", align: "right", width: "110px" },
];

const customers = ref([]);
const customer = ref("");
const month = ref("");
const months = ref(recentMonths(18));
const board = ref(null);
const loading = ref(false);
const savingVehicle = ref("");

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

/* Kilometres keep one decimal — the odometer feed has that precision and
   rounding it away here would stop the column adding up to the total above it. */
function km(n) {
	return Number(n || 0).toLocaleString("en-IN", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}

async function loadBoard() {
	if (!customer.value || !month.value) return;
	loading.value = true;
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
	try {
		await callAPI("km_reports.set_monthly_dead_km", {
			vehicle: v.vehicle,
			year_month: month.value,
			dead_km: dead,
		});
		// Refresh so billable + fleet totals reflect the change everywhere.
		await loadBoard();
		toast.success(`${v.registration}: ${km(dead)} km dead for ${month.value}.`);
	} catch (e) {
		toast.error(`Could not save dead KM for ${v.registration}. ${e?.message || ""}`.trim());
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
