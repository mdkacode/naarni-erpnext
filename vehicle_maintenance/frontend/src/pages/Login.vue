<template>
  <div class="min-h-screen flex items-center justify-center bg-gray-50 px-4">
    <div class="w-full max-w-sm">
      <h1 class="text-2xl font-bold text-center text-gray-900 mb-8">
        NaArNi Service Portal Login
      </h1>
      <form @submit.prevent="handleLogin" class="bg-white shadow rounded-xl p-6 space-y-4">
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Phone Number</label>
          <div class="flex items-stretch">
            <span class="inline-flex items-center px-3 text-sm text-gray-500 bg-gray-50 border border-r-0 border-gray-300 rounded-l-lg">
              +91
            </span>
            <input
              :value="phone"
              @input="onPhoneInput"
              type="tel"
              inputmode="numeric"
              maxlength="10"
              pattern="[6-9][0-9]{9}"
              required
              class="flex-1 px-3 py-2 border border-gray-300 rounded-r-lg focus:ring-2 focus:ring-brand-500 focus:border-brand-500 tracking-wider"
              placeholder="10-digit mobile number"
              autocomplete="tel"
              aria-describedby="phone-hint"
            />
          </div>
          <p
            id="phone-hint"
            class="mt-1 text-xs"
            :class="phoneHint.tone"
          >
            {{ phoneHint.text }}
          </p>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Password</label>
          <input
            v-model="password"
            type="password"
            required
            class="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-brand-500 focus:border-brand-500"
          />
        </div>
        <div v-if="error" class="text-sm text-red-600">{{ error }}</div>
        <button
          type="submit"
          :disabled="submitting || !isPhoneValid || !password"
          class="w-full py-2.5 px-4 text-sm font-medium text-white bg-brand-600 rounded-lg hover:bg-brand-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {{ submitting ? "Signing in..." : "Sign In" }}
        </button>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from "vue";
import { useRoute } from "vue-router";

const route = useRoute();
const phone = ref("");
const password = ref("");
const error = ref("");
const submitting = ref(false);

// ── Phone input: strip non-digits, cap at 10, validate Indian mobile ──
const PHONE_PATTERN = /^[6-9]\d{9}$/;

function onPhoneInput(event) {
  const digitsOnly = (event.target.value || "").replace(/\D+/g, "").slice(0, 10);
  phone.value = digitsOnly;
  // Sync the DOM value in case it differed (e.g., user pasted a formatted number)
  if (event.target.value !== digitsOnly) {
    event.target.value = digitsOnly;
  }
}

const isPhoneValid = computed(() => PHONE_PATTERN.test(phone.value));

const phoneHint = computed(() => {
  if (!phone.value) {
    return { tone: "text-gray-400", text: "We'll send the OTP to this number." };
  }
  if (phone.value.length < 10) {
    return {
      tone: "text-gray-400",
      text: `${phone.value.length}/10 digits entered.`,
    };
  }
  if (!isPhoneValid.value) {
    return {
      tone: "text-red-600",
      text: "Indian mobile numbers start with 6, 7, 8 or 9.",
    };
  }
  return { tone: "text-green-600", text: "Looks good." };
});

async function handleLogin() {
  error.value = "";

  if (!isPhoneValid.value) {
    error.value = "Enter a valid 10-digit mobile number (starting with 6/7/8/9).";
    return;
  }

  submitting.value = true;
  try {
    const usr = phone.value.includes("@") ? phone.value : `${phone.value}@test.localhost`;
    const res = await fetch("/api/method/login", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: `usr=${encodeURIComponent(usr)}&pwd=${encodeURIComponent(password.value)}`,
    });
    const data = await res.json();
    if (!res.ok || data.exc_type) {
      throw new Error(data.message || "Login failed");
    }
    // Full reload so useSession picks up the new cookie cleanly
    const redirect = route.query.redirect || "/service-portal";
    window.location.href = redirect;
  } catch (e) {
    error.value = "Invalid phone number or password.";
  } finally {
    submitting.value = false;
  }
}
</script>
