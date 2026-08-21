/**
 * NaArNi Design Language — the one place a domain value becomes a colour.
 *
 * Workflow state, priority, severity and a health score all answer the same
 * question for the reader: is this fine, moving, waiting, broken, or not yet
 * started? They therefore share one five-step ramp (see DESIGN_LANGUAGE.md §3.3).
 *
 * The web previously ran eight named status hues — indigo, amber, violet, pink,
 * cyan, orange, green, red — which asked the reader to memorise a legend, and
 * gave "Urgent" on a job card and "Critical" on an alert two different reds even
 * though they mean the same thing to whoever is looking at them.
 *
 * Adding a workflow state means adding a row here. It does not mean picking a
 * colour in a template.
 */

/** The five meanings. Every helper below returns one of these strings. */
export const SEMANTICS = ["positive", "caution", "critical", "active", "idle"];

/** Tailwind classes per semantic, so a component never assembles a class name. */
const CLASSES = {
	positive: {
		text: "text-positive",
		bg: "bg-positive",
		tint: "bg-positive-tint",
		border: "border-positive",
	},
	caution: { text: "text-caution", bg: "bg-caution", tint: "bg-caution-tint", border: "border-caution" },
	critical: {
		text: "text-critical",
		bg: "bg-critical",
		tint: "bg-critical-tint",
		border: "border-critical",
	},
	active: { text: "text-active", bg: "bg-active", tint: "bg-active-tint", border: "border-active" },
	idle: { text: "text-idle", bg: "bg-idle", tint: "bg-idle-tint", border: "border-idle" },
};

export function semanticClasses(semantic) {
	return CLASSES[semantic] || CLASSES.idle;
}

// ── Workflow state ─────────────────────────────────────────────────────────

const STATE_SEMANTIC = {
	draft: "idle",
	open: "active",
	wip: "active",
	"work in progress": "active",
	"in progress": "active",
	"parts fitted": "active",
	"closure from technician": "active",
	"awaiting customer approval": "caution",
	"awaiting parts": "caution",
	"verification pending": "caution",
	"on hold": "caution",
	closed: "positive",
	completed: "positive",
	approved: "positive",
	reopened: "critical",
	rejected: "critical",
	cancelled: "critical",
};

/**
 * The word a person reads. Workflow enums are storage, not language: `WIP` is a
 * column value, "In progress" is what the state actually is.
 */
const STATE_LABEL = {
	WIP: "In progress",
	"Awaiting Customer Approval": "Awaiting approval",
	"Awaiting Parts": "Awaiting parts",
	"Parts Fitted": "Parts fitted",
	"Verification Pending": "Verification",
	"Closure from Technician": "Technician closed",
};

export function stateSemantic(state) {
	return (
		STATE_SEMANTIC[
			String(state || "")
				.toLowerCase()
				.trim()
		] || "idle"
	);
}

export function stateLabel(state) {
	if (!state) return "—";
	if (STATE_LABEL[state]) return STATE_LABEL[state];
	// Sentence case a raw enum rather than shouting it.
	const s = String(state).replace(/[_-]+/g, " ").trim();
	return s.charAt(0).toUpperCase() + s.slice(1);
}

// ── Priority / severity ────────────────────────────────────────────────────

const PRIORITY_SEMANTIC = {
	urgent: "critical",
	critical: "critical",
	high: "caution",
	major: "caution",
	medium: "caution",
	warning: "caution",
	normal: "idle",
	low: "positive",
	minor: "positive",
};

export function prioritySemantic(value) {
	return (
		PRIORITY_SEMANTIC[
			String(value || "")
				.toLowerCase()
				.trim()
		] || "idle"
	);
}

/** Severity shares the ramp with priority on purpose — see the module header. */
export const severitySemantic = prioritySemantic;

// ── Work items ─────────────────────────────────────────────────────────────

/**
 * A line item on a job card. "Parts requested" is `caution` and "parts issued"
 * is `active` for a real reason: a request is waiting on somebody else, and work
 * that is waiting on somebody else is the thing a depot manager needs to see.
 */
const ITEM_SEMANTIC = {
	completed: "positive",
	done: "positive",
	"parts issued": "active",
	"parts allocated": "active",
	"in progress": "active",
	"parts requested": "caution",
	pending: "idle",
	"not started": "idle",
	"customer rejected": "critical",
	rejected: "critical",
};

export function itemStatusSemantic(status) {
	return (
		ITEM_SEMANTIC[
			String(status || "")
				.toLowerCase()
				.trim()
		] || "idle"
	);
}

// ── Scores ─────────────────────────────────────────────────────────────────

/**
 * A health / PMS score. The thresholds live here rather than in three separate
 * `healthBarColor()` copies across three pages, which is how the customer
 * dashboard and the health card came to disagree about what "good" was.
 */
export function scoreSemantic(score) {
	const n = Number(score);
	if (!Number.isFinite(n)) return "idle";
	if (n >= 80) return "positive";
	if (n >= 60) return "caution";
	return "critical";
}

/** Turnaround time in hours, against the depot target. */
export function tatSemantic(hours) {
	const n = Number(hours);
	if (!Number.isFinite(n)) return "idle";
	if (n <= 4) return "positive";
	if (n <= 8) return "caution";
	return "critical";
}

/**
 * A queued dispatch — a reminder, an email, an SMS. Scheduled is `active`
 * because it is work in flight, not a warning: nothing has gone wrong with a
 * reminder that has simply not fired yet.
 */
const DISPATCH_SEMANTIC = {
	scheduled: "active",
	queued: "active",
	sending: "active",
	sent: "positive",
	delivered: "positive",
	failed: "critical",
	bounced: "critical",
	cancelled: "idle",
};

export function dispatchSemantic(status) {
	return (
		DISPATCH_SEMANTIC[
			String(status || "")
				.toLowerCase()
				.trim()
		] || "idle"
	);
}

/** SLA: a boolean flag, but the reader needs a word, not a colour. */
export function slaSemantic(breached) {
	return breached ? "critical" : "positive";
}
