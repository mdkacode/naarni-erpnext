<template>
	<!--
    PhotoSlot — single photo upload slot wired to /api/method/upload_file +
    onboarding.attach_before_photos. Persists the file_url server-side and
    emits the final URL.
  -->
	<div class="border border-gray-200 rounded-xl p-3 bg-white">
		<div class="flex items-center justify-between mb-2">
			<p class="text-sm font-medium text-gray-800">{{ label }}</p>
			<span v-if="currentUrl" class="text-[10px] uppercase tracking-wide text-green-600">Uploaded</span>
		</div>

		<div v-if="currentUrl" class="relative">
			<img :src="currentUrl" :alt="label" class="w-full h-40 object-cover rounded-lg" />
			<button
				type="button"
				@click="trigger"
				class="absolute bottom-2 right-2 px-2 py-1 text-[10px] bg-white/90 border border-gray-200 rounded-md"
			>
				Replace
			</button>
		</div>

		<button
			v-else
			type="button"
			@click="trigger"
			class="w-full h-40 border-2 border-dashed border-gray-300 rounded-lg text-sm text-gray-500 hover:bg-gray-50 flex items-center justify-center"
		>
			<span v-if="!uploading">Tap to capture / upload</span>
			<span v-else>Uploading… {{ progressLabel }}</span>
		</button>

		<input
			ref="fileInput"
			type="file"
			accept="image/*"
			capture="environment"
			class="hidden"
			@change="onPick"
		/>
		<p v-if="error" class="mt-2 text-xs text-red-600">{{ error }}</p>
	</div>
</template>

<script setup>
import { ref, computed } from "vue";
import { uploadFile } from "../composables/useFileUpload.js";
import { onboardingApi } from "../composables/useOnboardingApi.js";

const props = defineProps({
	label: { type: String, required: true },
	jobCard: { type: String, required: true },
	field: { type: String, required: true }, // "chassis_photo" | "odometer_photo"
	existingUrl: { type: String, default: "" },
});
const emit = defineEmits(["uploaded"]);

const fileInput = ref(null);
const uploading = ref(false);
const error = ref("");
const currentUrl = ref(props.existingUrl || "");
const progress = ref(0);

const progressLabel = computed(() => (progress.value > 0 ? `${progress.value}%` : ""));

function trigger() {
	fileInput.value?.click();
}

async function onPick(e) {
	const file = e.target?.files?.[0];
	if (!file) return;
	uploading.value = true;
	error.value = "";
	progress.value = 5;
	try {
		const res = await uploadFile(file, {
			doctype: "Job Card",
			docname: props.jobCard,
			fieldname: props.field,
		});
		progress.value = 60;
		if (!res.url) throw new Error("Upload returned no URL");

		// Persist URL on the JC via the typed onboarding endpoint (it runs the
		// permission check + permlevel-aware save).
		const attachArgs = { chassis_photo: undefined, odometer_photo: undefined };
		attachArgs[props.field] = res.url;
		await onboardingApi.attachPhotos(props.jobCard, attachArgs.chassis_photo, attachArgs.odometer_photo);

		progress.value = 100;
		currentUrl.value = res.url;
		emit("uploaded", res.url);
	} catch (e) {
		error.value = e?.message || "Upload failed";
	} finally {
		uploading.value = false;
		if (fileInput.value) fileInput.value.value = "";
	}
}
</script>
