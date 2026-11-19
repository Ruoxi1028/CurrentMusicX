package io.github.currencortex.music.feature.provider

import io.github.currencortex.music.data.qqmusic.QqAccountState
import io.github.currencortex.music.core.netease.NeteaseAccountState

/**
 * Online music providers the app can log into, in the spirit of folia's Omni layer:
 * each provider owns its own account session, search, library and playback.
 * The app itself has no account system of its own.
 */
enum class OnlineProviderId(val id: String, val label: String) {
    NETEASE("netease", "网易云音乐"),
    QQ("qq", "QQ 音乐"),
    KUGOU("kugou", "酷狗音乐"),
    BODIAN("bodian", "波点音乐"),
    ;

    companion object {
        fun from(id: String?): OnlineProviderId = entries.firstOrNull { it.id == id } ?: NETEASE
    }
}

/** What a provider can do; drives which rows and entries the account page shows. */
data class OnlineProviderCapabilities(
    val search: Boolean = true,
    val playback: Boolean = true,
    val lyrics: Boolean = true,
    val auth: Boolean = true,
    val userLibrary: Boolean = true,
    val playlists: Boolean = true,
    val recommendations: Boolean = false,
)

data class OnlineProviderSummary(
    val providerId: OnlineProviderId,
    val label: String,
    val capabilities: OnlineProviderCapabilities,
    /** Logged-in state of the provider's own account. */
    val loggedIn: Boolean,
    val nickname: String = "",
    val avatar: String = "",
)

/** A live view of every provider account, produced by [ProviderAccountHub]. */
data class ProviderAccountSnapshot(
    val active: OnlineProviderId,
    val providers: List<OnlineProviderSummary>,
)

/**
 * Single source of truth for "which music app account am I using". The active
 * provider mirrors [io.github.currencortex.music.data.settings.MusicPlatform]
 * so existing search/playback routing keeps working unchanged.
 */
class ProviderAccountHub(
    private val active: () -> OnlineProviderId,
    private val netease: () -> NeteaseAccountState,
    private val qq: () -> QqAccountState,
    private val kugou: () -> io.github.currencortex.music.data.kugou.KugouAccountState,
    private val bodian: () -> io.github.currencortex.music.data.bodian.BodianAccountState,
) {
    fun snapshot(): ProviderAccountSnapshot {
        val neteaseState = netease()
        val qqState = qq()
        val kugouState = kugou()
        val bodianState = bodian()
        return ProviderAccountSnapshot(
            active = active(),
            providers = listOf(
                OnlineProviderSummary(OnlineProviderId.NETEASE, OnlineProviderId.NETEASE.label,
                    OnlineProviderCapabilities(recommendations = true),
                    neteaseState.loggedIn && !neteaseState.stale,
                    neteaseState.profile?.nickname.orEmpty(), neteaseState.profile?.avatar.orEmpty()),
                OnlineProviderSummary(OnlineProviderId.QQ, OnlineProviderId.QQ.label,
                    OnlineProviderCapabilities(recommendations = true),
                    qqState.loggedIn, qqState.nickname, qqState.avatar),
                OnlineProviderSummary(OnlineProviderId.KUGOU, OnlineProviderId.KUGOU.label,
                    OnlineProviderCapabilities(recommendations = true),
                    kugouState.loggedIn, kugouState.nickname, kugouState.avatar),
                OnlineProviderSummary(OnlineProviderId.BODIAN, OnlineProviderId.BODIAN.label,
                    OnlineProviderCapabilities(recommendations = true),
                    bodianState.loggedIn, bodianState.nickname, bodianState.avatar),
            ),
        )
    }
}
