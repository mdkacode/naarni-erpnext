<template>
	<div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
		<!-- Header -->
		<div class="flex items-center justify-between mb-6">
			<div>
				<h1 class="text-2xl font-bold text-gray-900">Leads</h1>
				<p class="text-sm text-gray-500 mt-0.5">Prospects captured during sales pitches.</p>
			</div>
			<router-link
				to="/service-portal/crm/leads/new"
				class="inline-flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-brand-600 rounded-lg hover:bg-brand-700"
			>
				+ New Lead
			</router-link>
		</div>

		<!-- Filter bar (dropdown-heavy per EAS) -->
		<div
			class="bg-white border border-gray-200 rounded-xl p-4 mb-4 grid grid-cols-1 md:grid-cols-5 gap-3"
		>
			<div>
				<label class="block text-xs font-medium text-gray-500 mb-1">Status</label>
				<select v-model="filters.status" @change="reload" class="select-field">
					<option value="">All</option>
					<option v-for="s in dropdowns.statuses" :key="s.name" :value="s.name">
						{{ s.status_name }}
					</option>
				</select>
			</div>

			<div>
				<label class="block text-xs font-medium text-gray-500 mb-1">Source</label>
				<select v-model="filters.lead_source" @change="reload" class="select-field">
					<option value="">All</option>
					<option v-for="s in dropdowns.sources" :key="s.name" :value="s.name">
						{{ s.source_name }}
					</option>
				</select>
			</div>

			<div>
				<label class="block text-xs font-medium text-gray-500 mb-1">Assigned To</label>
				<select v-model="filters.assigned_to" @change="reload" class="select-field">
					<option value="">All</option>
					<option v-for="u in dropdowns.sales_users" :key="u.name" :value="u.name">
						{{ u.full_name || u.name }}
					</option>
				</select>
			</div>

			<div>
				<label class="block text-xs font-medium text-gray-500 mb-1">Priority</label>
				<select v-model="filters.priority" @change="reload" class="select-field">
					<option value="">All</option>
					<option>Low</option>
					<option>Medium</option>
					<option>High</option>
				</select>
			</div>

			<div>
				<label class="block text-xs font-medium text-gray-500 mb-1">Search</label>
				<input
					v-model="search"
					@input="debouncedReload"
					type="text"
					placeholder="Name, phone, company"
					class="input-field"
				/>
			</div>
		</div>

		<!-- Table -->
		<div class="bg-white border border-gray-200 rounded-xl overflow-hidden">
			<table class="min-w-full text-sm">
				<thead class="bg-gray-50">
					<tr class="text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
						<th class="px-4 py-3">Lead</th>
						<th class="px-4 py-3">Phone</th>
						<th class="px-4 py-3">Source</th>
						<th class="px-4 py-3">Status</th>
						<th class="px-4 py-3">Priority</th>
						<th class="px-4 py-3">Est. Value</th>
						<th class="px-4 py-3">Assigned</th>
						<th class="px-4 py-3">Updated</th>
					</tr>
				</thead>
				<tbody class="divide-y divide-gray-100">
					<tr v-if="loading">
						<td colspan="8" class="px-4 py-8 text-center text-gray-400">Loading…</td>
					</tr>
					<tr v-else-if="!rows.length">
						<td colspan="8" class="px-4 py-8 text-center text-gray-400">
							No leads match the filters.
						</td>
					</tr>
					<tr
						v-for="row in rows"
						:key="row.name"
						class="hover:bg-gray-50 cursor-pointer"
						@click="openLead(row.name)"
					>
						<td class="px-4 py-3">
							<div class="font-medium text-gray-900">{{ row.lead_name }}</div>
							<div class="text-xs text-gray-500">{{ row.company_name || "—" }}</div>
						</td>
						<td class="px-4 py-3 text-gray-700">{{ row.phone }}</td>
						<td class="px-4 py-3 text-gray-700">{{ row.lead_source || "—" }}</td>
						<td class="px-4 py-3">
							<span
								class="inline-flex px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-700"
							>
								{{ row.status || "—" }}
							</span>
						</td>
						<td class="px-4 py-3">
							<span :class="priorityBadge(row.priority)">{{ row.priority || "—" }}</span>
						</td>
						<td class="px-4 py-3 text-gray-700">
							{{ row.estimated_value ? formatCurrency(row.estimated_value) : "—" }}
						</td>
						<td class="px-4 py-3 text-gray-700">{{ row.assigned_to || "—" }}</td>
						<td class="px-4 py-3 text-xs text-gray-500">{{ formatDate(row.modified) }}</td>
					</tr>
				</tbody>
			</table>

			<!-- Pagination -->
			<div
				v-if="total > pageSize"
				class="flex items-center justify-between px-4 py-3 border-t border-gray-100 bg-gray-50 text-xs text-gray-600"
			>
				<span>
					Showing {{ (page - 1) * pageSize + 1 }}–{{ Math.min(page * pageSize, total) }} of
					{{ total }}
				</span>
				<div class="flex gap-2">
					<button
						class="px-3 py-1 bg-white border border-gray-200 rounded disabled:opacity-40"
						:disabled="page <= 1"
						@click="setPage(page - 1)"
					>
						Prev
					</button>
					<button
						class="px-3 py-1 bg-white border border-gray-200 rounded disabled:opacity-40"
						:disabled="page * pageSize >= total"
						@click="setPage(page + 1)"
					>
						Next
					</button>
				</div>
			</div>
		</div>
	</div>
