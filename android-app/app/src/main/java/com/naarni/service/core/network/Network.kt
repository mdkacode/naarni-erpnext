package com.naarni.service.core.network

import com.naarni.service.BuildConfig
import com.naarni.service.core.auth.SessionManager
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Builds the singleton Retrofit/OkHttp stack pointed at the Frappe backend.
 *
 * Auth: a [SessionCookieJar] replays the persisted `sid` session cookie on every
 * request and captures it on login.
 *
 * The client is exposed via [client] because three other things must share it:
 * the chat WebSocket (so the socket upgrade carries `sid`), the chunked upload
 * worker, and Coil's ImageLoader — private chat attachments live under
 * `/private/files/` and 403 without the session cookie.
 */
object Network {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Volatile
    private var shared: OkHttpClient? = null

    /** The one OkHttp client for the whole app. */
    fun client(session: SessionManager): OkHttpClient =
        shared ?: synchronized(this) {
            shared ?: build(session).also { shared = it }
        }

    private fun build(session: SessionManager): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .cookieJar(SessionCookieJar(session))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Chunks are up to 8 MB and a depot's uplink can be dire; the default
            // 10s write timeout aborts mid-chunk on a slow 3G link.
            .writeTimeout(120, TimeUnit.SECONDS)
            // Keep the socket alive through carrier NAT idle timeouts.
            .pingInterval(30, TimeUnit.SECONDS)
            .addInterceptor(DevHostInterceptor())
            .addInterceptor(logging)
            .addInterceptor(FrappeErrorInterceptor(session))
            .build()
    }

    fun api(session: SessionManager): FrappeApi =
        Retrofit.Builder()
            .baseUrl(BuildConfig.BASE_URL)
            .client(client(session))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FrappeApi::class.java)
}

/**
 * When a debug build targets the local bench over `adb reverse`, the request goes
 * to `localhost` but Frappe resolves the site from the Host header — and
 * `localhost` is not a site. Rewrite Host (and Origin, which the realtime auth
 * middleware also checks) to the real site name.
 *
 * No-op in every build that is not pointed at loopback, so production traffic is
 * untouched.
 */
private class DevHostInterceptor : Interceptor {
    private val active = BuildConfig.BASE_URL.contains("localhost") ||
        BuildConfig.BASE_URL.contains("127.0.0.1")

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!active) return chain.proceed(chain.request())
        val req = chain.request().newBuilder()
            .header("Host", BuildConfig.SITE_HOST)
            .header("Origin", BuildConfig.ORIGIN_URL)
            .build()
        return chain.proceed(req)
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
