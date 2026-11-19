package io.github.currencortex.music.feature.bodian

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.core.network.AppResult
import io.github.currencortex.music.core.network.appResult
import io.github.currencortex.music.data.bodian.BodianSong
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.ui.component.*
import io.github.currencortex.music.ui.component.nativeui.*
import io.github.currencortex.music.ui.util.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class BodianSearchState(
    val query: String = "", val submitted: String = "",
    val loading: Boolean = false, val songs: List<Song> = emptyList(), val error: String? = null,
)

class BodianSearchViewModel(private val container: AppContainer) : ViewModel() {
    val state = MutableStateFlow(BodianSearchState())
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
                container.bodian.search(keyword).mapIndexed { index, entry -> container.bodian.toSong(entry, index.toLong()) }
            }) {
                is AppResult.Success -> state.value = state.value.copy(loading = false, songs = result.value)
                is AppResult.Failure -> state.value = state.value.copy(loading = false, error = result.message)
            }
        }
    }
}

/** Bodian home: platform switcher, account row and an inline catalogue search. */
@Composable
fun BodianHomeScreen(container: AppContainer, onAccount: () -> Unit, play: (List<Song>, Int) -> Unit) {
    val preferences by container.musicSettings.state.collectAsStateWithLifecycle()
    val account by container.bodian.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val vm: BodianSearchViewModel = viewModel(factory = viewModelFactory { BodianSearchViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val bottom = LocalMusicBottomInset.current

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().testTag("bodian_home"),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + bottom),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "search") {
            MusicSearchBar(
                state.query, { vm.search() }, Modifier.testTag("bodian_home_search"),
                onInput = vm::input, placeholder = "搜索波点音乐歌曲", inputTag = "bodian_home_input",
                leadingContent = {
                    MusicPlatformSwitcher(preferences.musicPlatform, { platform ->
                        scope.launch { container.musicSettings.setMusicPlatform(platform) }
                    })
                },
            )
        }
        item(key = "account") { BodianAccountRow(account.loggedIn, account.nickname, account.avatar, onAccount) }
        state.error?.let { error -> item(key = "error") { SettingsHint(error) } }
        if (state.loading) item(key = "loading") { LoadingSongList(6) }
        itemsIndexed(state.songs, key = { index, song -> "${song.bodianId}-$index" }) { index, song ->
            SongRow(SongRowUi(song), { play(state.songs, index) },
                { container.playerController.add(song, true) }, { container.playerController.add(song) })
        }
        if (!state.loading && state.songs.isEmpty()) item(key = "hint") {
            SettingsHint(if (state.submitted.isBlank()) "输入歌名或歌手，搜索并播放波点曲库。"
            else "没有找到「${state.submitted}」相关歌曲")
        }
    }
}

/** Bodian catalogue subpage reached from the library section. */
@Composable
fun BodianSearchScreen(container: AppContainer, onBack: () -> Unit, onAccount: () -> Unit) {
    val account by container.bodian.state.collectAsStateWithLifecycle()
    val vm: BodianSearchViewModel = viewModel(factory = viewModelFactory { BodianSearchViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    MusicSubpage("波点音乐", "bodian_search", onBack, backTag = "bodian_search_back",
        actions = {
            GlassIconButton(onAccount, "波点账号", Modifier.testTag("bodian_open_account")) {
                MusicIcons.Account
            }
        }) { inset ->
        LazyColumn(Modifier.fillMaxSize().testTag("bodian_search_list"),
            contentPadding = musicScrollPadding(PaddingValues(16.dp, inset + 8.dp, 16.dp, 32.dp)),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                MusicSearchBar(state.query, { vm.search() }, Modifier.testTag("bodian_search_field"),
                    onInput = vm::input, placeholder = "搜索波点音乐歌曲")
            }
            item { BodianAccountRow(account.loggedIn, account.nickname, account.avatar, onAccount) }
            state.error?.let { error -> item { SettingsHint(error) } }
            if (state.loading) item { LoadingSongList(6) }
            itemsIndexed(state.songs, key = { index, song -> "${song.bodianId}-$index" }) { index, song ->
                SongRow(SongRowUi(song), { container.playerController.playList(state.songs, index) },
                    { container.playerController.add(song, true) }, { container.playerController.add(song) })
            }
            if (!state.loading && state.songs.isEmpty()) item {
                SettingsHint(if (state.submitted.isBlank()) "输入歌名或歌手，搜索并播放波点曲库。"
                else "没有找到「${state.submitted}」相关歌曲")
            }
        }
    }
}

@Composable
private fun BodianAccountRow(loggedIn: Boolean, nickname: String, avatar: String, onAccount: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onAccount).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (avatar.isNotBlank()) {
            AsyncImage(avatar, "波点头像", Modifier.size(38.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(38.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary.copy(alpha = .1f)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("波点音乐", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (loggedIn) nickname.ifBlank { "已登录" } else "未登录 · 点击扫码登录",
                fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f),
            )
        }
        Text(if (loggedIn) "账号" else "登录", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
    }
}