</template>

<script setup>
import { ref, reactive, onMounted } from "vue";
import { useRouter } from "vue-router";
import { useLeads } from "../composables/useLeads.js";
import { useCrmDropdowns } from "../composables/useCrmDropdowns.js";

const router = useRouter();
const leads = useLeads();
const { dropdowns, load: loadDropdowns } = useCrmDropdowns();

const filters = reactive({
	status: "",
	lead_source: "",
	assigned_to: "",
	priority: "",
});
const search = ref("");
const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);

let searchTimer = null;

async function reload() {
	loading.value = true;
	try {
		const res = await leads.list({
			filters: stripEmpty(filters),
			page: page.value,
			pageSize,
			search: search.value.trim(),
		});
		rows.value = res.rows || [];
		total.value = res.total || 0;
	} finally {
		loading.value = false;
	}
}

function debouncedReload() {
	clearTimeout(searchTimer);
	searchTimer = setTimeout(() => {
		page.value = 1;
		reload();
	}, 300);
}

function setPage(p) {
	page.value = p;
	reload();
}

function openLead(name) {
	router.push(`/service-portal/crm/leads/${encodeURIComponent(name)}`);
}

function stripEmpty(obj) {
	return Object.fromEntries(Object.entries(obj).filter(([, v]) => v));
}

function priorityBadge(p) {
	const base = "inline-flex px-2 py-0.5 rounded-full text-xs font-medium";
	if (p === "High") return `${base} bg-red-50 text-red-700`;
	if (p === "Medium") return `${base} bg-amber-50 text-amber-700`;
	if (p === "Low") return `${base} bg-gray-100 text-gray-600`;
	return `${base} bg-gray-100 text-gray-600`;
}

function formatCurrency(v) {
	return new Intl.NumberFormat("en-IN", {
		style: "currency",
		currency: "INR",
		maximumFractionDigits: 0,
	}).format(v);
}

function formatDate(s) {
	if (!s) return "";
	return new Date(s).toLocaleDateString("en-IN", {
		day: "2-digit",
		month: "short",
		year: "2-digit",
	});
}

onMounted(async () => {
	await loadDropdowns();
	await reload();
});
</script>

<style scoped>
.input-field {
	@apply w-full px-3 py-2 text-sm border border-gray-200 rounded-lg focus:border-brand-500 focus:ring-1 focus:ring-brand-500 outline-none;
}
.select-field {
	@apply w-full px-3 py-2 text-sm border border-gray-200 rounded-lg bg-white focus:border-brand-500 focus:ring-1 focus:ring-brand-500 outline-none;
}
</style>
