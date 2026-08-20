package com.naarni.service.data.inspection

import com.naarni.service.data.dto.ProcessOption
import com.naarni.service.data.dto.ProcessStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device evaluator, tested against the server's own cases.
 *
 * Every test here has a twin in `process_engine/test_engine.py`, with the same
 * name and the same numbers. That is the point of the file: the device judges an
 * answer so the engineer sees a verdict instantly, and the server judges it
 * again for the record. If the two ever disagree, an engineer is told a torque
 * reading passed, walks away, and finds it quarantined the next morning — with
 * the pack already down the line.
 *
 * The advisory design keeps that from being a data-integrity problem. It does
 * not keep it from being a trust problem, and trust is the thing that makes an
 * engineer actually use the offline mode instead of waiting for signal.
 */
class LocalEvaluatorTest {

	private val passFail = listOf(
		ProcessOption(value = "Pass", label = "Pass", is_pass = 1, is_critical = 0),
		ProcessOption(value = "Fail", label = "Fail", is_pass = 0, is_critical = 1, requires_photo = 1),
		ProcessOption(value = "NA", label = "N/A", is_pass = 1, is_critical = 0),
	)

	private fun choiceStep(
		options: List<ProcessOption> = passFail,
		isCritical: Int = 1,
		requiresPhoto: Int = 1,
		policy: String? = "On Fail",
		type: String = "Choice",
	) = ProcessStep(
		step_code = "S1",
		response_type = type,
		options = options,
		is_critical = isCritical,
		weight = 1.0,
		requires_photo = requiresPhoto,
		photo_policy = policy,
	)

	private fun torqueStep(
		nominal: Double = 10.0,
		tolerance: Double = 1.0,
		decimals: Int = 1,
	) = ProcessStep(
		step_code = "T1",
		response_type = "Number with Tolerance",
		nominal_value = nominal,
		tolerance = tolerance,
		unit = "Nm",
		decimals = decimals,
		is_critical = 1,
		weight = 3.0,
	)

	private fun rangeStep(type: String, min: Double = 10.0, max: Double = 20.0) = ProcessStep(
		step_code = "R1",
		response_type = type,
		min_value = min,
		max_value = max,
		pass_condition = "Within Range",
		unit = "KG",
		weight = 1.0,
	)

	// -------------------------------------------------- numeric type parity

	/**
	 * Every numeric type the server judges as a number must be judged as a number
	 * here too.
	 *
	 * `Number in Range` was in the server's set and missing from the app's, so a
	 * range check answered offline fell through to the free-text branch — which
	 * passes whatever it is given. A value outside its band went green on the
	 * phone and red in the record once it synced, and nobody watching the phone
	 * had any way to know.
	 */
	@Test
	fun `every numeric response type is judged as a number offline`() {
		// Each type carries its band differently — a tolerance step is nominal ±
		// tolerance, the rest are min/max — so each gets a step shaped the way the
		// engine actually configures it. What is being asserted is the same for
		// all of them: the number is judged, not waved through as text.
		val cases = listOf(
			"Number" to rangeStep("Number"),
			"Number in Range" to rangeStep("Number in Range"),
			"Weight from Photo" to rangeStep("Weight from Photo"),
			"Number with Tolerance" to torqueStep(nominal = 15.0, tolerance = 5.0),
		)
		for ((type, step) in cases) {
			val outside = LocalEvaluator.evaluate(step, value = "99", photoCount = 1)
			assertEquals("$type must be answered", true, outside.isAnswered)
			assertEquals("$type outside its band must fail", false, outside.isPass)
			assertEquals("$type outside its band is a deviation", true, outside.isDeviation)

			val inside = LocalEvaluator.evaluate(step, value = "15", photoCount = 1)
			assertEquals("$type inside its band must pass", true, inside.isPass)
		}
	}

	@Test
	fun `a weight read from a photo is judged like any other number`() {
		val step = rangeStep("Weight from Photo", min = 0.01, max = 50_000.0)
		val judged = LocalEvaluator.evaluate(step, value = "232.45", photoCount = 1)
		assertEquals(true, judged.isPass)
		assertEquals(232.45, judged.valueNumeric!!, 0.001)
	}

