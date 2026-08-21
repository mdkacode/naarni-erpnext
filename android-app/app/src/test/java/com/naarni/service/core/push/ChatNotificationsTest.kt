package com.naarni.service.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The notification tray's redraw cache.
 *
 * This is the piece with no Android in it and the most ways to go wrong: it is
 * mutated from FCM's callback thread, from an IO thread when somebody replies
 * inline, and from the main thread when a conversation is opened. The failure
 * it had was not a wrong tray — it was a ConcurrentModificationException thrown
 * out of `onMessageReceived`, which takes the push process down with it.
 */
class ChatNotificationsTest {

    @Before
    fun reset() = ChatNotifications.resetHistoryForTest()

    private fun line(text: String) =
        ChatNotifications.Line(sender = "Ravi", text = text, at = 1L, image = null)

    @Test
    fun `a room's run is capped so the tray stays readable`() {
        repeat(20) { ChatNotifications.record("room-a", line("m$it")) }
        val lines = ChatNotifications.record("room-a", line("last"))

        assertEquals(6, lines.size)
        assertEquals("last", lines.last().text)
    }

    @Test
    fun `the oldest lines are the ones dropped`() {
        (1..8).forEach { ChatNotifications.record("room-a", line("m$it")) }
        val lines = ChatNotifications.record("room-a", line("m9"))

        assertEquals(listOf("m4", "m5", "m6", "m7", "m8", "m9"), lines.map { it.text })
    }

    @Test
    fun `the cache is bounded across rooms, not only within one`() {
        // A manager is pushed from dozens of rooms they never open. Unbounded,
        // every one is retained for the life of the process, along with the
        // staged image URI on each line.
        repeat(40) { ChatNotifications.record("room-$it", line("hello")) }

        assertTrue(
            "cached ${ChatNotifications.cachedRoomCount()} rooms",
            ChatNotifications.cachedRoomCount() <= 12,
        )
    }

    @Test
    fun `rooms stay separate`() {
        ChatNotifications.record("room-a", line("for a"))
        val b = ChatNotifications.record("room-b", line("for b"))

        assertEquals(1, b.size)
        assertEquals("for b", b.single().text)
    }

    @Test
    fun `the returned run is a snapshot, not the live list`() {
        // build() iterates whatever record() hands back. Were that the live
        // list, another thread appending mid-draw would throw.
        val first = ChatNotifications.record("room-a", line("one"))
        ChatNotifications.record("room-a", line("two"))

        assertEquals(1, first.size)
    }

    @Test
    fun `recording from many threads at once does not throw`() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val failures = mutableListOf<Throwable>()

        repeat(8) { worker ->
            pool.execute {
                try {
                    start.await()
                    repeat(200) { ChatNotifications.record("room-${worker % 3}", line("m$it")) }
                } catch (t: Throwable) {
                    synchronized(failures) { failures += t }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(emptyList<Throwable>(), failures)
    }

    @Test
    fun `a notification id is stable per room and distinct between rooms`() {
        assertEquals(ChatNotifications.idFor("CHAT-00001"), ChatNotifications.idFor("CHAT-00001"))
        assertNotEquals(ChatNotifications.idFor("CHAT-00001"), ChatNotifications.idFor("CHAT-00002"))
    }
}
