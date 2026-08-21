<!--
  Leads.

  Filters move into the page-header toolbar rather than a five-column panel of
  labelled dropdowns above the table: the filters are chrome, the table is the
  page, and giving the chrome its own bordered card made it the biggest object
  on screen.
-->
<template>
	<div>
		<NPageHeader title="Leads" :subtitle="subtitle">
			<template #actions>
				<NButton variant="primary" icon="plus" to="/service-portal/crm/leads/new">New lead</NButton>
			</template>

			<template #toolbar>
				<NSearch
					v-model="search"
					placeholder="Name, phone or company"
					class="w-64"
					@update:model-value="debouncedReload"
				/>
				<select
					v-model="filters.status"
					class="h-control rounded-sm border border-line bg-sunken px-2 text-body-sm text-ink"
					aria-label="Status"
					@change="reload"
				>
					<option value="">All statuses</option>
					<option v-for="s in dropdowns.statuses" :key="s.name" :value="s.name">
						{{ s.status_name }}
					</option>
				</select>
				<select
					v-model="filters.lead_source"
					class="h-control rounded-sm border border-line bg-sunken px-2 text-body-sm text-ink"
					aria-label="Source"
					@change="reload"
				>
					<option value="">All sources</option>
					<option v-for="s in dropdowns.sources" :key="s.name" :value="s.name">
						{{ s.source_name }}
					</option>
				</select>
				<select
					v-model="filters.assigned_to"
					class="h-control rounded-sm border border-line bg-sunken px-2 text-body-sm text-ink"
					aria-label="Assigned to"
					@change="reload"
				>
					<option value="">Anyone</option>
					<option v-for="u in dropdowns.sales_users" :key="u.name" :value="u.name">
						{{ u.full_name || u.name }}
					</option>
				</select>
				<select
					v-model="filters.priority"
					class="h-control rounded-sm border border-line bg-sunken px-2 text-body-sm text-ink"
					aria-label="Priority"
					@change="reload"
				>
					<option value="">Any priority</option>
					<option>Low</option>
					<option>Medium</option>
					<option>High</option>
				</select>
				<NButton v-if="anyFilter" variant="ghost" icon="x" @click="clearFilters">Clear</NButton>
			</template>
		</NPageHeader>

		<div class="space-y-3 p-5">
			<NTable
				:columns="columns"
				:rows="rows"
				:loading="loading"
				:row-to="(row) => `/service-portal/crm/leads/${encodeURIComponent(row.name)}`"
				empty-icon="hand-coins"
				:empty-title="anyFilter ? 'No leads match these filters' : 'No leads yet'"
				:empty-body="
					anyFilter
						? 'Try widening the filters.'
						: 'Leads captured during sales pitches appear here.'
				"
			>
				<template #emptyAction>
					<NButton v-if="anyFilter" icon="x" @click="clearFilters">Clear filters</NButton>
					<NButton v-else variant="primary" icon="plus" to="/service-portal/crm/leads/new"
						>New lead</NButton
					>
				</template>

				<template #cell:lead_name="{ row }">
					<div class="min-w-0">
						<p class="truncate text-title-sm text-ink">{{ row.lead_name }}</p>
						<p class="truncate text-caption text-muted">{{ fmt.or(row.company_name, "—") }}</p>
					</div>
				</template>
				<template #cell:status="{ row }"
					><NBadge semantic="idle" :label="fmt.or(row.status)" :dot="false"
				/></template>
				<template #cell:priority="{ row }"><NPriority :value="row.priority" /></template>
				<template #cell:estimated_value="{ row }">{{ fmt.money(row.estimated_value) }}</template>
				<template #cell:assigned_to="{ row }">{{ fmt.person(row.assigned_to) }}</template>
				<template #cell:modified="{ row }">{{ fmt.since(row.modified) }}</template>
			</NTable>

			<div v-if="total > pageSize" class="flex items-center justify-between text-body-sm text-muted">
				<span class="tabular">
					{{ (page - 1) * pageSize + 1 }}–{{ Math.min(page * pageSize, total) }} of {{ total }}
				</span>
				<div class="flex gap-2">
					<NButton icon="chevron-left" :disabled="page <= 1" @click="setPage(page - 1)"
						>Previous</NButton
					>
					<NButton
						trailing-icon="chevron-right"
						:disabled="page * pageSize >= total"
						@click="setPage(page + 1)"
						>Next</NButton
					>
				</div>
			</div>
		</div>
	</div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { useLeads } from "../composables/useLeads.js";
import { useCrmDropdowns } from "../composables/useCrmDropdowns.js";
import { NPageHeader, NTable, NSearch, NButton, NBadge, NPriority, fmt } from "../ui/index.js";

const columns = [
	{ key: "lead_name", label: "Lead", link: true },
	{ key: "phone", label: "Phone", mono: true, width: "140px" },
	{ key: "lead_source", label: "Source", width: "140px" },
	{ key: "status", label: "Status", width: "140px" },
	{ key: "priority", label: "Priority", width: "110px" },
	{ key: "estimated_value", label: "Est. value", align: "right", width: "130px" },
	{ key: "assigned_to", label: "Assigned", width: "140px" },
	{ key: "modified", label: "Updated", align: "right", width: "110px" },
];

const leads = useLeads();
const { dropdowns, load: loadDropdowns } = useCrmDropdowns();

const filters = reactive({ status: "", lead_source: "", assigned_to: "", priority: "" });
const search = ref("");
const rows = ref([]);
const total = ref(0);
const page = ref(1);
const pageSize = 20;
const loading = ref(false);

let searchTimer = null;

const anyFilter = computed(() => Boolean(search.value.trim()) || Object.values(filters).some(Boolean));
const subtitle = computed(() => (total.value ? `${total.value} lead${total.value === 1 ? "" : "s"}` : ""));

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

function clearFilters() {
	search.value = "";
	Object.keys(filters).forEach((k) => (filters[k] = ""));
	page.value = 1;
	reload();
}

function setPage(p) {
	page.value = p;
	reload();
}

function stripEmpty(obj) {
	return Object.fromEntries(Object.entries(obj).filter(([, v]) => v));
}

onMounted(async () => {
	await loadDropdowns();
	await reload();
});
</script>
