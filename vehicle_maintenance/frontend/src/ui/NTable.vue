<!--
  NTable — the workhorse of every ops screen.

  Rules encoded here so no page has to remember them (DESIGN_LANGUAGE.md §5.5):

    · the header is `sunken`, uppercase `label-sm`, and sticks while the body scrolls
    · rows are separated by a hairline and respond on hover by changing ground
    · numbers, money and dates are right-aligned and tabular; text is left-aligned
    · the identity column renders as a link, so a clickable row is also a
      *visibly* clickable row and works from the keyboard
    · loading paints skeleton rows at the real column widths — the layout does
      not move when the data lands
    · empty is an EmptyState spanning the body, never a bare "No records"

  Columns: `{ key, label, align?, width?, mono?, tabular?, link?, sortable?, class? }`
  Cells: override any cell with `#cell:<key>="{ row, value }"`.
-->
<template>
	<div class="overflow-hidden rounded-md border border-hairline bg-raised">
		<div
			class="overflow-x-auto"
			:class="maxHeight && 'overflow-y-auto'"
			:style="maxHeight ? { maxHeight } : undefined"
		>
			<table class="w-full border-collapse text-body-sm">
				<caption v-if="caption" class="sr-only-ndl">
					{{
						caption
					}}
				</caption>

				<thead class="sticky top-0 z-sticky">
					<tr class="bg-sunken">
						<th
							v-for="col in columns"
							:key="col.key"
							scope="col"
							class="border-b border-hairline px-cell py-2 text-label-sm font-label uppercase tracking-wide text-muted"
							:class="alignClass(col)"
							:style="col.width ? { width: col.width } : undefined"
						>
							<button
								v-if="col.sortable"
								type="button"
								class="inline-flex items-center gap-1 uppercase hover:text-ink"
								@click="toggleSort(col.key)"
							>
								{{ col.label }}
								<NIcon
									:name="
										sort.key === col.key
											? sort.dir === 'asc'
												? 'chevron-up'
												: 'chevron-down'
											: 'chevrons-up-down'
									"
									:size="12"
									:class="sort.key === col.key ? 'text-ink' : 'text-subtle'"
								/>
							</button>
							<template v-else>{{ col.label }}</template>
						</th>
						<th
							v-if="$slots.rowActions"
							scope="col"
							class="w-px border-b border-hairline px-cell py-2"
						>
							<span class="sr-only-ndl">Actions</span>
						</th>
					</tr>
				</thead>

				<tbody>
					<!-- Loading: the real grid, greyed. Not a spinner in the middle of nowhere. -->
					<template v-if="loading">
						<tr
							v-for="i in skeletonRows"
							:key="`sk-${i}`"
							class="border-b border-hairline last:border-0"
						>
							<td v-for="col in columns" :key="col.key" class="h-row px-cell">
								<div
									class="h-3 animate-ndl-pulse rounded-sm bg-sunken"
									:style="{ width: skeletonWidth(col, i) }"
								/>
							</td>
							<td v-if="$slots.rowActions" class="h-row px-cell" />
						</tr>
					</template>

					<tr v-else-if="!rows.length">
						<td :colspan="columns.length + ($slots.rowActions ? 1 : 0)">
							<slot name="empty">
								<NEmptyState :icon="emptyIcon" :title="emptyTitle" :body="emptyBody">
									<template v-if="$slots.emptyAction" #action
										><slot name="emptyAction"
									/></template>
								</NEmptyState>
							</slot>
						</td>
					</tr>

					<tr
						v-for="(row, index) in rows"
						v-else
						:key="rowKey ? row[rowKey] : index"
						class="group border-b border-hairline transition-colors duration-instant last:border-0 hover:bg-sunken"
						:class="[rowTo && 'cursor-pointer', rowClass?.(row)]"
						@click="rowTo && $router.push(rowTo(row))"
					>
						<td
							v-for="col in columns"
							:key="col.key"
							class="h-row px-cell align-middle"
							:class="[
								alignClass(col),
								col.mono && 'font-mono',
								(col.mono || col.align === 'right') && 'tabular',
								col.class,
							]"
						>
							<slot :name="`cell:${col.key}`" :row="row" :value="row[col.key]">
								<router-link
									v-if="isLinkColumn(col) && rowTo"
									:to="rowTo(row)"
									class="text-accent hover:underline"
									@click.stop
								>
									{{ display(row[col.key]) }}
								</router-link>
								<template v-else>{{ display(row[col.key]) }}</template>
							</slot>
						</td>
						<td
							v-if="$slots.rowActions"
							class="h-row px-cell text-right align-middle"
							@click.stop
						>
							<div class="flex items-center justify-end gap-0.5">
								<slot name="rowActions" :row="row" />
							</div>
						</td>
					</tr>
				</tbody>

				<tfoot v-if="$slots.footer" class="border-t border-hairline bg-sunken">
					<slot name="footer" :colspan="columns.length + ($slots.rowActions ? 1 : 0)" />
				</tfoot>
			</table>
		</div>
	</div>
</template>

<script setup>
import { computed } from "vue";
import NIcon from "./NIcon.vue";
import NEmptyState from "./NEmptyState.vue";
import { EMPTY } from "./format.js";

const props = defineProps({
	columns: { type: Array, required: true },
	rows: { type: Array, default: () => [] },
	rowKey: { type: String, default: "name" },
	/** `(row) => routeLocation`. Makes the row clickable *and* the identity cell a link. */
	rowTo: { type: Function, default: null },
	rowClass: { type: Function, default: null },
	loading: { type: Boolean, default: false },
	skeletonRows: { type: Number, default: 6 },
	sort: { type: Object, default: () => ({ key: "", dir: "asc" }) },
	maxHeight: { type: String, default: "" },
	caption: { type: String, default: "" },
	emptyIcon: { type: String, default: "list" },
	emptyTitle: { type: String, default: "Nothing here yet" },
	emptyBody: { type: String, default: "" },
});

const emit = defineEmits(["update:sort"]);

const linkKey = computed(() => (props.columns.find((c) => c.link) || props.columns[0])?.key);
const isLinkColumn = (col) => col.key === linkKey.value;

function alignClass(col) {
	if (col.align === "right") return "text-right";
	if (col.align === "center") return "text-center";
	return "text-left";
}

function display(value) {
	return value === null || value === undefined || value === "" ? EMPTY : value;
}

function toggleSort(key) {
	const dir = props.sort.key === key && props.sort.dir === "asc" ? "desc" : "asc";
	emit("update:sort", { key, dir });
}

/* Ragged skeleton widths read as text rather than as a broken grid. Deterministic
   per (column, row) so nothing shimmers between renders. */
const RATIOS = [0.82, 0.55, 0.7, 0.45, 0.9, 0.62];
function skeletonWidth(col, i) {
	const base = RATIOS[(props.columns.indexOf(col) + i) % RATIOS.length];
	return `${Math.round(base * 100)}%`;
}
</script>
