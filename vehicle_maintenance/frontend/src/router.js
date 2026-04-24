import { createRouter, createWebHistory } from "vue-router";
import { useSession } from "./composables/useSession.js";

const routes = [
	{ path: "/", redirect: "/service-portal" },
	{
		path: "/service-portal",
		name: "Dashboard",
		component: () => import("./pages/Dashboard.vue"),
		meta: { requiresAuth: true },
	},
	{
		path: "/service-portal/depot-manager",
		name: "DepotManagerDashboard",
		component: () => import("./pages/DepotManagerDashboard.vue"),
		meta: { requiresAuth: true },
	},
	{
		path: "/service-portal/central-ops",
		name: "CentralOpsDashboard",
		component: () => import("./pages/CentralOpsDashboard.vue"),
		meta: { requiresAuth: true },
	},
	{
		path: "/service-portal/my-fleet",
		name: "CustomerDashboard",
		component: () => import("./pages/CustomerDashboard.vue"),
		meta: { requiresAuth: true },
	},
	{
		path: "/service-portal/job-card/new",
		name: "JobCardNew",
		component: () => import("./pages/JobCardNew.vue"),
		meta: { requiresAuth: true },
	},
	{
		path: "/service-portal/job-card/:jobCardName/health-card",
		name: "HealthCardView",
		component: () => import("./pages/HealthCardView.vue"),
		meta: { requiresAuth: true },
		props: true,
	},
	{
		path: "/service-portal/job-card/:name",
		name: "JobCardDetail",
		component: () => import("./pages/CustomerJobCard.vue"),
		meta: { requiresAuth: true },
		props: true,
	},
	{
		path: "/service-portal/inspection/new",
		name: "TechnicianInspection",
		component: () => import("./pages/TechnicianInspection.vue"),
		meta: { requiresAuth: true },
	},
	{
		path: "/service-portal/crm/leads",
		name: "LeadsList",
		component: () => import("./pages/LeadsList.vue"),
		meta: { requiresAuth: true },
	},
	{
		path: "/service-portal/crm/leads/new",
		name: "LeadNew",
		component: () => import("./pages/LeadNew.vue"),
		meta: { requiresAuth: true },
	},
	{
		path: "/service-portal/crm/leads/:name",
		name: "LeadDetail",
		component: () => import("./pages/LeadDetail.vue"),
		meta: { requiresAuth: true },
		props: true,
	},
	{
		path: "/service-portal/login",
		name: "Login",
		component: () => import("./pages/Login.vue"),
	},
];

const router = createRouter({
	history: createWebHistory(),
	routes,
});

router.beforeEach(async (to, from, next) => {
	if (!to.meta.requiresAuth) {
		return next();
	}

	// Wait for the session check to complete before deciding
	const { waitForSession, isLoggedIn } = useSession();
	await waitForSession();

	if (!isLoggedIn.value) {
		next({ name: "Login", query: { redirect: to.fullPath } });
	} else {
		next();
	}
});

export default router;