	// ------------------------------------------------------------- choices

	@Test
	fun `choice reads its verdict from the authored option`() {
		// Never from the label. A shop that renames "Fail" to "Reject" must not
		// have every rejected pack silently start passing.
		val step = choiceStep()

		assertTrue(LocalEvaluator.evaluate(step, response = "Pass").isPass)
		assertFalse(LocalEvaluator.evaluate(step, response = "Fail").isPass)
		assertTrue(LocalEvaluator.evaluate(step, response = "Fail").isCritical)
	}

	@Test
	fun `NA counts as pass when authored that way`() {
		val verdict = LocalEvaluator.evaluate(choiceStep(), response = "NA")

		assertTrue(verdict.isPass)
		assertFalse(verdict.isCritical)
	}

	@Test
	fun `a three tier vocabulary needs no code`() {
		// An advisory / immediate split, on a step that is not itself critical —
		// so criticality can only come from the option, which is the point.
		val step = choiceStep(
			isCritical = 0,
			options = listOf(
				ProcessOption(value = "Good", label = "Good", is_pass = 1),
				ProcessOption(value = "Recommend", label = "Recommend", is_pass = 0, is_critical = 0),
				ProcessOption(value = "Immediate", label = "Immediate", is_pass = 0, is_critical = 1),
			),
		)

		assertTrue(LocalEvaluator.evaluate(step, response = "Good").isPass)
		assertFalse(LocalEvaluator.evaluate(step, response = "Recommend").isPass)
		assertFalse(LocalEvaluator.evaluate(step, response = "Recommend").isCritical)
		assertTrue(LocalEvaluator.evaluate(step, response = "Immediate").isCritical)
	}

	@Test
	fun `a critical step marked critical still fails critically on a non-critical option`() {
		// The other half of the rule, and the one the server actually applies at
		// the bottom of `evaluate`: the *step's* criticality promotes any failure.
		val step = choiceStep(
			isCritical = 1,
			options = listOf(
				ProcessOption(value = "Good", label = "Good", is_pass = 1),
				ProcessOption(value = "Recommend", label = "Recommend", is_pass = 0, is_critical = 0),
			),
		)

		assertTrue(LocalEvaluator.evaluate(step, response = "Recommend").isCritical)
	}

	@Test
	fun `an unknown option is recorded but not credited`() {
		val verdict = LocalEvaluator.evaluate(choiceStep(), response = "Maybe")

		assertTrue(verdict.isAnswered)
		assertFalse(verdict.isPass)
		assertEquals("Maybe", verdict.response)
	}

	@Test
	fun `an option matches by label when the value does not`() {
		// The server falls back to the label, and so must this — otherwise a
		// definition authored with labels only reads as unknown on the handset
		// and as a pass on the server.
		val verdict = LocalEvaluator.evaluate(choiceStep(), response = "N/A")

		assertTrue(verdict.isPass)
	}

	@Test
	fun `yes no defaults to yes passing`() {
		val step = ProcessStep(step_code = "Y1", response_type = "Yes No")

		assertTrue(LocalEvaluator.evaluate(step, response = "Yes").isPass)
		assertFalse(LocalEvaluator.evaluate(step, response = "No").isPass)
	}

	@Test
	fun `yes no vocabulary can be inverted by config`() {
		// "Any leaks?" — Yes is the bad answer.
		val step = ProcessStep(
			step_code = "Y1",
			response_type = "Yes No",
			options = listOf(
				ProcessOption(value = "Yes", label = "Yes", is_pass = 0, is_critical = 1),
				ProcessOption(value = "No", label = "No", is_pass = 1),
			),
		)

		assertFalse(LocalEvaluator.evaluate(step, response = "Yes").isPass)
		assertTrue(LocalEvaluator.evaluate(step, response = "No").isPass)
	}

