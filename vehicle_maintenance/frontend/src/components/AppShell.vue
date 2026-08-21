<!--
  AppShell — sidebar plus a full-bleed content column.

  Density is set here, once: `compact` for the ops surfaces this shell wraps.
  A page that genuinely needs room (a form, a customer-facing view) declares
  `data-density="comfortable"` on its own root and every control inside it
  changes height without knowing why.
-->
<template>
	<div class="flex h-screen overflow-hidden bg-canvas" data-density="compact">
		<!-- Desktop rail -->
		<AppSidebar class="hidden lg:flex" :user="user" :roles="roles" @logout="logout" />

		<!-- Mobile drawer -->
		<Transition
			enter-active-class="transition-opacity duration-base"
			leave-active-class="transition-opacity duration-fast"
			enter-from-class="opacity-0"
			leave-to-class="opacity-0"
		>
			<div v-if="drawer" class="fixed inset-0 z-drawer lg:hidden">
				<div class="absolute inset-0 bg-overlay" @click="drawer = false" />
				<AppSidebar
					class="absolute inset-y-0 left-0"
					:user="user"
					:roles="roles"
					@logout="logout"
					@navigate="drawer = false"
				/>
			</div>
		</Transition>

		<div class="flex min-w-0 flex-1 flex-col overflow-hidden">
			<button
				type="button"
				class="flex h-header shrink-0 items-center gap-2 border-b border-hairline bg-raised px-4 text-label text-ink lg:hidden"
				@click="drawer = true"
			>
				<NIcon name="menu" :size="18" />
				NaArNi Service
			</button>

			<main class="min-h-0 flex-1 overflow-y-auto">
				<slot />
			</main>
		</div>
	</div>
</template>

<script setup>
import { ref, watch } from "vue";
import { useRoute } from "vue-router";
import AppSidebar from "./AppSidebar.vue";
import { NIcon } from "../ui/index.js";
import { useSession } from "../composables/useSession.js";

const { user, roles, logout } = useSession();
const drawer = ref(false);
const route = useRoute();

watch(
	() => route.fullPath,
	() => (drawer.value = false)
);
</script>
