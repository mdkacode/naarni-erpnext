<!--
  Sign in.

  The one screen in the product with no content to compete with, and therefore
  the one screen where the brand gets the floor: a single accent mark, and
  nothing else coloured. Comfortable density — this is a form, not a table, and
  it is often filled in on a phone.
-->
<template>
	<div class="flex min-h-screen items-center justify-center bg-canvas px-4" data-density="comfortable">
		<div class="w-full max-w-[22rem]">
			<div class="mb-6 flex flex-col items-center gap-3 text-center">
				<span class="flex h-12 w-12 items-center justify-center rounded-lg bg-accent text-accent-on">
					<NIcon name="bus" :size="24" />
				</span>
				<div>
					<h1 class="text-title-lg text-ink">NaArNi Service</h1>
					<p class="text-body-sm text-muted">Sign in to your depot account</p>
				</div>
			</div>

			<form
				class="space-y-4 rounded-lg border border-hairline bg-raised p-5"
				@submit.prevent="handleLogin"
			>
				<NInput
					:model-value="phone"
					label="Mobile number"
					type="tel"
					inputmode="numeric"
					maxlength="10"
					autocomplete="tel"
					prefix="+91"
					placeholder="10-digit mobile number"
					required
					:hint="phoneHint"
					:error="phoneError"
					@update:model-value="onPhoneInput"
				/>

				<NInput
					v-model="password"
					label="Password"
					type="password"
					autocomplete="current-password"
					required
				/>

				<NAlert v-if="error" semantic="critical" :body="error" />

				<NButton
					type="submit"
					variant="primary"
					size="lg"
					block
					:loading="submitting"
					:disabled="!isPhoneValid || !password"
				>
					Sign in
				</NButton>
			</form>
		</div>
	</div>
</template>

<script setup>
import { computed, ref } from "vue";
import { useRoute } from "vue-router";
import { NIcon, NInput, NButton, NAlert } from "../ui/index.js";

const route = useRoute();
const phone = ref("");
const password = ref("");
const error = ref("");
const submitting = ref(false);

const PHONE_PATTERN = /^[6-9]\d{9}$/;
const isPhoneValid = computed(() => PHONE_PATTERN.test(phone.value));

function onPhoneInput(value) {
	phone.value = String(value || "")
		.replace(/\D+/g, "")
		.slice(0, 10);
}

/* The error is only shown once there are ten digits to be wrong about. Marking a
   half-typed number invalid tells the reader they have made a mistake while they
   are still in the middle of not making one. */
const phoneError = computed(() =>
	phone.value.length === 10 && !isPhoneValid.value ? "Indian mobile numbers start with 6, 7, 8 or 9." : ""
);

const phoneHint = computed(() => {
	if (phoneError.value) return "";
	if (!phone.value) return "The number registered with your depot.";
	if (phone.value.length < 10) return `${phone.value.length} of 10 digits.`;
	return "";
});

async function handleLogin() {
	error.value = "";
	if (!isPhoneValid.value) {
		error.value = "Enter a valid 10-digit mobile number.";
		return;
	}

	submitting.value = true;
	try {
		const res = await fetch("/api/method/vehicle_maintenance.api.auth.login_with_phone", {
			method: "POST",
			headers: { "Content-Type": "application/x-www-form-urlencoded" },
			body: `phone=${encodeURIComponent(phone.value)}&password=${encodeURIComponent(password.value)}`,
		});
		const data = await res.json();
		if (!res.ok || data.exc_type) throw new Error(data.message || "Login failed");
		// Full reload so useSession picks up the new cookie cleanly
		window.location.href = route.query.redirect || "/service-portal";
	} catch {
		error.value = "That mobile number and password did not match an account.";
	} finally {
		submitting.value = false;
	}
}
</script>
