import { computed, ref } from "vue";
import { call } from "frappe-ui";

const sessionUser = ref(null);
const userRoles = ref([]);
const sessionChecked = ref(false);
const rolesLoaded = ref(false);
let fetchPromise = null;

async function checkSession() {
  try {
    // If the server-rendered page already injected window.csrf_token (via
    // www/service-portal/index.py boot), use it directly. This avoids the
    // chicken-and-egg where the first POST needs a CSRF header that we
    // don't have yet.
    if (window.user && window.user !== "Guest") {
      sessionUser.value = window.user;
      await fetchRoles();
      sessionChecked.value = true;
      return;
    }

    const user = await call("frappe.auth.get_logged_user");
    sessionUser.value = user;
    if (user && user !== "Guest") {
      if (!window.csrf_token) await fetchCsrfToken();
      await fetchRoles();
    } else {
      rolesLoaded.value = true;
    }
  } catch {
    sessionUser.value = null;
    userRoles.value = [];
    rolesLoaded.value = true;
  } finally {
    sessionChecked.value = true;
  }
}

async function fetchCsrfToken() {
  try {
    const token = await call("vehicle_maintenance.api.auth.get_csrf_token");
    if (token) window.csrf_token = token;
  } catch {
    // CSRF fetch failure is non-fatal; POSTs will surface it
  }
}

async function fetchRoles() {
  try {
    const roles = await call(
      "vehicle_maintenance.fleet_service.doctype.job_card.job_card.get_user_roles"
    );
    userRoles.value = roles || [];
  } catch {
    userRoles.value = [];
  } finally {
    rolesLoaded.value = true;
  }
}

// Single shared promise so multiple callers don't trigger parallel fetches
function initSession() {
  if (!fetchPromise) {
    fetchPromise = checkSession();
  }
  return fetchPromise;
}

export function useSession() {
  // Always kick off session check (idempotent via shared promise)
  initSession();

  const isLoggedIn = computed(
    () => sessionUser.value && sessionUser.value !== "Guest"
  );

  const loading = computed(
    () => !sessionChecked.value || (!rolesLoaded.value && isLoggedIn.value)
  );

  function logout() {
    return fetch("/api/method/logout", { method: "POST" }).then(() => {
      sessionUser.value = null;
      userRoles.value = [];
      fetchPromise = null;
      sessionChecked.value = false;
      window.location.href = "/service-portal/login";
    });
  }

  return {
    user: sessionUser,
    roles: userRoles,
    isLoggedIn,
    logout,
    loading,
    sessionChecked,
    waitForSession: initSession,
  };
}
