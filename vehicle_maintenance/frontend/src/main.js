import { createApp } from "vue";
import { FrappeUI, setConfig, frappeRequest, resourcesPlugin } from "frappe-ui";
import App from "./App.vue";
import router from "./router.js";
import { initTheme } from "./composables/useTheme.js";
import "./index.css";

// Point frappe-ui at the Frappe backend (same origin in production)
setConfig("resourceFetcher", frappeRequest);

// Stamp the theme before the first paint, so a dark-mode user never sees a
// white flash while Vue boots.
initTheme();

const app = createApp(App);

app.use(router);
app.use(resourcesPlugin);
app.use(FrappeUI);

app.mount("#app");
