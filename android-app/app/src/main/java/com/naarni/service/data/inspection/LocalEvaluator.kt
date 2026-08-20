package com.naarni.service.data.inspection

import com.naarni.service.data.dto.ProcessOption
import com.naarni.service.data.dto.ProcessStep
import kotlin.math.abs

/**
 * Judging an answer on the handset, so the engineer never waits to find out.
 *
 * This is a port of the server's `process_engine/evaluation.py`, and it is
 * deliberately a port rather than an improvement — every rule below exists on
 * the server too, and the two agreeing matters far more than either being
 * clever. The parity tests in `LocalEvaluatorTest` are lifted from the server's
 * own `test_engine.py` for exactly that reason.
 *
 * **Advisory, always.** Nothing this file decides is ever the record. The device
 * posts the raw answer and the server judges it again; that verdict overwrites
 * this one on sync, and a disagreement is surfaced rather than swallowed. Which
 * is what makes an on-device evaluator safe to have at all: it decides what the
 * engineer *sees* — the green tick, the prompt for a photo of a failed weld —
 * and never what the certificate says.
 *
 * Without it the offline story does not work. A failing torque reading has to
 * ask for its photograph while the engineer is still holding the wrench; a
 * verdict that arrives four hours later, when the van reaches signal, is a
 * verdict about a pack that has already left the shed.
 */
object LocalEvaluator {

	// Mirrors of the server's `constants.py`. Spelled out rather than derived so
	// a renamed response type fails a test here instead of silently evaluating
	// as free text on one side of the wire and a number on the other.
	private const val CHOICE = "Choice"
	private const val CHOICE_MULTI = "Choice Multi"
	private const val YES_NO = "Yes No"
	private const val NUMBER = "Number"
	private const val NUMBER_IN_RANGE = "Number in Range"
	private const val NUMBER_WITH_TOLERANCE = "Number with Tolerance"
	private const val WEIGHT_PHOTO = "Weight from Photo"
	private const val COMPUTED = "Computed"
	private const val PHOTO_ONLY = "Photo Only"
	private const val SCAN = "Scan"
	private const val SECTION_NOTE = "Section Note"

	/**
	 * Must match `constants.NUMERIC_TYPES` on the server, exactly.
	 *
	 * `Number in Range` was missing here while the server had it, which is the
	 * drift the comment above warns about: a range check answered offline was
	 * judged as free text — always a pass, whatever the number — and then
	 * re-judged properly on sync, so a value outside its band went green on the
	 * phone and red in the record.
	 */
	private val NUMERIC_TYPES =
		setOf(NUMBER, NUMBER_IN_RANGE, NUMBER_WITH_TOLERANCE, COMPUTED, WEIGHT_PHOTO)

	private const val WITHIN_RANGE = "Within Range"
	private const val GREATER_THAN_MIN = "Greater Than Min"
	private const val LESS_THAN_MAX = "Less Than Max"
	private const val EQUALS_NOMINAL = "Equals Nominal"
	private const val ANY_VALUE = "Any Value"

	/** What one answer came to. */
	data class Verdict(
		val isPass: Boolean = false,
		val isCritical: Boolean = false,
		val isDeviation: Boolean = false,
		val isAnswered: Boolean = false,
		val valueNumeric: Double? = null,
		val valueText: String? = null,
		val response: String? = null,
		val specSummary: String = "",
	)

	/**
	 * The effective `(low, high)` band for a numeric step. Either end may be null.
	 *
	 * `pass_condition` decides which bounds are operands, and it has to: a Frappe
	 * Float is non-nullable and defaults to zero, so `min_value = 0` is
	 * indistinguishable from "no minimum". Guessing would silently fail every
	 * reading below zero on a step that never had a lower bound.
	 */
	fun band(step: ProcessStep): Pair<Double?, Double?> {
		if (step.response_type == NUMBER_WITH_TOLERANCE) {
			val tol = abs(step.tolerance)
			return step.nominal_value - tol to step.nominal_value + tol
		}
		return when (step.pass_condition ?: WITHIN_RANGE) {
			ANY_VALUE -> null to null
			GREATER_THAN_MIN -> step.min_value to null
			LESS_THAN_MAX -> null to step.max_value
			EQUALS_NOMINAL -> {
				val tol = abs(step.tolerance)
				step.nominal_value - tol to step.nominal_value + tol
			}
			else -> step.min_value to step.max_value
		}
	}

