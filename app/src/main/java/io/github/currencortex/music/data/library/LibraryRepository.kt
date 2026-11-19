package io.github.currencortex.music.data.library

import io.github.currencortex.music.core.network.*
import io.github.currencortex.music.data.song.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Public NetEase catalog metadata plus the daily/recent feeds. There is no
 * CurrentMusic account layer: playlists, likes and play reports live on the
 * platforms themselves (NetEase direct, QQ Music, Navidrome).
 */
class LibraryRepository(private val api: ApiClient, private val session: () -> RequestSession) {
    val revision = MutableStateFlow(0L)
    private val mutationMutex = Mutex()
    private val reads = SessionReadCache(session, { revision.value })
    fun clearReads() = reads.clear()
    fun invalidate() { reads.clear(); revision.update { it + 1 } }
    private suspend inline fun <reified T> read(path: String, expected: RequestSession, query: Map<String, String> = emptyMap()): T =
        api.decode(api.request("GET", path, query, authenticated = true, expectedSession = expected))
    private suspend fun <T : Any> backgroundRead(key: String, fresh: Boolean = false, load: suspend (RequestSession) -> T): T =
        withContext(Dispatchers.Default) { reads.read(key, fresh, load) }
    suspend fun daily(fresh: Boolean = false): Daily = backgroundRead("daily", fresh) { expected ->
        val d = api.netease?.takeIf { api.nativeMediaEnabled || api.hasLocalNeteaseLogin || expected.token == null }
            ?.let { api.decode<DailyDto>(it.daily(expected.neteaseRevision)) } ?: read<DailyDto>("daily", expected)
        val daily = d.daily.map(SongDto::toDomain)
        val recommended = d.forYou.map(SongDto::toDomain).distinctBy { it.id }
        // Preserve FM/server order; fill a short batch from the already loaded daily list.
        // This also covers legacy servers that only provide three forYou songs.
        val forYou = if (recommended.size >= 12) recommended else
            (recommended + daily).distinctBy { it.id }.take(12)
        Daily(daily, forYou, d.artists)
    }
    suspend fun recent(fresh: Boolean = false): List<Song> = backgroundRead("recent", fresh) { expected ->
        (if (expected.token != null && !api.nativeMediaEnabled) read<SongListDto>("plays/recent", expected, mapOf("limit" to "50"))
        else api.netease?.let { native -> api.decode<SongListDto>(native.recent(expectedRevision = expected.neteaseRevision)) }
            ?: read<SongListDto>("plays/recent", expected, mapOf("limit" to "50"))).songs.map(SongDto::toDomain)
    }
    suspend fun artist(id: Long, offset: Int = 0): Artist {
        val d = api.get<ArtistDto>("ncm/artist", mapOf("id" to "$id", "offset" to "$offset", "limit" to "100"))
        return Artist(id, d.name, d.pic.replace("http:", "https:"), d.alias, d.songs.map(SongDto::toDomain), d.more,
            d.albums.map(AlbumDto::domain), d.albumTotal, d.total, d.briefDesc, d.mvTotal)
    }
    suspend fun artistMvs(id: Long, offset: Int = 0): ArtistMvsDto =
        api.get<ArtistMvsDto>("ncm/artist/mv", mapOf("id" to "$id", "offset" to "$offset", "limit" to "20"))
    suspend fun artistProfile(id: Long): ArtistProfile = api.get("ncm/artist/profile", mapOf("id" to "$id"))
    suspend fun artistFans(id: Long): ArtistFans = api.get("ncm/artist/fans", mapOf("id" to "$id"))
    suspend fun artistOverview(id: Long): ArtistOverview {
        val d = api.get<ArtistOverviewDto>("ncm/artist/overview", mapOf("id" to "$id"))
        return ArtistOverview(d.songs.map(SongDto::toDomain).filter { it.id > 0 }.distinctBy { it.id },
            d.albums.map(AlbumDto::domain).filter { it.id > 0 }.distinctBy { it.id },
            d.artists.filter { it.id > 0 && it.id != id }.distinctBy { it.id }
                .map { CatalogEntry(it.id, it.name, it.pic.replace("http:", "https:"), it.alias) },
            d.awardTitle, d.awardCount, d.awardImages)
    }
    suspend fun artistBiography(id: Long, fresh: Boolean = false): ArtistBiography = backgroundRead("artist-bio/$id", fresh) {
        api.get<ArtistBiography>("ncm/artist/desc", mapOf("id" to "$id")).also {
            if (it.code != 200) throw ApiException(if (it.code == 404) ErrorKind.NotFound else ErrorKind.Server, it.code)
        }
    }
    suspend fun artistAlbums(id: Long, offset: Int, order: String = "time"): Pair<List<Album>, Boolean> {
        require(order in setOf("time", "hot"))
        val d = api.get<ArtistAlbumsDto>("ncm/artist/albums", mapOf("id" to "$id", "offset" to "$offset", "limit" to "30", "order" to order))
        return d.albums.map(AlbumDto::domain) to d.more
    }
    suspend fun album(id: Long) = api.get<AlbumDto>("ncm/album", mapOf("id" to "$id")).domain().let { album ->
        album.copy(id = id, songs = album.songs.map { it.copy(albumId = id) })
    }
    suspend fun catalog(query: String, albums: Boolean, offset: Int = 0): CatalogPage {
        val d = api.get<CatalogSearchDto>("ncm/search", mapOf("keywords" to query, "type" to if (albums) "album" else "artist", "limit" to "30", "offset" to "$offset"))
        return if (albums) CatalogPage(d.albums.map { CatalogEntry(it.id, it.name, it.pic.replace("http:", "https:"), it.artist) }, d.hasMore.album, d.totals.album)
        else CatalogPage(d.artists.map { CatalogEntry(it.id, it.name, it.pic.replace("http:", "https:"), it.alias) }, d.hasMore.artist, d.totals.artist)
    }
    suspend fun searchPlaylists(query: String, offset: Int = 0): CatalogPage {
        val d = api.get<CatalogSearchDto>("ncm/search", mapOf("keywords" to query, "type" to "playlist", "limit" to "30", "offset" to "$offset"))
        return CatalogPage(d.playlists.map { playlist ->
            CatalogEntry(playlist.id, playlist.name, playlist.pic.replace("http:", "https:"),
                listOfNotNull(playlist.trackCount.takeIf { it > 0 }?.let { "$it 首" },
                    playlist.creator.takeIf(String::isNotBlank)?.let { "创建者：$it" }).joinToString(" · "))
        }, d.hasMore.playlist, d.totals.playlist)
    }
    suspend fun mv(id: Long) = api.get<MvDetailDto>("ncm/mv/detail", mapOf("mvid" to "$id")).data
    suspend fun mvSource(id: Long, resolution: Int = 1080): String {
        require(resolution in listOf(240, 480, 720, 1080))
        val url = read<MvUrlDto>("ncm/mv/url", session(), mapOf("id" to "$id", "r" to "$resolution")).data.url
        return url?.toHttpUrlOrNull()?.toString() ?: throw ApiException(ErrorKind.NotFound)
    }
    companion object {
        fun metadata(song: Song) = buildJsonObject {
            put("ncm_id", song.id); put("name", song.name); put("artists", song.artists)
            put("album", song.album); put("pic", song.cover); put("duration", song.durationMs); put("mv", song.mv)
            put("artist_ids", JsonArray(song.artistIds.map(::JsonPrimitive)))
        }
    }
}
