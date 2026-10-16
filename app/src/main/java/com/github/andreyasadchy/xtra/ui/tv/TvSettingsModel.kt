package com.github.andreyasadchy.xtra.ui.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.StringRes
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.mlkit.nl.translate.TranslateLanguage
import org.chromium.net.CronetProvider
import java.util.Locale

/** One row of a TV settings section: a preference taken from one of the preference XML files, or a group header. */
sealed interface TvSettingsEntry {
    class Pref(@XmlRes val xml: Int, val key: String) : TvSettingsEntry
    class Header(@StringRes val title: Int) : TvSettingsEntry
}

class TvSettingsSection(
    val id: String,
    val title: CharSequence,
    val entries: List<TvSettingsEntry>,
)

/**
 * Builds the TV settings from the same preference XML files used by the phone settings, keeping only the options
 * that affect the TV interface, and applies the same special cases that the phone settings fragments apply in code.
 */
object TvSettingsModel {

    /** Plain preferences inside a section that open another XML as a sub-page. */
    val subPageXml = mapOf(
        "player_menu_settings" to R.xml.player_menu_preferences,
    )

    /** Preferences that keep their value in the token preferences instead of the default ones. */
    val tokenKeys = mapOf(
        "user_id" to C.USER_ID,
        "username" to C.USERNAME,
        "token" to C.TOKEN,
        "gql_token2" to C.GQL_TOKEN2,
        "gql_token_web" to C.GQL_TOKEN_WEB,
        "gql_headers" to C.GQL_HEADERS,
    )

    val numericKeys = setOf(C.PLAYER_REWIND, C.PLAYER_FORWARD, "update_check_frequency")

    /** Actions handled directly in the TV settings, after a confirmation. */
    val confirmActions = mapOf(
        "delete_video_positions" to R.string.delete_video_positions_message,
        "delete_recent_searches" to R.string.delete_recent_searches_message,
    )

    const val ACTION_IMPORT_DOWNLOADS = "import_app_downloads"
    const val ACTION_ADMIN_SETTINGS = "admin_settings"

    /** Editors that are opened in the classic settings screen, with the navigation action to reach them (0 = main screen). */
    val classicDestinations = mapOf(
        "custom_proxy_settings" to R.id.action_global_customProxySettingsFragment,
        "stream_proxy_settings" to R.id.action_global_streamProxySettingsFragment,
        "video_swap_settings" to R.id.action_global_videoSwapSettingsFragment,
        "get_integrity_token" to R.id.action_global_debugSettingsFragment,
        "check_updates" to 0,
        TvPrefs.CLASSIC_SETTINGS to 0,
    )

