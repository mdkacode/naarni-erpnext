/**
 * Centralized RBAC utility.
 *
 * Reads from the reactive `roles` array provided by useSession().
 * All role checks go through this module — never check roles inline in templates.
 */

/**
 * Check if the user has ANY of the specified roles.
 * @param {import("vue").Ref<string[]>} userRoles - Reactive ref of current user's roles
 * @param {string[]} requiredRoles - Roles to check against
 * @returns {boolean}
 */
export function hasAnyRole(userRoles, requiredRoles) {
  if (!userRoles.value || !requiredRoles.length) return false;
  return requiredRoles.some((r) => userRoles.value.includes(r));
}

/**
 * Check if the user has ALL of the specified roles.
 * @param {import("vue").Ref<string[]>} userRoles
 * @param {string[]} requiredRoles
 * @returns {boolean}
 */
export function hasAllRoles(userRoles, requiredRoles) {
  if (!userRoles.value || !requiredRoles.length) return false;
  return requiredRoles.every((r) => userRoles.value.includes(r));
}

/**
 * Check if user is an internal (non-customer) role.
 * @param {import("vue").Ref<string[]>} userRoles
 * @returns {boolean}
 */
export function isInternalUser(userRoles) {
  return hasAnyRole(userRoles, [
    "Depot Manager",
    "Service Engineer",
    "Technician",
    "Central Ops",
  ]);
}
