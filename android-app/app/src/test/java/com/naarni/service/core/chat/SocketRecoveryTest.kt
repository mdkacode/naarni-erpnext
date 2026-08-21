package com.naarni.service.core.chat

import com.naarni.service.core.chat.FrappeSocket.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that decide whether the chat socket ever comes back.
 *
 * Both were written after the same complaint — "it doesn't connect every time" —
 * and both are the kind of thing that cannot be reproduced on demand: a
 * handshake that stalls, a lift with no signal, a session that rotated while the
 * phone was asleep. Pure functions, so the rules can be pinned down here rather
 * than argued about against a live depot.
 */
class SocketRecoveryTest {

    private val sid = "abc123"

    // ------------------------------------------------------------- supervisor

    @Test
    fun `a live socket is left alone`() {
        assertEquals(
            SocketAction.Idle,
            supervisorAction(State.Live, sid, sid, reconnectPending = false),
        )
    }

    @Test
    fun `a dial in flight is given its deadline to finish`() {
        assertEquals(
            SocketAction.Wait,
            supervisorAction(State.Connecting, sid, sid, reconnectPending = false),
        )
    }

    @Test
    fun `a scheduled backoff is not raced`() {
        // Two dials at once end up closing each other's transport.
        assertEquals(
            SocketAction.Wait,
            supervisorAction(State.Backoff, sid, sid, reconnectPending = true),
        )
    }

    @Test
    fun `a backoff that never armed is dialled`() {
        // The failure this exists for: a job cancelled with its scope, so
        // nothing is pending and nothing will ever fire.
        assertEquals(
            SocketAction.Dial,
            supervisorAction(State.Backoff, sid, sid, reconnectPending = false),
        )
    }

    @Test
    fun `an idle socket with a session is dialled`() {
        assertEquals(
            SocketAction.Dial,
            supervisorAction(State.Idle, sid, sid, reconnectPending = false),
        )
    }

    @Test
    fun `a new session outranks an auth rejection`() {
        // Fatal used to be terminal. A phone whose session expired overnight
        // then stayed dead all morning, holding a rejection that belonged to a
        // sid nobody was using any more.
        assertEquals(
            SocketAction.Redial,
            supervisorAction(State.Fatal, "fresh", "stale", reconnectPending = false),
        )
    }

    @Test
    fun `a new session outranks a dial already in flight`() {
        // The attempt in flight is arguing with the old cookie; it can only lose.
        assertEquals(
            SocketAction.Redial,
            supervisorAction(State.Connecting, "fresh", "stale", reconnectPending = false),
        )
    }

    @Test
    fun `a fatal that is still current is retried, not abandoned`() {
        assertEquals(
            SocketAction.Dial,
            supervisorAction(State.Fatal, sid, sid, reconnectPending = false),
        )
    }

    @Test
    fun `logged out means nothing to dial`() {
        // Spinning a reconnect loop with no session is pure battery burn, and
        // signing in is what will wake it.
        assertEquals(
            SocketAction.Idle,
            supervisorAction(State.Idle, null, null, reconnectPending = false),
        )
        assertEquals(
            SocketAction.Idle,
            supervisorAction(State.Backoff, "", sid, reconnectPending = false),
        )
    }

    // -------------------------------------------------------- polling fallback

    @Test
    fun `a proxy answering anything but 101 has refused the upgrade`() {
        assertTrue(isUpgradeRefusal(everOpened = false, responseCode = 403))
        assertTrue(isUpgradeRefusal(everOpened = false, responseCode = 502))
    }

    @Test
    fun `no response at all is the network, not a refusal`() {
        // This is the regression that mattered: one tunnel used to move the
        // whole process onto long-polling for the rest of its life.
        assertFalse(isUpgradeRefusal(everOpened = false, responseCode = null))
    }

    @Test
    fun `a socket that already upgraded cannot have been refused`() {
        assertFalse(isUpgradeRefusal(everOpened = true, responseCode = null))
        assertFalse(isUpgradeRefusal(everOpened = true, responseCode = 101))
    }

    @Test
    fun `a successful upgrade code is not a refusal`() {
        assertFalse(isUpgradeRefusal(everOpened = false, responseCode = 101))
    }
}