	@Test
	fun `choice multi fails if any pick fails and is critical if any pick is`() {
		val step = choiceStep(type = "Choice Multi")

		val both = LocalEvaluator.evaluate(step, response = "Pass,Fail")
		assertFalse(both.isPass)
		assertTrue(both.isCritical)

		val clean = LocalEvaluator.evaluate(step, response = "Pass,NA")
		assertTrue(clean.isPass)
		assertFalse(clean.isCritical)
	}

	// ------------------------------------------------------------- numbers

	@Test
	fun `torque band is nominal plus minus tolerance`() {
		val step = torqueStep()

		assertTrue(LocalEvaluator.evaluate(step, value = "10").isPass)
		assertTrue(LocalEvaluator.evaluate(step, value = "9").isPass)
		assertTrue(LocalEvaluator.evaluate(step, value = "11").isPass)
		assertFalse(LocalEvaluator.evaluate(step, value = "8.9").isPass)
		assertFalse(LocalEvaluator.evaluate(step, value = "11.1").isPass)
	}

	@Test
	fun `greater than min ignores a zero max`() {
		// The bug this guards: a Float column defaults to 0, so an unset maximum
		// reads as "must be at most zero" and fails every real reading.
		val step = ProcessStep(
			step_code = "N1",
			response_type = "Number",
			min_value = 3.5,
			max_value = 0.0,
			pass_condition = "Greater Than Min",
		)

		assertTrue(LocalEvaluator.evaluate(step, value = "4").isPass)
		assertTrue(LocalEvaluator.evaluate(step, value = "400").isPass)
		assertFalse(LocalEvaluator.evaluate(step, value = "3.4").isPass)
	}

	@Test
	fun `less than max ignores a zero min`() {
		val step = ProcessStep(
			step_code = "N1",
			response_type = "Number",
			min_value = 0.0,
			max_value = 5.0,
			pass_condition = "Less Than Max",
		)

		assertTrue(LocalEvaluator.evaluate(step, value = "-2").isPass)
		assertTrue(LocalEvaluator.evaluate(step, value = "5").isPass)
		assertFalse(LocalEvaluator.evaluate(step, value = "5.1").isPass)
	}

	@Test
	fun `any value passes whatever is entered`() {
		val step = ProcessStep(
			step_code = "N1",
			response_type = "Number",
			min_value = 3.0,
			max_value = 4.0,
			pass_condition = "Any Value",
		)

		assertTrue(LocalEvaluator.evaluate(step, value = "-999").isPass)
	}

	@Test
	fun `a numeric step with no reading is unanswered rather than failed`() {
		// Blank is not zero. Treating it as zero would mark every untouched
		// torque check as a critical failure the moment the screen loaded.
		val verdict = LocalEvaluator.evaluate(torqueStep(), value = null)

		assertFalse(verdict.isAnswered)
		assertFalse(verdict.isPass)
		assertFalse(verdict.isCritical)
	}

	@Test
	fun `junk in a numeric field is unanswered rather than zero`() {
		val verdict = LocalEvaluator.evaluate(torqueStep(), value = "abc")

		assertFalse(verdict.isAnswered)
		assertEquals(null, verdict.valueNumeric)
	}

	// ---------------------------------------------------------------- misc

	@Test
	fun `skip is not a verdict`() {
		val verdict = LocalEvaluator.evaluate(choiceStep(), response = "Fail", skipped = true)

		assertFalse(verdict.isPass)
		assertFalse(verdict.isCritical)
		assertFalse(verdict.isAnswered)
	}

	@Test
	fun `a scan step passes on partial capture`() {
		val step = ProcessStep(step_code = "SC1", response_type = "Scan", scan_count = 4)

		val verdict = LocalEvaluator.evaluate(step, scanCount = 2)

		assertTrue(verdict.isPass)
		assertEquals("2 of 4 captured", verdict.specSummary)
	}

