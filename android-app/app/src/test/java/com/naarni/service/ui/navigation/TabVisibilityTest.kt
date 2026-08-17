package com.naarni.service.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tabs a signed-in user sees.
 *
 * This is presentation, not security — the server decides what anyone may read
 * or write (`process_run.get_permission_query_conditions`). It is tested anyway
 * because the failure is silent in both directions: too strict and an operator
 * loses the tab they need with no error to explain it, too loose and a driver
 * gets a Battery tab that answers every tap with a permission failure.
 */
class TabVisibilityTest {

    @Test
    fun `tabs without a role requirement are visible to everyone`() {
        val noRoles = emptySet<String>()

        assertTrue(Tab.Home.isVisibleTo(noRoles))
        assertTrue(Tab.Chat.isVisibleTo(noRoles))
        assertTrue(Tab.Alerts.isVisibleTo(noRoles))
        assertTrue(Tab.Profile.isVisibleTo(noRoles))
    }

    @Test
    fun `battery is hidden from someone who runs no inspections`() {
        assertFalse(Tab.Battery.isVisibleTo(setOf("Technician", "Driver")))
    }

    @Test
    fun `battery is hidden when the session carries no roles at all`() {
        // A cleared or not-yet-populated session must fail closed, not open.
        assertFalse(Tab.Battery.isVisibleTo(emptySet()))
    }

    @Test
    fun `an operator sees battery`() {
        assertTrue(Tab.Battery.isVisibleTo(setOf("Process Operator")))
    }

    @Test
    fun `supervisors and authors see battery too`() {
        assertTrue(Tab.Battery.isVisibleTo(setOf("Process Verifier")))
        assertTrue(Tab.Battery.isVisibleTo(setOf("Process Author")))
        assertTrue(Tab.Battery.isVisibleTo(setOf("Battery QA Admin")))
        assertTrue(Tab.Battery.isVisibleTo(setOf("System Manager")))
    }

    @Test
    fun `one qualifying role among several unrelated ones is enough`() {
        assertTrue(Tab.Battery.isVisibleTo(setOf("Driver", "Technician", "Process Operator")))
    }

    @Test
    fun `role matching is exact, not by prefix`() {
        // "Process Operator Trainee" is a different role and must not inherit
        // access from a substring match.
        assertFalse(Tab.Battery.isVisibleTo(setOf("Process Operator Trainee")))
    }

    @Test
    fun `home always survives the filter so the shell has a start destination`() {
        val visible = Tab.entries.filter { it.isVisibleTo(emptySet()) }

        assertTrue(visible.contains(Tab.Home))
        assertTrue(visible.isNotEmpty())
    }
}
