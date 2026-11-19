package io.github.currencortex.music.data.qishui

import io.github.currencortex.music.core.media.AudioQuality
import io.github.currencortex.music.core.network.ApiException
import io.github.currencortex.music.core.network.ApiJson
import io.github.currencortex.music.core.network.ErrorKind
import io.github.currencortex.music.core.network.isPublicMediaUrl
import io.github.currencortex.music.data.song.AudioSource
import io.github.currencortex.music.data.song.MusicSource
import io.github.currencortex.music.data.song.Song
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Qishui (汽水音乐) device identity; no account is required for catalogue/playback. */
@kotlinx.serialization.Serializable
data class QishuiAccountState(val deviceId: String = "", val revision: Long = 0L)

data class QishuiSong(
    val id: String = "",
    val name: String = "",
    val artists: String = "",
    val album: String = "",
    val cover: String = "",
    val durationMs: Long = 0,
)

/**
 * Qishui provider: folia's qishui-music-api public car client. Only the anonymous
 * client-token flow is used here; the ticket-guard account flow (Douyin QR) is not
 * needed for search or the plaintext audio the car endpoint issues.
 */
class QishuiRepository(
    private val persist: (suspend (QishuiAccountState) -> Unit)? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {
    private val mutable = MutableStateFlow(QishuiAccountState())
    val state: StateFlow<QishuiAccountState> = mutable

    @Volatile private var device: String = ""
    @Volatile private var token: String? = null

    suspend fun restore(saved: QishuiAccountState) {
        device = saved.deviceId.ifBlank { newDeviceId() }
        val next = saved.copy(deviceId = device, revision = System.currentTimeMillis())
        mutable.value = next
        if (saved.deviceId.isBlank()) persist?.invoke(next)
    }

    private fun newDeviceId(): String {
        val random = SecureRandom()
        val first = (1 + random.nextInt(9)).toString()
        return first + buildString { repeat(18) { append(random.nextInt(10)) } }
    }

    private fun deviceId(): String {
        if (device.isBlank()) device = newDeviceId()
        return device
    }

    suspend fun search(keyword: String, pageSize: Int = 20): List<QishuiSong> = withContext(Dispatchers.IO) {
        require(keyword.isNotBlank())
        val limit = pageSize.coerceIn(1, 50)
        val searchId = UUID.randomUUID().toString()
        val data = platform("/search/", buildJsonObject {
            put("q", keyword.trim())
            put("cursor", "0")
            put("search_id", searchId)
            put("from_search_id", searchId)
            put("search_method", "input")
            put("search_type", "track")
            put("search_scene", "normal")
            put("scene_name", "search")
            put("ab_param", "")
            put("last_viewed_media", buildJsonObject {})
        })
        val groups = data.array("result_groups")
        val group = groups.firstOrNull { (it as? JsonObject)?.text("id") == "tracks" } ?: groups.firstOrNull()
        val items = ((group as? JsonObject)?.array("data")).orEmpty()
        items.mapNotNull { element ->
            val entity = (element as? JsonObject)?.obj("entity") ?: return@mapNotNull null
            val track = entity.obj("track") ?: entity
            val base = track.obj("base_info") ?: return@mapNotNull null
            val id = base.text("id").ifBlank { track.text("id") }
            val name = base.text("name")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val related = track.obj("related_info")
            val artists = (related?.array("artist_links")).orEmpty()
                .mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank) }
                .joinToString(" / ")
            QishuiSong(
                id = id,
                name = name,
                artists = artists,
                album = related?.obj("album_link")?.text("name").orEmpty(),
                cover = coverOf(track),
                durationMs = base.long("duration_ms") ?: 0L,
            )
        }.take(limit)
    }

    fun toSong(entry: QishuiSong, id: Long) = Song(
        id = id,
        name = entry.name,
        artists = entry.artists,
        album = entry.album,
        cover = entry.cover,
        durationMs = entry.durationMs,
        musicSource = MusicSource.QISHUI,
        qishuiId = entry.id,
    )

    /** Resolves the plaintext car endpoint; encrypted-only tracks are reported as unavailable. */
    suspend fun audioSource(id: String, quality: AudioQuality): AudioSource = withContext(Dispatchers.IO) {
        require(id.isNotBlank()) { "歌曲缺少汽水 ID" }
        val media = platform("/media/mget/", buildJsonObject {
            putJsonArray("media_list") { addJsonObject { put("id", id); put("type", "track") } }
            put("card_context", buildJsonObject {})
        })
        val entity = media.array("media_list").firstNotNullOfOrNull { row ->
            val item = row as? JsonObject ?: return@firstNotNullOfOrNull null
            val track = item.obj("track_entity") ?: return@firstNotNullOfOrNull null
            val trackId = track.obj("base_info")?.text("id").orEmpty()
            if (trackId == id || trackId.isBlank()) track else null
        } ?: throw ApiException(ErrorKind.NotFound, userMessage = "汽水未返回该歌曲")
        val player = entity.obj("player_info") ?: throw ApiException(ErrorKind.AudioUnavailable, userMessage = "汽水未提供播放信息")
        for (variant in listOf("full", "preview")) {
            val info = player.obj(variant)?.obj("video_model_info") ?: continue
            val candidates = mutableListOf<Pair<String, String>>()
            val modelText = info.text("video_model")
            if (modelText.isNotBlank()) {
                val model = runCatching { ApiJson.parseToJsonElement(modelText) }.getOrNull() as? JsonObject
                model?.array("video_list").orEmpty().forEach { raw ->
                    val entry = raw as? JsonObject ?: return@forEach
                    if (entry.obj("encrypt_info")?.bool("encrypt") == true) return@forEach
                    val url = entry.text("main_url")
                    if (url.isBlank()) return@forEach
                    candidates += url to qualityLabel(entry.obj("video_meta")?.text("quality").orEmpty())
                }
            }
            if (candidates.isEmpty()) {
                val playerUrl = info.text("url_player_info")
                if (playerUrl.isNotBlank()) {
                    val playInfo = getJson(playerUrl, allowDouyinMediaHost = true)
                    val list = playInfo.obj("Result")?.obj("Data")?.array("PlayInfoList")
                        ?: playInfo.obj("data")?.array("PlayInfoList").orEmpty()
                    list.forEach { raw ->
                        val entry = raw as? JsonObject ?: return@forEach
                        if (entry.bool("Encrypt") || entry.text("PlayAuth").isNotBlank()) return@forEach
                        val url = entry.text("MainPlayUrl")
                        if (url.isBlank()) return@forEach
                        candidates += url to qualityLabel(entry.text("Quality"))
                    }
                }
            }
            val safe = candidates.firstNotNullOfOrNull { (url, label) ->
                trustedMediaUrl(url)?.let { it to label }
            } ?: continue
            val level = safe.second
            return@withContext AudioSource(safe.first, level, 0, 0, "m4a", validForSeconds = 300)
        }
        throw ApiException(ErrorKind.AudioUnavailable, userMessage = "汽水仅提供加密音源，无法直接播放")
    }

    // ---- Transport ---------------------------------------------------------------

    private suspend fun platform(path: String, body: JsonObject): JsonObject {
        val requestBody = buildJsonObject {
            body.forEach { (key, value) -> put(key, value) }
            put("common_params", buildJsonObject {})
        }
        val url = "$PLATFORM_ORIGIN$PREFIX$path?${queryParams()}"
        return postJson(url, accessToken(), requestBody)
    }

    private fun accessToken(): String {
        token?.let { return it }
        val url = "$TOKEN_ORIGIN$PREFIX/client/token/get/?${queryParams()}&trigger_type=cold_start"
        val response = getJson(url)
        val value = response.text("client_token")
        if (value.isBlank()) throw ApiException(ErrorKind.Parse, userMessage = "汽水客户端令牌获取失败")
        token = value
        return value
    }

    private fun queryParams(): String {
        val values = linkedMapOf(
            "aid" to "765213", "app_id" to "765213", "app_name" to "byte_autoservice_lite",
            "version_code" to "100010", "version_name" to "1.0.10", "channel" to "bydSong",
            "sdk_aid" to "753999", "caller_name" to "luna_open_sdk", "sdk_version_code" to "100000100",
            "sdk_version_name" to "0.0.1", "sdk_channel" to "Test", "vehicle_type" to "BYD",
            "device_brand" to "BYD", "device_type" to "BYD", "device_platform" to "android",
            "resolution" to "1920x1080", "os_version" to "13", "ac" to "wifi",
        )
        val id = deviceId()
        values["device_id"] = id
        values["did"] = id
        values["iid"] = id
        values["vehicle_id"] = "qishui-$id"
        values["_timestamp"] = (System.currentTimeMillis() / 1000).toString()
        values["_nonce"] = SecureRandom().nextInt(65536).toString()
        return values.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
    }

    private fun getJson(url: String, allowDouyinMediaHost: Boolean = false): JsonObject {
        val parsed = url.toHttpUrlOrNull() ?: throw ApiException(ErrorKind.Parse, userMessage = "汽水接口地址无效")
        val allowedHost = parsed.host in ALLOWED_API_HOSTS ||
            (allowDouyinMediaHost && parsed.host.endsWith(".douyin.com"))
        require(parsed.scheme == "https" && allowedHost)
        val request = Request.Builder().url(parsed)
            .header("user-agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .get().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(ErrorKind.Server, response.code, "汽水接口错误（HTTP ${response.code}）")
            return parse(raw)
        }
    }

    private fun postJson(url: String, accessToken: String, body: JsonObject): JsonObject {
        val parsed = url.toHttpUrlOrNull() ?: throw ApiException(ErrorKind.Parse, userMessage = "汽水接口地址无效")
        require(parsed.scheme == "https" && parsed.host in ALLOWED_API_HOSTS)
        val request = Request.Builder().url(parsed)
            .header("user-agent", USER_AGENT)
            .header("content-type", "application/json")
            .header("Accept-Encoding", "identity")
            .header("access-token", accessToken)
            .post(body.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) token = null
                throw ApiException(ErrorKind.Server, response.code, "汽水接口错误（HTTP ${response.code}）")
            }
            val root = parse(raw)
            val error = root.long("err_no") ?: 0L
            if (error != 0L) {
                if (error == 28001008L) token = null
                throw ApiException(ErrorKind.Server, error.toInt(), root.text("err_msg").ifBlank { "汽水接口错误（code $error）" })
            }
            return root.obj("data") ?: throw ApiException(ErrorKind.Parse, userMessage = "汽水接口返回为空")
        }
    }

    private fun parse(raw: String): JsonObject =
        runCatching { ApiJson.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw ApiException(ErrorKind.Parse, userMessage = "汽水接口返回格式异常") }

    private fun trustedMediaUrl(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.port != 443) return null
        if (!url.isPublicMediaUrl()) return null
        val host = url.host.lowercase()
        val allowed = MEDIA_HOSTS.any { host == it || host.endsWith(".$it") }
        return if (allowed) url.toString() else null
    }

    private fun qualityLabel(raw: String): String = when (raw.lowercase()) {
        "medium" -> "standard"
        "higher", "highest" -> "high"
        "lossless" -> "lossless"
        "hi_res", "hires" -> "hires"
        else -> "standard"
    }

    private fun coverOf(track: JsonObject): String {
        val related = track.obj("related_info")
        val album = related?.obj("album_link")
        val candidates = listOf(
            album?.text("cover_url"), album?.text("url_cover"),
            track.obj("display_info")?.text("cover_url"),
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }?.replace("http://", "https://").orEmpty()
    }

    private companion object {
        const val USER_AGENT = "LunaOpenSDK/1.0.10"
        const val PREFIX = "/api/luna/v1/platform"
        const val TOKEN_ORIGIN = "https://open.qishui.com"
        const val PLATFORM_ORIGIN = "https://open.douyin.com"
        val ALLOWED_API_HOSTS = setOf("open.qishui.com", "open.douyin.com")
        val MEDIA_HOSTS = listOf(
            "365yg.com", "douyinvod.com", "douyinscdn.com", "bytefcdn.com", "bytecdn.cn", "byteicdn.com",
            "ibytedtos.com", "pstatp.com", "snssdk.com", "bytedance.com", "bytetos.com", "volcvideo.com",
            "volccdn.com", "volces.com", "volcengineapi.com", "douyin.com", "qishui.com", "zijieapi.com", "zjcdn.com",
        )
    }
}

private fun JsonObject?.text(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject?.long(key: String): Long? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject?.bool(key: String): Boolean =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull in setOf("true", "1")

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

private fun JsonObject?.array(key: String): List<JsonElement> =
    (this?.get(key) as? JsonArray)?.toList().orEmpty()
