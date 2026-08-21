package com.naarni.service.data.inspection

import com.naarni.service.data.dto.SyncAnswerAck
import com.naarni.service.data.dto.SyncRejection
import com.naarni.service.data.dto.SyncReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a sync response means for the queue.
 *
 * Every test here is a way an engineer's work could disappear without anybody
 * noticing. That is not hyperbole about test coverage — it is what this class
 * does. The HTTP call fails loudly, Room fails loudly; marking a row delivered
 * that the server never took fails silently, and surfaces weeks later as a
 * certificate that is one check short.
 *
 * The scenario driving most of it: a batch goes up, and while it is in flight
 * the engineer changes an answer. That is not an edge case on a plant network,
 * it is a Tuesday — a sync can take twenty seconds on one bar and an engineer
 * works faster than that.
 */
class SyncReconcilerTest {

	private fun report(
		applied: List<String> = emptyList(),
		rejected: Map<String, String> = emptyMap(),
		stagesSubmitted: List<String> = emptyList(),
		stagesRejected: List<SyncRejection> = emptyList(),
	) = SyncReport(
		answers_applied = applied.map { SyncAnswerAck(step_code = it, changed = true) },
		answers_rejected = rejected.map { (step, reason) ->
			SyncRejection(step_code = step, reason = reason)
		},
		stages_submitted = stagesSubmitted,
		stages_rejected = stagesRejected,
	)

	private fun sent(vararg pairs: Pair<String, Long>) =
		pairs.map { SyncReconciler.Sent(it.first, it.second) }

	// ------------------------------------------------------- the happy path

	@Test
	fun `everything the server applied is marked delivered at the sequence we sent`() {
		val outcomes = SyncReconciler.reconcile(
			sent = sent("C1" to 1L, "C2" to 2L),
			report = report(applied = listOf("C1", "C2")),
			serverVerdicts = mapOf("C1" to 1, "C2" to 0),
		)

		assertEquals(
			listOf(
				SyncReconciler.Outcome.Delivered("C1", 1L, 1),
				SyncReconciler.Outcome.Delivered("C2", 2L, 0),
			),
			outcomes,
		)
	}

	@Test
	fun `the servers verdict travels with the acknowledgement`() {
		// The device judged this one for the engineer's benefit; the server's
		// answer is the record, and this is where the two meet.
		val outcomes = SyncReconciler.reconcile(
			sent = sent("T1" to 4L),
			report = report(applied = listOf("T1")),
			serverVerdicts = mapOf("T1" to 0),
		)

		assertEquals(0, (outcomes.single() as SyncReconciler.Outcome.Delivered).serverPass)
	}

	// ---------------------------------------------------- partial rejection

	@Test
	fun `a rejected row is refused and the rest are still delivered`() {
		// The property that keeps a queue drainable: one bad row costs its own
		// place and nothing else. Reject the batch as a unit and the handset
		// resends it forever, and the other answers never arrive at all.
		val outcomes = SyncReconciler.reconcile(
			sent = sent("C1" to 1L, "BAD" to 2L, "C2" to 3L),
			report = report(
				applied = listOf("C1", "C2"),
				rejected = mapOf("BAD" to "Step 'BAD' is not part of this process."),
			),
		)

		assertEquals(3, outcomes.size)
		assertTrue(outcomes[0] is SyncReconciler.Outcome.Delivered)
		assertTrue(outcomes[1] is SyncReconciler.Outcome.Refused)
		assertTrue(outcomes[2] is SyncReconciler.Outcome.Delivered)
	}

	@Test
	fun `a refusal wins over an application for the same step`() {
		// Only possible when a batch carried two versions of one answer and the
		// later was bad. Telling the queue the step is done would leave the good
		// version delivered and the bad one silently discarded.
		val outcomes = SyncReconciler.reconcile(
			sent = sent("C1" to 1L),
			report = report(applied = listOf("C1"), rejected = mapOf("C1" to "no")),
		)

		assertTrue(outcomes.single() is SyncReconciler.Outcome.Refused)
	}

