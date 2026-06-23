package com.naarni.service

import android.app.Application
import android.content.Context
import com.naarni.service.core.auth.SessionManager
import com.naarni.service.core.network.Network
import com.naarni.service.data.repo.AuthRepository
import com.naarni.service.data.repo.JobCardRepository

/**
 * Application + manual DI container. We avoid an annotation-processor DI framework
 * to keep the APK small and the build fast (plan §2) — the graph is tiny.
 */
class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val session = SessionManager(context)
    val api by lazy { Network.api(session) }
    val authRepo by lazy { AuthRepository(api, session) }
    val jobCardRepo by lazy { JobCardRepository(api) }
}

/** Reach the DI container from any Context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as App).container
