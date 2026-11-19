package io.github.currencortex.music.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.currencortex.music.data.settings.LyricsSource
import io.github.currencortex.music.ui.component.MusicDestinationRow
import io.github.currencortex.music.ui.component.MusicIcons
import io.github.currencortex.music.ui.component.nativeui.*

/**
 * Connections only: lyrics source and the self-hosted Navidrome library. Audio
 * plays from the platform itself (NetEase / QQ Music / KuGou direct) or
 * Navidrome — there is no CurrentMusic service or source-selection layer.
 */
@Composable fun MusicSettingsScreen(vm: MusicSettingsViewModel, onNavidrome: () -> Unit = {}) {
    SettingsUiScope {
    val settings by vm.settings.collectAsStateWithLifecycle()
    SettingsPage("音源与网络", "network_settings") {
        item("lyrics") { SettingsSection("歌词来源", MusicIcons.File) {
            SettingsOptionsRow("默认来源", when (settings.lyricsSource) {
                LyricsSource.AUTO -> "自动择优"
                LyricsSource.CURRENT_MUSIC -> "CurrentMusic"
                LyricsSource.NETEASE -> "网易云"
                LyricsSource.LOCAL -> "本地内嵌"
            }, LyricsSource.entries.map { source ->
                SettingsOption("lyrics_source_${source.name.lowercase()}", source.label, source == settings.lyricsSource,
                    summary = when (source) {
                        LyricsSource.AUTO -> "信息匹配 ≥75% · QQ 优先查找真实逐字"
                        LyricsSource.CURRENT_MUSIC -> "CurrentMusic · LDDC 真实逐字"
                        LyricsSource.NETEASE -> "手机直连网易云逐字或逐行歌词"
                        LyricsSource.LOCAL -> "离线读取本地音频文件内的歌词"
                    }, onSelect = { vm.lyricsSource(source) })
            }, Modifier.testTag("lyrics_source"))
        } }
        item("library") { SettingsSection("自建曲库", MusicIcons.List) {
            MusicDestinationRow("Navidrome", onNavidrome, Modifier.testTag("open_navidrome"),
                summary = "连接自己的音乐服务器 · Subsonic")
        } }
    }
    }
}
