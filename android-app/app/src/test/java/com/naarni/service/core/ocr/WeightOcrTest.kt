package com.naarni.service.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser, against what a scale display and a phone camera actually produce
 * together. ML Kit's recognition itself needs a device; this is the half that
 * decides which of the numbers on the image is the weight, and it is the half
 * that gets it wrong.
 */
class WeightOcrTest {

    private fun kg(text: String) = WeightOcr.parse(text)?.kilograms

    @Test
    fun `reads a plain kg reading`() {
        assertEquals(232.4, kg("232.4 kg")!!, 0.001)
        assertEquals(232.4, kg("232.4KG")!!, 0.001)
        assertEquals(75.0, kg("75 Kg")!!, 0.001)
        assertEquals(12.5, kg("12.5 kilograms")!!, 0.001)
    }

    @Test
    fun `handles a comma decimal mark`() {
        // A great many scales are configured European-style.
        assertEquals(12.5, kg("12,5 kg")!!, 0.001)
    }

    @Test
    fun `handles thousands separators`() {
        assertEquals(1234.0, kg("1,234 kg")!!, 0.001)
        assertEquals(1234.5, kg("1,234.5 kg")!!, 0.001)
        assertEquals(1234.0, kg("1 234 kg")!!, 0.001)
    }

    @Test
    fun `takes the gross when a display shows tare and net`() {
        // Scales routinely show several figures at once; the largest is the one
        // being recorded at a gate.
        assertEquals(232.4, kg("NET 180.0 kg\nTARE 52.4 kg\nGROSS 232.4 kg")!!, 0.001)
    }

    @Test
    fun `a unit beats a bare number elsewhere on the image`() {
        val reading = WeightOcr.parse("SERIAL 998877\nMODEL 5000\n232.4 kg")
        assertEquals(232.4, reading!!.kilograms, 0.001)
        assertTrue("a unit should be high confidence", reading.confidence >= 0.9f)
    }

    @Test
    fun `a bare number is offered but flagged as unsure`() {
        val reading = WeightOcr.parse("232.4")
        assertEquals(232.4, reading!!.kilograms, 0.001)
        assertTrue("no unit should be low confidence", reading.confidence < WeightOcr.LOW_CONFIDENCE)
    }

    @Test
    fun `many bare numbers are least trusted of all`() {
        val one = WeightOcr.parse("232.4")!!.confidence
        val many = WeightOcr.parse("11 22 33 232.4")!!.confidence
        assertTrue("a label full of numbers is a worse guess than one number", many < one)
    }

    @Test
    fun `rejects text with nothing weight-shaped in it`() {
        assertNull(WeightOcr.parse(""))
        assertNull(WeightOcr.parse("NO READING"))
    }

    @Test
    fun `rejects numbers past any weighbridge`() {
        assertNull(kg("999999"))
        assertNull(kg("0.001 kg"))
    }

    @Test
    fun `a bare four-digit number is offered, not rejected`() {
        // "2026" could be a year on a label or 2026 kg of structure — there is no
        // way to tell from the pixels, and rejecting it would throw away real
        // weights to avoid a wrong guess the operator can see and correct. Being
        // openly unsure is the honest answer; refusing to read is not.
        val reading = WeightOcr.parse("2026")
        assertEquals(2026.0, reading!!.kilograms, 0.001)
        assertTrue("must be flagged unsure", reading.confidence < WeightOcr.LOW_CONFIDENCE)
    }

    @Test
    fun `a year next to a real kg reading does not win`() {
        assertEquals(232.4, kg("MFG 2026\n232.4 kg")!!, 0.001)
    }

    @Test
    fun `keeps the source text so a misread is visible`() {
        assertEquals("232.4 kg", WeightOcr.parse("GROSS 232.4 kg")!!.sourceText)
    }
}
