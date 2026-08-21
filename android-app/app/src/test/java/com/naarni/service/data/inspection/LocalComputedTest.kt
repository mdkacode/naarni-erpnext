package com.naarni.service.data.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Derived values on the handset, against the server's own cases.
 *
 * Twinned with `test_engine.py::TestComputed`. The reason this exists offline at
 * all is the submit interlock: a mandatory Computed step that the device never
 * fills in is a stage an engineer cannot submit, on a check that was never
 * theirs to answer.
 */
class LocalComputedTest {

	private fun answers(vararg pairs: Pair<String, Double?>) = pairs.associate { (code, value) ->
		code to LocalConditions.Answered(valueNumeric = value, isAnswered = value != null)
	}

	@Test
	fun `diff is the spread`() {
		val value = LocalComputed.evaluate(
			"DIFF(CELL_MAX, CELL_MIN)",
			answers("CELL_MAX" to 3.95, "CELL_MIN" to 3.82),
		)

		assertEquals(0.13, value!!, 0.000001)
	}

	@Test
	fun `avg sum max min`() {
		val given = answers("A" to 2.0, "B" to 4.0, "C" to 6.0)

		assertEquals(4.0, LocalComputed.evaluate("AVG(A, B, C)", given)!!, 0.000001)
		assertEquals(12.0, LocalComputed.evaluate("SUM(A, B, C)", given)!!, 0.000001)
		assertEquals(6.0, LocalComputed.evaluate("MAX(A, B, C)", given)!!, 0.000001)
		assertEquals(2.0, LocalComputed.evaluate("MIN(A, B, C)", given)!!, 0.000001)
	}

	@Test
	fun `literals mix with step codes`() {
		val value = LocalComputed.evaluate("DIFF(A, 1.5)", answers("A" to 4.0))

		assertEquals(2.5, value!!, 0.000001)
	}

	@Test
	fun `unanswered inputs yield nothing`() {
		// A dash, not a zero. Zero is a reading; nothing is not.
		assertNull(LocalComputed.evaluate("DIFF(A, B)", answers("A" to null, "B" to null)))
	}

	@Test
	fun `malformed expressions return nothing rather than throwing`() {
		// A broken expression somebody else authored must not cost an engineer
		// their run — offline there is nobody to fix it and nowhere to go.
		val given = answers("A" to 1.0)

		assertNull(LocalComputed.evaluate("A + B", given))
		assertNull(LocalComputed.evaluate("DIFF(DIFF(A, B), C)", given))
		assertNull(LocalComputed.evaluate("NOPE(A)", given))
		assertNull(LocalComputed.evaluate("DIFF()", given))
		assertNull(LocalComputed.evaluate("", given))
		assertNull(LocalComputed.evaluate(null, given))
	}

	@Test
	fun `count failed reads outcomes rather than values`() {
		val given = mapOf(
			"A" to LocalConditions.Answered(isAnswered = true, isPass = false),
			"B" to LocalConditions.Answered(isAnswered = true, isPass = true),
			"C" to LocalConditions.Answered(isAnswered = true, isPass = false),
			// Unanswered is not failed.
			"D" to LocalConditions.Answered(isAnswered = false, isPass = false),
		)

		assertEquals(2.0, LocalComputed.evaluate("COUNT_FAILED(A, B, C, D)", given)!!, 0.0)
	}

	@Test
	fun `a partially answered set still computes over what is there`() {
		// Matching the server: it drops the missing operands rather than
		// refusing, so a spread across three cells still reads correctly when
		// only two have been measured so far.
		val value = LocalComputed.evaluate(
			"DIFF(A, B, C)",
			answers("A" to 3.9, "B" to null, "C" to 3.7),
		)

		assertEquals(0.2, value!!, 0.000001)
	}
}