	@Test
	fun `a row the server mentioned neither way stays queued`() {
		// Should not happen. If it ever does, retrying costs one request and the
		// alternative costs a check off the certificate.
		val outcomes = SyncReconciler.reconcile(
			sent = sent("C1" to 1L, "GHOST" to 2L),
			report = report(applied = listOf("C1")),
		)

		assertEquals(1, outcomes.size)
		assertEquals("C1", (outcomes.single() as SyncReconciler.Outcome.Delivered).stepCode)
	}

	// ------------------------------------------------ correction in flight

	@Test
	fun `an acknowledgement carries the sequence that travelled, not the current one`() {
		// The engineer corrected C1 to sequence 9 while sequence 1 was in the
		// air. The acknowledgement is for 1. The DAO update is keyed on that,
		// matches nothing, and the correction stays queued — which is the whole
		// mechanism, and it only works if the sequence is carried through here.
		val outcomes = SyncReconciler.reconcile(
			sent = sent("C1" to 1L),
			report = report(applied = listOf("C1")),
		)

		assertEquals(1L, (outcomes.single() as SyncReconciler.Outcome.Delivered).clientSeq)
	}

	@Test
	fun `a refusal is also pinned to the sequence that travelled`() {
		// Otherwise a corrected answer inherits the refusal of the version it
		// replaced, is excluded from every future batch, and never goes up.
		val outcomes = SyncReconciler.reconcile(
			sent = sent("C1" to 1L),
			report = report(rejected = mapOf("C1" to "bad")),
		)

		assertEquals(1L, (outcomes.single() as SyncReconciler.Outcome.Refused).clientSeq)
	}

	@Test
	fun `an empty batch reconciles to nothing rather than throwing`() {
		assertTrue(SyncReconciler.reconcile(emptyList(), report()).isEmpty())
	}

	// --------------------------------------------------------- submits

	@Test
	fun `an accepted stage leaves the queue`() {
		val left = SyncReconciler.stagesStillPending(
			submitted = listOf("S1", "S2"),
			report = report(stagesSubmitted = listOf("S1", "S2")),
		)

		assertTrue(left.isEmpty())
	}

	@Test
	fun `a stage refused for missing checks stays queued`() {
		// Those checks are work the engineer still has to do, and the submit
		// should fire by itself once they have done it.
		val left = SyncReconciler.stagesStillPending(
			submitted = listOf("S1"),
			report = report(
				stagesRejected = listOf(
					SyncRejection(stage = "S1", reason = "still need an answer", missing = listOf("7")),
				),
			),
		)

		assertEquals(listOf("S1"), left)
	}

	@Test
	fun `a stage refused for a reason that will never change is dropped`() {
		// "Not part of this process", or blocked pending review. Retrying that
		// forever would hold the run permanently unsynced and keep a warning up
		// that the engineer can do nothing about — which teaches them to ignore
		// warnings.
		val left = SyncReconciler.stagesStillPending(
			submitted = listOf("S1", "GONE"),
			report = report(
				stagesSubmitted = listOf("S1"),
				stagesRejected = listOf(
					SyncRejection(stage = "GONE", reason = "Not part of this process."),
				),
			),
		)

		assertTrue(left.isEmpty())
	}

	@Test
	fun `a stage the server said nothing about is retried`() {
		val left = SyncReconciler.stagesStillPending(
			submitted = listOf("S1"),
			report = report(),
		)

		assertEquals(listOf("S1"), left)
	}

	// ----------------------------------------------------------- run state

	@Test
	fun `a queue with more to send is pending, not a problem`() {
		// Being behind is normal working life for a handset in a shed. Calling
		// it an error is how a real error gets ignored.
		assertEquals(SyncState.PENDING, SyncReconciler.nextState(refusals = 0, queueRemaining = 12))
	}

	@Test
	fun `a refusal needs a person and says so`() {
		assertEquals(SyncState.ATTENTION, SyncReconciler.nextState(refusals = 1, queueRemaining = 0))
	}

	@Test
	fun `a refusal outranks a queue that still has work`() {
		assertEquals(SyncState.ATTENTION, SyncReconciler.nextState(refusals = 1, queueRemaining = 5))
	}

	@Test
	fun `an empty queue with no refusals is done`() {
		assertEquals(SyncState.SYNCED, SyncReconciler.nextState(refusals = 0, queueRemaining = 0))
	}
}
