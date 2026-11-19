package io.github.currencortex.music.data.bodian

import io.github.currencortex.music.core.media.AudioQuality
import io.github.currencortex.music.core.network.ApiException
import io.github.currencortex.music.core.network.ApiJson
import io.github.currencortex.music.core.network.ErrorKind
import io.github.currencortex.music.core.network.isPublicMediaUrl
import io.github.currencortex.music.data.song.AudioSource
import io.github.currencortex.music.data.song.MusicSource
import io.github.currencortex.music.data.song.Song
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Bodian (波点音乐) account, ported from folia's bodian-music-api transport. */
@kotlinx.serialization.Serializable
data class BodianAccountState(
    val deviceId: String = "",
    val loggedIn: Boolean = false,
    val uid: String = "",
    val token: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val revision: Long = 0L,
)

data class BodianSong(
    val id: String = "",
    val name: String = "",
    val artists: String = "",
    val album: String = "",
    val cover: String = "",
    val durationMs: Long = 0,
    val freeSign: String = "",
)

/**
 * Bodian provider: signed car-protocol requests against bd-api.kuwo.cn, QR login
 * and id-based playback. Only fixed public HTTPS hosts are ever contacted; playback
 * URLs are host-checked before they reach the media player.
 */
class BodianRepository(
    private val persist: (suspend (BodianAccountState) -> Unit)? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
) {
    private val mutable = MutableStateFlow(BodianAccountState())
    val state: StateFlow<BodianAccountState> = mutable
    private val mutex = Mutex()

    @Volatile private var device: String = ""

    suspend fun restore(saved: BodianAccountState) {
        device = saved.deviceId.ifBlank { newDevice() }
        val next = saved.copy(deviceId = device, revision = System.currentTimeMillis())
        mutable.value = next
        if (saved.deviceId.isBlank()) persist?.invoke(next)
    }

    private suspend fun publish(next: BodianAccountState) {
        mutex.withLock {
            mutable.value = next.copy(deviceId = device, revision = System.currentTimeMillis())
            persist?.invoke(mutable.value)
        }
    }

    private fun newDevice() = UUID.randomUUID().toString().replace("-", "")

    private fun deviceId(): String {
        if (device.isBlank()) device = newDevice()
        return device
    }

    suspend fun logout() = publish(BodianAccountState(deviceId = device))

    // ---- QR login ----------------------------------------------------------------

    fun qrLoginUrl(key: String) = "https://bodian-oia.kuwo.cn/bodian/download.html?pageName=login_pc&pt=3&id=" +
        URLEncoder.encode(key, "UTF-8")

    suspend fun qrKey(): String {
        val data = call("/api/ucenter/login/qrCode", signed = true, anonymous = true).obj("data")
        val key = data?.text("qrCode").orEmpty()
        if (key.isBlank()) throw ApiException(ErrorKind.Parse, userMessage = "波点二维码生成失败")
        return key
    }

    /** Maps the car protocol to the app's 800/801/802/803 QR states. */
    suspend fun qrCheck(key: String): Int {
        val data = call("/api/ucenter/login/qrCodeStatus", mapOf("qrCode" to key),
            signed = true, anonymous = true).obj("data")
        return when (data?.int("status") ?: -1) {
            1 -> 801
            2 -> 800
            3 -> confirmLogin(key)
            else -> 801
        }
    }

    private suspend fun confirmLogin(key: String): Int {
        val login = try {
            call("/api/ucenter/users/login", body = buildJsonObject {
                put("authType", 10)
                put("qrCode", key)
            }, signed = true, anonymous = true, method = "POST")
        } catch (error: ApiException) {
            // 11027 means the code was scanned but not confirmed yet.
            if (error.status == 11027) return 802
            throw error
        }
        val data = login.obj("data") ?: throw ApiException(ErrorKind.Parse, userMessage = "波点登录结果为空")
        val user = data.obj("userInfo") ?: data.obj("user") ?: data
        val uid = (data.text("id").ifBlank { data.text("uid") })
            .ifBlank { user.text("id") }.ifBlank { user.text("uid") }
        val token = data.text("token").ifBlank { login.text("token") }
        if (uid.isBlank() || token.isBlank()) throw ApiException(ErrorKind.Parse, userMessage = "波点登录结果缺少凭证")
        publish(mutable.value.copy(
            loggedIn = true, uid = uid, token = token,
            nickname = user.text("nickname"), avatar = user.text("headImg").ifBlank { user.text("avatarUrl") },
        ))
        return 803
    }

    // ---- Catalog -----------------------------------------------------------------

    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 30): List<BodianSong> {
        require(keyword.isNotBlank())
        val limit = pageSize.coerceIn(1, 100)
        val offset = (page.coerceAtLeast(1) - 1) * limit
        val data = call("/api/search/music/list", linkedMapOf(
            "keyword" to keyword.trim(), "correct" to "1",
            "pn" to (offset / limit).toString(), "rn" to limit.toString(),
        )).obj("data")
        val list = data?.array("resultList") ?: return emptyList()
        return list.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.text("id").ifBlank { item.long("id")?.toString().orEmpty() }
            if (id.isBlank() || id.all { it == '0' }) return@mapNotNull null
            BodianSong(
                id = id,
                name = item.text("songName").ifBlank { item.text("name") },
                artists = (item["artists"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotBlank) }
                    ?.joinToString(" / ").orEmpty().ifBlank { item.text("artist") },
                album = item.text("album"),
                cover = item.text("albumPic").ifBlank { item.text("albumPic120") }
                    .replace("http://", "https://"),
                durationMs = (item.long("duration") ?: 0L) * 1000,
                freeSign = item.text("freeSign").ifBlank { item.text("fsig") },
            ).takeIf { it.name.isNotBlank() }
        }
    }

    fun toSong(entry: BodianSong, id: Long) = Song(
        id = id,
        name = entry.name,
        artists = entry.artists,
        album = entry.album,
        cover = entry.cover,
        durationMs = entry.durationMs,
        musicSource = MusicSource.BODIAN,
        bodianId = entry.id,
        bodianFreeSign = entry.freeSign,
    )

    // ---- Playback ----------------------------------------------------------------

    suspend fun audioSource(id: String, freeSign: String, quality: AudioQuality): AudioSource {
        require(id.isNotBlank()) { "歌曲缺少波点 ID" }
        val musicId = id.toLongOrNull() ?: throw ApiException(ErrorKind.Parse, userMessage = "波点歌曲 ID 无效")
        val rightBody = buildJsonObject {
            put("musicId", musicId)
            put("freeSign", freeSign)
        }
        val right = call("/api/play/music/v2/checkRight",
            linkedMapOf("musicId" to id, "freeSign" to freeSign),
            body = rightBody, signed = true, withBody = true).obj("data")
        when (val status = right?.int("status") ?: -1) {
            3 -> {
                // Anonymous or non-VIP accounts receive a bounded audition.
                val audition = right.obj("audition")
                val url = audition?.text("https").orEmpty().ifBlank { audition?.text("url").orEmpty() }
                val safe = url.toHttpUrlOrNull()?.takeIf { it.isPublicMediaUrl() }?.toString()
                    ?: throw ApiException(ErrorKind.NeteaseAudioUnavailable, userMessage = "波点试听音源不可用")
                return AudioSource(safe, "standard", 0, 0, "mp3", validForSeconds = 120)
            }
            7 -> throw ApiException(ErrorKind.Forbidden, userMessage = "波点需要额外播放权限，请登录会员账号")
            -1 -> throw ApiException(ErrorKind.NeteaseBindingRequired, userMessage = "请先登录波点音乐账号")
            else -> Unit
        }
        val (level, format, br) = when (quality) {
            AudioQuality.LOSSLESS, AudioQuality.HIRES -> Triple("lossless", "flac", "2000kflac")
            AudioQuality.EXHIGH, AudioQuality.HIGHER -> Triple("high", "mp3", "320kmp3")
            else -> Triple("standard", "mp3", "128kmp3")
        }
        val audioBody = buildJsonObject {
            put("devId", deviceId())
            put("musicId", musicId)
            put("format", format)
            put("br", br)
            put("freeSign", freeSign)
        }
        val audio = call("/api/play/music/v2/audioUrl", linkedMapOf(
            "devId" to deviceId(), "musicId" to id, "format" to format, "br" to br, "freeSign" to freeSign,
        ), body = audioBody, signed = true, withBody = true).obj("data")
        val url = audio?.text("audioHttpsUrl").orEmpty().ifBlank { audio?.text("audioUrl").orEmpty() }
        val safe = url.toHttpUrlOrNull()?.takeIf { it.isPublicMediaUrl() }?.toString()
            ?: throw ApiException(ErrorKind.NeteaseAudioUnavailable, userMessage = "波点未提供可播放音源")
        return AudioSource(safe, level, 0, 0, format, validForSeconds = 300)
    }

    // ---- Transport ---------------------------------------------------------------

    private suspend fun call(path: String, params: Map<String, String> = emptyMap(),
        body: JsonObject? = null, signed: Boolean = false, anonymous: Boolean = false,
        withBody: Boolean = false, method: String = "GET"): JsonObject = withContext(Dispatchers.IO) {
        require(path.startsWith("/") && !path.contains("..") && !path.contains("//"))
        val account = mutable.value
        val query = LinkedHashMap<String, String>()
        query.putAll(params)
        query["uid"] = if (anonymous) "-1" else account.uid.ifBlank { "-1" }
        query["token"] = if (anonymous) "" else account.token
        val bodyText = body?.toString().orEmpty()
        if (signed) {
            query["timestamp"] = System.currentTimeMillis().toString()
            query["sign"] = signQuery(path, query, bodyText)
        }
        val queryText = query.entries.joinToString("&") { (key, value) ->
            encode(key) + "=" + encode(value)
        }
        val headers = desktopHeaders()
        if (!anonymous && account.loggedIn) {
            headers["uid"] = account.uid
            headers["token"] = account.token
        }
        val raw = when {
            method.equals("POST", true) -> post("$API_ORIGIN$path?$queryText", headers, bodyText)
            withBody -> getWithBody(path + "?" + queryText, headers, bodyText)
            else -> get("$API_ORIGIN$path?$queryText", headers)
        }
        val json = runCatching { ApiJson.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw ApiException(ErrorKind.Parse, userMessage = "波点接口返回格式异常") }
        val code = json.int("code") ?: -1
        if (code != 200) {
            if (code == 11012) {
                publish(mutable.value.copy(loggedIn = false, uid = "", token = "", nickname = "", avatar = ""))
                throw ApiException(ErrorKind.Unauthorized, code, "波点登录已失效，请重新扫码")
            }
            val message = when (code) {
                407 -> json.text("msg").ifBlank { json.text("message") }
                    .ifBlank { "由于版权保护，该资源仅限中国大陆地区使用" }
                20018 -> "波点未提供可播放音源"
                else -> "波点接口错误（code $code）"
            }
            throw ApiException(if (code == 407) ErrorKind.Forbidden else ErrorKind.Server, code, message)
        }
        json
    }

    private fun desktopHeaders(): MutableMap<String, String> {
        val id = deviceId()
        return linkedMapOf(
            "User-Agent" to "Dart/3.3 (dart:io)", "plat" to "win", "channel" to "W1", "ver" to "1.1.7",
            "svrver" to "13", "api-ver" to "application/json", "brand" to "Windows", "net" to "wifi",
            "devid" to id, "qimei36" to id, "Content-Type" to "application/json",
        )
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val parsed = url.toHttpUrl()
        require(parsed.scheme == "https" && parsed.host == API_HOST)
        val request = Request.Builder().url(parsed).apply {
            headers.forEach { (key, value) -> header(key, value) }
            header("Accept-Encoding", "identity")
        }.get().build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(ErrorKind.Server, response.code, "波点接口错误（HTTP ${response.code}）")
            return raw
        }
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        val parsed = url.toHttpUrl()
        require(parsed.scheme == "https" && parsed.host == API_HOST)
        val request = Request.Builder().url(parsed).apply {
            headers.forEach { (key, value) -> header(key, value) }
            header("Accept-Encoding", "identity")
        }.post(body.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(ErrorKind.Server, response.code, "波点接口错误（HTTP ${response.code}）")
            return raw
        }
    }

    /** The car protocol sends JSON bodies on GET; OkHttp refuses that, so use a raw TLS request. */
    private fun getWithBody(pathAndQuery: String, headers: Map<String, String>, body: String): String {
        require(pathAndQuery.startsWith("/") && !pathAndQuery.contains("//"))
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        // createSocket(host, port) also seeds SNI, which some CDN edges require.
        val socket = factory.createSocket(API_HOST, 443) as SSLSocket
        try {
            socket.soTimeout = 30_000
            val parameters = socket.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = parameters
            socket.startHandshake()
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("GET ").append(pathAndQuery).append(" HTTP/1.1\r\n")
                append("Host: ").append(API_HOST).append("\r\n")
                headers.forEach { (key, value) ->
                    if (!key.equals("Host", true) && !key.equals("Content-Length", true) &&
                        !key.equals("Connection", true) && !key.equals("Accept-Encoding", true))
                        append(key).append(": ").append(value).append("\r\n")
                }
                append("Accept-Encoding: identity\r\n")
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)
            socket.outputStream.use { output ->
                output.write(head)
                output.write(bodyBytes)
                output.flush()
            }
            return readResponse(socket.inputStream)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Reads exactly the framed body instead of waiting for the peer to close the socket. */
    private fun readResponse(input: InputStream): String {
        val headerBytes = readHeaderBytes(input)
        val head = String(headerBytes, Charsets.ISO_8859_1).trimEnd('\r', '\n')
        val status = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
        val headers = head.lineSequence().drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        var payload = when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> readChunked(input)
            headers["content-length"]?.toIntOrNull()?.let { it >= 0 } == true ->
                readExactly(input, headers.getValue("content-length").toInt())
            else -> input.readBytes()
        }
        if (headers["content-encoding"]?.contains("gzip", true) == true &&
            payload.size > 2 && payload[0] == 0x1f.toByte() && payload[1] == 0x8b.toByte()) {
            payload = GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
        }
        if (status !in 200..299) throw ApiException(ErrorKind.Server, status, "波点接口错误（HTTP $status）")
        return String(payload, Charsets.UTF_8)
    }

    private fun readHeaderBytes(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        var match = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) break
            output.write(byte)
            match = when {
                byte == 13 && (match == 0 || match == 2) -> match + 1
                byte == 10 && (match == 1 || match == 3) -> match + 1
                else -> 0
            }
            if (match == 4) break
        }
        return output.toByteArray()
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val output = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(output, read, size - read)
            if (count < 0) break
            read += count
        }
        return if (read == size) output else output.copyOf(read)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val line = readAsciiLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readAsciiLine(input)
                break
            }
            output.write(readExactly(input, size))
            readAsciiLine(input)
        }
        return output.toByteArray()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (output.size() == 0) null else output.toString("ISO-8859-1")
            if (byte == 10) break
            if (byte != 13) output.write(byte)
        }
        return output.toString("ISO-8859-1")
    }

    private fun parseResponse(raw: ByteArray): String {
        val boundary = findHeaderEnd(raw)
        if (boundary < 0) throw ApiException(ErrorKind.Parse, userMessage = "波点接口返回格式异常")
        val head = String(raw, 0, boundary, Charsets.ISO_8859_1)
        val status = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
        val headers = head.lineSequence().drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        var payload = raw.copyOfRange(boundary + 4, raw.size)
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) payload = dechunk(payload)
        if (headers["content-encoding"]?.contains("gzip", true) == true &&
            payload.size > 2 && payload[0] == 0x1f.toByte() && payload[1] == 0x8b.toByte()) {
            payload = GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
        }
        if (status !in 200..299) throw ApiException(ErrorKind.Server, status, "波点接口错误（HTTP $status）")
        return String(payload, Charsets.UTF_8)
    }

    private fun findHeaderEnd(raw: ByteArray): Int {
        for (index in 0..raw.size - 4) {
            if (raw[index] == 13.toByte() && raw[index + 1] == 10.toByte() &&
                raw[index + 2] == 13.toByte() && raw[index + 3] == 10.toByte()) return index
        }
        return -1
    }

    private fun dechunk(raw: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var index = 0
        while (index < raw.size) {
            val lineEnd = run {
                var cursor = index
                while (cursor + 1 < raw.size && !(raw[cursor] == 13.toByte() && raw[cursor + 1] == 10.toByte())) cursor++
                if (cursor + 1 >= raw.size) return output.toByteArray()
                cursor
            }
            val size = String(raw, index, lineEnd - index, Charsets.ISO_8859_1)
                .substringBefore(';').trim().toIntOrNull(16) ?: return output.toByteArray()
            index = lineEnd + 2
            if (size == 0) break
            if (index + size > raw.size) return output.toByteArray()
            output.write(raw, index, size)
            index += size + 2
        }
        return output.toByteArray()
    }

    private fun signQuery(path: String, params: Map<String, String>, body: String): String {
        val query = params.entries.joinToString("&") { (key, value) -> encode(key) + "=" + encode(value) }
        val seed = StringBuilder("kuwotest")
        seed.append(query.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.toCharArray().sorted().joinToString(""))
        if (body.isNotEmpty()) seed.append(md5(body + "kuwotest"))
        return md5(seed.toString() + path)
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val API_HOST = "bd-api.kuwo.cn"
        const val API_ORIGIN = "https://$API_HOST"
    }
}

private fun JsonObject?.text(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

private fun JsonObject?.int(key: String): Int? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonObject?.long(key: String): Long? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

private fun JsonObject?.array(key: String): List<JsonElement> =
    (this?.get(key) as? JsonArray)?.toList().orEmpty()
