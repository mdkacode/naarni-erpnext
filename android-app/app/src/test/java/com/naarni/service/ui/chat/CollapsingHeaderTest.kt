package com.naarni.service.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hide-on-scroll rule for the conversation list's search bar.
 *
 * Tested here rather than on a handset because reproducing it on the device
 * needs an account with enough conversations to overflow the screen, and
 * manufacturing those in production to check a UI detail is not a fair trade.
 */
class CollapsingHeaderTest {

    private fun run(vararg positions: Pair<Int, Int>, threshold: Int = 12): Boolean {
        var state = CollapsingHeader.initial()
        positions.forEach { (index, offset) ->
            state = CollapsingHeader.next(state, index, offset, threshold)
        }
        return state.visible
    }

    @Test
    fun `starts visible`() {
        assertTrue(CollapsingHeader.initial().visible)
    }

    @Test
    fun `hides once the list is dragged down past the threshold`() {
        assertFalse(run(0 to 200))
    }

    @Test
    fun `comes back the moment the list moves back up`() {
        assertTrue(run(0 to 400, 0 to 300))
    }

    @Test
    fun `is always visible when pinned to the very top`() {
        // Scrolled away, then returned to the top: the bar must be back even
        // though the last movement alone would not have revealed it.
        assertTrue(run(0 to 500, 0 to 0))
    }

    @Test
    fun `ignores jitter below the threshold`() {
        // A finger resting on the screen produces a stream of 1-2px deltas. None
        // of them should flip the header.
        assertFalse(run(0 to 300, 0 to 303, 0 to 301, 0 to 304))
    }

    @Test
    fun `sub-threshold movement accumulates instead of resetting the anchor`() {
        // Ten 5px steps are a 50px drag, and must eventually hide the bar —
        // this is the bug you get from advancing the anchor on every frame.
        val positions = (1..10).map { 0 to (300 + it * 5) }.toTypedArray()
        var state = CollapsingHeader.State(visible = true, index = 0, offset = 300)
        positions.forEach { (i, o) -> state = CollapsingHeader.next(state, i, o, 12) }
        assertFalse(state.visible)
    }

    @Test
    fun `a new item index counts as movement regardless of offset`() {
        // Crossing into item 3 with a small offset must read as scrolling down,
        // not as a 0-to-4px move upward — offsets reset at item boundaries.
        assertFalse(run(1 to 0, 2 to 0, 3 to 4))
    }

    @Test
    fun `scrolling back through item indices reveals the header`() {
        assertTrue(run(4 to 100, 3 to 90))
    }

    @Test
    fun `a fling down then up ends visible`() {
        assertTrue(run(0 to 120, 1 to 40, 3 to 10, 2 to 60, 1 to 30))
    }

    @Test
    fun `the anchor follows the position that decided the state`() {
        val state = CollapsingHeader.next(CollapsingHeader.initial(), 2, 80, 12)
        assertEquals(2, state.index)
        assertEquals(80, state.offset)
    }
}
