package com.naarni.service.data.inspection

import com.naarni.service.data.dto.ProcessCondition
import com.naarni.service.data.dto.ProcessOption
import com.naarni.service.data.dto.ProcessStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what an engineer sees, and what still has to go up.
 *
 * Two things are tested here, and both are pure logic deliberately extracted
 * from anything that needs a database or a network — so they can be asserted
 * exactly, and so a regression in either shows up in seconds rather than on a
 * shop floor.
 *
 * **Conditional visibility**, because it is the difference between an inspection
 * and a partial one. "Any leaks? — Yes" reveals four more checks. Get this
 * wrong offline and the engineer answers Yes in a shed, is never shown the four
 * checks that matter most, and finds out at sync time that the stage will not
 * submit — with the pack long gone.
 *
 * **The submit interlock**, because the whole promise of offline work is that
 * the engineer is told what is missing while they are still standing at the
 * pack.
 */
class InspectionQueueTest {

	private fun step(
		code: String,
		stage: String = "S1",
		mandatory: Int = 1,
		conditions: List<ProcessCondition> = emptyList(),
		displayNo: String? = null,
	) = ProcessStep(
		step_code = code,
		stage = stage,
		label = code,
		display_no = displayNo,
		response_type = "Choice",
		is_mandatory = mandatory,
		options = listOf(
			ProcessOption(value = "Pass", label = "Pass", is_pass = 1),
			ProcessOption(value = "Fail", label = "Fail", is_pass = 0, is_critical = 1),
		),
		visibility_conditions = conditions,
	)

	private fun answered(
		response: String? = "Pass",
		pass: Boolean = true,
		deviation: Boolean = false,
		skipped: Boolean = false,
		numeric: Double? = null,
	) = LocalConditions.Answered(
		response = response,
		valueNumeric = numeric,
		isAnswered = true,
		isPass = pass,
		isDeviation = deviation,
		isSkipped = skipped,
	)

	// ------------------------------------------------------- visibility

	@Test
	fun `a step with no conditions is always visible`() {
		assertTrue(LocalConditions.isVisible(step("C1"), emptyMap()))
	}

	@Test
	fun `a follow-up appears only once its trigger is answered`() {
		// The case that makes conditional visibility worth porting at all.
		val followUp = step(
			"C2",
			conditions = listOf(ProcessCondition(when_step = "C1", operator = "Equals", value = "Fail")),
		)

		assertFalse(
			"nothing answered yet — the follow-up must stay hidden",
			LocalConditions.isVisible(followUp, emptyMap()),
		)
		assertFalse(LocalConditions.isVisible(followUp, mapOf("C1" to answered("Pass"))))
		assertTrue(
			LocalConditions.isVisible(followUp, mapOf("C1" to answered("Fail", pass = false))),
		)
	}

	@Test
	fun `equals ignores casing because option values are hand-authored`() {
		val followUp = step(
			"C2",
			conditions = listOf(ProcessCondition(when_step = "C1", operator = "Equals", value = "fail")),
		)

		assertTrue(LocalConditions.isVisible(followUp, mapOf("C1" to answered("Fail", pass = false))))
	}

	@Test
	fun `is failed needs an answer, not merely the absence of a pass`() {
		// An unanswered step is not a failed step. Treating it as one would
		// unfold every follow-up in the process the moment a run was opened.
		val followUp = step(
			"C2",
			conditions = listOf(ProcessCondition(when_step = "C1", operator = "Is Failed")),
		)

		assertFalse(LocalConditions.isVisible(followUp, emptyMap()))
		assertTrue(LocalConditions.isVisible(followUp, mapOf("C1" to answered(pass = false))))
	}

	@Test
	fun `is not answered is true when the step is absent`() {
		val followUp = step(
			"C2",
			conditions = listOf(ProcessCondition(when_step = "C1", operator = "Is Not Answered")),
		)

		assertTrue(LocalConditions.isVisible(followUp, emptyMap()))
		assertFalse(LocalConditions.isVisible(followUp, mapOf("C1" to answered())))
	}

