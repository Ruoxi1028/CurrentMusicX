package io.github.currencortex.music.core.update

import io.github.currencortex.music.core.config.AppMetadata
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class UpdateSource(val label: String, val prefix: String) {
    GITHUB("GitHub 原站", ""),
    TBAP("github.tbap.top", "https://github.tbap.top/"),
    GH_PROXY("gh-proxy.com", "https://gh-proxy.com/"),
    GEEKERTAO("ghfile.geekertao.top", "https://ghfile.geekertao.top/"),
    DPIK("github.dpik.top", "https://github.dpik.top/");

    /** Try the requested source once, then the other mirrors, with the origin as the final fallback. */
    fun fallbacks(): List<UpdateSource> = when (this) {
        GITHUB -> listOf(GITHUB) + available.filter { it != GITHUB }
        else -> listOf(this) + available.filter { it != this && it != GITHUB } + GITHUB
    }

    fun url(release: AppRelease): String {
        val official = requireNotNull(release.apkUrl) { "此发布没有 APK" }
        require(official.startsWith("${AppMetadata.RELEASES_PROJECT_URL}/releases/download/")) { "更新来源不匹配" }
        if (prefix.isNotEmpty()) require(prefix.startsWith("https://")) { "下载源必须使用 HTTPS" }
        val result = prefix + official
        val uri = URI(result)
        require(uri.scheme == "https" && uri.rawUserInfo == null) { "下载源必须使用 HTTPS" }
        return result
    }

    /** Repository files use the updater's selected mirror order. */
    fun repositoryFileUrl(path: String): String {
        require(path.startsWith("/") && !path.contains("..")) { "仓库文件路径无效" }
        val official = "${AppMetadata.RELEASES_PROJECT_URL}/raw/main$path"
        val result = prefix + official
        val uri = URI(result)
        require(uri.scheme == "https" && uri.rawUserInfo == null) { "下载源必须使用 HTTPS" }
        return result
    }

    fun repositoryFileFallbacks(): List<UpdateSource> = fallbacks()

    companion object {
        val available: List<UpdateSource> get() = entries
        val default: UpdateSource get() = GITHUB
        fun forChannel(channel: UpdateChannel) = available
    }
}
