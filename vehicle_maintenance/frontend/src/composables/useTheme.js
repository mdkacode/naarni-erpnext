/**
 * Theme preference: light, dark, or follow the system.
 *
 * "System" is the default and is represented by the *absence* of a `data-theme`
 * attribute, so the token layer's `prefers-color-scheme` block does the work.
 * Pinning a theme stamps the attribute, which wins in both directions.
 */
import { ref } from "vue";

const KEY = "ndl-theme";
const MODES = ["system", "light", "dark"];

const mode = ref(readStored());

function readStored() {
	try {
		const v = localStorage.getItem(KEY);
		return MODES.includes(v) ? v : "system";
	} catch {
		return "system";
	}
}

function apply(next) {
	const root = document.documentElement;
	if (next === "system") root.removeAttribute("data-theme");
	else root.setAttribute("data-theme", next);
}

export function initTheme() {
	apply(mode.value);
}

export function useTheme() {
	function setMode(next) {
		if (!MODES.includes(next)) return;
		mode.value = next;
		apply(next);
		try {
			localStorage.setItem(KEY, next);
		} catch {
			/* a locked-down browser is not a reason to fail to render */
		}
	}

	function cycle() {
		setMode(MODES[(MODES.indexOf(mode.value) + 1) % MODES.length]);
	}

	return { mode, setMode, cycle, MODES };
}
