package com.naarni.service.core.network

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
 */
class FrappeErrorInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        // A WebSocket upgrade answers 101 Switching Protocols, which is not
        // "successful" by OkHttp's 200..299 definition. The chat socket shares
        // this client (so the session cookie is attached), so without this the
        // interceptor throws on every upgrade and the socket can never connect.
        if (response.code == HTTP_SWITCHING_PROTOCOLS) return response

        if (response.isSuccessful) return response

        val body = runCatching { response.peekBody(MAX_PEEK).string() }.getOrNull()
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
    }
}

/** A Frappe-side error already carrying a user-facing message. */
class FrappeHttpException(message: String) : IOException(message)
