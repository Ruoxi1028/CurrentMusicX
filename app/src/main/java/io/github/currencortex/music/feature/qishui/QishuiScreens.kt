package io.github.currencortex.music.feature.qishui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.core.network.AppResult
import io.github.currencortex.music.core.network.appResult
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.feature.provider.ProviderHomeIntro
import io.github.currencortex.music.feature.provider.ProviderHomeScaffold
import io.github.currencortex.music.feature.provider.ProviderHotSearches
import io.github.currencortex.music.ui.component.*
import io.github.currencortex.music.ui.component.nativeui.*
import io.github.currencortex.music.ui.util.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class QishuiSearchState(
    val query: String = "", val submitted: String = "",
    val loading: Boolean = false, val songs: List<Song> = emptyList(), val error: String? = null,
)

class QishuiSearchViewModel(private val container: AppContainer) : ViewModel() {
    val state = MutableStateFlow(QishuiSearchState())
    private var job: Job? = null
    fun input(value: String) { state.value = state.value.copy(query = value) }
    fun search() {
        val before = state.value
        val keyword = before.query.trim()
        if (keyword.isBlank()) return
        job?.cancel()
        state.value = before.copy(loading = true, error = null, submitted = keyword)
        job = viewModelScope.launch {
            when (val result = appResult {
                container.qishui.search(keyword).mapIndexed { index, entry -> container.qishui.toSong(entry, index.toLong()) }
            }) {
                is AppResult.Success -> state.value = state.value.copy(loading = false, songs = result.value)
                is AppResult.Failure -> state.value = state.value.copy(loading = false, error = result.message)
            }
        }
    }
}

/** Qishui home, using the same brand/search-first shell as the NetEase and QQ homes. */
@Composable
fun QishuiHomeScreen(container: AppContainer, play: (List<Song>, Int) -> Unit) {
    val preferences by container.musicSettings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val vm: QishuiSearchViewModel = viewModel(factory = viewModelFactory { QishuiSearchViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()

    ProviderHomeScaffold(tag = "qishui_home", avatarUrl = null) {
        item(key = "search") {
            MusicSearchBar(
                state.query, { vm.search() }, Modifier.testTag("qishui_home_search"),
                onInput = vm::input, placeholder = "搜索汽水音乐歌曲", inputTag = "qishui_home_input",
                leadingContent = {
                    MusicPlatformSwitcher(preferences.musicPlatform, { platform ->
                        scope.launch { container.musicSettings.setMusicPlatform(platform) }
                    })
                },
            )
        }
        item(key = "intro") { ProviderHomeIntro("汽水音乐", "免登录搜索 · 平台明文标准音源") }
        state.error?.let { error -> item(key = "error") { SettingsHint(error) } }
        if (state.loading) item(key = "loading") { LoadingSongList(6) }
        itemsIndexed(state.songs, key = { index, song -> "${song.qishuiId}-$index" }) { index, song ->
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
                item(key = "hint") { SettingsHint("输入歌名或歌手，搜索并播放汽水曲库。") }
            } else item(key = "empty") { SettingsHint("没有找到「${state.submitted}」相关歌曲") }
        }
    }
}

/** Qishui catalogue subpage reached from the library section. */
@Composable
fun QishuiSearchScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: QishuiSearchViewModel = viewModel(factory = viewModelFactory { QishuiSearchViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    MusicSubpage("汽水音乐", "qishui_search", onBack, backTag = "qishui_search_back") { inset ->
        LazyColumn(Modifier.fillMaxSize().testTag("qishui_search_list"),
            contentPadding = musicScrollPadding(PaddingValues(16.dp, inset + 8.dp, 16.dp, 32.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                MusicSearchBar(state.query, { vm.search() }, Modifier.testTag("qishui_search_field"),
                    onInput = vm::input, placeholder = "搜索汽水音乐歌曲")
            }
            state.error?.let { error -> item { SettingsHint(error) } }
            if (state.loading) item { LoadingSongList(6) }
            itemsIndexed(state.songs, key = { index, song -> "${song.qishuiId}-$index" }) { index, song ->
                SongRow(SongRowUi(song), { container.playerController.playList(state.songs, index) },
                    { container.playerController.add(song, true) }, { container.playerController.add(song) })
            }
            if (!state.loading && state.songs.isEmpty()) item {
                SettingsHint(if (state.submitted.isBlank()) "输入歌名或歌手，搜索并播放汽水曲库。"
                else "没有找到「${state.submitted}」相关歌曲")
            }
        }
    }
}
