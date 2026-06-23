package com.naarni.service.core.network

import kotlinx.serialization.Serializable

/**
 * Frappe wraps every whitelisted-method return value in a top-level `message`
 * key: `GET /api/method/x.y.z` → `{"message": <return value>}`. Our endpoints
 * return the standard `{success, data, message}` envelope, so the wire shape is
 * `{"message": {"success": ..., "data": ...}}`.
 */
@Serializable
data class FrappeWrap<T>(val message: T? = null)

/** The app-level response envelope returned by every custom endpoint. */
@Serializable
data class Envelope<T>(
    val success: Boolean = false,
    val data: T? = null,
    val message: String? = null,
)

/** Unwrap `FrappeWrap<Envelope<T>>` to the payload, or throw a readable error. */
fun <T> FrappeWrap<Envelope<T>>.payload(): T {
    val env = message ?: throw ApiException("Empty response from server")
    if (!env.success) throw ApiException(env.message ?: "Request failed")
    return env.data ?: throw ApiException(env.message ?: "No data")
}

class ApiException(message: String) : Exception(message)