    fun sections(context: Context): List<TvSettingsSection> {
        val root = R.xml.root_preferences
        val tv = R.xml.tv_preferences
        val chat = R.xml.chat_preferences
        val player = R.xml.player_preferences
        val playback = R.xml.playback_preferences
        val debug = R.xml.debug_preferences
        val api = R.xml.api_token_preferences
        fun prefs(xml: Int, vararg keys: String) = keys.map { TvSettingsEntry.Pref(xml, it) }
        fun header(title: Int) = TvSettingsEntry.Header(title)
        return listOf(
            TvSettingsSection(
                "general", context.getString(R.string.tv_settings_general),
                prefs(root, C.UI_LANGUAGE) +
                        prefs(tv, TvPrefs.START_SCREEN, TvPrefs.COLUMNS, TvPrefs.AUTO_REFRESH),
            ),
            TvSettingsSection(
                "account", context.getString(R.string.tv_section_account),
                prefs(tv, TvPrefs.ACCOUNT) + prefs(R.xml.ui_preferences, C.UI_FOLLOW_BUTTON),
            ),
            TvSettingsSection(
                "player", context.getString(R.string.tv_section_player),
                prefs(player, C.PLAYER_DEFAULT_QUALITY) +
                        prefs(tv, TvPrefs.OVERLAY_TIMEOUT) +
                        prefs(R.xml.player_button_preferences, C.PLAYER_REWIND, C.PLAYER_FORWARD) +
                        prefs(player, "player_use_videopositions", "delete_video_positions", "player_audio_focus", "player_handle_audio_becoming_noisy"),
            ),
            TvSettingsSection(
                "chat", context.getString(R.string.tv_section_chat),
                prefs(chat, C.CHAT_DISABLE) +
                        prefs(tv, C.KEY_CHAT_OPENED) +
                        prefs(chat, "chatWidth") +
                        header(R.string.tv_header_appearance) +
                        prefs(
                            chat, "chat_size_modifier", "chat_text_size", "chat_emote_size", "chat_badge_size", "chat_boldnames",
                            "chat_randomcolor", "chat_theme_adapted_username_color", "chat_timestamps", "chat_timestamp_format",
                        ) +
                        header(R.string.tv_header_content) +
                        prefs(
                            chat, "chat_limit", "chat_recent", "chat_recent_limit", "chat_show_usernotice", "chat_show_clearmsg",
                            "chat_show_clearchat", "chat_firstmsg_visibility", "chat_raids_show", "chat_raids_auto_switch",
                            "chat_polls_show", "chat_predictions_show", "chat_points_collect",
                        ) +
                        header(R.string.tv_header_emotes) +
                        prefs(
                            chat, "animatedGifEmotes", "chat_zerowidth", "chat_show_gif_messages", "chat_gif_size",
                            "chat_system_message_emotes", "chat_enable_stv", "chat_enable_bttv", "chat_enable_ffz",
                            "chat_show_paints", "chat_show_stv_badges", "chat_show_personal_emotes",
                        ),
            ),
            TvSettingsSection(
                "ads", context.getString(R.string.tv_section_ads),
                prefs(root, C.PLAYER_USE_CUSTOM_PROXY, "custom_proxy_settings", C.PLAYER_USE_STREAM_PROXY, "stream_proxy_settings") +
                        prefs(
                            playback, "proxy_timeout", "player_hide_ads", "player_use_video_swap", "video_swap_settings",
                            "token_include_token_stream", "token_include_token_video", "token_supported_codecs",
                        ),
            ),
            TvSettingsSection(
                "updates", context.getString(R.string.tv_section_updates),
                prefs(root, "check_updates") + prefs(R.xml.update_preferences, C.UPDATE_URL),
            ),
            TvSettingsSection(
                "advanced", context.getString(R.string.tv_section_advanced),
                listOf(header(R.string.tv_header_network)) +
                        prefs(root, C.NETWORK_LIBRARY) +
                        header(R.string.tv_header_buffer) +
                        prefs(
                            R.xml.buffer_preferences, "player_buffer_min", "player_buffer_max", "player_buffer_playback",
                            "player_buffer_rebuffer", "player_live_min_speed", "player_live_max_speed", "player_live_target_offset",
                        ) +
                        header(R.string.tv_header_chat_connection) +
                        prefs(
                            chat, C.CHAT_IMAGE_LIBRARY, "chat_use_webp", "chat_image_quality", "chat_recent_messages_url",
                            "chat_use_websocket", "chat_use_ssl", "chat_pubsub_enabled",
                        ) +
                        prefs(debug, "debug_eventsub_chat") +
                        prefs(chat, "chat_stv_live_updates") +
                        header(R.string.tv_header_api) +
                        prefs(
                            api, "api_login", "helix_client_id", "helix_redirect", "gql_client_id2", "user_id", "username",
                            "token", "gql_token2", "gql_token_web", "validate_tokens",
                        ) +
                        prefs(debug, "enable_integrity", "use_webview_integrity", "get_all_gql_headers", "gql_headers", "get_integrity_token") +
                        prefs(tv, TvPrefs.CLASSIC_SETTINGS),
            ),
        )
    }

