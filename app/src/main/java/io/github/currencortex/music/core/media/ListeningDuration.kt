package io.github.currencortex.music.core.media

/** Formats a duration in milliseconds as a compact Chinese listening-time label. */
internal fun listeningDuration(milliseconds: Long): String {
    val total = milliseconds.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return when {
        hours > 0 -> "${hours} 小时 ${minutes} 分钟"
        minutes > 0 -> "${minutes} 分钟"
        else -> "${seconds} 秒"
    }
}
