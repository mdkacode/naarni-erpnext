/**
 * NaArNi Design Language — Tailwind binding.
 *
 * Every scale here resolves to a variable in `src/styles/tokens.css`, so the
 * utilities themselves are theme-aware: `bg-raised` is white on the light canvas
 * and #141418 on the dark one without a single `dark:` prefix in a template.
 *
 * Three scales are deliberately *overridden* rather than extended — colour,
 * radius and shadow. That is the enforcement mechanism: the rainbow palette,
 * the blobby `rounded-3xl` and the decorative `shadow-lg` are simply not in the
 * vocabulary any more, and an author reaching for `rounded-lg` or `shadow-md`
 * out of habit still lands on an NDL value.
 *
 * See design/DESIGN_LANGUAGE.md §3 and §9.
 */

/**
 * The frappe-ui Tailwind preset is deliberately NOT used.
 *
 * Its theme plugin extends `textColor.ink`, `fill.ink`, `stroke.ink` and
 * `backgroundColor.surface` with its own nested scales, which *shadow* a
 * same-named entry in `theme.colors` — so declaring `ink` as NDL's body-text
 * colour makes `text-ink` resolve to an object with no DEFAULT and vanish. The
 * preset contributed nothing else this app uses: no frappe-ui component is
 * rendered anywhere in `src/`, and its two real plugins are listed directly
 * below.
 */

/** A colour that carries its own theme, with Tailwind's opacity modifier still working. */
const v = (name) => `var(--ndl-${name})`;

const neutral = {
	0: v("n-0"),
	50: v("n-50"),
	100: v("n-100"),
	200: v("n-200"),
	300: v("n-300"),
	400: v("n-400"),
	500: v("n-500"),
	700: v("n-700"),
	800: v("n-800"),
	850: v("n-850"),
	900: v("n-900"),
	950: v("n-950"),
};

