package com.naarni.service.ui.chat

import com.naarni.service.data.chat.ChatMessageEntity

/**
 * When the app offers to take a message back.
 *
 * Pulled out of the ViewModel so the rule can be tested directly and so there is
 * exactly one place it is written down. The **server** is the authority — see
 * `DELETE_WINDOW_SECONDS` in `api/chat.py` — and this only governs whether the
 * action is drawn, so nobody is handed a button whose only possible answer is a
 * refusal.
 */

/**
 * How long after sending the delete action stays available.
 *
 * Never shown, never counted down. A visible deadline turns a quiet affordance
 * into something people race, and the honest thing to show is simply whether the
 * action is there.
 */
const val DELETE_WINDOW_MS = 30 * 60 * 1000L

/**
 * May this message still be taken back?
 *
 * Yours, not already gone, and sent recently enough. `now` is a parameter rather
 * than read inside, so the boundary is testable without waiting half an hour.
 *
 * A message the server has never seen is deletable whatever its age: there is
 * nobody to withdraw it from, and a failed send stuck in the outbox is exactly
 * what people most need to be able to clear.
 */
fun canDeleteMessage(
    message: ChatMessageEntity,
    me: String,
    now: Long = System.currentTimeMillis(),
): Boolean {
    if (message.author != me || message.deleted) return false
    if (message.serverName.isNullOrBlank()) return true
    return now - message.createdAt <= DELETE_WINDOW_MS
}
