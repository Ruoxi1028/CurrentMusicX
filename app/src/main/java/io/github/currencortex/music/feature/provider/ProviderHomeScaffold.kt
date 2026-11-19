package io.github.currencortex.music.feature.provider

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.currencortex.music.ui.component.HomeBrandTitle
import io.github.currencortex.music.ui.component.HomeProfileAvatar
import io.github.currencortex.music.ui.component.LocalMusicBottomInset
import io.github.currencortex.music.ui.component.MusicTextAction
import io.github.currencortex.music.ui.component.nativeui.SettingsRootTopBar
import io.github.currencortex.music.ui.component.nativeui.SettingsScrollLayout
import io.github.currencortex.music.ui.component.nativeui.SettingsUiScope
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Shared shell for provider homes without recommendation feeds: the same brand bar,
 * scrolling surface and search-led layout as the NetEase / QQ homes.
 */
@Composable
internal fun ProviderHomeScaffold(
    tag: String,
    avatarUrl: String?,
    onProfile: (String?, Rect?) -> Unit = { _, _ -> },
    content: LazyListScope.() -> Unit,
) {
    SettingsUiScope {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            SettingsScrollLayout(
                Modifier.widthIn(max = 840.dp).fillMaxSize().align(Alignment.TopCenter),
                glassOverContent = false,
                topBar = {
                    SettingsRootTopBar("Current Music", Modifier.testTag("${tag}_toolbar"),
                        titleContent = { HomeBrandTitle() }) {
                        HomeProfileAvatar(avatarUrl?.takeIf { it.isNotBlank() }, true, onProfile)
                    }
                },
            ) { topInset ->
                LazyColumn(
                    Modifier.fillMaxSize().testTag(tag),
                    contentPadding = PaddingValues(start = 16.dp, top = topInset + 8.dp, end = 16.dp,
                        bottom = 24.dp + LocalMusicBottomInset.current),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) { content() }
            }
        }
    }
}

@Composable
internal fun ProviderHomeIntro(title: String, subtitle: String) {
    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, fontSize = 13.sp, lineHeight = 19.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .55f))
    }
}

/** Quick search suggestions, drawn as the same pill actions used by settings pages. */
@Composable
internal fun ProviderHotSearches(terms: List<String>, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        terms.forEach { term -> MusicTextAction(term, { onSelect(term) }) }
    }
}
