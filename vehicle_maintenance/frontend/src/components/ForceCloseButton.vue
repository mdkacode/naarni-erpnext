<!--
  ForceCloseButton — closing a card that cannot complete normally.

  Genuinely destructive to the record's meaning, so: a danger button, a named
  confirm, a mandatory reason, and the severity's authority shown *next to the
  choice* rather than discovered when the server refuses. The backend re-checks
  the severity against the caller's roles — this dialog is a hint, not the gate.
-->
<template>
	<div>
		<NButton
			variant="secondary"
			icon="circle-x"
			:disabled="disabled"
			class="text-critical"
			@click="open = true"
		>
			Force close
		</NButton>

		<NDialog v-model="open" title="Force close job card" size="sm" persistent @close="reset">
			<div class="space-y-4">
				<p class="text-body-sm text-muted">
					Pick the severity that describes why this card cannot complete normally.
				</p>

				<div class="space-y-2" role="radiogroup" aria-label="Severity">
					<button
						v-for="s in severities"
						:key="s.value"
						type="button"
						role="radio"
						:aria-checked="severity === s.value"
						class="w-full rounded-md border p-3 text-left transition-colors duration-instant focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
						:class="
							severity === s.value
								? `${semanticClasses(s.semantic).border} ${semanticClasses(s.semantic).tint}`
								: 'border-hairline bg-raised hover:bg-sunken'
						"
						@click="severity = s.value"
					>
						<div class="flex items-baseline gap-2">
							<span
								class="text-title-sm"
								:class="severity === s.value ? semanticClasses(s.semantic).text : 'text-ink'"
							>
								{{ s.label }}
							</span>
							<span class="ml-auto text-caption text-muted">{{ s.authority }}</span>
						</div>
						<p class="mt-0.5 text-body-sm text-muted">{{ s.description }}</p>
					</button>
				</div>

				<NTextarea
					v-model="reason"
					label="What prevented normal closure?"
					required
					:rows="3"
					placeholder="Inventory unavailable, issue resolved on its own…"
				/>

				<NAlert v-if="error" semantic="critical" :body="error" />
			</div>

			<template #actions>
				<NButton @click="open = false">Cancel</NButton>
				<NButton variant="danger" :loading="submitting" :disabled="!canSubmit" @click="submit">
					Force close
				</NButton>
			</template>
		</NDialog>
	</div>
</template>

<script setup>
import { computed, ref } from "vue";
import { NButton, NDialog, NTextarea, NAlert, semanticClasses } from "../ui/index.js";

defineProps({ disabled: { type: Boolean, default: false } });
const emit = defineEmits(["submit"]);

const open = ref(false);
const severity = ref("");
const reason = ref("");
const submitting = ref(false);
const error = ref("");

const severities = [
	{
		value: "Minor",
		label: "Minor",
		semantic: "caution",
		description: "Cosmetic or minor functional issue. Safe to defer to the next PMS.",
		authority: "SE can force close",
	},
	{
		value: "Major",
		label: "Major",
		semantic: "caution",
		description: "Operational but with a significant issue. Needs follow-up within 24 hours.",
		authority: "DM or aftersales approval",
	},
	{
		value: "Critical",
		label: "Critical",
		semantic: "critical",
		description: "Vehicle non-operational or a safety risk. A follow-up card is raised in 24 hours.",
		authority: "Maintenance head only",
	},
];

const canSubmit = computed(() => severity.value && reason.value.trim().length > 0);

function reset() {
	severity.value = "";
	reason.value = "";
	error.value = "";
	submitting.value = false;
}

async function submit() {
	if (!canSubmit.value) return;
	submitting.value = true;
	error.value = "";
	try {
		await emit("submit", { severity: severity.value, reason: reason.value.trim() });
		open.value = false;
		reset();
	} catch (e) {
		error.value = e?.message || "Force close failed. Try again.";
		submitting.value = false;
	}
}
</script>
