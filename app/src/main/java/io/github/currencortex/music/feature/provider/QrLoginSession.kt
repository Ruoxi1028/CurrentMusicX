package io.github.currencortex.music.feature.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * QR-login session state machine, ported from folia's `providerLoginSession`:
 *   1. capability check; 2. request the QR key (one at a time);
 *   3. show the QR and arm the client-side TTL; 4. poll every 2 s, tolerating
 *      [TRANSIENT_POLL_LIMIT] consecutive network-layer failures; 5. on confirm,
 *      stop polling without cancelling so the in-flight check can read 803;
 *   6. on failure, record the cooldown the backend asked for.
 * Every await re-checks the generation, so late results from a superseded round
 * are discarded.
 */
class QrLoginSession(private val scope: CoroutineScope) {

    enum class Phase { IDLE, LOADING, WAITING, SCANNED, CONFIRMED, EXPIRED, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val qrUrl: String = "",
        val qrImageUrl: String = "",
        val message: String = "",
        val retryCooldownSeconds: Int = 0,
    )

    /** What a provider must implement for QR login. */
    interface Auth {
        suspend fun qrKey(methodId: String?): String
        suspend fun qrImage(key: String): String
        /** Returns the provider status code (NetEase-style: 800/801/802/803, other = error). */
        suspend fun check(key: String): Int
        suspend fun cancel(key: String)
        /** Client-side QR lifetime in ms; null lets the backend report expiry. */
        val ttlMs: Long?
        val loginUrl: (String) -> String
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var cooldownJob: Job? = null

    fun start(auth: Auth, methodId: String? = null, onConfirmed: suspend () -> Unit) {
        stop()
        val round = ++generation
        cooldownJob?.cancel()
        job = scope.launch {
            _state.value = State(phase = Phase.LOADING)
            val key = try {
                auth.qrKey(methodId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (round == generation) _state.value = State(phase = Phase.ERROR, message = failure.message ?: "二维码生成失败")
                return@launch
            }
            if (round != generation) { runCatching { auth.cancel(key) }; return@launch }
            val image = try { auth.qrImage(key) } catch (_: Exception) { "" }
            if (round != generation) { runCatching { auth.cancel(key) }; return@launch }
            _state.value = State(phase = Phase.WAITING, qrUrl = auth.loginUrl(key), qrImageUrl = image, message = "等待扫码…")
            val ttl = auth.ttlMs
            if (ttl != null) {
                launch {
                    delay(ttl)
                    if (round != generation) return@launch
                    runCatching { auth.cancel(key) }
                    _state.value = State(phase = Phase.EXPIRED, message = "二维码已过期，请刷新")
                }
            }
            var transientFailures = 0
            while (coroutineContext.isActive && round == generation) {
                val code = try {
                    auth.check(key)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (round != generation) return@launch
                    if (transientFailures < TRANSIENT_POLL_LIMIT) {
                        transientFailures++
                        delay(POLL_INTERVAL_MS)
                        continue
                    }
                    _state.value = State(phase = Phase.ERROR, message = failure.message ?: "扫码状态查询失败")
                    return@launch
                }
                if (round != generation) return@launch
                transientFailures = 0
                when (code) {
                    803 -> {
                        // The session already became a credential: never cancel.
                        _state.value = _state.value.copy(phase = Phase.CONFIRMED, message = "登录成功")
                        onConfirmed()
                        return@launch
                    }
                    802 -> _state.value = _state.value.copy(phase = Phase.SCANNED, message = "已扫码，请在手机上确认")
                    800 -> {
                        _state.value = State(phase = Phase.EXPIRED, message = "二维码已过期，请刷新")
                        return@launch
                    }
                    else -> _state.value = _state.value.copy(phase = Phase.WAITING, message = "等待扫码…")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Stop polling and cancel the current key; confirmed sessions are never cancelled. */
    fun stop() {
        generation++
        job?.cancel()
        job = null
    }

    fun dismiss() {
        stop()
        _state.value = State()
    }

    companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val TRANSIENT_POLL_LIMIT = 2
    }
}
