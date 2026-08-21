/**
 * NaArNi Design Language — the one rendering of each fact.
 *
 * Every page in the old frontend carried its own `formatCurrency`,
 * `formatDateShort` and `formatVehicle`. They had already drifted: two of them
 * rendered money compactly and two did not, and one rendered a date the browser
 * default way. A fact that looks different on two screens is a fact the reader
 * has to re-read.
 *
 * See DESIGN_LANGUAGE.md §7. A page that formats a value itself is a bug.
 */

const LOCALE = "en-IN";

const inr = new Intl.NumberFormat(LOCALE, {
	style: "currency",
	currency: "INR",
	maximumFractionDigits: 0,
});

const inrCompact = new Intl.NumberFormat(LOCALE, {
	style: "currency",
	currency: "INR",
	notation: "compact",
	maximumFractionDigits: 1,
});

const num = new Intl.NumberFormat(LOCALE, { maximumFractionDigits: 0 });

/** The em dash every empty value renders as. Never an empty cell, never "N/A". */
export const EMPTY = "—";

function parseDate(value) {
	if (!value) return null;
	if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;
	// Frappe hands back "2026-08-21 14:05:32"; Safari will not parse that.
	const d = new Date(String(value).replace(" ", "T"));
	return Number.isNaN(d.getTime()) ? null : d;
}

export const fmt = {
	/** `₹1,24,500`. Zero renders as `₹0`; null renders as an em dash, because
	 *  "we have not been told" and "it is nothing" are different facts. */
	money(value) {
		if (value === null || value === undefined || value === "") return EMPTY;
		return inr.format(Number(value) || 0);
	},

	/** `₹1.2L` — stat tiles only, where the exact rupee is not the point. */
	moneyShort(value) {
		if (value === null || value === undefined || value === "") return EMPTY;
		return inrCompact.format(Number(value) || 0);
	},

	/** `1,24,500` */
	number(value) {
		if (value === null || value === undefined || value === "") return EMPTY;
		return num.format(Number(value) || 0);
	},

	/** `1,24,500 km` */
	distance(value, unit = "km") {
		if (value === null || value === undefined || value === "") return EMPTY;
		return `${num.format(Number(value) || 0)} ${unit}`;
	},

	/** `82%` — integer, always. A health score to one decimal is false precision. */
	percent(value) {
		if (value === null || value === undefined || value === "") return EMPTY;
		return `${Math.round(Number(value) || 0)}%`;
	},

	/** `12 Aug 2026`, dropping the year when it is the current one. */
	date(value) {
		const d = parseDate(value);
		if (!d) return EMPTY;
		const sameYear = d.getFullYear() === new Date().getFullYear();
		return d.toLocaleDateString(LOCALE, {
			day: "numeric",
			month: "short",
			...(sameYear ? {} : { year: "numeric" }),
		});
	},

	/** `14:05` — 24-hour, because a depot runs on a 24-hour clock. */
	time(value) {
		const d = parseDate(value);
		if (!d) return EMPTY;
		return d.toLocaleTimeString(LOCALE, { hour: "2-digit", minute: "2-digit", hour12: false });
	},

	/** `12 Aug, 14:05` */
	dateTime(value) {
		const d = parseDate(value);
		if (!d) return EMPTY;
		return `${fmt.date(value)}, ${fmt.time(value)}`;
	},

	/**
	 * `4h ago` for the last week, then the absolute date. Past seven days a
	 * relative time stops being an orientation and starts being arithmetic
	 * homework — "37 days ago" tells nobody when it happened.
	 */
	since(value) {
		const d = parseDate(value);
		if (!d) return EMPTY;
		const mins = Math.floor((Date.now() - d.getTime()) / 60000);
		if (mins < 1) return "just now";
		if (mins < 60) return `${mins}m ago`;
		const hours = Math.floor(mins / 60);
		if (hours < 24) return `${hours}h ago`;
		const days = Math.floor(hours / 24);
		if (days < 7) return `${days}d ago`;
		return fmt.date(value);
	},

	/** `4h 20m`, never `4.33 hrs`. */
	duration(hours) {
		const n = Number(hours);
		if (!Number.isFinite(n)) return EMPTY;
		const h = Math.floor(n);
		const m = Math.round((n - h) * 60);
		if (h === 0) return `${m}m`;
		return m === 0 ? `${h}h` : `${h}h ${m}m`;
	},

	/**
	 * `TN 01 AB 1234` — grouped into its four parts so the eye can land on the
	 * serial. `VehicleNumber` renders the last group heavier; this is the plain
	 * string for places that cannot take markup (a title attribute, an export).
	 */
	vehicle(value) {
		if (!value) return EMPTY;
		const clean = String(value).replace(/[-\s]/g, "").toUpperCase();
		const m = clean.match(/^([A-Z]{2})(\d{1,2})([A-Z]{1,3})(\d{1,4})$/);
		return m ? `${m[1]} ${m[2].padStart(2, "0")} ${m[3]} ${m[4]}` : String(value);
	},

	/** Splits a registration into `{ prefix, serial }` for the two-weight render. */
	vehicleParts(value) {
		const grouped = fmt.vehicle(value);
		if (grouped === EMPTY) return { prefix: "", serial: EMPTY };
		const cut = grouped.lastIndexOf(" ");
		if (cut < 0) return { prefix: "", serial: grouped };
		return { prefix: grouped.slice(0, cut + 1), serial: grouped.slice(cut + 1) };
	},

	/** A person. Falls back to the part of an email before the @, never to blank. */
	person(value) {
		if (!value) return EMPTY;
		const s = String(value);
		return s.includes("@") ? s.split("@")[0] : s;
	},

	/** Initials for an avatar: at most two, always uppercase. */
	initials(value) {
		const s = fmt.person(value);
		if (s === EMPTY) return "?";
		const parts = s
			.replace(/[._-]+/g, " ")
			.trim()
			.split(/\s+/);
		return (parts[0][0] + (parts[1]?.[0] || "")).toUpperCase();
	},

	/** Anything that might be absent. `fmt.or(v)` beats `v || "—"` at the call site. */
	or(value, fallback = EMPTY) {
		return value === null || value === undefined || value === "" ? fallback : value;
	},
};

export default fmt;
