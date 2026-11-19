package io.github.currencortex.music.feature.kugou

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.feature.binding.BindingAction
import io.github.currencortex.music.feature.binding.BindingNotice
import io.github.currencortex.music.feature.provider.QrLoginSession
import io.github.currencortex.music.ui.component.MusicDialog
import io.github.currencortex.music.ui.component.MusicDialogButton
import io.github.currencortex.music.ui.component.musicScrollPadding
import io.github.currencortex.music.ui.component.nativeui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** QR pixels rendered locally; the scanner only ever sees the public login URL. */
internal fun kugouQrPixels(url: String, size: Int = 512): IntArray {
    val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.CHARACTER_SET to "UTF-8"))
    return IntArray(size * size) { index ->
        if (matrix[index % size, index / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
}

/** KuGou account page: QR-code login and logout, folia-style. */
@Composable
fun KugouAccountScreen(container: AppContainer, onBack: () -> Unit) {
    val repository = container.kugou
    val account by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val session = remember(scope) { QrLoginSession(scope) }
    val qrState by session.state.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    var loginVisible by remember { mutableStateOf(false) }
    LaunchedEffect(owner, loginVisible) {
        if (loginVisible) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.start(auth = object : QrLoginSession.Auth {
                override suspend fun qrKey(methodId: String?) = repository.qrKey()
                override suspend fun qrImage(key: String) = ""
                override suspend fun check(key: String) = repository.qrCheck(key)
                override suspend fun cancel(key: String) = Unit
                override val ttlMs: Long? = 175_000L
                override val loginUrl: (String) -> String = repository::qrLoginUrl
            }, onConfirmed = { loginVisible = false })
            awaitCancellation()
        } else session.dismiss()
    }
    val bitmap by produceState<Bitmap?>(null, qrState.qrUrl) {
        val url = qrState.qrUrl
        value = if (url.isBlank()) null else withContext(Dispatchers.Default) {
            Bitmap.createBitmap(kugouQrPixels(url), 512, 512, Bitmap.Config.ARGB_8888)
        }
    }
    MusicSubpage("酷狗账号", "kugou_account", onBack, backTag = "kugou_account_back") { topInset ->
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize().testTag("kugou_account_screen"),
            contentPadding = musicScrollPadding(PaddingValues(16.dp, topInset + 8.dp, 16.dp, 32.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (account.loggedIn) item {
                SettingsGroup(Modifier.fillMaxWidth().testTag("kugou_account_card")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (account.avatar.isNotBlank()) AsyncImage(account.avatar, "酷狗头像",
                                Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape), contentScale = ContentScale.Crop)
                            else Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = .08f)))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(account.nickname.ifBlank { "酷狗音乐" }, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                                Text("酷狗音乐账号", fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
                            }
                            Text("已登录", Modifier.clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = .08f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                fontSize = 11.sp, color = MiuixTheme.colorScheme.primary)
                        }
                        Text("登录后可直接搜索并播放酷狗曲库。", fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
                    }
                }
            } else item {
                Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("连接酷狗音乐", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text("扫码登录酷狗音乐账号，直接搜索并播放。", fontSize = 13.sp, lineHeight = 21.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
                }
            }
            item {
                SettingsGroup(Modifier.fillMaxWidth()) {
                    if (account.loggedIn) {
                        SettingsPreference("退出登录", "清除本机保存的酷狗登录态",
                            Modifier.testTag("kugou_logout"), onClick = { confirmLogout = true })
                    } else {
                        SettingsPreference(if (loginVisible) "隐藏登录二维码" else "扫码登录酷狗",
                            modifier = Modifier.testTag("kugou_login"), onClick = { loginVisible = !loginVisible })
                    }
                }
            }
            if (loginVisible && !account.loggedIn) item {
                SettingsGroup(Modifier.fillMaxWidth().testTag("kugou_login_card")) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            val image = bitmap
                            if (image != null) Image(image.asImageBitmap(), "酷狗登录二维码",
                                Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit)
                            else Text("正在生成二维码…", fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .5f))
                        }
                        Text(qrState.message, Modifier.testTag("kugou_qr_message"), fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .6f))
                        if (qrState.phase == QrLoginSession.Phase.EXPIRED || qrState.phase == QrLoginSession.Phase.ERROR)
                            BindingAction("刷新二维码", { loginVisible = false; loginVisible = true },
                                Modifier.testTag("kugou_qr_refresh"))
                        Text("使用酷狗音乐 App 扫描二维码，并在手机上确认登录。", fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .45f))
                    }
                }
            }
            if (account.loggedIn) item {
                BindingNotice("登录态加密保存在本机，可随时退出。", Modifier.testTag("kugou_notice"))
            }
        }
    }
    SettingsUiScope {
        if (confirmLogout) MusicDialog("退出酷狗音乐？", { confirmLogout = false }) {
            Text("将清除本机保存的酷狗登录态，之后需要重新扫码。")
            MusicDialogButton("确认退出", onClick = {
                confirmLogout = false
                scope.launch { repository.logout() }
            }, modifier = Modifier.testTag("kugou_confirm_logout"))
            BindingAction("取消", { confirmLogout = false })
        }
    }
}
