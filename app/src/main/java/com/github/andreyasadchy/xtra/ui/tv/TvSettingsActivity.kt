package com.github.andreyasadchy.xtra.ui.tv

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.app.admin.DeviceAdminReceiver
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.TwoStatePreference
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.settings.SettingsViewModel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs

/**
 * Android TV settings: sections on the left, options on the right and side panels for lists and text,
 * built from the same preference XML files as the phone settings.
 */
class TvSettingsActivity : AppCompatActivity() {

    private sealed interface Panel {
        class Choice(val preference: ListPreference) : Panel
        class Input(val preference: EditTextPreference) : Panel
        class Confirm(val message: String, val action: () -> Unit) : Panel
    }

    private val settingsViewModel: SettingsViewModel by viewModels { SettingsViewModel.SettingsViewModelFactory }
    private lateinit var sections: List<TvSettingsSection>
    private val screens = mutableMapOf<Int, PreferenceScreen>()

    private var selectedSection by mutableIntStateOf(0)
    private val pageStack = mutableStateListOf<PreferenceGroup>()
    private var panel by mutableStateOf<Panel?>(null)
    private var panelOwnerKey: String? = null
    private var version by mutableIntStateOf(0)
    private var focusContentOnNextPage = false

    private val updateController = TvUpdateController(this)

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        markChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvCrashLog.install(this)
        sections = TvSettingsModel.sections(this)
        setContent {
            XtraTvTheme {
                SettingsScreen()
                TvUpdatePanel(updateController)
            }
        }
    }

    private fun screenFor(xml: Int): PreferenceScreen {
        return screens.getOrPut(xml) { TvSettingsModel.inflate(this, xml) }
    }

    private fun markChanged() {
        setResult(RESULT_OK)
        version++
    }

    // region Changes

    private fun setSwitch(preference: TwoStatePreference, checked: Boolean) {
        val key = preference.key
        preference.isChecked = checked
        when (key) {
            "live_notifications_enabled" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                settingsViewModel.toggleNotifications(
                    enabled = checked,
                    networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(this, true),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(this),
                )
            }
            "sleep_timer_lock" -> if (checked) {
                val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(this, DeviceAdminReceiver::class.java)
                if (!devicePolicyManager.isAdminActive(admin)) {
                    tryStart(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin))
                }
            }
            C.UPDATE_CHECK_ENABLED, C.UPDATE_USE_BROWSER -> {
                val needsInstallPermission = if (key == C.UPDATE_CHECK_ENABLED) {
                    checked && !prefs().getBoolean(C.UPDATE_USE_BROWSER, false)
                } else {
                    !checked && prefs().getBoolean(C.UPDATE_CHECK_ENABLED, false)
                }
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE && needsInstallPermission &&
                    !packageManager.canRequestPackageInstalls()
                ) {
                    tryStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
                }
            }
        }
        markChanged()
    }

    private fun setListValue(preference: ListPreference, value: String) {
        preference.value = value
        if (preference.key == C.UI_LANGUAGE) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(if (value == "auto") null else value)
            )
        }
        markChanged()
    }

    private fun setText(preference: EditTextPreference, value: String) {
        preference.text = value
        TvSettingsModel.tokenKeys[preference.key]?.let { tokenKey ->
            tokenPrefs().edit { putString(tokenKey, value) }
        }
        markChanged()
    }

    private fun setSeekBar(preference: SeekBarPreference, value: Int) {
        val newValue = value.coerceIn(preference.min, preference.max)
        if (newValue == preference.value) return
        preference.value = newValue
        if (preference.key == "chatWidth") {
            val width = resources.displayMetrics.widthPixels
            val height = resources.displayMetrics.heightPixels
            val chatWidth = ((if (height > width) height else width) * (newValue / 100f)).toInt()
            prefs().edit { putInt(C.LANDSCAPE_CHAT_WIDTH, chatWidth) }
        }
        markChanged()
    }

    private fun onPreferenceClick(preference: Preference) {
        if (!preference.isEnabled) return
        val key = preference.key
        when {
            key == TvPrefs.UPDATE_CHECK_NOW -> updateController.check(manual = true)
            key == TvPrefs.UPDATE_VERSION -> Unit
            key == TvPrefs.ACCOUNT -> {
                if (TvPrefs.isLoggedIn(this)) {
                    val name = tokenPrefs().getString(C.USERNAME, null) ?: ""
                    openPanel(key, Panel.Confirm(getString(R.string.tv_logout_confirm, name)) {
                        TvPrefs.logout(this)
                        markChanged()
                    })
                } else {
                    loginLauncher.launch(Intent(this, LoginActivity::class.java))
                }
            }
            preference is TwoStatePreference -> setSwitch(preference, !preference.isChecked)
            preference is ListPreference -> openPanel(key, Panel.Choice(preference))
            preference is EditTextPreference -> openPanel(key, Panel.Input(preference))
            preference is SeekBarPreference -> Unit
            preference is PreferenceGroup -> pushPage(preference)
            key != null && TvSettingsModel.subPageXml.containsKey(key) -> {
                pushPage(TvSettingsModel.inflate(this, TvSettingsModel.subPageXml.getValue(key)))
            }
            key != null && TvSettingsModel.confirmActions.containsKey(key) -> {
                openPanel(key, Panel.Confirm(getString(TvSettingsModel.confirmActions.getValue(key))) {
                    when (key) {
                        "delete_video_positions" -> settingsViewModel.deletePositions()
                        "delete_recent_searches" -> settingsViewModel.deleteRecentSearches()
                    }
                    Toast.makeText(this, R.string.tv_done, Toast.LENGTH_SHORT).show()
                })
            }
            key == TvSettingsModel.ACTION_IMPORT_DOWNLOADS -> {
                settingsViewModel.importDownloads()
                Toast.makeText(this, R.string.tv_done, Toast.LENGTH_SHORT).show()
            }
            key == TvSettingsModel.ACTION_ADMIN_SETTINGS -> {
                tryStart(Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings")))
            }
            key != null && TvSettingsModel.classicDestinations.containsKey(key) -> {
                openClassic(TvSettingsModel.classicDestinations.getValue(key))
            }
            preference.intent != null -> tryStart(preference.intent!!)
            else -> openClassic(0)
        }
    }

    private fun openPanel(key: String?, newPanel: Panel) {
        panelOwnerKey = key
        panel = newPanel
    }

    private fun closePanel() {
        panel = null
    }

    private fun pushPage(group: PreferenceGroup) {
        focusContentOnNextPage = true
        pageStack.add(group)
    }

    private fun openClassic(destination: Int) {
        setResult(RESULT_OK)
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_DESTINATION, destination))
    }

    private fun tryStart(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.tv_not_available, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.tv_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    // endregion

    // region UI

    /** Rows of the section (or of the open sub-page): visible preferences, and group headers as plain strings. */
    private fun rows(section: TvSettingsSection, subPage: PreferenceGroup?): List<Any> {
        if (subPage != null) {
            return TvSettingsModel.children(subPage).flatMap { preference ->
                if (preference is PreferenceCategory) {
                    listOf<Any>(preference.title?.toString().orEmpty()) + TvSettingsModel.children(preference)
                } else {
                    listOf(preference)
                }
            }
        }
        val result = mutableListOf<Any>()
        section.entries.forEach { entry ->
            when (entry) {
                is TvSettingsEntry.Header -> result.add(getString(entry.title))
                is TvSettingsEntry.Pref -> screenFor(entry.xml).findPreference<Preference>(entry.key)?.takeIf { it.isVisible }?.let { result.add(it) }
            }
        }
        // drop headers that ended up without any option below them
        return result.filterIndexed { index, item ->
            item !is String || result.getOrNull(index + 1).let { it != null && it !is String }
        }
    }

    @Composable
    private fun SettingsScreen() {
        val sectionFocus = remember { List(sections.size) { FocusRequester() } }
        // first option of the open page: → / OK from the section list always lands here
        val contentFocus = remember { FocusRequester() }
        var contentHasFocus by remember { mutableStateOf(false) }
        BackHandler {
            when {
                panel != null -> closePanel()
                pageStack.isNotEmpty() -> pageStack.removeAt(pageStack.lastIndex)
                contentHasFocus -> runCatching { sectionFocus[selectedSection].requestFocus() }
                else -> finish()
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { sectionFocus[selectedSection].requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(TvColors.Background),
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(340.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF18181B))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 40.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings),
                        color = TvColors.Text,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, bottom = 24.dp),
                    )
                    sections.forEachIndexed { index, section ->
                        SectionItem(
                            title = section.title.toString(),
                            selected = index == selectedSection,
                            modifier = Modifier
                                .focusRequester(sectionFocus[index])
                                .focusProperties { right = contentFocus }
                                .onFocusChanged {
                                    if (it.isFocused && selectedSection != index) {
                                        selectedSection = index
                                        pageStack.clear()
                                        closePanel()
                                    }
                                },
                            onClick = { runCatching { contentFocus.requestFocus() } },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onFocusChanged { contentHasFocus = it.hasFocus },
                ) {
                    // a fresh list (scrolled to the top) for every section and sub-page
                    key(selectedSection, pageStack.size) {
                        SectionContent(contentFocus, sectionFocus[selectedSection])
                    }
                }
            }
            panel?.let { current ->
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(460.dp)
                        .fillMaxHeight()
                        .background(Color(0xFA202026)),
                ) {
                    when (current) {
                        is Panel.Choice -> ChoicePanel(current.preference)
                        is Panel.Input -> InputPanel(current.preference)
                        is Panel.Confirm -> ConfirmPanel(current)
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionItem(title: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            shape = TvFocus.cardShape(),
            colors = TvFocus.colors(transparent = !selected),
            border = TvFocus.border(),
            scale = TvFocus.scale(1.02f),
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Color.White else TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }

    @Composable
    private fun SectionContent(firstRowFocus: FocusRequester, sectionFocus: FocusRequester) {
        val section = sections[selectedSection]
        val subPage = pageStack.lastOrNull()
        val group: Any = subPage ?: section.id
        val currentVersion = version
        val rows = remember(group, currentVersion) { rows(section, subPage) }
        val firstFocusable = rows.indexOfFirst { it is Preference }
        LaunchedEffect(group, currentVersion) {
            if (focusContentOnNextPage) {
                focusContentOnNextPage = false
                withFrameNanos { }
                runCatching { firstRowFocus.requestFocus() }
            }
        }
        Column(modifier = Modifier.padding(start = 40.dp, end = 48.dp, top = 40.dp)) {
            Text(
                text = (listOf(section.title.toString()) + pageStack.map { it.title?.toString().orEmpty() }).joinToString("  ›  "),
                color = TvColors.Text,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 20.dp),
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(rows, key = { index, row -> "$index:${(row as? Preference)?.key ?: row}" }) { index, row ->
                    val preference = row as? Preference
                    if (preference == null) {
                        Text(
                            text = row.toString(),
                            color = TvColors.Accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                        )
                    } else {
                        PreferenceRow(
                            preference = preference,
                            version = currentVersion,
                            // ← always goes back to the section this page belongs to
                            modifier = Modifier
                                .focusProperties { left = sectionFocus }
                                .then(if (index == firstFocusable) Modifier.focusRequester(firstRowFocus) else Modifier),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PreferenceRow(preference: Preference, version: Int, modifier: Modifier) {
        val rowFocus = remember { FocusRequester() }
        // give the focus back to this row when its side panel closes
        LaunchedEffect(panel == null) {
            if (panel == null && panelOwnerKey != null && panelOwnerKey == preference.key) {
                panelOwnerKey = null
                withFrameNanos { }
                runCatching { rowFocus.requestFocus() }
            }
        }
        val enabled = preference.isEnabled
        val seekBar = preference as? SeekBarPreference
        Surface(
            onClick = { onPreferenceClick(preference) },
            modifier = modifier
                .focusRequester(rowFocus)
                .fillMaxWidth()
                .then(
                    if (seekBar != null) {
                        Modifier.onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {
                                val step = seekBar.seekBarIncrement.coerceAtLeast(1)
                                setSeekBar(seekBar, seekBar.value + if (event.key == Key.DirectionRight) step else -step)
                                true
                            } else {
                                false
                            }
                        }
                    } else {
                        Modifier
                    }
                ),
            shape = TvFocus.cardShape(),
            colors = TvFocus.colors(),
            border = TvFocus.border(),
            scale = TvFocus.scale(1.01f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preference.title?.toString().orEmpty(),
                        fontSize = 17.sp,
                        color = if (enabled) TvColors.Text else TvColors.TextSecondary.copy(alpha = 0.5f),
                    )
                    summaryOf(preference, version)?.let {
                        Text(
                            text = it,
                            fontSize = 14.sp,
                            color = TvColors.TextSecondary.copy(alpha = if (enabled) 1f else 0.5f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Trailing(preference, version)
            }
        }
    }

    @Composable
    private fun Trailing(preference: Preference, @Suppress("UNUSED_PARAMETER") version: Int) {
        val key = preference.key
        when {
            preference is TwoStatePreference -> {
                val on = preference.isChecked
                Text(
                    text = stringResource(if (on) R.string.yes else R.string.no),
                    color = if (on) Color.White else TvColors.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(if (on) TvColors.Accent else TvColors.SurfaceFocused, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
            preference is SeekBarPreference -> {
                Text(text = "◂  ${preference.value}  ▸", fontSize = 16.sp, color = TvColors.Text)
            }
            preference is PreferenceGroup || (key != null && TvSettingsModel.subPageXml.containsKey(key)) -> {
                Text(text = "›", fontSize = 26.sp, color = TvColors.TextSecondary)
            }
            key != null && TvSettingsModel.classicDestinations.containsKey(key) -> {
                Text(text = "↗", fontSize = 20.sp, color = TvColors.TextSecondary)
            }
        }
    }

    private fun summaryOf(preference: Preference, @Suppress("UNUSED_PARAMETER") version: Int): String? {
        val key = preference.key
        return when {
            key == TvPrefs.ACCOUNT -> {
                val name = tokenPrefs().getString(C.USERNAME, null)
                if (TvPrefs.isLoggedIn(this)) {
                    getString(R.string.tv_account_logged_in, name ?: "?")
                } else {
                    getString(R.string.tv_account_logged_out)
                }
            }
            key == C.PLAYER_REWIND || key == C.PLAYER_FORWARD -> {
                getString(R.string.seconds_full, (preference as? EditTextPreference)?.text ?: "10")
            }
            key == TvPrefs.UPDATE_VERSION -> TvUpdater.versionSummary(this)
            key == TvPrefs.UPDATE_CHECK_NOW -> getString(R.string.tv_update_check_now_summary)
            key != null && TvSettingsModel.tokenKeys.containsKey(key) -> {
                val text = (preference as? EditTextPreference)?.text
                when {
                    text.isNullOrBlank() -> getString(R.string.tv_not_set)
                    key == "user_id" || key == "username" -> text
                    else -> "•".repeat(minOf(text.length, 12))
                }
            }
            key != null && TvSettingsModel.classicDestinations.containsKey(key) -> {
                listOfNotNull(
                    try { preference.summary?.toString() } catch (e: Exception) { null },
                    getString(R.string.tv_opens_classic),
                ).joinToString("\n")
            }
            preference is ListPreference -> preference.entry?.toString() ?: safeSummary(preference)
            preference is EditTextPreference -> safeSummary(preference) ?: preference.text?.takeIf { it.isNotBlank() }
            else -> safeSummary(preference)
        }
    }

    private fun safeSummary(preference: Preference): String? {
        return try {
            preference.summary?.toString()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    @Composable
    private fun PanelTitle(text: String) {
        Text(
            text = text,
            color = TvColors.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }

    @Composable
    private fun ChoicePanel(preference: ListPreference) {
        val entries = preference.entries.orEmpty()
        val values = preference.entryValues.orEmpty()
        val currentIndex = values.indexOfFirst { it.toString() == preference.value }.coerceAtLeast(0)
        val focus = remember(preference) { FocusRequester() }
        LaunchedEffect(preference) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { PanelTitle(preference.title?.toString().orEmpty()) }
            itemsIndexed(entries.toList()) { index, entry ->
                val value = values.getOrNull(index)?.toString()
                PillButton(
                    text = entry.toString(),
                    selected = value != null && value == preference.value,
                    icon = if (value != null && value == preference.value) R.drawable.baseline_circle_24 else null,
                    onClick = {
                        if (value != null) {
                            setListValue(preference, value)
                        }
                        closePanel()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == currentIndex) Modifier.focusRequester(focus) else Modifier),
                )
            }
        }
    }

    @Composable
    private fun InputPanel(preference: EditTextPreference) {
        var text by remember(preference) { mutableStateOf(preference.text.orEmpty()) }
        var fieldFocused by remember { mutableStateOf(false) }
        val focus = remember(preference) { FocusRequester() }
        LaunchedEffect(preference) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
        ) {
            PanelTitle(preference.title?.toString().orEmpty())
            preference.dialogMessage?.let {
                Text(text = it.toString(), color = TvColors.TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(bottom = 16.dp))
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = preference.key !in setOf("gql_headers"),
                textStyle = TextStyle(color = TvColors.Text, fontSize = 20.sp),
                cursorBrush = SolidColor(TvColors.Accent),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (preference.key in TvSettingsModel.numericKeys) KeyboardType.Number else KeyboardType.Text,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .background(TvColors.Surface, RoundedCornerShape(12.dp))
                    .border(BorderStroke(3.dp, if (fieldFocused) TvColors.Accent else TvColors.Surface), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(
                    text = stringResource(R.string.tv_save),
                    onClick = {
                        setText(preference, text.trim())
                        closePanel()
                    },
                )
                PillButton(text = stringResource(android.R.string.cancel), onClick = ::closePanel)
            }
        }
    }

    @Composable
    private fun ConfirmPanel(confirm: Panel.Confirm) {
        val focus = remember(confirm) { FocusRequester() }
        LaunchedEffect(confirm) {
            withFrameNanos { }
            runCatching { focus.requestFocus() }
        }
        Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 40.dp)) {
            Text(text = confirm.message, color = TvColors.Text, fontSize = 18.sp)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PillButton(
                    text = stringResource(R.string.yes),
                    onClick = {
                        confirm.action()
                        closePanel()
                    },
                )
                PillButton(
                    text = stringResource(R.string.no),
                    onClick = ::closePanel,
                    modifier = Modifier.focusRequester(focus),
                )
            }
        }
    }

    // endregion
}
