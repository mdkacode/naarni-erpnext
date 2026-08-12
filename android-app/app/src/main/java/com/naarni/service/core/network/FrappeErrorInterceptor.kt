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

        // A WebSocket upgrade answers 101 Switching Protocols, which is not
        // "successful" by OkHttp's 200..299 definition. The chat socket shares
        // this client (so the session cookie is attached), so without this the
        // interceptor throws on every upgrade and the socket can never connect.
        if (response.code == HTTP_SWITCHING_PROTOCOLS) return response

        if (response.isSuccessful) return response

        val body = runCatching { response.peekBody(MAX_PEEK).string() }.getOrNull()

        if (isSessionDeadResponse(response.code, body)) {
            session.markExpired()
            throw FrappeHttpException(SESSION_EXPIRED_MESSAGE)
        }

        val message = body?.let(::parseFrappeError)
            ?: "Something went wrong. Please try again."
        throw FrappeHttpException(message)
    }

    private fun parseFrappeError(body: String): String? {
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

    /** `exception`: "frappe.exceptions.ValidationError: <message>" → <message>. */
    private fun exceptionMessage(obj: JSONObject): String? {
        val ex = obj.optString("exception").ifBlank { return null }
        return ex.substringAfter(": ", ex).trim().ifBlank { null }
    }

    private companion object {
        const val MAX_PEEK = 1L * 1024 * 1024
        const val HTTP_SWITCHING_PROTOCOLS = 101
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}

/**
 * Is this "your session is gone", or merely "you may not do that"?
 *
 * Getting this wrong in the permissive direction is the dangerous failure: a
 * Technician who touches one endpoint their role does not cover would be signed
 * out mid-job. So the test is the server's own explicit flag, not the status
 * code — a dead session and a forbidden action both answer 403, and a dead
 * session's message even claims the method "is not whitelisted".
 *
 * `session_expired` is set in `frappe/sessions.py` at exactly one place
 * (`get_session_record`): a non-Guest `sid` was presented and no session record
 * exists for it. It is absent when no cookie was sent at all, and absent for a
 * permission denial on a live session. HTTP 401 is Frappe's other expiry path —
 * its "Session Expired" web page — and is unambiguous on its own.
 *
 * Top-level and internal so it can be tested without an Android runtime; this
 * is the one branch in the app that must never fire by accident.
 */
internal fun isSessionDeadResponse(code: Int, body: String?): Boolean {
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