	@Test
	fun `a photo only step needs its photos`() {
		val step = ProcessStep(step_code = "P1", response_type = "Photo Only", min_photos = 2)

		assertFalse(LocalEvaluator.evaluate(step, photoCount = 1).isPass)
		assertTrue(LocalEvaluator.evaluate(step, photoCount = 2).isPass)
	}

	@Test
	fun `a queued photo counts towards the step immediately`() {
		// The count passed in includes photos still sitting in the upload queue.
		// Waiting for the upload would leave a Photo Only step red in a shed with
		// no signal, which is the one place it must not be.
		val step = ProcessStep(step_code = "P1", response_type = "Photo Only", min_photos = 1)

		assertTrue(LocalEvaluator.evaluate(step, photoCount = 1).isPass)
	}

	@Test
	fun `spec summary reads like the paper sheet`() {
		assertEquals("10 ± 1 Nm (9–11)", LocalEvaluator.specSummary(torqueStep()))
		assertEquals(
			"≥ 3.5",
			LocalEvaluator.specSummary(
				ProcessStep(
					step_code = "N1",
					response_type = "Number",
					min_value = 3.5,
					pass_condition = "Greater Than Min",
					decimals = 1,
				),
			),
		)
	}

	@Test
	fun `the servers own spec summary wins when the definition carried one`() {
		// So what the engineer reads offline is the exact string the certificate
		// will print, rather than this file's best reconstruction of it.
		val step = torqueStep().copy(spec_summary = "10 ± 1 Nm (as per WI-204)")

		assertEquals("10 ± 1 Nm (as per WI-204)", LocalEvaluator.specSummary(step))
	}

	@Test
	fun `photo policy decides when evidence is asked for`() {
		val onFail = choiceStep(policy = "On Fail")
		assertTrue(LocalEvaluator.photoRequired(onFail, isPass = false))
		assertFalse(LocalEvaluator.photoRequired(onFail, isPass = true))

		val always = choiceStep(policy = "Always")
		assertTrue(LocalEvaluator.photoRequired(always, isPass = true))

		val never = choiceStep(policy = "Never")
		assertFalse(LocalEvaluator.photoRequired(never, isPass = false))

		val off = choiceStep(requiresPhoto = 0, policy = "Always")
		assertFalse(LocalEvaluator.photoRequired(off, isPass = false))
	}

	@Test
	fun `an option can demand a photo on its own`() {
		val step = choiceStep(requiresPhoto = 0)
		val fail = LocalEvaluator.optionFor(step, "Fail")

		assertTrue(LocalEvaluator.photoRequired(step, isPass = false, chosen = fail))
	}

	@Test
	fun `a section note is answered by being shown`() {
		val step = ProcessStep(step_code = "SN1", response_type = "Section Note")

		val verdict = LocalEvaluator.evaluate(step)

		assertTrue(verdict.isPass)
		assertTrue(verdict.isAnswered)
	}

	@Test
	fun `free text passes once it has content`() {
		val step = ProcessStep(step_code = "TX1", response_type = "Text Short")

		assertFalse(LocalEvaluator.evaluate(step, response = "").isAnswered)
		assertTrue(LocalEvaluator.evaluate(step, response = "Cell 4 replaced").isPass)
		assertEquals("Cell 4 replaced", LocalEvaluator.evaluate(step, response = "Cell 4 replaced").valueText)
	}

	@Test
	fun `criticality needs both the flag and a failure`() {
		// A step marked critical that passes is not critical. Getting this
		// backwards would quarantine every pack that was inspected correctly.
		val critical = choiceStep(isCritical = 1)

		assertFalse(LocalEvaluator.evaluate(critical, response = "Pass").isCritical)
		assertTrue(LocalEvaluator.evaluate(critical, response = "Fail").isCritical)

		val ordinary = choiceStep(
			isCritical = 0,
			options = listOf(
				ProcessOption(value = "Pass", label = "Pass", is_pass = 1),
				ProcessOption(value = "Fail", label = "Fail", is_pass = 0),
			),
		)
		assertFalse(LocalEvaluator.evaluate(ordinary, response = "Fail").isCritical)
	}
}
