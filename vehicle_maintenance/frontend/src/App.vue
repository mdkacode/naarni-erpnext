<!--
  App — chrome or no chrome, and the one place the toaster is mounted.

  Sign-in has no sidebar: there is nothing to navigate to yet, and a nav rail
  next to a login form is a promise the page cannot keep.
-->
<template>
	<AppShell v-if="isLoggedIn">
		<router-view />
	</AppShell>
	<router-view v-else />
	<NToaster />
</template>

<script setup>
import AppShell from "./components/AppShell.vue";
import { NToaster } from "./ui/index.js";
import { useSession } from "./composables/useSession.js";

const { isLoggedIn } = useSession();
</script>
