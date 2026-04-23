<template>
  <div class="min-h-screen flex flex-col">
    <NavBar v-if="isLoggedIn" />
    <main class="flex-1">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed } from "vue";
import { useSession } from "./composables/useSession.js";
import NavBar from "./components/NavBar.vue";

const { isLoggedIn } = useSession();
</script>
