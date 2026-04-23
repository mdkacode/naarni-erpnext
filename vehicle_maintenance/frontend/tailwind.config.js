/** @type {import('tailwindcss').Config} */
export default {
  presets: [require("frappe-ui/tailwind")],
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
    "./node_modules/frappe-ui/src/components/**/*.{vue,js,ts}",
  ],
  theme: {
    extend: {
      colors: {
        // Brand palette — workshop / automotive feel
        brand: {
          50: "#eff6ff",
          100: "#dbeafe",
          200: "#bfdbfe",
          300: "#93c5fd",
          400: "#60a5fa",
          500: "#3b82f6", // primary
          600: "#2563eb",
          700: "#1d4ed8",
          800: "#1e40af",
          900: "#1e3a8a",
          950: "#172554",
        },
        // Semantic status colors aligned with workflow states
        status: {
          open: "#6366f1",       // indigo — Open
          wip: "#f59e0b",        // amber — WIP
          approval: "#8b5cf6",   // violet — Awaiting Customer Approval
          parts: "#ec4899",      // pink — Awaiting Parts
          fitted: "#06b6d4",     // cyan — Parts Fitted
          verify: "#f97316",     // orange — Verification Pending
          closed: "#22c55e",     // green — Closed
          breach: "#ef4444",     // red — SLA Breached
        },
      },
      fontFamily: {
        sans: [
          "Inter",
          "ui-sans-serif",
          "system-ui",
          "-apple-system",
          "sans-serif",
        ],
        mono: ["JetBrains Mono", "Fira Code", "monospace"],
      },
      fontSize: {
        "2xs": ["0.625rem", { lineHeight: "0.875rem" }],
      },
      borderRadius: {
        "4xl": "2rem",
      },
      spacing: {
        18: "4.5rem",
        88: "22rem",
        112: "28rem",
        128: "32rem",
      },
    },
  },
  plugins: [],
};
