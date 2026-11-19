package io.github.currencortex.music.core.network

/**
 * Immutable request credentials. Never include credentials in generated toString/equals/logs.
 *
 * The app has no CurrentMusic account layer: [token] is always null and the session
 * only identifies the server plus the NetEase direct-login revision.
 */
data class RequestSession(val server: String, val token: String? = null, val neteaseRevision: Long? = null)
