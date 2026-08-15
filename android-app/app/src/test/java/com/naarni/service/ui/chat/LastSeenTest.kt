package com.naarni.service.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The "last seen" label.
 *
 * Worth testing off-device because every one of these branches is a sentence
 * shown to a person about a colleague, and the failure mode is not a crash but
 * a plausible-looking lie — "last seen today at 03:00" for somebody who was
 * here a minute ago reads as fact, not as a bug.
 */
class LastSeenTest {

    private fun at(daysAgo: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `a beat within the last minute reads as just now`() {
        val now = System.currentTimeMillis()
        assertEquals("last seen just now", lastSeenLabel(now - 20_000, now))
    }

    @Test
    fun `a gap of minutes is reported with the time, not as just now`() {
        // The boundary matters: presence beats every 30 seconds and the written
        // copy lags up to three minutes, so "just now" has to cover the ordinary
        // jitter without swallowing a genuine ten-minute absence.
        val now = System.currentTimeMillis()
        val label = lastSeenLabel(now - 10 * 60_000, now)
        assertTrue(label, label.startsWith("last seen today at "))
    }

    @Test
    fun `earlier today is labelled today`() {
        val now = System.currentTimeMillis()
        val label = lastSeenLabel(at(daysAgo = 0, hour = 1, minute = 5), now)
        assertEquals("last seen today at 01:05", label)
    }

    @Test
    fun `yesterday is named rather than dated`() {
        val now = System.currentTimeMillis()
        val label = lastSeenLabel(at(daysAgo = 1, hour = 18, minute = 30), now)
        assertEquals("last seen yesterday at 18:30", label)
    }

    @Test
    fun `older than yesterday gets a date`() {
        val now = System.currentTimeMillis()
        val label = lastSeenLabel(at(daysAgo = 5, hour = 9, minute = 0), now)
        assertTrue(label, label.startsWith("last seen "))
        assertTrue(label, label.endsWith(" at 09:00"))
        // Neither of the relative words — this is far enough back that a date is
        // the only thing that actually locates it.
        assertTrue(label, !label.contains("today") && !label.contains("yesterday"))
    }

    @Test
    fun `a server stamp parses to epoch millis`() {
        val millis = parseServerTime("2026-08-15 14:03:11")
        assertTrue(millis != null && millis > 0)
    }

    @Test
    fun `fractional seconds are tolerated`() {
        // Frappe hands back microseconds on some paths and not others.
        assertEquals(
            parseServerTime("2026-08-15 14:03:11"),
            parseServerTime("2026-08-15 14:03:11.482913"),
        )
    }

    @Test
    fun `junk parses to null rather than to the epoch`() {
        // Returning 0 here would render "last seen 01 Jan 1970", which is worse
        // than showing nothing at all.
        assertNull(parseServerTime("not a date"))
        assertNull(parseServerTime(""))
        assertNull(parseServerTime(null))
    }
}
