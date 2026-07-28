<template>
	<header class="bg-white border-b border-gray-200 sticky top-0 z-40">
		<div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
			<div class="flex items-center justify-between h-14">
				<!-- Logo -->
				<router-link
					to="/service-portal"
					class="flex items-center gap-2 text-gray-900 font-semibold text-lg"
				>
					<svg
						class="w-6 h-6 text-brand-600"
						fill="none"
						viewBox="0 0 24 24"
						stroke="currentColor"
						stroke-width="2"
					>
						<path
							stroke-linecap="round"
							stroke-linejoin="round"
							d="M19.428 15.428a2 2 0 00-1.022-.547l-2.387-.477a6 6 0 00-3.86.517l-.318.158a6 6 0 01-3.86.517L6.05 15.21a2 2 0 00-1.806.547M8 4h8l-1 1v5.172a2 2 0 00.586 1.414l5 5c1.26 1.26.367 3.414-1.415 3.414H4.828c-1.782 0-2.674-2.154-1.414-3.414l5-5A2 2 0 009 10.172V5L8 4z"
						/>
					</svg>
					NaArNi Service
				</router-link>

				<!-- Nav Links + Actions -->
				<div class="flex items-center gap-3">
					<!-- Depot Manager -->
					<RoleBoundary :roles="['Depot Manager']">
						<router-link
							to="/service-portal/depot-manager"
							class="text-sm text-gray-600 hover:text-brand-600 font-medium px-2 py-1 rounded-lg hover:bg-brand-50"
						>
							Dashboard
						</router-link>
					</RoleBoundary>

					<!-- Central Ops -->
					<RoleBoundary :roles="['Central Ops']">
						<router-link
							to="/service-portal/central-ops"
							class="text-sm text-gray-600 hover:text-brand-600 font-medium px-2 py-1 rounded-lg hover:bg-brand-50"
						>
							Fleet Overview
						</router-link>
					</RoleBoundary>

					<RoleBoundary :roles="['Central Ops', 'Depot Manager']">
						<router-link
							to="/service-portal/km-corrections"
							class="text-sm text-gray-600 hover:text-brand-600 font-medium px-2 py-1 rounded-lg hover:bg-brand-50"
						>
							KM Corrections
						</router-link>
					</RoleBoundary>

					<!-- Customer -->
					<RoleBoundary :roles="['Customer']">
						<router-link
							to="/service-portal/my-fleet"
							class="text-sm text-gray-600 hover:text-brand-600 font-medium px-2 py-1 rounded-lg hover:bg-brand-50"
						>
							My Fleet
						</router-link>
					</RoleBoundary>

					<!-- Technician -->
					<RoleBoundary :roles="['Technician']">
						<router-link
							to="/service-portal/inspection/new"
							class="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white bg-amber-600 rounded-lg hover:bg-amber-700"
						>
							New Inspection
						</router-link>
					</RoleBoundary>

					<!-- SE / DM -->
					<RoleBoundary :roles="['Service Engineer', 'Depot Manager']">
						<router-link
							to="/service-portal/job-card/new"
							class="inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white bg-brand-600 rounded-lg hover:bg-brand-700"
						>
							+ New Job Card
						</router-link>
					</RoleBoundary>

					<!-- Sales / Central Ops — CRM -->
					<RoleBoundary :roles="['Sales Executive', 'Central Ops']">
						<router-link
							to="/service-portal/crm/leads"
							class="text-sm text-gray-600 hover:text-brand-600 font-medium px-2 py-1 rounded-lg hover:bg-brand-50"
						>
							Leads
						</router-link>
					</RoleBoundary>

					<span class="text-sm text-gray-500 ml-2">{{ user }}</span>
					<button @click="logout" class="text-sm text-gray-400 hover:text-gray-600">Logout</button>
				</div>
			</div>
		</div>
	</header>
</template>

<script setup>
import { useSession } from "../composables/useSession.js";
import RoleBoundary from "./RoleBoundary.vue";
const { user, logout } = useSession();
</script>
