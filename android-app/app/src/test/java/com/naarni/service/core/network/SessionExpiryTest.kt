package com.naarni.service.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app signs a user out by itself.
 *
 * The bodies below are real — captured from a Frappe 15 bench by presenting a
 * dead `sid`, no cookie at all, and a live session doing something it is not
 * allowed to do. All three answer 403, which is exactly why the status code
 * cannot be the test.
 *
 * The asymmetry matters: a missed expiry costs a confusing screen until the next
 * request, while a false positive throws a technician out of the app mid-job for
 * touching one endpoint their role does not cover. Every case here is written
 * from that direction.
 */
class SessionExpiryTest {

    private val deadSid = """
        {"session_expired":1,
         "exception":"frappe.exceptions.PermissionError: You are not permitted to access this resource. Login to access",
         "exc_type":"PermissionError"}
    """.trimIndent()

    private val noCookie = """
        {"exception":"frappe.exceptions.PermissionError: You are not permitted to access this resource. Login to access",
         "exc_type":"PermissionError"}
    """.trimIndent()

    private val forbiddenAction = """
        {"exception":"frappe.exceptions.PermissionError: Not permitted",
         "exc_type":"PermissionError"}
    """.trimIndent()

    private val validationError = """
        {"exception":"frappe.exceptions.ValidationError: Message cannot be empty.",
         "exc_type":"ValidationError",
         "_server_messages":"[\"{\\\"message\\\": \\\"Message cannot be empty.\\\"}\"]"}
    """.trimIndent()

    @Test
    fun `a dead sid signs the user out`() {
        assertTrue(isSessionDeadResponse(403, deadSid))
    }

    @Test
    fun `a 401 signs the user out whatever the body says`() {
        assertTrue(isSessionDeadResponse(401, null))
        assertTrue(isSessionDeadResponse(401, "<html>Session Expired</html>"))
    }

    @Test
    fun `a forbidden action does not sign the user out`() {
        assertFalse(isSessionDeadResponse(403, forbiddenAction))
    }

    @Test
    fun `a request with no cookie does not sign the user out`() {
        // Same status, same exc_type, same wording as an expiry — only the flag
        // separates them, which is the whole reason this function exists.
        assertFalse(isSessionDeadResponse(403, noCookie))
    }

    @Test
    fun `an ordinary validation error is left alone`() {
        assertFalse(isSessionDeadResponse(417, validationError))
    }

    @Test
    fun `a server error is not an expiry`() {
        assertFalse(isSessionDeadResponse(500, """{"exception":"boom"}"""))
    }

    @Test
    fun `an html error page is not mistaken for an expiry`() {
        // A proxy or load balancer can answer 403 with no JSON at all.
        assertFalse(isSessionDeadResponse(403, "<html><body>Forbidden</body></html>"))
    }

    @Test
    fun `an empty or absent body on a 403 is not an expiry`() {
        assertFalse(isSessionDeadResponse(403, null))
        assertFalse(isSessionDeadResponse(403, ""))
    }

    @Test
    fun `the flag is only honoured when it is actually set`() {
        assertFalse(isSessionDeadResponse(403, """{"session_expired":0}"""))
    }

    @Test
    fun `success codes never reach a sign-out`() {
        assertFalse(isSessionDeadResponse(200, deadSid))
    }
}
