package com.naarni.service.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** An ordinary authenticated call, made while holding a session. */
    private fun dead(code: Int, body: String?) = isSessionDeadResponse(
        path = "/api/method/vehicle_maintenance.api.chat.list_rooms",
        code = code,
        body = body,
        hadSession = true,
    )

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
        assertTrue(dead(403, deadSid))
    }

    @Test
    fun `a 401 signs the user out whatever the body says`() {
        assertTrue(dead(401, null))
        assertTrue(dead(401, "<html>Session Expired</html>"))
    }

    @Test
    fun `a forbidden action does not sign the user out`() {
        assertFalse(dead(403, forbiddenAction))
    }

    @Test
    fun `a request with no cookie does not sign the user out`() {
        // Same status, same exc_type, same wording as an expiry — only the flag
        // separates them, which is the whole reason this function exists.
        assertFalse(dead(403, noCookie))
    }

    @Test
    fun `an ordinary validation error is left alone`() {
        assertFalse(dead(417, validationError))
    }

    @Test
    fun `a server error is not an expiry`() {
        assertFalse(dead(500, """{"exception":"boom"}"""))
    }

    @Test
    fun `an html error page is not mistaken for an expiry`() {
        // A proxy or load balancer can answer 403 with no JSON at all.
        assertFalse(dead(403, "<html><body>Forbidden</body></html>"))
    }

    @Test
    fun `an empty or absent body on a 403 is not an expiry`() {
        assertFalse(dead(403, null))
        assertFalse(dead(403, ""))
    }

    @Test
    fun `the flag is only honoured when it is actually set`() {
        assertFalse(dead(403, """{"session_expired":0}"""))
    }

    @Test
    fun `success codes never reach a sign-out`() {
        assertFalse(dead(200, deadSid))
    }

    // ------------------------------------- signing in is not signing out

    @Test
    fun `a wrong password is not an expired session`() {
        // Frappe answers AuthenticationError with 401 — the same status it uses
        // for a dead session. Reported "Your session expired" on a mistyped
        // password on a real handset before this case existed.
        assertFalse(
            isSessionDeadResponse(
                path = "/api/method/vehicle_maintenance.api.auth.login_with_phone",
                code = 401,
                body = """{"message":"Invalid login credentials","exc_type":"AuthenticationError"}""",
                hadSession = true,
            )
        )
    }

    @Test
    fun `a stale sid left over from a previous user cannot break a fresh login`() {
        // The login POST carries the dead cookie, so the response really does
        // set session_expired — but the user is signing *in*, and bouncing them
        // to the login screen they are already on would strand them there.
        assertFalse(
            isSessionDeadResponse(
                path = "/api/method/vehicle_maintenance.api.auth.login_with_phone",
                code = 403,
                body = """{"session_expired":1,"message":"Invalid login credentials"}""",
                hadSession = true,
            )
        )
    }

    @Test
    fun `otp endpoints are exempt too`() {
        for (endpoint in listOf("api.auth.request_otp", "api.auth.verify_otp")) {
            assertFalse(
                endpoint,
                isSessionDeadResponse("/api/method/vehicle_maintenance.$endpoint", 401, null, true),
            )
        }
    }

    @Test
    fun `nothing expires when we never had a session`() {
        assertFalse(
            isSessionDeadResponse(
                path = "/api/method/vehicle_maintenance.api.chat.list_rooms",
                code = 401,
                body = null,
                hadSession = false,
            )
        )
    }
}

/**
 * What the user is told when a request fails.
 *
 * A field engineer reading "frappe.exceptions.AuthenticationError" learns
 * nothing; the readable sentence is sitting in the same body under `message`.
 * That is exactly what a mistyped password showed on a handset.
 */
class FrappeErrorMessageTest {

    @Test
    fun `a bare exception class falls through to the message field`() {
        assertEquals(
            "Invalid login credentials",
            parseFrappeError(
                """{"message":"Invalid login credentials","exception":"frappe.exceptions.AuthenticationError"}""",
            ),
        )
    }

    @Test
    fun `an exception carrying its own text is used`() {
        assertEquals(
            "Message cannot be empty.",
            parseFrappeError("""{"exception":"frappe.exceptions.ValidationError: Message cannot be empty."}"""),
        )
    }

    @Test
    fun `server messages win over everything`() {
        assertEquals(
            "You are not a member of this room.",
            parseFrappeError(
                """{"_server_messages":"[\"{\\\"message\\\": \\\"You are not a member of this room.\\\"}\"]",""" +
                    """"exception":"frappe.exceptions.PermissionError: nope"}""",
            ),
        )
    }

    @Test
    fun `an unparseable body yields nothing rather than nonsense`() {
        assertNull(parseFrappeError("<html>502 Bad Gateway</html>"))
        assertNull(parseFrappeError(""))
    }

    // ------------------------------------------------- codes that are not errors

    @Test
    fun `304 is not an error — it is Coil revalidating a cached photo`() {
        // Treating this as a failure blanked every image the app had already
        // cached, so photos vanished the more the chat was used.
        assertTrue(isPassThroughStatus(304))
    }

    @Test
    fun `101 is not an error — it is the chat socket upgrading`() {
        assertTrue(isPassThroughStatus(101))
    }

    @Test
    fun `real failures are still failures`() {
        listOf(400, 401, 403, 404, 417, 500, 502).forEach { code ->
            assertFalse("HTTP $code must not pass through", isPassThroughStatus(code))
        }
    }

    @Test
    fun `ordinary success is not routed through the pass-through list`() {
        // 200 is handled by isSuccessful; this only guards the exceptions to it.
        assertFalse(isPassThroughStatus(200))
        assertFalse(isPassThroughStatus(204))
    }
}
