package com.naarni.service.core.network

import com.naarni.service.BuildConfig
import com.naarni.service.core.auth.SessionManager
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds the singleton Retrofit/OkHttp stack pointed at the Frappe backend.
 *
 * Auth: a [SessionCookieJar] replays the persisted `sid` session cookie on every
 * request and captures it on login. A header interceptor adds X-Frappe-CSRF only
 * if/when we obtain one (not needed for token/cookie GETs).
 */
object Network {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun api(session: SessionManager): FrappeApi {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .cookieJar(SessionCookieJar(session))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor(FrappeErrorInterceptor())
            .build()

        return Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FrappeApi::class.java)
    }
}

/** Persists/replays the Frappe `sid` session cookie via [SessionManager]. */
private class SessionCookieJar(private val session: SessionManager) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.firstOrNull { it.name == "sid" }?.let { session.sid = it.value }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val sid = session.sid ?: return emptyList()
        return listOf(
            Cookie.Builder()
                .name("sid")
                .value(sid)
                .domain(url.host)
                .path("/")
                .build()
        )
    }
}
