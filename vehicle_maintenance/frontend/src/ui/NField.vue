<!--
  NField — label, control, and *one* line beneath it.

  Helper or error, never both: two lines of guidance under a field is two things
  to read at the moment the reader wants to type. When there is an error, the
  error is the only thing that matters.

  Required is marked; optional is not. In this product most fields are required,
  so marking the exception carries more information than marking the rule.
-->
<template>
	<div class="flex flex-col gap-1.5">
		<label v-if="label" :for="id" class="flex items-center gap-1 text-label text-ink">
			{{ label }}
			<span v-if="required" class="text-critical" aria-hidden="true">*</span>
			<span v-if="required" class="sr-only-ndl">required</span>
		</label>

		<slot :id="id" :described-by="describedBy" :invalid="Boolean(error)" />

		<p
			v-if="error"
			:id="`${id}-msg`"
			class="flex items-start gap-1 text-caption text-critical"
			role="alert"
		>
			<NIcon name="circle-alert" :size="13" class="mt-px" />
			{{ error }}
		</p>
		<p v-else-if="hint" :id="`${id}-msg`" class="text-caption text-muted">{{ hint }}</p>
	</div>
</template>

<script setup>
import { computed, useId } from "vue";
import NIcon from "./NIcon.vue";

const props = defineProps({
	label: { type: String, default: "" },
	hint: { type: String, default: "" },
	error: { type: String, default: "" },
	required: { type: Boolean, default: false },
	fieldId: { type: String, default: "" },
});

const auto = useId();
const id = computed(() => props.fieldId || `f-${auto}`);
const describedBy = computed(() => (props.error || props.hint ? `${id.value}-msg` : undefined));
</script>
