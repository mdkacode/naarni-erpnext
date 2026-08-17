package com.naarni.service.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Parsing the server's timestamp.
 *
 * There is a test here at all because the absence of this parse was invisible:
 * `ChatMessageEntity.createdAt` defaults to `System.currentTimeMillis()`, so a
 * mapper that simply forgot the field produced a perfectly plausible time on
 * every bubble — the current one. Nothing crashed, nothing was blank, and every
 * day divider said "Today". The only way to catch that class of bug is to
 * assert the value, not the absence of an exception.
 */
class ServerTimeTest {

    private fun expected(text: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(text)!!.time

    @Test
    fun `a plain frappe timestamp parses`() {
        assertEquals(expected("2026-08-17 09:14:03"), ServerTime.millis("2026-08-17 09:14:03"))
    }

    @Test
    fun `microseconds are tolerated`() {
        // Some endpoints include them and some do not, depending on whether the
        // value came back through str() or through the query builder.
        assertEquals(
            ServerTime.millis("2026-08-17 09:14:03"),
            ServerTime.millis("2026-08-17 09:14:03.482913"),
        )
    }

    @Test
    fun `an old message keeps its own time rather than becoming now`() {
        // The actual bug: a week-old message showed the current time.
        val week = ServerTime.millis("2026-08-10 06:30:00")
        assertNotNull(week)
        assertTrue(
            "parsed $week should be well before now",
            System.currentTimeMillis() - week!! > TimeUnit.DAYS.toMillis(1),
        )
    }

    @Test
    fun `two different stamps do not collapse to the same instant`() {
        val a = ServerTime.millis("2026-08-17 09:14:03")
        val b = ServerTime.millis("2026-08-17 11:47:59")
        assertNotNull(a); assertNotNull(b)
        assertEquals(TimeUnit.MINUTES.toMillis(153) + TimeUnit.SECONDS.toMillis(56), b!! - a!!)
    }

    @Test
    fun `junk is null rather than the epoch`() {
        // Zero would render as January 1970 on a bubble, which reads as data
        // loss rather than as a parse failure.
        assertNull(ServerTime.millis("not a date"))
        assertNull(ServerTime.millis(""))
        assertNull(ServerTime.millis("   "))
        assertNull(ServerTime.millis(null))
    }

    @Test
    fun `the fallback is used only when there is nothing to parse`() {
        assertEquals(123L, ServerTime.millisOr(null, orElse = 123L))
        assertEquals(123L, ServerTime.millisOr("rubbish", orElse = 123L))
        assertEquals(
            expected("2026-08-17 09:14:03"),
            ServerTime.millisOr("2026-08-17 09:14:03", orElse = 123L),
        )
    }

    @Test
    fun `parsing from many threads gives the same answer every time`() {
        // SimpleDateFormat is not thread-safe, and this is called from the sync
        // worker, the socket callback and the main thread. Shared, it does not
        // throw — it quietly returns wrong dates under load, which is the worst
        // possible failure for a timestamp.
        val stamp = "2026-08-17 09:14:03"
        val want = expected(stamp)
        val pool = Executors.newFixedThreadPool(8)
        val work = List(400) { Callable { ServerTime.millis(stamp) } }

        val results = pool.invokeAll(work).map { it.get() }
        pool.shutdownNow()

        assertEquals(setOf(want), results.toSet())
    }
}
