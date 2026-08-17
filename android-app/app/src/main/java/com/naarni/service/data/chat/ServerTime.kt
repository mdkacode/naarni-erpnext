package com.naarni.service.data.chat

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Turning a Frappe timestamp into epoch millis.
 *
 * Frappe writes `yyyy-MM-dd HH:mm:ss`, sometimes with microseconds on the end
 * and sometimes without, depending on which endpoint produced it. Both are
 * accepted here so no caller has to care which one it got.
 *
 * **No timezone is applied**, matching every other timestamp in the app: the
 * server and every handset that talks to it run on IST, and converting in one
 * place alone would make a message's own time disagree with the day divider
 * above it and the room list beside it. If the fleet ever spans timezones this
 * is the single place to fix, which is most of the reason it lives here rather
 * than being re-written at each call site.
 */
object ServerTime {

    /**
     * `SimpleDateFormat` is not thread-safe, and this is called from the sync
     * worker, the socket callback and the main thread. One instance per thread
     * costs a few bytes; one shared instance costs corrupted parses that only
     * show up under load.
     */
    private val parser = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    /**
     * Epoch millis for [raw], or null if it is missing or unparseable.
     *
     * Null rather than zero: a caller that substitutes its own fallback gets a
     * plausible time, where zero would render as January 1970 on the bubble.
     */
    fun millis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        // Microseconds are dropped rather than parsed — nothing in this app
        // displays anything finer than a minute.
        val trimmed = raw.substringBefore('.').trim()
        return runCatching { parser.get()?.parse(trimmed)?.time }.getOrNull()
    }

    /** Epoch millis for [raw], falling back to [orElse] (default: now). */
    fun millisOr(raw: String?, orElse: Long = System.currentTimeMillis()): Long =
        millis(raw) ?: orElse
}
