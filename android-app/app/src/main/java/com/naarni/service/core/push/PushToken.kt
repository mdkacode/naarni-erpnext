package com.naarni.service.core.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.naarni.service.appContainer
import kotlinx.coroutines.tasks.await

/**
 * Fetch the current FCM registration token and register it with the backend.
 * Safe to call repeatedly (e.g. on every login) — registration is idempotent and
 * failures (offline / logged-out) are swallowed.
 */
suspend fun registerFcmToken(context: Context) {
    runCatching {
        val token = FirebaseMessaging.getInstance().token.await()
        context.appContainer.jobCardRepo.registerPushToken(token)
    }
}
