package io.github.currencortex.music.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import io.github.currencortex.music.core.netease.NeteaseGateway

/**
 * Project-server HTTP. There is no account token: sessions identify a server plus
 * the NetEase direct-login revision. NetEase routes (`ncm*` / `ncmbind*`) are
 * answered by the on-device [NeteaseGateway] whenever it handles the path.
 */
class ApiClient(
    private val server: () -> String,
    private val token: () -> String?,
    private val log: (String) -> Unit = {},
    client: OkHttpClient = defaultHttpClient(),
    val netease: NeteaseGateway? = null,
    private val nativeMedia: () -> Boolean = { false },
    val routeServer: (String) -> String = { it },
) {
    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).followRedirects(false).build()
    }

    private val http = client.newBuilder().build()
    private val singleAttemptHttp = http.newBuilder().retryOnConnectionFailure(false).build()
    val hasLocalNeteaseLogin get() = netease?.sessions?.state?.value?.loggedIn == true
    val nativeMediaEnabled get() = nativeMedia() && netease != null
    /** Public metadata requests belong to a server, never to an account token. */
    fun publicSession() = RequestSession(server(), null)
    private fun useNative(path: String, authenticated: Boolean, projectToken: String?): Boolean {
        if (netease?.handles(path, nativeMedia()) != true) return false
        // Explicit native mode never falls back to the project server for NetEase routes.
        if (nativeMedia()) return true
        // Login runs on-device; the project binding is only a legacy fallback.
        if (path.startsWith("ncmbind/phone/") || path.startsWith("ncmbind/qr/") ||
            path in setOf("ncmbind/membership", "ncmbind/medals", "ncm/captcha/sent/v1")) return true
        // Without a local credential, the project server can use its own saved binding.
        return hasLocalNeteaseLogin || !authenticated || projectToken.isNullOrBlank()
    }
    suspend fun request(method: String, path: String, query: Map<String, String> = emptyMap(),
                        body: JsonElement? = null, authenticated: Boolean = false, expectedSession: RequestSession? = null,
                        retryConnection: Boolean = true, callTimeoutMillis: Long? = null,
                        projectOnly: Boolean = false,
                        extraHeaders: Map<String, String> = emptyMap()): JsonElement = withContext(Dispatchers.IO) {
        require(extraHeaders.keys.all { it.equals("X-QQ-Session", ignoreCase = true) } &&
            (extraHeaders.isEmpty() || path.startsWith("qqmusic/")))
        val session = RequestSession(server(), if (authenticated) token() else null)
        if (expectedSession != null && (expectedSession.server != session.server || expectedSession.token != session.token)) throw ApiException(ErrorKind.Unauthorized)
        if ((netease?.handles(path) == true) && expectedSession?.neteaseRevision != null &&
            expectedSession.neteaseRevision != netease?.sessions?.state?.value?.revision) throw ApiException(ErrorKind.NeteaseSessionChanged)
        if (!projectOnly && useNative(path, authenticated, session.token)) {
            val netease = requireNotNull(netease)
            if (expectedSession?.neteaseRevision != null && expectedSession.neteaseRevision != netease.sessions.state.value.revision)
                throw ApiException(ErrorKind.NeteaseSessionChanged)
            val response = netease.request(method, path, query, body, expectedSession?.neteaseRevision)
            if (expectedSession != null && (expectedSession.server != server() || expectedSession.token != if (authenticated) token() else null))
                throw ApiException(ErrorKind.Unauthorized)
            val changesLogin = path == "ncmbind/phone/login" || path == "ncmbind/qr/check" || path == "ncmbind/refresh" || (path == "ncmbind" && method == "DELETE")
            if (!changesLogin && expectedSession?.neteaseRevision != null && expectedSession.neteaseRevision != netease.sessions.state.value.revision)
                throw ApiException(ErrorKind.NeteaseSessionChanged)
            return@withContext response
        }
        val url = ServerUrl.endpoint(routeServer(session.server), path, query)
        val request = Request.Builder().url(url).tag(RequestSession::class.java, session)
            .header("Accept", "application/json")
            .apply { extraHeaders.forEach { (key, value) -> header(key, value) } }
            .method(method, if (method == "GET") null else (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            .build()
        val requestClient = when {
            retryConnection -> http
            else -> singleAttemptHttp
        }
        val call = requestClient.newCall(request)
        callTimeoutMillis?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        val (code, raw) = call.readResponse { response ->
            response.code to response.body?.string().orEmpty()
        }
        if (expectedSession != null && (expectedSession.server != server() || expectedSession.token != if (authenticated) token() else null))
            throw ApiException(ErrorKind.Unauthorized)
        if ((netease?.handles(path) == true) && expectedSession?.neteaseRevision != null &&
            expectedSession.neteaseRevision != netease?.sessions?.state?.value?.revision) throw ApiException(ErrorKind.NeteaseSessionChanged)
        log("$method $path HTTP $code")
        if (code !in 200..299) {
            val errorText = raw
            // Upstream validation text is user-facing; other routes keep the bounded mapping.
            val accountError = if (code in 400..499 && (path.startsWith("qqmusic/")))
                runCatching {
                    val error = ApiJson.parseToJsonElement(errorText).jsonObject
                    ((error["error"] ?: error["message"]) as? JsonPrimitive)
                        ?.takeIf { value -> value.isString }?.content?.trim()?.takeIf { value -> value.isNotEmpty() }?.take(240)
                }.getOrNull() else null
            throw ApiException(when (code) {
                401 -> ErrorKind.Unauthorized
                403 -> ErrorKind.Forbidden; 404 -> ErrorKind.NotFound; 429 -> ErrorKind.RateLimited
                in 500..599 -> ErrorKind.Server; else -> ErrorKind.Unknown
            }, code, accountError)
        }
        if (raw.isBlank()) JsonNull else ApiJson.parseToJsonElement(raw)
    }
    suspend inline fun <reified T> decode(value: JsonElement): T = withContext(Dispatchers.Default) {
        ApiJson.decodeFromJsonElement(value)
    }
    suspend inline fun <reified T> get(path: String, query: Map<String, String> = emptyMap(), authenticated: Boolean = false): T =
        decode(request("GET", path, query, authenticated = authenticated))
}
