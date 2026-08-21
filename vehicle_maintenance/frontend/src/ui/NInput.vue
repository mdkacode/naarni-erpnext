<!--
  NInput — a text control on a sunken ground.

  Errors change the border *and* say what is wrong; a red outline on its own is
  not a message. Focus is the product-wide accent ring, never a border colour
  swap, so it survives being looked at by someone who cannot separate the hues.
-->
<template>
	<NField
		v-slot="{ id, describedBy, invalid }"
		:label="label"
		:hint="hint"
		:error="error"
		:required="required"
	>
		<div class="relative flex items-stretch">
			<span
				v-if="prefix"
				class="inline-flex shrink-0 items-center rounded-l-sm border border-r-0 border-line bg-sunken px-2.5 text-body-sm text-muted"
				>{{ prefix }}</span
			>
			<span
				v-if="icon"
				class="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-subtle"
			>
				<NIcon :name="icon" :size="15" />
			</span>
			<input
				:id="id"
				:value="modelValue"
				:type="type"
				:inputmode="inputmode || undefined"
				:placeholder="placeholder"
				:maxlength="maxlength || undefined"
				:autocomplete="autocomplete || undefined"
				:disabled="disabled"
				:readonly="readonly"
				:required="required"
				:aria-describedby="describedBy"
				:aria-invalid="invalid || undefined"
				class="h-control w-full rounded-sm border bg-sunken text-body text-ink transition-colors duration-instant placeholder:text-subtle focus:bg-raised disabled:cursor-not-allowed disabled:opacity-55"
				:class="[
					invalid ? 'border-critical' : 'border-line',
					prefix ? 'rounded-l-none' : '',
					icon ? 'pl-8 pr-2.5' : 'px-2.5',
				]"
				@input="$emit('update:modelValue', $event.target.value)"
				@blur="$emit('blur', $event)"
			/>
			<div v-if="$slots.suffix" class="absolute right-1 top-1/2 -translate-y-1/2">
				<slot name="suffix" />
			</div>
		</div>
	</NField>
</template>

<script setup>
import NField from "./NField.vue";
import NIcon from "./NIcon.vue";

defineProps({
	modelValue: { type: [String, Number], default: "" },
	label: { type: String, default: "" },
	type: { type: String, default: "text" },
	inputmode: { type: String, default: "" },
	placeholder: { type: String, default: "" },
	hint: { type: String, default: "" },
	error: { type: String, default: "" },
	icon: { type: String, default: "" },
	prefix: { type: String, default: "" },
	maxlength: { type: [String, Number], default: "" },
	autocomplete: { type: String, default: "" },
	required: { type: Boolean, default: false },
	disabled: { type: Boolean, default: false },
	readonly: { type: Boolean, default: false },
});
defineEmits(["update:modelValue", "blur"]);
</script>
