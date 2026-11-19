package io.github.currencortex.music.feature.kugou

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.feature.provider.ProviderHomeIntro
import io.github.currencortex.music.feature.provider.ProviderHomeScaffold
import io.github.currencortex.music.feature.provider.ProviderHotSearches
import io.github.currencortex.music.ui.component.LoadingSongList
import io.github.currencortex.music.ui.component.MusicPlatformSwitcher
import io.github.currencortex.music.ui.component.MusicSearchBar
import io.github.currencortex.music.ui.component.MusicTextAction
import io.github.currencortex.music.ui.component.SongRow
import io.github.currencortex.music.ui.component.SongRowUi
import io.github.currencortex.music.ui.component.nativeui.SettingsHint
import io.github.currencortex.music.ui.util.viewModelFactory
import kotlinx.coroutines.launch

/** KuGou home, using the same brand/search-first shell as the NetEase and QQ homes. */
@Composable
fun KugouHomeScreen(container: AppContainer, onAccount: () -> Unit, play: (List<Song>, Int) -> Unit) {
    val preferences by container.musicSettings.state.collectAsStateWithLifecycle()
    val account by container.kugou.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val vm: KugouSearchViewModel = viewModel(factory = viewModelFactory { KugouSearchViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()

    ProviderHomeScaffold(
        tag = "kugou_home",
        avatarUrl = account.avatar,
        onProfile = { _, _ -> onAccount() },
    ) {
        item(key = "search") {
            MusicSearchBar(
                state.query, { vm.search() }, Modifier.testTag("kugou_home_search"),
                onInput = vm::input, placeholder = "搜索酷狗音乐歌曲", inputTag = "kugou_home_input",
                leadingContent = {
                    MusicPlatformSwitcher(preferences.musicPlatform, { platform ->
                        scope.launch { container.musicSettings.setMusicPlatform(platform) }
                    })
                },
            )
        }
        item(key = "intro") {
            ProviderHomeIntro(
                "酷狗音乐",
                if (account.loggedIn) "${account.nickname.ifBlank { "已登录" }} · 搜索并播放酷狗曲库"
                else "登录酷狗音乐账号，搜索并播放酷狗曲库",
            )
        }
        if (!account.loggedIn) item(key = "login") {
            MusicTextAction("扫码登录酷狗音乐 ›", onAccount, Modifier.testTag("kugou_home_login"))
        }
        state.error?.let { error -> item(key = "error") { SettingsHint(error) } }
        if (state.loading) item(key = "loading") { LoadingSongList(6) }
        itemsIndexed(state.songs, key = { index, song -> "${song.kugouHash}-$index" }) { index, song ->
            SongRow(SongRowUi(song), { play(state.songs, index) },
                { container.playerController.add(song, true) }, { container.playerController.add(song) })
        }
        if (!state.loading && state.songs.isEmpty()) {
            if (state.submitted.isBlank()) {
                item(key = "hot") {
                    ProviderHotSearches(listOf("周杰伦", "林俊杰", "薛之谦", "邓紫棋", "晴天", "稻香")) { term ->
                        vm.input(term); vm.search()
                    }
                }
                item(key = "hint") { SettingsHint("输入歌名或歌手，搜索并播放酷狗曲库。") }
            } else item(key = "empty") { SettingsHint("没有找到「${state.submitted}」相关歌曲") }
        }
    }
}
