package com.naarni.service.data.inspection

import kotlin.math.round

/**
 * Derived values, computed on the handset.
 *
 * A port of the server's `process_engine/computed.py`, down to the grammar:
 * one function call over earlier step codes and numeric literals, nothing
 * nested and nothing infix.
 *
 *     DIFF(CELL_V_MAX, CELL_V_MIN)
 *     AVG(MOD1_V, MOD2_V, MOD3_V)
 *     SUM(A, B, C)   MAX(…)   MIN(…)   COUNT_FAILED(…)
 *
 * Worth porting rather than deferring to the server, for a reason that only
 * shows up offline: a Computed step can be mandatory, and the submit interlock
 * asks whether every mandatory step has been answered. Without this the derived
 * step is never answered on the handset, the stage will not submit, and an
 * engineer with a finished pack in front of them is stuck on a check they
 * cannot possibly fill in — because it is not theirs to fill in.
 *
 * Anything unparseable returns null rather than throwing. A broken expression
 * authored by somebody else must never cost an engineer their run.
 */
object LocalComputed {

	/** `FUNC(arg, arg, …)` — nothing nested, nothing infix. */
	private val CALL = Regex("""^\s*([A-Z_]+)\s*\(\s*([^()]*?)\s*\)\s*$""")

	/**
	 * Compute a derived value from the answers gathered so far.
	 *
	 * Returns null when the expression is malformed or none of its inputs have
	 * been answered yet, in which case the step stays unanswered and the
	 * engineer sees a dash — exactly as they would online.
	 */
	fun evaluate(expression: String?, answers: Map<String, LocalConditions.Answered>): Double? {
		if (expression.isNullOrBlank()) return null
		val match = CALL.matchEntire(expression) ?: return null

		val func = match.groupValues[1].uppercase()
		val args = match.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
		if (args.isEmpty()) return null

		if (func == "COUNT_FAILED") {
			// Operates on outcomes rather than values, so it resolves separately.
			return args.count { token ->
				val answer = answers[token]
				answer != null && answer.isAnswered && !answer.isPass
			}.toDouble()
		}

		val present = args.mapNotNull { resolve(it, answers) }
		if (present.isEmpty()) return null

		return when (func) {
			// The spread across the inputs — max minus min. With the usual two
			// arguments this is exactly the "Difference:" line on the paper sheet.
			"DIFF" -> round6(present.max() - present.min())
			"SUM" -> round6(present.sum())
			"AVG" -> round6(present.sum() / present.size)
			"MAX" -> round6(present.max())
			"MIN" -> round6(present.min())
			else -> null
		}
	}

	/** A token is either a numeric literal or an earlier step's numeric answer. */
	private fun resolve(token: String, answers: Map<String, LocalConditions.Answered>): Double? =
		token.toDoubleOrNull() ?: answers[token]?.valueNumeric

	private fun round6(value: Double): Double = round(value * 1_000_000) / 1_000_000
}
