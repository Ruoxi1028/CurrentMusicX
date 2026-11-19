package io.github.currencortex.music.feature.provider

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.data.settings.MusicPlatform
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.ui.component.MusicDestinationRow
import io.github.currencortex.music.ui.component.MusicIcons
import io.github.currencortex.music.ui.component.musicScrollPadding
import io.github.currencortex.music.ui.component.nativeui.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The "我的" tab, in the spirit of folia's account surface: the app has no account
 * of its own — each row is a music-platform account (NetEase / QQ / KuGou) that the
 * user logs into with a QR code, plus their libraries and local music.
 */
@Composable
fun ProviderAccountScreen(
    container: AppContainer,
    navigate: (String) -> Unit,
    onSettings: () -> Unit,
    play: (List<Song>, Int) -> Unit,
) {
    val settings by container.musicSettings.state.collectAsStateWithLifecycle()
    val netease by container.neteaseSessions.state.collectAsStateWithLifecycle()
    val qq by container.qqMusic.state.collectAsStateWithLifecycle()
    val kugou by container.kugou.state.collectAsStateWithLifecycle()
    val navidrome by container.navidrome.state.collectAsStateWithLifecycle()
    val qqPlatform = settings.musicPlatform == MusicPlatform.QQ_MUSIC
    val hub = remember(container) { container.providerHub }
    // Recomposes on any provider session change; snapshot() reads the live states.
    val snapshot = hub.snapshot()
    SettingsUiScope {
    SettingsPage("我的", "provider_account") {
        item("accounts") {
            SettingsSection("音乐平台账号", MusicIcons.Account) {
                snapshot.providers.forEach { provider ->
                    SettingsPreference(provider.label,
                        if (provider.loggedIn) provider.nickname.ifBlank { "已登录" } else "未登录 · 点击扫码登录",
                        Modifier.testTag("provider_${provider.providerId.id}"),
                        onClick = {
                            navigate(when (provider.providerId) {
                                OnlineProviderId.NETEASE -> "user/binding"
                                OnlineProviderId.QQ -> "qq/account"
                                OnlineProviderId.KUGOU -> "kugou/account"
                                OnlineProviderId.BODIAN -> "bodian/account"
                            })
                        })
                    if (provider != snapshot.providers.last()) SettingsDivider()
                }
            }
        }
        item("library") {
            SettingsSection("我的音乐", MusicIcons.List) {
                MusicDestinationRow(if (qqPlatform) "我喜欢" else "我喜欢",
                    { navigate(if (qqPlatform) "qq/likes" else "lib/likes") },
                    Modifier.testTag("provider_likes"),
                    summary = if (qqPlatform) "QQ 音乐收藏的歌曲" else "网易云收藏的歌曲")
                SettingsDivider()
                MusicDestinationRow("歌单", { navigate(if (qqPlatform) "qq/playlists" else "lib/playlists") },
                    Modifier.testTag("provider_playlists"), summary = "自建与收藏的歌单")
                SettingsDivider()
                MusicDestinationRow("最近播放", { navigate("lib/recent") },
                    Modifier.testTag("provider_recent"), summary = "本机与账号的播放记录")
                SettingsDivider()
                MusicDestinationRow("本地音乐", { navigate("lib/local") },
                    Modifier.testTag("provider_local"), summary = "扫描文件夹中的歌曲")
                SettingsDivider()
                MusicDestinationRow("听歌时长", { navigate("user/listening") },
                    Modifier.testTag("provider_listening"), summary = "本机播放时长与网易云年度报告")
                if (netease.loggedIn && !netease.stale) {
                    SettingsDivider()
                    MusicDestinationRow("音乐云盘", { navigate("lib/cloud") },
                        Modifier.testTag("provider_cloud"), summary = "上传到网易云的个人曲库")
                }
            }
        }
        item("sources") {
            SettingsSection("曲库", MusicIcons.Storage) {
                MusicDestinationRow("酷狗音乐", { navigate("kugou/search") },
                    Modifier.testTag("provider_kugou"), summary = "搜索并播放酷狗曲库")
                SettingsDivider()
                MusicDestinationRow("波点音乐", { navigate("bodian/search") },
                    Modifier.testTag("provider_bodian"), summary = "搜索并播放波点曲库")
                SettingsDivider()
                MusicDestinationRow("汽水音乐", { navigate("qishui/search") },
                    Modifier.testTag("provider_qishui"), summary = "免登录搜索，播放平台明文音源")
                if (navidrome.configured) {
                    SettingsDivider()
                    MusicDestinationRow("Navidrome", { navigate("navidrome") },
                        Modifier.testTag("provider_navidrome"), summary = "自己的音乐服务器 · Subsonic")
                }
            }
        }
        item("settings") {
            SettingsSection("设置", MusicIcons.Palette) {
                MusicDestinationRow("外观与设置", onSettings, Modifier.testTag("provider_settings"),
                    summary = "主题、音效、音源与网络、存储")
            }
        }
        item("hint") {
            SettingsHint("CurrentMusicX 不提供自有账号：登录各音乐平台账号后即可搜索、播放与同步收藏。")
        }
    }
    }
}
