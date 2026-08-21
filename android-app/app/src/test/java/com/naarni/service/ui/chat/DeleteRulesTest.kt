package com.naarni.service.ui.chat

import com.naarni.service.data.chat.ChatMessageEntity
import com.naarni.service.data.chat.SendStatus
import com.naarni.service.data.chat.previewOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app offers to take a message back, and what a withdrawn one reads as.
 *
 * The server has the final say on both — these cover the client's half, which is
 * whether the action is drawn at all. Getting that wrong is not a security hole
 * but it is a bad experience: a bin icon that can only answer with a refusal.
 */
class DeleteRulesTest {

    private val me = "ravi@naarni.com"
    private val now = 1_700_000_000_000L

    private fun message(
        author: String = me,
        sentMsAgo: Long = 0,
        serverName: String? = "VMCM-0001",
        deleted: Boolean = false,
        kind: String = "text",
        body: String = "hello",
    ) = ChatMessageEntity(
        clientId = "c1",
        serverName = serverName,
        room = "room-1",
        seq = 4,
        sortSeq = 4,
        author = author,
        authorName = author,
        kind = kind,
        body = body,
        deleted = deleted,
        createdAt = now - sentMsAgo,
        status = if (serverName == null) SendStatus.FAILED else SendStatus.SENT,
    )

    @Test
    fun `a message just sent can be taken back`() {
        assertTrue(canDeleteMessage(message(sentMsAgo = 1_000), me, now))
    }

    @Test
    fun `a message sent well inside the window can be taken back`() {
        assertTrue(canDeleteMessage(message(sentMsAgo = DELETE_WINDOW_MS - 1_000), me, now))
    }

    @Test
    fun `an older message cannot`() {
        assertFalse(canDeleteMessage(message(sentMsAgo = DELETE_WINDOW_MS + 1), me, now))
    }

    @Test
    fun `the boundary itself is still deletable`() {
        assertTrue(canDeleteMessage(message(sentMsAgo = DELETE_WINDOW_MS), me, now))
    }

    @Test
    fun `somebody else's message is never offered`() {
        assertFalse(canDeleteMessage(message(author = "asha@naarni.com"), me, now))
    }

    @Test
    fun `an already deleted message is not offered again`() {
        assertFalse(canDeleteMessage(message(deleted = true), me, now))
    }

    @Test
    fun `an unsent message can be cleared however old it is`() {
        // Nobody to withdraw it from — and a send stuck in the outbox for a day
        // is precisely what somebody needs to be able to get rid of.
        val stuck = message(serverName = null, sentMsAgo = 7 * 24 * 60 * 60 * 1000L)
        assertTrue(canDeleteMessage(stuck, me, now))
    }

    @Test
    fun `a deleted message reads the same in a room list row`() {
        assertEquals(DELETED_LABEL_TEXT, previewOf(message(deleted = true, body = "the secret")))
    }

    @Test
    fun `a deleted photo does not leave its caption in the room list`() {
        val photo = message(deleted = true, kind = "image", body = "look at this")
        assertEquals(DELETED_LABEL_TEXT, previewOf(photo))
    }

    private companion object {
        const val DELETED_LABEL_TEXT = "This message was deleted"
    }
}
