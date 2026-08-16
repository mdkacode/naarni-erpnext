package com.naarni.service.core.network

import com.naarni.service.core.auth.SessionManager
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Turns Frappe error responses into readable messages.
 *
 * Frappe signals `frappe.throw(...)` as a non-2xx (usually HTTP 417) with the
 * user-facing text in the body — `_server_messages` (a JSON-encoded list of
 * JSON-encoded `{message,...}`) or `exception` ("Class: message"). Without this,
 * Retrofit surfaces only the raw status line ("HTTP 417 EXPECTATION FAILED"),
 * which is meaningless to a field user. We parse the real message and raise it
 * as an IOException so the repositories' runCatching reports it verbatim.
 *
 * It is also where a dead session is noticed, because it is the one place every
 * failed response already passes through with its body in hand.
 */
class FrappeErrorInterceptor(private val session: SessionManager) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if (isPassThroughStatus(response.code)) return response

        if (response.isSuccessful) return response

        val body = runCatching { response.peekBody(MAX_PEEK).string() }.getOrNull()

        val dead = isSessionDeadResponse(
            path = chain.request().url.encodedPath,
            code = response.code,
            body = body,
            hadSession = session.hasLiveSession,
        )
        if (dead) {
            session.markExpired()
            throw FrappeHttpException(SESSION_EXPIRED_MESSAGE)
        }

        // The status is carried in the fallback because a response with no
        // Frappe error body is, by definition, one we could not explain — and
        // "Something went wrong" with nothing else attached is unactionable for
        // whoever has to work out why a depot's photos are blank.
        val message = body?.let(::parseFrappeError)
            ?: "Something went wrong (HTTP ${response.code}). Please try again."
        throw FrappeHttpException(message)
    }

    private companion object {
        const val MAX_PEEK = 1L * 1024 * 1024
    }
}

private const val HTTP_SWITCHING_PROTOCOLS = 101
private const val HTTP_NOT_MODIFIED = 304

/**
 * Responses that are fine despite falling outside OkHttp's 200..299.
 *
 * `isSuccessful` is the wrong question for this interceptor, and answering it
 * as though it were the right one broke two features:
 *
 * * **101 Switching Protocols** — the chat socket shares this client so its
 *   upgrade carries `sid`. Throwing here meant the socket could never connect.
 * * **304 Not Modified** — Coil revalidates every image it has on disk, and the
 *   server rightly answers 304 with an empty body. Throwing here meant a photo
 *   rendered the *first* time and was blank on every later visit, so the chat
 *   appeared to lose its images the more it was used. Found on a handset with
 *   the gallery showing one thumbnail out of five.
 *
 * Neither is an error, and neither carries a Frappe error body to parse.
 */
internal fun isPassThroughStatus(code: Int): Boolean =
    code == HTTP_SWITCHING_PROTOCOLS || code == HTTP_NOT_MODIFIED

internal fun parseFrappeError(body: String): String? {
    if (body.isBlank()) return null
    return try {
        val obj = JSONObject(body)
        serverMessage(obj) ?: exceptionMessage(obj) ?: obj.optString("message").ifBlank { null }
    } catch (_: Exception) {
        null
    }
}

/** `_server_messages`: "[\"{\\\"message\\\": \\\"...\\\"}\"]" → the message. */
private fun serverMessage(obj: JSONObject): String? {
    val raw = obj.optString("_server_messages").ifBlank { return null }
    return try {
        val arr = JSONArray(raw)
        if (arr.length() == 0) return null
        val first = arr.getString(0)
        try {
            JSONObject(first).optString("message").ifBlank { first }
        } catch (_: Exception) {
            first
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * `exception`: "frappe.exceptions.ValidationError: <message>" → <message>.
 *
 * Returns null when there is no `: ` — some Frappe errors carry only the
 * class name there and put the readable text in `message` instead, and a
 * failed login showing "frappe.exceptions.AuthenticationError" is no use to
 * anyone holding a phone.
 */
private fun exceptionMessage(obj: JSONObject): String? {
    val ex = obj.optString("exception").ifBlank { return null }
    if (": " !in ex) return null
    return ex.substringAfter(": ").trim().ifBlank { null }
}

/**
 * Endpoints whose whole purpose is to *obtain* a session.
 *
 * A failure here can never mean "your session expired" — there was no session
 * to lose. This matters because Frappe answers a wrong password with 401, the
 * same status it uses for a dead session, so without this list a mistyped
 * password reports itself as an expiry and the real reason never reaches the
 * user. Found on a handset, not in a test.
 */
private val UNAUTHENTICATED_ENDPOINTS = listOf(
    "api.auth.login_with_phone",
    "api.auth.request_otp",
    "api.auth.verify_otp",
    "api.auth.login_with_naarni_token",
)

/**
 * Is this "your session is gone", or something else entirely?
 *
 * Getting this wrong in the permissive direction is the dangerous failure: a
 * Technician who touches one endpoint their role does not cover, or fat-fingers
 * a password, would be signed out and told the wrong reason. So this is
 * deliberately narrow, and every clause earns its place:
 *
 * * **[hadSession]** — if we sent no session, there is nothing to expire. Note
 *   that Frappe answers a dead `sid` by setting the cookie to `Guest`, so a
 *   literal "Guest" counts as no session; see [SessionCookieJar].
 * * **an unauthenticated endpoint** — see above; 401 there means bad
 *   credentials.
 * * **401** on anything else is Frappe's "Session Expired" path.
 * * **403 with `session_expired`** — set in `frappe/sessions.py`
 *   (`get_session_record`) at exactly one place: a non-Guest `sid` was
 *   presented and no session record exists for it. The flag is what separates
 *   this from a permission denial, because both answer 403 and the expired one
 *   even claims the method "is not whitelisted".
 *
 * Top-level and pure so it can be tested without an Android runtime; this is
 * the one branch in the app that must never fire by accident.
 */
internal fun isSessionDeadResponse(
    path: String,
    code: Int,
    body: String?,
    hadSession: Boolean,
): Boolean {
    if (!hadSession) return false
    if (UNAUTHENTICATED_ENDPOINTS.any { path.contains(it) }) return false
    if (code == 401) return true
    if (code != 403 || body.isNullOrBlank()) return false
    return runCatching { JSONObject(body).optInt("session_expired") == 1 }.getOrDefault(false)
}

/**
 * Shown on the login screen after an automatic sign-out.
 *
 * Frappe's own words for this are "You are not permitted to access this
 * resource", which reads as an accusation to somebody who did nothing wrong.
 */
const val SESSION_EXPIRED_MESSAGE = "Your session expired. Please sign in again."

/** A Frappe-side error already carrying a user-facing message. */
class FrappeHttpException(message: String) : IOException(message)
