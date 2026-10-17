package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs

/**
 * Settings that only exist in the Android TV interface (defined in res/xml/tv_preferences.xml).
 */
object TvPrefs {
    const val START_SCREEN = "tv_start_screen"
    const val COLUMNS = "tv_columns"
    const val AUTO_REFRESH = "tv_auto_refresh"
    const val OVERLAY_TIMEOUT = "tv_overlay_timeout"
    const val ACCOUNT = "tv_account"
    const val CLASSIC_SETTINGS = "tv_classic_settings"
    const val UPDATE_VERSION = "tv_update_version"
    const val UPDATE_CHECK_NOW = "tv_update_check_now"

    const val START_AUTO = "auto"
    const val START_FOLLOWING = "following"
    const val START_POPULAR = "popular"
    const val START_CATEGORIES = "categories"

    private const val UPSTREAM_UPDATE_URL = "https://api.github.com/repos/crackededed/xtra/releases/tags/latest"
    private const val FORK_UPDATE_URL = "https://api.github.com/repos/danigalaxy98/XtraTV/releases/tags/latest"

    fun streamColumns(context: Context): Int {
        return context.prefs().getString(COLUMNS, "4")?.toIntOrNull()?.coerceIn(3, 6) ?: 4
    }

    /** Minutes after which lists reload by themselves; 0 disables automatic refresh. */
    fun autoRefreshMs(context: Context): Long {
        val minutes = context.prefs().getString(AUTO_REFRESH, "2")?.toLongOrNull() ?: 2L
        return minutes * 60_000L
    }

    fun isLoggedIn(context: Context): Boolean {
        return !TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(context)[C.HEADER_TOKEN].isNullOrBlank()
    }

    /** Forgets the account tokens, like the phone app does before logging in again. */
    fun logout(context: Context) {
        TwitchApiHelper.checkedValidation = false
        context.tokenPrefs().edit {
            putString(C.TOKEN, null)
            putString(C.GQL_HEADERS, null)
            putLong(C.INTEGRITY_EXPIRATION, 0)
            putString(C.GQL_TOKEN2, null)
            putString(C.GQL_TOKEN_WEB, null)
            putString(C.USER_ID, null)
            putString(C.USERNAME, null)
        }
    }

    /**
     * The TV build is distributed from the fork, so updates must come from there:
     * an update from the original repository would replace the app with one without the TV interface.
     */
    fun ensureForkUpdateUrl(context: Context) {
        val current = context.prefs().getString(C.UPDATE_URL, null)
        if (current.isNullOrBlank() || current.equals(UPSTREAM_UPDATE_URL, ignoreCase = true)) {
            context.prefs().edit { putString(C.UPDATE_URL, FORK_UPDATE_URL) }
        }
    }
}