	/**
	 * The specification in words — "10 ± 1 Nm (9–11)".
	 *
	 * Prefers the server's own `spec_summary` when the definition carried one,
	 * so what the engineer reads offline is the same string the certificate will
	 * print. This is only the fallback for a definition cached before the server
	 * started pre-rendering it.
	 */
	fun specSummary(step: ProcessStep): String {
		step.spec_summary?.takeIf { it.isNotBlank() }?.let { return it }
		if (step.response_type !in NUMERIC_TYPES) return ""

		val unit = step.unit?.trim().orEmpty()
		val suffix = if (unit.isNotEmpty()) " $unit" else ""
		val decimals = if (step.decimals > 0) step.decimals else 2

		fun fmt(v: Double): String {
			val s = String.format("%.${decimals}f", v).trimEnd('0').trimEnd('.')
			return s.ifEmpty { "0" }
		}

		if (step.response_type == NUMBER_WITH_TOLERANCE || step.pass_condition == EQUALS_NOMINAL) {
			val tol = abs(step.tolerance)
			return if (tol != 0.0) {
				"${fmt(step.nominal_value)} ± ${fmt(tol)}$suffix " +
					"(${fmt(step.nominal_value - tol)}–${fmt(step.nominal_value + tol)})"
			} else {
				"${fmt(step.nominal_value)}$suffix"
			}
		}

		val (lo, hi) = band(step)
		return when {
			lo != null && hi != null -> "${fmt(lo)}–${fmt(hi)}$suffix"
			lo != null -> "≥ ${fmt(lo)}$suffix"
			hi != null -> "≤ ${fmt(hi)}$suffix"
			else -> "Any value$suffix"
		}
	}

	/** The authored option matching [response], by value first and then by label. */
	fun optionFor(step: ProcessStep, response: String?): ProcessOption? {
		val target = response?.trim().orEmpty()
		if (target.isEmpty()) return null
		return step.options.firstOrNull { it.value.trim() == target }
			?: step.options.firstOrNull { it.label.trim() == target }
	}

	/**
	 * Judge one answer.
	 *
	 * @param photoCount photos already attached to this step, including ones
	 *   still queued on the device — a Photo Only step must go green the moment
	 *   the shutter closes, not when the upload finishes.
	 */
	fun evaluate(
		step: ProcessStep,
		response: String? = null,
		value: String? = null,
		skipped: Boolean = false,
		photoCount: Int = 0,
		scanCount: Int = 0,
	): Verdict {
		val trimmed = response?.trim()
		val spec = specSummary(step)
		val blank = Verdict(response = trimmed, specSummary = spec)

		// A skip is not a verdict. Scoring decides whether it counts against the
		// run, per the process's own `skipped_steps_count_as`.
		if (skipped) return blank

		if (step.response_type == SECTION_NOTE) {
			return blank.copy(isPass = true, isAnswered = true)
		}

		// A null here means "nothing was entered" — and that is *not* the same as
		// a failure. The server returns the blank verdict immediately in that
		// case, before the criticality rule at the bottom of this function can
		// see it. Falling through instead would mark every untouched critical
		// check as a critical failure the moment the screen drew, painting a
		// fresh inspection solid red.
		val judged = when {
			step.response_type in NUMERIC_TYPES -> judgeNumeric(step, value, blank)
			step.response_type in setOf(CHOICE, CHOICE_MULTI, YES_NO) -> judgeChoice(step, trimmed, blank)
			step.response_type == PHOTO_ONLY -> blank.copy(
				isAnswered = photoCount > 0,
				isPass = photoCount >= maxOf(1, step.min_photos),
			)
			step.response_type == SCAN -> {
				val expected = maxOf(1, step.scan_count)
				blank.copy(
					isAnswered = scanCount > 0,
					// A scan is never mandatory, so a partial capture still passes
					// the step — the shortfall shows up as traceability
					// completeness on the run, not as a failed check.
					isPass = scanCount > 0,
					valueNumeric = scanCount.toDouble(),
					specSummary = "$scanCount of $expected captured",
				)
			}
			// Text, Date, Datetime, Signature, Link — answered is passed.
			//
			// Either field, and this matters more than it looks. The app has two
			// renderers for a text step and they disagree about where a typed
			// answer goes: the list card puts it in `response`, the full-screen
			// runner in `value`. Judging only `response` meant every serial and
			// batch number typed on the runner — the screen operators actually
			// use — was scored as unanswered, so the module showed 0 of 26 with
			// answers on the screen and the submit gate asked for checks that had
			// already been done. The server has the same fix, for the same reason.
			else -> (trimmed?.takeIf { it.isNotEmpty() } ?: value?.trim()?.takeIf { it.isNotEmpty() })
				?.let { text ->
					blank.copy(response = text, valueText = text, isAnswered = true, isPass = true)
				}
		} ?: return blank

		// Choice Multi has already settled its own criticality across several
		// picks, so it is returned as-is rather than being re-judged here.
		if (step.response_type == CHOICE_MULTI) return judged

		// A step only becomes *critically* failed if it is marked critical and failed.
		return when {
			!judged.isPass && step.is_critical == 1 -> judged.copy(isCritical = true)
			judged.isPass -> judged.copy(isCritical = false)
			else -> judged
		}
	}

