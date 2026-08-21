/**
 * NaArNi Design Language — transient messages.
 *
 * A toast is for something that has *already happened* and needs no decision:
 * "Job card created", "Copied". Anything the reader must choose about belongs in
 * a dialog or an inline NAlert, because a toast that carries a decision will be
 * missed by the one person who needed to make it.
 *
 * Errors that block work do not auto-dismiss.
 */
import { ref } from "vue";

export const toasts = ref([]);
let seq = 0;

function push(semantic, message, options = {}) {
	const id = ++seq;
	const life = options.duration ?? (semantic === "critical" ? 0 : 5000);
	toasts.value = [...toasts.value.slice(-2), { id, semantic, message, action: options.action || null }];
	if (life > 0) setTimeout(() => dismiss(id), life);
	return id;
}

export function dismiss(id) {
	toasts.value = toasts.value.filter((t) => t.id !== id);
}

export const toast = {
	success: (message, options) => push("positive", message, options),
	warn: (message, options) => push("caution", message, options),
	error: (message, options) => push("critical", message, options),
	info: (message, options) => push("active", message, options),
};

export default toast;
