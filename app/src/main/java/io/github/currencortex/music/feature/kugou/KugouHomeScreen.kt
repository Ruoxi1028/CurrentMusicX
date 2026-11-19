package io.github.currencortex.music.feature.kugou

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.data.song.Song
import io.github.currencortex.music.ui.component.LoadingSongList
import io.github.currencortex.music.ui.component.LocalMusicBottomInset
import io.github.currencortex.music.ui.component.MusicPlatformSwitcher
import io.github.currencortex.music.ui.component.MusicSearchBar
import io.github.currencortex.music.ui.component.SongRow
import io.github.currencortex.music.ui.component.SongRowUi
import io.github.currencortex.music.ui.component.nativeui.SettingsHint
import io.github.currencortex.music.ui.util.viewModelFactory
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * KuGou home: the same platform-surface shape as the NetEase / QQ homes, with an
 * inline catalogue search instead of a separate search sheet (KuGou has no
 * recommendation endpoints in the folia port).
 */
@Composable
fun KugouHomeScreen(
    container: AppContainer,
    onAccount: () -> Unit,
    play: (List<Song>, Int) -> Unit,
) {
    val preferences by container.musicSettings.state.collectAsStateWithLifecycle()
    val account by container.kugou.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val vm: KugouSearchViewModel = viewModel(factory = viewModelFactory { KugouSearchViewModel(container) })
    val state by vm.state.collectAsStateWithLifecycle()
    val bottom = LocalMusicBottomInset.current

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().testTag("kugou_home"),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp + bottom),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "search") {
            MusicSearchBar(
                state.query,
                { vm.search() },
                Modifier.testTag("kugou_home_search"),
                onInput = vm::input,
                placeholder = "搜索酷狗音乐歌曲",
                inputTag = "kugou_home_input",
                leadingContent = {
                    MusicPlatformSwitcher(preferences.musicPlatform, { platform ->
                        scope.launch { container.musicSettings.setMusicPlatform(platform) }
                    })
                },
            )
        }
        item(key = "account") {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onAccount).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (account.avatar.isNotBlank()) {
                    AsyncImage(account.avatar, "酷狗头像", Modifier.size(38.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.size(38.dp).clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = .1f)))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("酷狗音乐", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (account.loggedIn) account.nickname.ifBlank { "已登录" } else "未登录 · 点击扫码登录",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f),
                    )
                }
                Text(if (account.loggedIn) "账号" else "登录", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary)
            }
        }
        state.error?.let { error -> item(key = "error") { SettingsHint(error) } }
        if (state.loading) item(key = "loading") { LoadingSongList(6) }
        itemsIndexed(state.songs, key = { index, song -> "${song.kugouHash}-$index" }) { index, song ->
            SongRow(SongRowUi(song), { play(state.songs, index) },
                { container.playerController.add(song, true) }, { container.playerController.add(song) })
        }
        if (!state.loading && state.songs.isEmpty()) item(key = "hint") {
            SettingsHint(if (state.submitted.isBlank()) "输入歌名或歌手，搜索并播放酷狗曲库。"
            else "没有找到「${state.submitted}」相关歌曲")
        }
    }
}
