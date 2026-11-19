package io.github.currencortex.music.feature.kugou

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
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.core.network.AppResult
import io.github.currencortex.music.core.network.appResult
import io.github.currencortex.music.data.kugou.KugouSong
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.ui.component.*
import io.github.currencortex.music.ui.component.nativeui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class KugouSearchState(
    val query: String = "", val submitted: String = "",
    val loading: Boolean = false, val songs: List<Song> = emptyList(), val error: String? = null,
)

class KugouSearchViewModel(private val container: AppContainer) : ViewModel() {
    val state = MutableStateFlow(KugouSearchState())
    private var job: Job? = null
    fun input(value: String) { state.value = state.value.copy(query = value) }
    fun search(more: Boolean = false) {
        val before = state.value
        val keyword = if (more) before.submitted else before.query.trim()
        if (keyword.isBlank()) return
        job?.cancel()
        state.value = before.copy(loading = true, error = null, submitted = keyword)
        job = viewModelScope.launch {
            when (val result = appResult {
                container.kugou.search(keyword).mapIndexed { index, entry -> container.kugou.toSong(entry, index.toLong()) }
            }) {
                is AppResult.Success -> state.value = state.value.copy(loading = false, songs = result.value)
                is AppResult.Failure -> state.value = state.value.copy(loading = false, error = result.message)
            }
        }
    }
}

/** KuGou catalog search; playback goes through the shared player and audio resolver. */
@Composable
fun KugouSearchScreen(container: AppContainer, onBack: () -> Unit, onAccount: () -> Unit) {
    val vm: KugouSearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = io.github.currencortex.music.ui.util.viewModelFactory { KugouSearchViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val account by container.kugou.state.collectAsStateWithLifecycle()
    MusicSubpage("酷狗音乐", "kugou_search", onBack, backTag = "kugou_search_back",
        actions = {
            GlassIconButton(onAccount, "酷狗账号", Modifier.testTag("kugou_open_account")) {
                io.github.currencortex.music.ui.component.MusicIcons.Account
            }
        }) { inset ->
        LazyColumn(Modifier.fillMaxSize().testTag("kugou_search_list"),
            contentPadding = musicScrollPadding(PaddingValues(16.dp, inset + 8.dp, 16.dp, 32.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                MusicSearchBar(state.query, { vm.search() },
                    modifier = Modifier.testTag("kugou_search_field"),
                    onInput = vm::input,
                    placeholder = if (account.loggedIn) "搜索酷狗歌曲" else "登录后可搜索酷狗歌曲")
            }
            if (!account.loggedIn) item {
                SettingsHint("请先在我的页面登录酷狗音乐账号", Modifier.testTag("kugou_login_hint"))
            }
            state.error?.let { error -> item { SettingsHint(error) } }
            if (state.loading) item { LoadingSongList(6) }
            itemsIndexed(state.songs, key = { index, song -> "${song.kugouHash}-$index" }) { index, song ->
                SongRow(SongRowUi(song), { container.playerController.playList(state.songs, index) },
                    { container.playerController.add(song, true) }, { container.playerController.add(song) })
            }
        }
    }
}
