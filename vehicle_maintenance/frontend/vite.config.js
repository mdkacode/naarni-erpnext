import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import frappeui from "frappe-ui/vite";
import { resolve } from "path";

export default defineConfig({
  plugins: [
    frappeui({
      lucideIcons: true,
      frappeProxy: true,
      buildConfig: {
        // The Frappe page that hosts the SPA shell — `www/service-portal/index.html`
        // is injected with the compiled bundle's <script>/<link> tags.
        indexHtmlPath: "../vehicle_maintenance/www/service-portal/index.html",
      },
    }),
    vue(),
  ],
  resolve: {
    alias: {
      "@": resolve(__dirname, "src"),
    },
  },
  build: {
    outDir: "../vehicle_maintenance/public/frontend",
    emptyOutDir: true,
    target: "es2015",
    rollupOptions: {
      input: {
        main: resolve(__dirname, "index.html"),
      },
    },
  },
  optimizeDeps: {
    // CJS deps used by frappe-ui that esbuild must convert to ESM.
    // socket.io-client pulls in engine.io-client -> debug (all CJS).
    include: [
      "feather-icons",
      "dayjs",
      "engine.io-client",
      "debug",
      "socket.io-client",
      "highlight.js/lib/core",
    ],
    // frappe-ui uses ~icons/lucide/* virtual imports that only the
    // lucideIcons Vite plugin can resolve — esbuild must not scan it.
    exclude: ["frappe-ui"],
  },
});
