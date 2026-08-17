package com.naarni.service.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing the reaction column.
 *
 * Worth testing off-device because this runs for every visible message on every
 * recomposition, and every failure mode here is silent: a chip that quietly
 * does not draw, or a thrown exception inside a composable that takes the whole
 * thread down with it.
 */
class ReactionsTest {

    @Test
    fun `chips come back in the declared order`() {
        // Not by count — a chip that reorders itself under the finger as other
        // people react is worse than one that is in an odd place.
        val raw = """[{"code":"thanks","count":1,"users":["a"]},{"code":"like","count":2,"users":["a","b"]}]"""
        assertEquals(listOf("thanks", "like"), Reactions.parse(raw).map { it.code })
    }

    @Test
    fun `the glyph is resolved locally from the code`() {
        val raw = """[{"code":"like","count":1,"users":["a"]}]"""
        assertEquals("👍", Reactions.parse(raw).single().emoji)
    }

    @Test
    fun `a code this build does not know falls back to the server's glyph`() {
        // Forward compatibility: a newer server adding a reaction must not make
        // it vanish for anyone who has not updated.
        val raw = """[{"code":"party","emoji":"🎉","count":1,"users":["a"]}]"""
        val chip = Reactions.parse(raw).single()
        assertEquals("party", chip.code)
        assertEquals("🎉", chip.emoji)
    }

    @Test
    fun `an unknown code with no glyph is dropped rather than drawn blank`() {
        val raw = """[{"code":"party","count":1,"users":["a"]}]"""
        assertTrue(Reactions.parse(raw).isEmpty())
    }

    @Test
    fun `a zero count is not drawn`() {
        // The server never sends one, but an empty chip is the sort of artefact
        // that survives a bad migration and looks like a rendering fault.
        val raw = """[{"code":"like","count":0,"users":[]}]"""
        assertTrue(Reactions.parse(raw).isEmpty())
    }

    @Test
    fun `who reacted survives the round trip`() {
        val raw = """[{"code":"like","count":2,"users":["ravi@x.test","sunil@x.test"]}]"""
        assertEquals(listOf("ravi@x.test", "sunil@x.test"), Reactions.parse(raw).single().users)
    }

    @Test
    fun `junk parses to empty rather than throwing`() {
        // This runs inside a composable. An exception here is a crashed screen.
        assertTrue(Reactions.parse("not json").isEmpty())
        assertTrue(Reactions.parse("{}").isEmpty())
        assertTrue(Reactions.parse("[").isEmpty())
        assertTrue(Reactions.parse("").isEmpty())
        assertTrue(Reactions.parse(null).isEmpty())
    }

    @Test
    fun `unknown fields from a newer server are ignored`() {
        val raw = """[{"code":"like","count":1,"users":["a"],"reacted_at":"2026-08-17"}]"""
        assertEquals("like", Reactions.parse(raw).single().code)
    }

    @Test
    fun `the vocabulary matches the server's allow-list`() {
        // If these drift, the picker offers a reaction the server will refuse.
        assertEquals(
            listOf("like", "love", "haha", "wow", "sad", "thanks"),
            Reactions.ALL.map { it.first },
        )
    }
}
