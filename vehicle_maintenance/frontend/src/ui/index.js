/**
 * NaArNi Design Language — the kit.
 *
 * If a screen needs a shape that is not here, the shape belongs here first.
 * A variant built inside a page file is how a design system dies: it works, it
 * ships, and six weeks later there are four spellings of the same card.
 *
 * See design/DESIGN_LANGUAGE.md §5 for what each of these is for.
 */

// Foundations
export { fmt, EMPTY } from "./format.js";
export {
	SEMANTICS,
	semanticClasses,
	stateSemantic,
	stateLabel,
	prioritySemantic,
	severitySemantic,
	scoreSemantic,
	tatSemantic,
	slaSemantic,
	dispatchSemantic,
	itemStatusSemantic,
} from "./semantic.js";
export { toast, toasts, dismiss } from "./toast.js";
export { ICONS } from "./icons.js";

// Primitives
export { default as NIcon } from "./NIcon.vue";
export { default as NSpinner } from "./NSpinner.vue";
export { default as NButton } from "./NButton.vue";
export { default as NIconButton } from "./NIconButton.vue";

// State
export { default as NBadge } from "./NBadge.vue";
export { default as NStatus } from "./NStatus.vue";
export { default as NPriority } from "./NPriority.vue";
export { default as NAlert } from "./NAlert.vue";

// Surfaces
export { default as NCard } from "./NCard.vue";
export { default as NSection } from "./NSection.vue";
export { default as NStat } from "./NStat.vue";
export { default as NDialog } from "./NDialog.vue";
export { default as NToaster } from "./NToaster.vue";

// Data
export { default as NTable } from "./NTable.vue";
export { default as NKeyValue } from "./NKeyValue.vue";
export { default as NMeter } from "./NMeter.vue";
export { default as NVehicle } from "./NVehicle.vue";
export { default as NRecordId } from "./NRecordId.vue";
export { default as NAvatar } from "./NAvatar.vue";
export { default as NTimestamp } from "./NTimestamp.vue";
export { default as NTimeline } from "./NTimeline.vue";

// Navigation & chrome
export { default as NPageHeader } from "./NPageHeader.vue";
export { default as NTabs } from "./NTabs.vue";
export { default as NSegmented } from "./NSegmented.vue";
export { default as NStepper } from "./NStepper.vue";

// Input
export { default as NField } from "./NField.vue";
export { default as NInput } from "./NInput.vue";
export { default as NSelect } from "./NSelect.vue";
export { default as NTextarea } from "./NTextarea.vue";
export { default as NSearch } from "./NSearch.vue";
export { default as NMultiSelect } from "./NMultiSelect.vue";
export { default as NCombobox } from "./NCombobox.vue";
export { default as NChoice } from "./NChoice.vue";
export { default as NFileField } from "./NFileField.vue";
export { default as NRating } from "./NRating.vue";
export { default as NCheckbox } from "./NCheckbox.vue";

// Feedback
export { default as NEmptyState } from "./NEmptyState.vue";
export { default as NSkeleton } from "./NSkeleton.vue";
