package io.github.currencortex.music.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.core.media.listeningDuration
import io.github.currencortex.music.feature.roaming.PersonalRoamingPage
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable internal fun PlayerListeningPage(container: AppContainer, onPlay: () -> Unit,
    onBinding: () -> Unit, onDialogActive: (Boolean) -> Unit, onListening: () -> Unit = {}) {
    val netease by container.neteaseSessions.state.collectAsStateWithLifecycle()
    val today by container.todayListening.today.collectAsStateWithLifecycle()
    val nickname = netease.profile?.nickname.orEmpty().ifBlank { if (netease.loggedIn) "网易云用户" else "未登录" }
    val avatar = netease.profile?.avatar.orEmpty()
    LaunchedEffect(container) {
        while (true) { container.todayListening.refresh(); delay(15_000) }
    }
    val colors = MiuixTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        PersonalRoamingPage(container,
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(end = maxWidth * .08f).testTag("player_listening_scroll"),
            onPlay, onBinding, onDialogActive) {
            Text("我的聆听", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Card {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (avatar.isNotBlank()) AsyncImage(avatar, nickname, Modifier.size(48.dp).clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Box(Modifier.size(48.dp).clip(CircleShape).background(colors.primary.copy(alpha = .08f)))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(nickname, Modifier.testTag("player_side_username"),
                                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onListening).testTag("player_open_listening"), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("今日听歌", fontSize = 14.sp)
                            Text("本机播放时长", fontSize = 11.sp, color = colors.onSurfaceVariantSummary)
                        }
                        Text(listeningDuration(today), Modifier.testTag("player_today_listening"),
                            fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
