package com.naarni.service.data.inspection

import com.naarni.service.data.dto.ProcessCondition
import com.naarni.service.data.dto.ProcessStep

/**
 * Step visibility, decided on the handset.
 *
 * A port of the server's `process_engine/conditions.py`, and needed offline for
 * a reason that is easy to miss: a conditional step is how a process asks a
 * follow-up question. "Any leaks? — Yes" reveals four more checks. Without this
 * running locally the engineer would answer Yes in a shed and simply never be
 * shown the four checks that matter most, and the gap would only surface at
 * sync, hours later, as a submit the server refuses.
 *
 * Clauses fold **left to right with no operator precedence** — `A AND B OR C`
 * reads as `(A AND B) OR C`. That matches how someone reads a list of rules top
 * to bottom, and it matches the server exactly, which matters more.
 */
object LocalConditions {

	private const val EQUALS = "Equals"
	private const val NOT_EQUALS = "Not Equals"
	private const val GREATER_THAN = "Greater Than"
	private const val LESS_THAN = "Less Than"
	private const val IS_ANSWERED = "Is Answered"
	private const val IS_NOT_ANSWERED = "Is Not Answered"
	private const val IS_PASS = "Is Pass"
	private const val IS_FAILED = "Is Failed"
	private const val IS_OUT_OF_RANGE = "Is Out Of Range"
	private const val IS_SKIPPED = "Is Skipped"

	/** What a condition can be asked about — one answered step, as judged locally. */
	data class Answered(
		val response: String? = null,
		val valueNumeric: Double? = null,
		val isAnswered: Boolean = false,
		val isPass: Boolean = false,
		val isDeviation: Boolean = false,
		val isSkipped: Boolean = false,
	)

	private fun clause(condition: ProcessCondition, answers: Map<String, Answered>): Boolean {
		val answer = answers[condition.when_step.trim()]

		when (condition.operator) {
			IS_ANSWERED -> return answer?.isAnswered == true
			IS_NOT_ANSWERED -> return answer?.isAnswered != true
			IS_SKIPPED -> return answer?.isSkipped == true
		}

		// Nothing to compare against yet. Unsatisfied rather than an error — a
		// half-finished run must still render.
		if (answer == null) return false

		return when (condition.operator) {
			IS_PASS -> answer.isPass
			IS_FAILED -> answer.isAnswered && !answer.isPass
			IS_OUT_OF_RANGE -> answer.isDeviation
			GREATER_THAN, LESS_THAN -> {
				val left = answer.valueNumeric ?: return false
				val right = condition.value?.trim()?.toDoubleOrNull() ?: return false
				if (condition.operator == GREATER_THAN) left > right else left < right
			}
			// Equals / Not Equals compare the response case-insensitively —
			// option values are authored by hand and casing drifts.
			EQUALS -> answer.response.orEmpty().trim().lowercase() ==
				condition.value.orEmpty().trim().lowercase()
			NOT_EQUALS -> answer.response.orEmpty().trim().lowercase() !=
				condition.value.orEmpty().trim().lowercase()
			else -> false
		}
	}

	/** Whether [step] should be shown, given the answers gathered so far. */
	fun isVisible(step: ProcessStep, answers: Map<String, Answered>): Boolean {
		val conditions = step.visibility_conditions
		if (conditions.isEmpty()) return true

		var result: Boolean? = null
		for (condition in conditions) {
			val value = clause(condition, answers)
			result = when {
				result == null -> value
				condition.join.uppercase() == "OR" -> result == true || value
				else -> result == true && value
			}
		}
		return result == true
	}

	/**
	 * Mandatory, visible, unanswered steps in one stage.
	 *
	 * The same interlock the server applies at submit. Computed locally so an
	 * engineer finishing a stage offline is told what is missing there and then,
	 * standing at the pack, rather than at sync time when the pack has gone.
	 */
	fun missingMandatory(
		steps: List<ProcessStep>,
		stageCode: String,
		answers: Map<String, Answered>,
	): List<String> = steps
		.filter { it.stage == stageCode && it.is_mandatory == 1 && isVisible(it, answers) }
		.filter { answers[it.step_code]?.isAnswered != true }
		.map { it.display_no ?: it.step_code }
}
