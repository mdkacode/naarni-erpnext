package com.naarni.service.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `@` rules, pinned.
 *
 * These are the cases that decide whether the picker feels like it reads your
 * mind or fights you, and every one of them is a string edge case rather than
 * anything you would catch by tapping around the app.
 */
class MentionsTest {

    // ------------------------------------------------------------ token scan

    @Test
    fun `an at at the caret opens an empty token`() {
        val token = Mentions.activeToken("hey @", 5)
        assertEquals("", token?.query)
    }

    @Test
    fun `the token grows as the name is typed`() {
        assertEquals("rav", Mentions.activeToken("hey @rav", 8)?.query)
    }

    @Test
    fun `a token may span a full name`() {
        assertEquals("Ravi Kumar", Mentions.activeToken("@Ravi Kumar", 11)?.query)
    }

    @Test
    fun `three words is still a name`() {
        assertEquals("Ravi Kumar Singh", Mentions.activeToken("@Ravi Kumar Singh", 17)?.query)
    }

    @Test
    fun `a fourth word means it was a sentence, not a name`() {
        assertNull(Mentions.activeToken("@one two three four", 19))
    }

    @Test
    fun `an email address does not open the picker`() {
        assertNull(Mentions.activeToken("mail me at ravi@naarni.com", 26))
    }

    @Test
    fun `a newline closes the token`() {
        assertNull(Mentions.activeToken("@ravi\nsecond line", 17))
    }

    @Test
    fun `text with no at has no token`() {
        assertNull(Mentions.activeToken("nothing here", 12))
    }

    @Test
    fun `a caret before the at sees nothing`() {
        assertNull(Mentions.activeToken("hey @ravi", 3))
    }

    @Test
    fun `the token range covers the at itself`() {
        val token = Mentions.activeToken("hey @rav", 8)!!
        assertEquals(4, token.range.first)
        assertEquals(7, token.range.last)
    }

    // ----------------------------------------------------------------- pick

    @Test
    fun `picking replaces the token and leaves a trailing space`() {
        val value = TextFieldValue("hey @rav", TextRange(8))
        val token = Mentions.activeToken(value.text, value.selection.start)!!
        val result = Mentions.applyPick(value, token, "Ravi Kumar")
        assertEquals("hey @Ravi Kumar ", result.text)
        assertEquals(16, result.selection.start)
    }

    @Test
    fun `picking mid sentence keeps the tail`() {
        val value = TextFieldValue("tell @rav about it", TextRange(9))
        val token = Mentions.activeToken(value.text, value.selection.start)!!
        val result = Mentions.applyPick(value, token, "Ravi")
        assertEquals("tell @Ravi  about it", result.text)
    }

    // ------------------------------------------------------------- survival

    @Test
    fun `a name still in the body survives`() {
        val picked = mapOf("Ravi Kumar" to "ravi@x.com")
        assertEquals(
            listOf("ravi@x.com"),
            Mentions.survivingMentions("@Ravi Kumar please check", picked),
        )
    }

    @Test
    fun `a name deleted from the body is not mentioned`() {
        val picked = mapOf("Ravi Kumar" to "ravi@x.com")
        assertTrue(Mentions.survivingMentions("please check", picked).isEmpty())
    }

    @Test
    fun `the same person named twice is mentioned once`() {
        val picked = mapOf("Ravi" to "ravi@x.com")
        assertEquals(
            listOf("ravi@x.com"),
            Mentions.survivingMentions("@Ravi and @Ravi again", picked),
        )
    }

    @Test
    fun `two different people both survive`() {
        val picked = mapOf("Ravi" to "ravi@x.com", "Sunil" to "sunil@x.com")
        val out = Mentions.survivingMentions("@Ravi @Sunil", picked)
        assertEquals(setOf("ravi@x.com", "sunil@x.com"), out.toSet())
    }

    // ------------------------------------------------------------ rendering

    @Test
    fun `annotating leaves the text identical`() {
        val out = Mentions.annotate("hi @Ravi Kumar ok", listOf("Ravi Kumar"), Color.Blue)
        assertEquals("hi @Ravi Kumar ok", out.text)
    }

    @Test
    fun `the mention is the only styled span`() {
        val out = Mentions.annotate("hi @Ravi ok", listOf("Ravi"), Color.Blue)
        assertEquals(1, out.spanStyles.size)
        assertEquals(3, out.spanStyles[0].start)
        assertEquals(8, out.spanStyles[0].end)
    }

    @Test
    fun `the longer name wins over its own prefix`() {
        val out = Mentions.annotate("@Ravi Kumar here", listOf("Ravi", "Ravi Kumar"), Color.Blue)
        assertEquals(1, out.spanStyles.size)
        assertEquals(11, out.spanStyles[0].end)
    }

    @Test
    fun `an at that names nobody is left alone`() {
        val out = Mentions.annotate("email ravi@naarni.com", listOf("Ravi"), Color.Blue)
        assertEquals("email ravi@naarni.com", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }

    @Test
    fun `a body with no labels is returned untouched`() {
        val out = Mentions.annotate("@Ravi", emptyList(), Color.Blue)
        assertEquals("@Ravi", out.text)
        assertTrue(out.spanStyles.isEmpty())
    }
}
