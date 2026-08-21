<!--
  AppSidebar — the product's spine.

  A fixed 240px rail rather than a centred top nav: an ops user has a wide
  monitor, and every pixel spent on a centred `max-w-7xl` column is a table row
  they cannot see. The active item is the only coloured thing in here.

  Nav is derived from roles in one place. Items the reader cannot use are absent
  from the DOM, not greyed out — a disabled control they will never be able to
  press is an unanswered question in the corner of every screen.
-->
<template>
	<nav class="flex h-full w-sidebar shrink-0 flex-col border-r border-hairline bg-raised" aria-label="Main">
		<div class="flex h-header shrink-0 items-center gap-2 border-b border-hairline px-4">
			<span class="flex h-6 w-6 items-center justify-center rounded-sm bg-accent text-accent-on">
				<NIcon name="bus" :size="15" />
			</span>
			<span class="text-title-sm text-ink">NaArNi <span class="text-muted">Service</span></span>
		</div>

		<div class="min-h-0 flex-1 overflow-y-auto px-2 py-3">
			<div v-for="group in groups" :key="group.title" class="mb-4 last:mb-0">
				<p v-if="group.title" class="px-2 pb-1 text-caption uppercase tracking-wide text-subtle">
					{{ group.title }}
				</p>
				<router-link
					v-for="item in group.items"
					:key="item.to"
					:to="item.to"
					class="mb-0.5 flex h-8 items-center gap-2.5 rounded-sm px-2 text-label transition-colors duration-instant"
					:class="
						isActive(item)
							? 'bg-accent-soft text-accent-ink'
							: 'text-muted hover:bg-sunken hover:text-ink'
					"
					@click="$emit('navigate')"
				>
					<NIcon :name="item.icon" :size="16" />
					<span class="truncate">{{ item.label }}</span>
				</router-link>
			</div>
		</div>

		<div class="shrink-0 border-t border-hairline p-2">
			<div class="flex items-center gap-2 rounded-sm px-2 py-1.5">
				<NAvatar :name="user" size="sm" />
				<div class="min-w-0 flex-1">
					<p class="truncate text-label-sm text-ink">{{ fmt.person(user) }}</p>
					<p class="truncate text-caption text-muted">{{ primaryRole }}</p>
				</div>
				<NIconButton :icon="themeIcon" :label="`Theme: ${mode}`" size="sm" @click="cycle" />
				<NIconButton icon="log-out" label="Sign out" size="sm" @click="$emit('logout')" />
			</div>
		</div>
	</nav>
</template>

<script setup>
import { computed } from "vue";
import { useRoute } from "vue-router";
import { NIcon, NIconButton, NAvatar, fmt } from "../ui/index.js";
import { useTheme } from "../composables/useTheme.js";

const props = defineProps({
	user: { type: String, default: "" },
	roles: { type: Array, default: () => [] },
});
defineEmits(["logout", "navigate"]);

const route = useRoute();
const { mode, cycle } = useTheme();

const themeIcon = computed(() =>
	mode.value === "dark" ? "moon" : mode.value === "light" ? "sun" : "settings"
);

/** The role a person is shown as. First match wins, most specific first. */
const ROLE_ORDER = [
	"Central Ops",
	"Depot Manager",
	"Service Engineer",
	"Technician",
	"Sales Executive",
	"Customer",
];
const primaryRole = computed(() => ROLE_ORDER.find((r) => props.roles.includes(r)) || "Signed in");

const NAV = [
	{
		title: "",
		items: [
			{
				label: "Job cards",
				to: "/service-portal",
				icon: "clipboard-list",
				roles: ["Depot Manager", "Service Engineer", "Technician", "Central Ops"],
			},
			{ label: "My fleet", to: "/service-portal/my-fleet", icon: "bus", roles: ["Customer"] },
		],
	},
	{
		title: "Operations",
		items: [
			{
				label: "Depot",
				to: "/service-portal/depot-manager",
				icon: "layout-dashboard",
				roles: ["Depot Manager"],
			},
			{
				label: "Fleet overview",
				to: "/service-portal/central-ops",
				icon: "gauge",
				roles: ["Central Ops"],
			},
			{
				label: "KM corrections",
				to: "/service-portal/km-corrections",
				icon: "route",
				roles: ["Central Ops", "Depot Manager"],
			},
		],
	},
	{
		title: "Sales",
		items: [
			{
				label: "Leads",
				to: "/service-portal/crm/leads",
				icon: "hand-coins",
				roles: ["Sales Executive", "Central Ops"],
			},
		],
	},
];

const groups = computed(() =>
	NAV.map((g) => ({
		...g,
		items: g.items.filter((i) => i.roles.some((r) => props.roles.includes(r))),
	})).filter((g) => g.items.length)
);

/* "/service-portal" is the prefix of every route, so an exact match is the only
   honest test for it; everything else matches on its own prefix. */
function isActive(item) {
	if (item.to === "/service-portal") return route.path === "/service-portal";
	return route.path.startsWith(item.to);
}
</script>