	@Test
	fun `numeric comparison reads the value not the response`() {
		val followUp = step(
			"C2",
			conditions = listOf(
				ProcessCondition(when_step = "T1", operator = "Greater Than", value = "12"),
			),
		)

		assertTrue(LocalConditions.isVisible(followUp, mapOf("T1" to answered(numeric = 14.0))))
		assertFalse(LocalConditions.isVisible(followUp, mapOf("T1" to answered(numeric = 11.0))))
		// No reading at all is not "greater than" anything.
		assertFalse(LocalConditions.isVisible(followUp, mapOf("T1" to answered(numeric = null))))
	}

	@Test
	fun `clauses fold left to right with no precedence`() {
		// A AND B OR C reads as (A AND B) OR C — matching the server exactly,
		// which matters more than matching anyone's idea of precedence.
		val followUp = step(
			"C4",
			conditions = listOf(
				ProcessCondition(when_step = "C1", operator = "Is Pass"),
				ProcessCondition(when_step = "C2", operator = "Is Pass", join = "AND"),
				ProcessCondition(when_step = "C3", operator = "Is Pass", join = "OR"),
			),
		)

		// (pass AND fail) OR pass  ->  true
		assertTrue(
			LocalConditions.isVisible(
				followUp,
				mapOf(
					"C1" to answered(),
					"C2" to answered(pass = false),
					"C3" to answered(),
				),
			),
		)
		// (pass AND fail) OR fail  ->  false
		assertFalse(
			LocalConditions.isVisible(
				followUp,
				mapOf(
					"C1" to answered(),
					"C2" to answered(pass = false),
					"C3" to answered(pass = false),
				),
			),
		)
	}

	// --------------------------------------------------------- interlock

	@Test
	fun `the interlock names every mandatory check still missing`() {
		val steps = listOf(
			step("C1", displayNo = "1"),
			step("C2", displayNo = "2"),
			step("C3", displayNo = "3"),
		)

		val missing = LocalConditions.missingMandatory(steps, "S1", mapOf("C2" to answered()))

		assertEquals(listOf("1", "3"), missing)
	}

	@Test
	fun `an optional check never holds up a submit`() {
		val steps = listOf(step("C1", displayNo = "1"), step("C2", mandatory = 0, displayNo = "2"))

		val missing = LocalConditions.missingMandatory(steps, "S1", mapOf("C1" to answered()))

		assertTrue(missing.isEmpty())
	}

	@Test
	fun `a hidden mandatory check does not hold up a submit`() {
		// The interlock has to respect conditions or it becomes unsatisfiable:
		// a follow-up that never unfolded can never be answered, and demanding
		// it would strand the engineer on a stage they have genuinely finished.
		val steps = listOf(
			step("C1", displayNo = "1"),
			step(
				"C2",
				displayNo = "2",
				conditions = listOf(
					ProcessCondition(when_step = "C1", operator = "Equals", value = "Fail"),
				),
			),
		)

		val missing = LocalConditions.missingMandatory(steps, "S1", mapOf("C1" to answered("Pass")))

		assertTrue("C2 never unfolded, so it cannot be outstanding", missing.isEmpty())
	}

	@Test
	fun `a revealed mandatory check does hold up a submit`() {
		val steps = listOf(
			step("C1", displayNo = "1"),
			step(
				"C2",
				displayNo = "2",
				conditions = listOf(
					ProcessCondition(when_step = "C1", operator = "Equals", value = "Fail"),
				),
			),
		)

		val missing = LocalConditions.missingMandatory(
			steps,
			"S1",
			mapOf("C1" to answered("Fail", pass = false)),
		)

		assertEquals(listOf("2"), missing)
	}

	@Test
	fun `a skipped check counts as answered for the interlock`() {
		// Skipping is a first-class action with a recorded reason. It is not a
		// gap, and blocking on it would leave an engineer who genuinely cannot
		// reach a weld unable to finish the stage at all.
		val steps = listOf(step("C1", displayNo = "1"))

		val missing = LocalConditions.missingMandatory(
			steps,
			"S1",
			mapOf("C1" to answered(response = null, pass = false, skipped = true)),
		)

		assertTrue(missing.isEmpty())
	}

	@Test
	fun `another stage's checks are not this stage's problem`() {
		val steps = listOf(step("C1", stage = "S1", displayNo = "1"), step("C2", stage = "S2", displayNo = "2"))

		val missing = LocalConditions.missingMandatory(steps, "S1", mapOf("C1" to answered()))

		assertTrue(missing.isEmpty())
	}
}