	/** Null when nothing was entered — see the note at the call site. */
	private fun judgeNumeric(step: ProcessStep, value: String?, blank: Verdict): Verdict? {
		val num = value?.trim()?.toDoubleOrNull() ?: return null
		val (lo, hi) = band(step)
		var inBand = true
		if (lo != null && num < lo) inBand = false
		if (hi != null && num > hi) inBand = false
		return blank.copy(
			valueNumeric = num,
			isAnswered = true,
			isPass = inBand,
			isDeviation = !inBand,
		)
	}

	/** Null when nothing was chosen — see the note at the call site. */
	private fun judgeChoice(step: ProcessStep, response: String?, blank: Verdict): Verdict? {
		if (response.isNullOrEmpty()) return null

		if (step.response_type == CHOICE_MULTI) {
			// Every chosen option must pass; any critical one is critical.
			val picks = response.split(",").map { it.trim() }.filter { it.isNotEmpty() }
			val known = picks.mapNotNull { optionFor(step, it) }
			if (known.isEmpty()) return blank.copy(isAnswered = true, isPass = true)
			val allPass = known.all { it.is_pass == 1 }
			return blank.copy(
				isAnswered = true,
				isPass = allPass,
				isDeviation = !allPass,
				isCritical = known.any { it.is_critical == 1 },
			)
		}

		val option = optionFor(step, response)
		val pass = when {
			option != null -> option.is_pass == 1
			// No authored vocabulary — the sensible default is that Yes passes.
			step.response_type == YES_NO -> response.lowercase() in setOf("yes", "1", "true")
			// An answer the template no longer offers: record it, do not credit it.
			else -> false
		}
		return blank.copy(
			isAnswered = true,
			isPass = pass,
			isDeviation = !pass,
			isCritical = option?.is_critical == 1,
		)
	}

	/**
	 * Whether this answer should prompt for a photograph.
	 *
	 * Never a hard block — the runner surfaces it and the engineer can move on,
	 * which is the documented behaviour of every gate in this app. An option row
	 * can demand a photo on its own, which is how "Fail" asks for evidence while
	 * "Pass" does not.
	 */
	fun photoRequired(step: ProcessStep, isPass: Boolean, chosen: ProcessOption? = null): Boolean {
		if (chosen != null && chosen.requires_photo == 1) return true
		if (step.requires_photo != 1) return false
		return when (step.photo_policy ?: "On Fail") {
			"Never" -> false
			"Always" -> true
			"On Fail" -> !isPass
			"On Pass" -> isPass
			else -> false
		}
	}
}
