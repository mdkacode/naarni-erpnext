<template>
	<!--
    ChecklistTemplatesAdmin — admin view of every PMS check sheet template.

    Shows the full list of checklist elements (e.g., all 103 items of Sheet A)
    grouped by section, and aggregate per-section timing once data exists.

    Visible to System Manager / Depot Manager / Central Ops / Service Engineer.
    Edit access (create/write/delete on Check Sheet Template) is enforced
    server-side and managed via Frappe's Role Permission Manager.
  -->
	<div class="max-w-5xl mx-auto px-4 py-6 space-y-6">
		<div class="flex items-center justify-between flex-wrap gap-3">
			<div>
				<router-link to="/service-portal" class="text-xs text-brand-600 hover:underline"
					>&larr; Dashboard</router-link
				>
				<h1 class="text-2xl font-bold text-gray-900 mt-1">Checklist Templates</h1>
				<p class="text-sm text-gray-500">
					Master list of all PMS inspection items. Edits require Depot Manager or System Manager
					role.
				</p>
			</div>
			<a
				href="/app/check-sheet-template"
				target="_blank"
				rel="noopener"
				class="inline-flex items-center gap-2 px-3 py-2 text-xs font-medium rounded-lg bg-brand-50 text-brand-700 hover:bg-brand-100"
			>
				Edit in Desk &rarr;
			</a>
		</div>

		<!-- Template selector -->
		<div v-if="templates.length" class="flex gap-2 overflow-x-auto pb-2">
			<button
				v-for="t in templates"
				:key="t.template_code"
				type="button"
				@click="selectTemplate(t.template_code)"
				class="shrink-0 px-3 py-1.5 text-xs font-medium rounded-full whitespace-nowrap border transition-colors"
				:class="
					activeCode === t.template_code
						? 'bg-brand-600 text-white border-brand-600'
						: 'bg-white border-gray-200 text-gray-700 hover:border-gray-300'
				"
			>
				{{ t.template_code }} · {{ t.item_count }} items
			</button>
		</div>

		<div v-if="loading" class="text-center py-10 text-sm text-gray-400">Loading…</div>
		<div v-else-if="loadError" class="p-4 rounded-lg bg-red-50 text-sm text-red-700">{{ loadError }}</div>

		<div v-else-if="active" class="space-y-4">
			<div class="bg-white border border-gray-200 rounded-xl p-4 grid grid-cols-2 sm:grid-cols-4 gap-4">
				<div>
					<p class="text-[10px] uppercase tracking-wide text-gray-400">Total checks</p>
					<p class="text-lg font-semibold">{{ active.total_items }}</p>
				</div>
				<div>
					<p class="text-[10px] uppercase tracking-wide text-gray-400">Sections</p>
					<p class="text-lg font-semibold">{{ active.sections.length }}</p>
				</div>
				<div>
					<p class="text-[10px] uppercase tracking-wide text-gray-400">Total std time</p>
					<p class="text-lg font-semibold">{{ active.total_std_time_minutes || 0 }} min</p>
				</div>
				<div>
					<p class="text-[10px] uppercase tracking-wide text-gray-400">Code</p>
					<p class="text-lg font-mono">{{ active.template_code }}</p>
				</div>
			</div>

			<!-- Per-section timing summary -->
			<div v-if="timingRows.length" class="bg-white border border-gray-200 rounded-xl overflow-hidden">
				<div class="px-4 py-3 border-b border-gray-200 flex items-center justify-between">
					<h2 class="text-sm font-semibold text-gray-900">Avg time per section (last 30 days)</h2>
					<span class="text-xs text-gray-400">{{ timingRows.length }} sections sampled</span>
				</div>
				<table class="w-full text-sm">
					<thead class="bg-gray-50 text-xs uppercase text-gray-500">
						<tr>
							<th class="text-left px-4 py-2">Section</th>
							<th class="text-right px-4 py-2">Samples</th>
							<th class="text-right px-4 py-2">Avg (min)</th>
							<th class="text-right px-4 py-2">Min</th>
							<th class="text-right px-4 py-2">Max</th>
							<th class="text-right px-4 py-2">Std</th>
						</tr>
					</thead>
					<tbody>
						<tr
							v-for="row in timingRows"
							:key="row.section_code"
							class="border-t border-gray-100"
						>
							<td class="px-4 py-2">
								<span class="font-mono text-xs mr-2">{{ row.section_code }}</span
								>{{ row.section_title }}
							</td>
							<td class="px-4 py-2 text-right tabular-nums">{{ row.sample_size }}</td>
							<td class="px-4 py-2 text-right tabular-nums font-semibold">
								{{ row.avg_minutes }}
							</td>
							<td class="px-4 py-2 text-right tabular-nums">{{ row.min_minutes }}</td>
							<td class="px-4 py-2 text-right tabular-nums">{{ row.max_minutes }}</td>
							<td class="px-4 py-2 text-right tabular-nums text-gray-400">
								{{ row.std_minutes }}
							</td>
						</tr>
					</tbody>
				</table>
			</div>

			<!-- Sections + items -->
			<div
				v-for="section in active.sections"
				:key="section.section_code"
				class="bg-white border border-gray-200 rounded-xl overflow-hidden"
			>
				<div class="px-4 py-3 bg-gray-50 border-b border-gray-200 flex items-center justify-between">
					<div>
						<p class="text-[10px] uppercase tracking-wide text-gray-400">
							Section {{ section.section_code }}
						</p>
						<h3 class="text-sm font-semibold text-gray-900">{{ section.section_title }}</h3>
					</div>
					<span class="text-xs text-gray-400">{{ section.item_count }} items</span>
				</div>
				<ul class="divide-y divide-gray-100">
					<li
						v-for="item in section.items"
						:key="item.sr_no"
						class="px-4 py-2.5 flex items-start gap-3"
					>
						<span class="text-xs text-gray-400 font-mono w-6 shrink-0">{{ item.sr_no }}</span>
						<span class="text-sm text-gray-800 flex-1">{{ item.parameter }}</span>
						<span
							class="shrink-0 inline-block px-2 py-0.5 text-[10px] font-medium rounded-full bg-gray-100 text-gray-600 whitespace-nowrap"
						>
							{{ item.inspection_method }}
						</span>
					</li>
				</ul>
			</div>
		</div>
	</div>
</template>

<script setup>
import { ref, onMounted } from "vue";
import { checklistApi } from "../composables/useChecklistApi.js";

const templates = ref([]);
const active = ref(null);
const activeCode = ref(null);
const timingRows = ref([]);
const loading = ref(true);
const loadError = ref("");

async function selectTemplate(code) {
	activeCode.value = code;
	loading.value = true;
	loadError.value = "";
	try {
		const [tplRes, timingRes] = await Promise.all([
			checklistApi.getTemplate({ template_code: code }),
			checklistApi.timingSummary({ template_code: code }).catch(() => ({ data: [] })),
		]);
		active.value = tplRes.data;
		timingRows.value = timingRes.data || [];
	} catch (e) {
		loadError.value = e?.message || "Failed to load template";
	} finally {
		loading.value = false;
	}
}

onMounted(async () => {
	try {
		const res = await checklistApi.listTemplates();
		templates.value = res.data || [];
		if (templates.value.length) {
			await selectTemplate(templates.value[0].template_code);
		} else {
			loading.value = false;
		}
	} catch (e) {
		loadError.value = e?.message || "Failed to load templates";
		loading.value = false;
	}
});
</script>
