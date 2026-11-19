package io.github.currencortex.music.feature.support

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

internal enum class SupportLinkResult { OPENED, INVALID, NO_APP }

internal fun openSupportLink(context: Context, value: String): SupportLinkResult {
    val url = supportHttpsUrl(value) ?: return SupportLinkResult.INVALID
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SupportLinkResult.OPENED
    } catch (_: ActivityNotFoundException) {
        SupportLinkResult.NO_APP
    } catch (_: SecurityException) {
        SupportLinkResult.NO_APP
    }
}
