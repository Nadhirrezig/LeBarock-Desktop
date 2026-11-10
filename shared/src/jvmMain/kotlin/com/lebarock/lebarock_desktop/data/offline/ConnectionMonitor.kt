package com.lebarock.lebarock_desktop.data.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Whether the caisse answers (the handheld's ConnectionMonitor). Every call to the caisse goes through
 * [interceptor]: an answer (whatever its status, except the proxy's 502 / 503 / 504 when the caisse behind it is
 * down) means reachable; a network failure counts. The PC goes « Hors ligne » once calls have kept failing for
 * [OFFLINE_AFTER_MS] (about 10 s), and back online at the first answer.
 */
class ConnectionMonitor(private val now: () -> Long = System::currentTimeMillis) {

    companion object {
        const val OFFLINE_AFTER_MS = 10_000L
    }

    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    /** Calls have started failing (not offline yet): the sync asks the caisse again until it answers. */
    private val _doubtful = MutableStateFlow(false)
    val doubtful: StateFlow<Boolean> = _doubtful.asStateFlow()

    /** When the caisse last answered (step 3: an offline session ends once it answers again). */
    private val _lastAnswerAt = MutableStateFlow(0L)
    val lastAnswerAt: StateFlow<Long> = _lastAnswerAt.asStateFlow()

    @Volatile
    private var failingSince: Long? = null

    val interceptor = Interceptor { chain ->
        val response: Response = try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            failed()
            throw e
        }
        if (response.code in 502..504) failed() else answered()
        response
    }

    fun answered() {
        _lastAnswerAt.value = now()
        failingSince = null
        _doubtful.value = false
        _offline.value = false
    }

    fun failed() {
        val since = failingSince ?: now().also { failingSince = it }
        _doubtful.value = true
        if (now() - since >= OFFLINE_AFTER_MS) _offline.value = true
    }

    /** Another caisse address was saved in Réglages: what was known of the old one is forgotten. */
    fun reset() {
        failingSince = null
        _doubtful.value = false
        _offline.value = false
    }
}
