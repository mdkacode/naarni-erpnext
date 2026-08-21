<!--
  NFileField — attach a photo or a document.

  Native file inputs are styled per browser and per platform, so the control is
  a labelled button plus a state line: either "nothing attached" or the file's
  name with a way to remove it. The state line matters more than it looks —
  the evidence photos on a job card were previously confirmed by a green
  "✓ Attached" that never said *which* file, so a technician who picked the
  wrong image had no way to notice.
-->
<template>
	<div class="space-y-1.5">
		<label :for="id" class="flex items-center gap-1 text-label text-ink">
			{{ label }}
			<span v-if="required" class="text-critical" aria-hidden="true">*</span>
		</label>

		<div class="flex items-center gap-2">
			<label
				:for="id"
				class="inline-flex h-control cursor-pointer items-center gap-1.5 rounded-sm border border-line bg-raised px-3 text-label text-ink transition-colors duration-instant hover:bg-sunken"
				:class="disabled && 'pointer-events-none opacity-50'"
			>
				<NIcon :name="icon" :size="15" />
				{{ fileName ? "Replace" : buttonLabel }}
			</label>

			<span v-if="fileName" class="flex min-w-0 items-center gap-1.5 text-body-sm text-positive">
				<NIcon name="circle-check" :size="14" />
				<span class="truncate">{{ fileName }}</span>
				<NIconButton icon="x" label="Remove file" size="sm" @click="clear" />
			</span>
			<span v-else class="text-body-sm text-subtle">Nothing attached</span>
		</div>

		<input
			:id="id"
			ref="input"
			type="file"
			:accept="accept"
			:disabled="disabled"
			class="sr-only-ndl"
			@change="onChange"
		/>

		<p v-if="hint" class="text-caption text-muted">{{ hint }}</p>
	</div>
</template>

<script setup>
import { computed, ref, useId } from "vue";
import NIcon from "./NIcon.vue";
import NIconButton from "./NIconButton.vue";

const props = defineProps({
	/** A File, or a stored URL string, or null. */
	modelValue: { type: [Object, String, null], default: null },
	label: { type: String, default: "" },
	buttonLabel: { type: String, default: "Choose file" },
	accept: { type: String, default: "image/*" },
	icon: { type: String, default: "camera" },
	hint: { type: String, default: "" },
	required: { type: Boolean, default: false },
	disabled: { type: Boolean, default: false },
});
const emit = defineEmits(["update:modelValue"]);

const id = `file-${useId()}`;
const input = ref(null);

const fileName = computed(() => {
	const v = props.modelValue;
	if (!v) return "";
	if (typeof v === "string") return v.split("/").pop();
	return v.name || "Attached";
});

function onChange(event) {
	const file = event.target.files?.[0];
	if (file) emit("update:modelValue", file);
}

function clear() {
	if (input.value) input.value.value = "";
	emit("update:modelValue", null);
}
</script>
