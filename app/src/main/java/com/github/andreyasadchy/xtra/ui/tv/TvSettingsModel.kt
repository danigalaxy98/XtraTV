package com.github.andreyasadchy.xtra.ui.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.XmlRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.mlkit.nl.translate.TranslateLanguage
import org.chromium.net.CronetProvider
import java.util.Locale

/**
 * A section of the TV settings: either a whole preference XML, or a subset of the root screen.
 */
class TvSettingsSection(
    val id: String,
    val title: CharSequence,
    @XmlRes val xml: Int,
    /** When set, only these keys of [xml] are shown, in this order. */
    val keys: List<String>? = null,
    /** Keys of [xml] that are never shown in this section. */
    val excludedKeys: Set<String> = emptySet(),
)

/**
 * Builds the TV settings from the same preference XML files used by the phone settings
 * and applies the same special cases that the phone settings fragments apply in code.
 */
object TvSettingsModel {

    /** Root navigation entries and the XML they open. */
    private val navigationXml = linkedMapOf(
        "theme_settings" to R.xml.theme_preferences,
        "ui_settings" to R.xml.ui_preferences,
        "chat_settings" to R.xml.chat_preferences,
        "player_settings" to R.xml.player_preferences,
        "player_button_settings" to R.xml.player_button_preferences,
        "buffer_settings" to R.xml.buffer_preferences,
        "playback_settings" to R.xml.playback_preferences,
        "api_token_settings" to R.xml.api_token_preferences,
        "download_settings" to R.xml.download_preferences,
        "update_settings" to R.xml.update_preferences,
        "debug_settings" to R.xml.debug_preferences,
    )

    /** Plain preferences inside a section that open another XML as a sub-page. */
    val subPageXml = mapOf(
        "player_menu_settings" to R.xml.player_menu_preferences,
    )

    private val proxyKeys = listOf(
        C.PLAYER_USE_CUSTOM_PROXY,
        "custom_proxy_settings",
        C.PLAYER_USE_STREAM_PROXY,
        "stream_proxy_settings",
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
        "ui_navigation_tab_list_dialog" to R.id.action_global_uiSettingsFragment,
        "ui_following_tabs_dialog" to R.id.action_global_uiSettingsFragment,
        "ui_saved_tabs_dialog" to R.id.action_global_uiSettingsFragment,
        "ui_channel_tabs_dialog" to R.id.action_global_uiSettingsFragment,
        "ui_game_tabs_dialog" to R.id.action_global_uiSettingsFragment,
        "ui_search_tabs_dialog" to R.id.action_global_uiSettingsFragment,
        "downloaded_languages" to R.id.action_global_chatSettingsFragment,
        "get_integrity_token" to R.id.action_global_debugSettingsFragment,
        "check_updates" to 0,
        "backup_settings" to 0,
        "restore_settings" to 0,
    )

    fun sections(context: Context): List<TvSettingsSection> {
        val root = inflate(context, R.xml.root_preferences)
        val titles = navigationXml.keys.associateWith { key ->
            root.findPreference<Preference>(key)?.title ?: key
        }
        val general = TvSettingsSection(
            id = "general",
            title = context.getString(R.string.tv_settings_general),
            xml = R.xml.root_preferences,
            excludedKeys = navigationXml.keys + proxyKeys,
        )
        val proxies = TvSettingsSection(
            id = "proxies",
            title = context.getString(R.string.tv_settings_proxies),
            xml = R.xml.root_preferences,
            keys = proxyKeys,
        )
        val sections = mutableListOf(general)
        navigationXml.forEach { (key, xml) ->
            sections.add(TvSettingsSection(key, titles[key] ?: key, xml))
            if (key == "playback_settings") {
                sections.add(proxies)
            }
        }
        return sections
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

    /** Visible children of a group, in order, honoring the section filters. */
    fun children(group: PreferenceGroup, section: TvSettingsSection?, isSectionRoot: Boolean): List<Preference> {
        val all = (0 until group.preferenceCount).map { group.getPreference(it) }.filter { it.isVisible }
        if (!isSectionRoot || section == null) return all
        val keys = section.keys
        return if (keys != null) {
            keys.mapNotNull { key -> all.find { it.key == key } }
        } else {
            all.filter { it.key !in section.excludedKeys }
        }
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
