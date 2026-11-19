package io.github.currencortex.music.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.currencortex.music.AppContainer
import io.github.currencortex.music.core.media.AudioQuality
import io.github.currencortex.music.data.settings.MusicPlatform
import io.github.currencortex.music.data.song.MusicSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class NetworkSettingsState(val busy: Boolean = false, val message: String? = null)
data class AudioCacheSettingsState(val busy: Boolean = false, val message: String? = null)

/**
 * Settings behind "音源与网络": lyrics source and the self-hosted Navidrome
 * library. Audio itself always plays from the platform (NetEase / QQ Music /
 * KuGou direct) or Navidrome — there is no CurrentMusic service or source layer.
 */
class MusicSettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings = container.musicSettings.state
    val state = MutableStateFlow(NetworkSettingsState())
    val cacheBytes = container.audioCache.bytes
    val cacheState = MutableStateFlow(AudioCacheSettingsState())
    fun refreshCache() = viewModelScope.launch(Dispatchers.IO) {
        runCatching { container.audioCache.refreshUsage() }.onFailure {
            cacheState.value = AudioCacheSettingsState(message = "歌曲缓存暂不可用")
        }
    }
    fun preload(value: Boolean) = viewModelScope.launch { container.musicSettings.setPreload(value) }
    fun listeningSource(value: io.github.currencortex.music.data.settings.ListeningSource) = viewModelScope.launch {
        try { container.musicSettings.setListeningSource(value) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { container.errorNotices.show("听歌足迹数据来源保存失败，请重试") }
    }
    fun lyricsSource(value: io.github.currencortex.music.data.settings.LyricsSource) = viewModelScope.launch {
        try { container.musicSettings.setLyricsSource(value) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { container.errorNotices.show("歌词来源保存失败，请重试") }
    }
    fun lyricsStyle(value: io.github.currencortex.music.data.settings.LyricsStyle) = viewModelScope.launch {
        try { container.musicSettings.editLyricsDisplay { it.copy(style = value) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { container.errorNotices.show("歌词样式保存失败，请重试") }
    }
    fun lyricsDisplay(transform: (io.github.currencortex.music.data.settings.LyricsDisplayOptions) -> io.github.currencortex.music.data.settings.LyricsDisplayOptions) = viewModelScope.launch {
        try { container.musicSettings.editLyricsDisplay(transform) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { container.errorNotices.show("歌词效果保存失败，请重试") }
    }
    fun lyricsFontSize(value: Float) = viewModelScope.launch {
        try { container.musicSettings.setLyricsFontSize(value) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { container.errorNotices.show("歌词字号保存失败，请重试") }
    }
    fun preloadMetered(value: Boolean) = viewModelScope.launch { container.musicSettings.setPreloadMetered(value) }
    fun clearCache() = viewModelScope.launch {
        if (cacheState.value.busy) return@launch
        cacheState.value = AudioCacheSettingsState(busy = true)
        try {
            container.audioSources.invalidate()
            container.audioCache.clear()
            cacheState.value = AudioCacheSettingsState(message = "歌曲缓存已清理")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { cacheState.value = AudioCacheSettingsState(message = "缓存清理失败，请重试") }
    }
    fun quality(value: AudioQuality) = viewModelScope.launch {
        val qq = settings.value.musicPlatform == MusicPlatform.QQ_MUSIC
        container.musicSettings.setQuality(value, qq = qq)
        val current = container.playbackQueue.state.value.current
        if (current != null && (current.musicSource == MusicSource.QQ_MUSIC) == qq)
            container.playerController.qualityChanged()
    }
    fun restore(value: Boolean) = viewModelScope.launch { container.musicSettings.setRestore(value) }
    fun concurrentPlayback(value: Boolean) = viewModelScope.launch { container.musicSettings.setConcurrentPlayback(value) }
    fun transition(value: io.github.currencortex.music.data.settings.MusicTransitionMode) = viewModelScope.launch { container.musicSettings.setTransitionMode(value) }
    fun crossfadeSeconds(value: Int) = viewModelScope.launch { container.musicSettings.setCrossfadeSeconds(value) }
}
