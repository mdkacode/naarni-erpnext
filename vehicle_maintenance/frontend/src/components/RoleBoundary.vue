<template>
  <!--
    RoleBoundary — conditionally renders its slot content based on the
    active user's Frappe roles. Unauthorized content is fully omitted
    from the DOM (not just hidden via CSS).

    Usage:
      <RoleBoundary :roles="['Service Engineer', 'Depot Manager']">
        <SensitiveAdminPanel />
      </RoleBoundary>

      <RoleBoundary :roles="['Customer']" :invert="true">
        <InternalOnlyWidget />
      </RoleBoundary>

      <RoleBoundary :roles="['Depot Manager']">
        <template #default>You have access</template>
        <template #fallback>You do not have permission to view this.</template>
      </RoleBoundary>
  -->
  <slot v-if="isAuthorized" />
  <slot v-else name="fallback" />
</template>

<script setup>
import { computed } from "vue";
import { useSession } from "../composables/useSession.js";
import { hasAnyRole, hasAllRoles } from "../utils/permissions.js";

const props = defineProps({
  /** List of Frappe role names. User needs at least one (or all, if requireAll is true). */
  roles: {
    type: Array,
    required: true,
    validator: (v) => v.length > 0 && v.every((r) => typeof r === "string"),
  },
  /** If true, user must have ALL listed roles (AND logic). Default is ANY (OR logic). */
  requireAll: {
    type: Boolean,
    default: false,
  },
  /** If true, inverts the check — renders content when user does NOT have the roles. */
  invert: {
    type: Boolean,
    default: false,
  },
});

const { roles: userRoles } = useSession();

const isAuthorized = computed(() => {
  const hasRole = props.requireAll
    ? hasAllRoles(userRoles, props.roles)
    : hasAnyRole(userRoles, props.roles);

  return props.invert ? !hasRole : hasRole;
});
</script>