    /**
     * Inflates a preference XML attached to its own [PreferenceManager] so dependencies and saved values work,
     * then applies the same adjustments as the phone settings fragments.
     */
    @SuppressLint("RestrictedApi")
    fun inflate(context: Context, @XmlRes xml: Int): PreferenceScreen {
        val manager = PreferenceManager(context)
        val screen = manager.inflateFromResource(context, xml, null)
        manager.setPreferences(screen)
        screen.onAttached()
        applyRules(context, screen)
        return screen
    }

    /** Visible children of a sub-page group, in order. */
    fun children(group: PreferenceGroup): List<Preference> {
        return (0 until group.preferenceCount).map { group.getPreference(it) }.filter { it.isVisible }
    }
    private fun applyRules(context: Context, screen: PreferenceScreen) {
        val hasPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (!hasPip) {
            hide(screen, C.PLAYER_PICTURE_IN_PICTURE)
            hide(screen, C.PLAYER_BACKGROUND_AUDIO_PIP_CLOSED)
            hide(screen, C.PLAYER_BACKGROUND_AUDIO_PIP_LOCKED)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            hide(screen, C.PLAYER_ROUNDED_CORNER_PADDING)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            hide(screen, C.PLAYER_AUDIO_COMPRESSOR_BUTTON)
            hide(screen, C.UI_DRAW_BEHIND_CUTOUTS)
            screen.findPreference<ListPreference>(C.CHAT_IMAGE_LIBRARY)?.apply {
                setEntries(R.array.imageLibraryEntriesNoWebp)
                setEntryValues(R.array.imageLibraryValuesNoWebp)
            }
        }
        if (Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
            screen.findPreference<ListPreference>("chat_translate_target")?.apply {
                val languages = TranslateLanguage.getAllLanguages()
                entries = languages.map { Locale.forLanguageTag(it).displayLanguage }.toTypedArray()
                entryValues = languages.toTypedArray()
            }
        } else {
            hide(screen, "chat_translate")
            hide(screen, "downloaded_languages")
            hide(screen, "chat_translate_target")
        }
        screen.findPreference<ListPreference>(C.NETWORK_LIBRARY)?.apply {
            val httpEngine = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
            val cronet = CronetProvider.getAllProviders(context).any { it.isEnabled }
            when {
                !httpEngine && !cronet -> isVisible = false
                !cronet -> {
                    setEntries(R.array.networkLibraryEntriesNoCronet)
                    setEntryValues(R.array.networkLibraryEntriesNoCronet)
                }
                !httpEngine -> {
                    setEntries(R.array.networkLibraryEntriesNoHttpEngine)
                    setEntryValues(R.array.networkLibraryEntriesNoHttpEngine)
                }
            }
        }
        screen.findPreference<ListPreference>(C.UI_LANGUAGE)?.apply {
            val lang = AppCompatDelegate.getApplicationLocales()
            val tag = if (lang.isEmpty) {
                "auto"
            } else {
                val full = lang.toLanguageTags()
                if (findIndexOfValue(full) >= 0) {
                    full
                } else {
                    when (val short = full.substringBefore("-")) {
                        "id" -> "in"
                        "pt" -> "pt-BR"
                        "zh" -> "zh-TW"
                        else -> short
                    }.takeIf { findIndexOfValue(it) >= 0 } ?: "en"
                }
            }
            if (findIndexOfValue(tag) >= 0) {
                value = tag
            }
        }
        screen.findPreference<SeekBarPreference>("chatWidth")?.apply {
            title = context.getString(R.string.tv_pref_chat_width)
            min = 15
            max = 50
        }
        tokenKeys.forEach { (key, tokenKey) ->
            screen.findPreference<EditTextPreference>(key)?.apply {
                isPersistent = false
                text = context.tokenPrefs().getString(tokenKey, null)
            }
        }
    }

    private fun hide(screen: PreferenceScreen, key: String) {
        screen.findPreference<Preference>(key)?.isVisible = false
    }
}
