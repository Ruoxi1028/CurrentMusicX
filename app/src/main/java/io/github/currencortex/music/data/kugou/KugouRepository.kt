package io.github.currencortex.music.data.kugou

import io.github.currencortex.music.core.media.AudioQuality
import io.github.currencortex.music.core.network.ApiJson
import io.github.currencortex.music.core.network.ErrorKind
import io.github.currencortex.music.core.network.ApiException
import io.github.currencortex.music.core.network.isPublicMediaUrl
import io.github.currencortex.music.data.song.AudioSource
import io.github.currencortex.music.data.song.MusicSource
import io.github.currencortex.music.data.song.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** KuGou account session; credentials are kept in memory and encrypted at rest by the caller. */
@kotlinx.serialization.Serializable
data class KugouAccountState(
    val loggedIn: Boolean = false,
    val nickname: String = "",
    val avatar: String = "",
    val userid: String = "",
    val revision: Long = 0L,
)

data class KugouSong(
    val name: String = "",
    val artists: String = "",
    val album: String = "",
    val cover: String = "",
    val durationMs: Long = 0,
    val hash: String = "",
    val albumId: String = "",
    val albumAudioId: String = "",
)

/**
 * KuGou provider, ported from folia's kugou provider/transport: QR-code login
 * against login-user.kugou.com, song search and hash-based playback URLs.
 * All endpoints are fixed public HTTPS hosts; no user-supplied URLs are fetched.
 */
class KugouRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
    private val persist: (suspend (KugouAccountState) -> Unit)? = null,
) {
    private val mutableState = MutableStateFlow(KugouAccountState())
    val state: StateFlow<KugouAccountState> = mutableState
    private val mutex = Mutex()

    suspend fun restore(saved: KugouAccountState) {
        if (saved.loggedIn && saved.userid.isNotBlank()) mutableState.value = saved.copy(revision = System.currentTimeMillis())
    }

    private suspend fun publish(next: KugouAccountState) {
        mutex.withLock {
            mutableState.value = next.copy(revision = System.currentTimeMillis())
            persist?.invoke(mutableState.value)
        }
    }

    // ---- QR login (folia: kugouProvider auth) ------------------------------------

    fun qrLoginUrl(key: String) = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=$key"

    suspend fun qrKey(): String {
        val url = "https://login-user.kugou.com/v2/qrcode?" + listOf(
            "appid=1001", "type=1", "plat=4",
            "qrcode_txt=" + URLEncoder.encode("https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1001&", "UTF-8"),
            "srcappid=2919",
        ).joinToString("&")
        val body = getJson(url)
        val key = body.string("qrcode").ifBlank { body.string("key") }.ifBlank { body.string("ticket") }
        if (key.isBlank()) throw ApiException(ErrorKind.Parse, userMessage = "酷狗二维码生成失败")
        return key
    }

    /** NetEase-style status mapping: 801 waiting, 802 scanned, 803 confirmed, 800 expired. */
    suspend fun qrCheck(key: String): Int {
        val url = "https://login-user.kugou.com/v2/get_userinfo_qrcode?" + listOf(
            "plat=4", "appid=1001", "srcappid=2919", "qrcode=$key",
        ).joinToString("&")
        val body = getJson(url)
        return when (body.int("status").takeIf { it >= 0 } ?: body.int("code")) {
            0 -> 800
            1 -> 801
            2, 3 -> 802
            4 -> {
                val userid = body.string("userid").ifBlank { body.string("user_id") }
                val token = body.string("token")
                if (userid.isBlank() || token.isBlank())
                    throw ApiException(ErrorKind.Parse, userMessage = "酷狗扫码结果缺少授权信息")
                publish(KugouAccountState(loggedIn = true, userid = userid, nickname = "酷狗用户"))
                803
            }
            else -> 801
        }
    }

    suspend fun logout() {
        publish(KugouAccountState())
    }

    // ---- Search (folia: anonymous complexsearch with the Android signature) ------

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 30): List<KugouSong> {
        require(keyword.isNotBlank())
        val mid = MessageDigest.getInstance("MD5").digest(System.currentTimeMillis().toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        val params = linkedMapOf(
            "appid" to "3116",
            "clientver" to "11070",
            "clienttime" to (System.currentTimeMillis() / 1000).toString(),
            "keyword" to keyword,
            "mid" to mid,
            "page" to page.toString(),
            "pagesize" to pageSize.toString(),
            "platform" to "AndroidFilter",
        )
        val signature = kgSignature(params)
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=" + URLEncoder.encode(value, "UTF-8")
        } + "&signature=$signature"
        val url = "https://complexsearch.kugou.com/v2/search/song?$query"
        val body = getJson(url)
        val lists = body.array("lists")
        return lists.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            KugouSong(
                name = item.text("SongName").ifBlank { item.text("name") },
                artists = item.text("SingerName").ifBlank { item.text("artists") }
                    .replace(Regex("<[^>]*>"), ""),
                album = item.text("AlbumName").ifBlank { item.text("album") },
                cover = item.text("Image").ifBlank { item.text("cover") }
                    .replace("{size}", "400"),
                durationMs = item.long("Duration") ?: item.long("duration") ?: 0L,
                hash = item.text("FileHash").ifBlank { item.text("hash") },
                albumId = item.text("AlbumID").ifBlank { item.text("album_id") },
                albumAudioId = item.text("AlbumAudioID").ifBlank { item.text("album_audio_id") },
            )
        }.filter { it.hash.isNotBlank() && it.name.isNotBlank() }
    }

    fun toSong(entry: KugouSong, id: Long) = Song(
        id = id,
        name = entry.name,
        artists = entry.artists,
        album = entry.album,
        cover = entry.cover,
        durationMs = entry.durationMs,
        musicSource = MusicSource.KUGOU,
        kugouHash = entry.hash,
    )

    // ---- Playback (folia: song/url with quality fallbacks) -----------------------

    suspend fun audioSource(hash: String, quality: AudioQuality): AudioSource {
        require(hash.isNotBlank()) { "歌曲缺少酷狗 Hash" }
        val params = linkedMapOf("r" to "play/getdata", "hash" to hash)
        val url = "https://wwwapi.kugou.com/yy/index.php?" + params.entries.joinToString("&") { (key, value) ->
            "$key=" + URLEncoder.encode(value, "UTF-8")
        }
        val body = getJson(url)
        val data = body.obj("data")
        val playUrl = data.text("play_url").ifBlank { data.text("play_backup_url") }
            .ifBlank { data.text("url") }
        // Only public http(s) playback URLs are ever handed to the player.
        val safeUrl = playUrl.toHttpUrlOrNull()?.takeIf { it.isPublicMediaUrl() }?.toString()
            ?: throw ApiException(ErrorKind.NeteaseAudioUnavailable, userMessage = "酷狗未提供可播放音源")
        return AudioSource(
            url = safeUrl,
            level = data?.text("quality").orEmpty().ifBlank { quality.value },
            sampleRate = 0,
            channelCount = 0,
            format = data?.text("format").orEmpty(),
            validForSeconds = 30 * 60,
        )
    }

    // ---- HTTP ---------------------------------------------------------------------

    private fun getJson(url: String): JsonObject {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile)")
            .header("Referer", "https://www.kugou.com/")
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful)
                throw ApiException(ErrorKind.Server, response.code, "酷狗接口错误（HTTP ${response.code}）")
            if (raw.isBlank()) throw ApiException(ErrorKind.Parse, userMessage = "酷狗接口返回为空")
            return runCatching { ApiJson.parseToJsonElement(raw).jsonObject }
                .getOrElse { throw ApiException(ErrorKind.Parse, userMessage = "酷狗接口返回格式异常") }
        }
    }

    private fun kgSignature(params: Map<String, String>): String {
        val sorted = params.entries.sortedBy { it.key }.joinToString("") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("MD5")
            .digest((KG_SIGN_PREFIX + sorted + KG_SIGN_SUFFIX).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KG_SIGN_PREFIX = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        const val KG_SIGN_SUFFIX = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    }
}

private fun JsonObject?.string(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject?.int(key: String): Int =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: -1

private fun JsonObject?.long(key: String): Long? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

private fun JsonObject?.array(key: String): List<JsonElement> =
    (this?.get(key) as? JsonArray)?.toList().orEmpty()

private fun JsonObject?.text(key: String): String = this.string(key)