/** @type {import('tailwindcss').Config} */
export default {
	darkMode: ["selector", '[data-theme="dark"]'],
	content: [
		"./index.html",
		"./src/**/*.{vue,js,ts,jsx,tsx}",
		"./node_modules/frappe-ui/src/components/**/*.{vue,js,ts}",
	],
	theme: {
		colors: {
			transparent: "transparent",
			current: "currentColor",
			inherit: "inherit",
			white: "#ffffff",
			black: "#000000",

			// ── Grounds ──────────────────────────────────────────────────────────
			canvas: v("canvas"),
			raised: v("raised"),
			sunken: v("sunken"),
			hairline: v("hairline"),
			line: v("border"),
			overlay: v("overlay"),

			// ── Text ─────────────────────────────────────────────────────────────
			ink: v("text"),
			muted: v("text-muted"),
			subtle: v("text-subtle"),
			inverse: v("text-inverse"),

			// ── The single accent ────────────────────────────────────────────────
			accent: {
				DEFAULT: v("accent"),
				hover: v("accent-hover"),
				soft: v("accent-soft"),
				on: v("accent-on"),
				ink: v("accent-ink"),
				ring: v("accent-ring"),
			},

			// ── Five semantics, plus presence ────────────────────────────────────
			positive: { DEFAULT: v("positive"), tint: v("positive-tint") },
			caution: { DEFAULT: v("caution"), tint: v("caution-tint") },
			critical: { DEFAULT: v("critical"), tint: v("critical-tint") },
			active: { DEFAULT: v("active"), tint: v("active-tint") },
			idle: { DEFAULT: v("idle"), tint: v("idle-tint") },
			online: v("online"),

			// The raw ramp, for the rare case that needs a specific step.
			// `gray` is an alias so a stray habit lands in the system, not nowhere.
			n: neutral,
			gray: neutral,
		},

		borderRadius: {
			none: "0px",
			xs: v("radius-xs"),
			sm: v("radius-sm"),
			DEFAULT: v("radius-sm"),
			md: v("radius-md"),
			lg: v("radius-lg"),
			// xl and above collapse onto 16px: a data container never gets blobbier.
			xl: v("radius-xl"),
			"2xl": v("radius-xl"),
			"3xl": v("radius-xl"),
			full: "9999px",
		},

		boxShadow: {
			none: "none",
			e0: "none",
			e1: v("e1"),
			e2: v("e2"),
			e3: v("e3"),
			// Habit aliases — every one of them lands on a level that means something.
			sm: v("e1"),
			DEFAULT: v("e1"),
			md: v("e2"),
			lg: v("e2"),
			xl: v("e3"),
			"2xl": v("e3"),
		},

		fontSize: {
			// Semantic names — prefer these.
			display: ["1.75rem", { lineHeight: "2rem", letterSpacing: "-0.02em", fontWeight: "700" }],
			"title-lg": ["1.25rem", { lineHeight: "1.625rem", letterSpacing: "-0.015em", fontWeight: "650" }],
			title: ["1rem", { lineHeight: "1.375rem", letterSpacing: "-0.01em", fontWeight: "600" }],
			"title-sm": ["0.875rem", { lineHeight: "1.25rem", letterSpacing: "-0.005em", fontWeight: "600" }],
			body: ["0.875rem", { lineHeight: "1.25rem" }],
			"body-sm": ["0.8125rem", { lineHeight: "1.125rem" }],
			label: ["0.8125rem", { lineHeight: "1rem", fontWeight: "550" }],
			"label-sm": ["0.75rem", { lineHeight: "1rem", fontWeight: "550" }],
			caption: ["0.6875rem", { lineHeight: "0.875rem", letterSpacing: "0.02em", fontWeight: "500" }],

			// Numeric aliases, remapped onto the same scale.
			"2xs": ["0.6875rem", { lineHeight: "0.875rem" }],
			xs: ["0.75rem", { lineHeight: "1rem" }],
			sm: ["0.8125rem", { lineHeight: "1.125rem" }],
			base: ["0.875rem", { lineHeight: "1.25rem" }],
			lg: ["1rem", { lineHeight: "1.375rem" }],
			xl: ["1.25rem", { lineHeight: "1.625rem" }],
			"2xl": ["1.75rem", { lineHeight: "2rem" }],
			"3xl": ["1.75rem", { lineHeight: "2rem" }],
		},

		extend: {
			fontFamily: {
				sans: [
					"Inter var",
					"Inter",
					"ui-sans-serif",
					"system-ui",
					"-apple-system",
					"Segoe UI",
					"sans-serif",
				],
				mono: ["ui-monospace", "SFMono-Regular", "JetBrains Mono", "Menlo", "monospace"],
			},
			fontWeight: {
				// Inter is variable; 550/650 are the two weights the scale actually needs
				// and neither exists in Tailwind's default ladder.
				medium: "500",
				label: "550",
				semibold: "600",
				display: "650",
				bold: "700",
			},
			spacing: {
				sidebar: v("sidebar-w"),
				header: v("header-h"),
				row: v("row-h"),
				control: v("control-h"),
				cell: v("cell-px"),
			},
			height: { row: v("row-h"), control: v("control-h"), header: v("header-h") },
			minHeight: { row: v("row-h"), control: v("control-h") },
			width: { sidebar: v("sidebar-w") },
			maxWidth: { prose: "72ch", form: "40rem" },
			transitionTimingFunction: { DEFAULT: v("ease"), ndl: v("ease") },
			transitionDuration: {
				DEFAULT: v("motion-fast"),
				instant: v("motion-instant"),
				fast: v("motion-fast"),
				base: v("motion-base"),
				slow: v("motion-slow"),
			},
			ringColor: { DEFAULT: v("accent-ring") },
			ringOffsetColor: { DEFAULT: v("canvas") },
			zIndex: { sticky: "20", drawer: "40", overlay: "50", dialog: "60", toast: "70" },
			keyframes: {
				"ndl-pulse": { "0%,100%": { opacity: "0.45" }, "50%": { opacity: "1" } },
				"ndl-in": {
					from: { opacity: "0", transform: "translateY(4px)" },
					to: { opacity: "1", transform: "none" },
				},
				"ndl-scale-in": {
					from: { opacity: "0", transform: "scale(0.98)" },
					to: { opacity: "1", transform: "none" },
				},
			},
			animation: {
				"ndl-pulse": "ndl-pulse 800ms ease-in-out infinite",
				"ndl-in": "ndl-in var(--ndl-motion-base) var(--ndl-ease)",
				"ndl-scale-in": "ndl-scale-in var(--ndl-motion-base) var(--ndl-ease)",
			},
		},
	},
	plugins: [require("@tailwindcss/forms"), require("@tailwindcss/typography")],
};
