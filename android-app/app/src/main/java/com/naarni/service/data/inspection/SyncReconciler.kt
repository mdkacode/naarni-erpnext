package com.naarni.service.data.inspection

import com.naarni.service.data.dto.SyncReport

/**
 * Deciding what a sync response means for the queue.
 *
 * Extracted from the repository and made pure for one reason: this is where a
 * bug would silently lose an engineer's work. Everything around it — the HTTP
 * call, the Room writes — is plumbing that fails loudly. Getting *this* wrong
 * fails quietly, by marking something delivered that the server never took, and
 * nobody finds out until the certificate is short a check.
 *
 * Three rules, each of which exists because the obvious version is wrong.
 *
 * **Only what the server says it applied is marked delivered.** Not "everything
 * we sent", which is the version that loses a row the moment a batch is
 * partially rejected.
 *
 * **A row is matched on its sequence, not just its step code.** An engineer who
 * corrects an answer while the batch is in flight has moved that row on; the
 * acknowledgement belongs to the version that travelled, and applying it to the
 * new one would mark the correction delivered and drop it.
 *
 * **Anything neither applied nor rejected stays queued.** It should not happen.
 * If it does, retrying costs one request and the alternative costs a check.
 */
object SyncReconciler {

	/** One answer as it went up — the minimum needed to match an acknowledgement. */
	data class Sent(val stepCode: String, val clientSeq: Long)

	/** What to do with one queued answer, now the server has replied. */
	sealed interface Outcome {
		/** The server took it. Mark delivered, at this exact sequence. */
		data class Delivered(val stepCode: String, val clientSeq: Long, val serverPass: Int?) : Outcome

		/** The server refused it and will refuse it again. Stop resending; tell someone. */
		data class Refused(val stepCode: String, val clientSeq: Long, val reason: String) : Outcome
	}

	/**
	 * Match a batch against the server's report.
	 *
	 * [serverVerdicts] maps a step code to the server's own pass flag, so the
	 * device can store the authoritative judgement alongside its advisory one.
	 */
	fun reconcile(
		sent: List<Sent>,
		report: SyncReport,
		serverVerdicts: Map<String, Int?> = emptyMap(),
	): List<Outcome> {
		val applied = report.answers_applied.map { it.step_code }.toSet()
		val refused = report.answers_rejected.associate { it.step_code to it.reason }

		return sent.mapNotNull { row ->
			when {
				// Refusal wins over application. A step code can appear in both
				// lists only if a batch carried two versions of the same answer
				// and the later one was bad — in which case the queue must not
				// be told the step is done.
				row.stepCode in refused ->
					Outcome.Refused(row.stepCode, row.clientSeq, refused.getValue(row.stepCode))
				row.stepCode in applied ->
					Outcome.Delivered(row.stepCode, row.clientSeq, serverVerdicts[row.stepCode])
				// Neither. Left queued deliberately — see the class docstring.
				else -> null
			}
		}
	}

	/**
	 * Which submitted stages still need pushing.
	 *
	 * A stage the server accepted is done. A stage it refused for a *fixable*
	 * reason — checks still outstanding — stays queued, because those checks are
	 * work the engineer has yet to do and the submit should fire once they have.
	 * A stage refused for an unfixable reason (it is not part of this process, or
	 * it is blocked pending review) is dropped: resending it forever would keep
	 * the run permanently unsynced and the warning permanently up.
	 */
	fun stagesStillPending(submitted: List<String>, report: SyncReport): List<String> {
		val accepted = report.stages_submitted.toSet()
		val unfixable = report.stages_rejected.filter { it.missing.isEmpty() }.map { it.stage }.toSet()
		return submitted.filter { it !in accepted && it !in unfixable }
	}

	/**
	 * The run's state after a sync.
	 *
	 * `ATTENTION` is reserved for something a person has to look at. A queue that
	 * simply has more to send is `PENDING`, however long it has been — that is
	 * normal working life for a handset in a shed, and dressing it up as a
	 * problem is how a real problem gets ignored.
	 */
	fun nextState(refusals: Int, queueRemaining: Int): String = when {
		refusals > 0 -> SyncState.ATTENTION
		queueRemaining > 0 -> SyncState.PENDING
		else -> SyncState.SYNCED
	}
}
