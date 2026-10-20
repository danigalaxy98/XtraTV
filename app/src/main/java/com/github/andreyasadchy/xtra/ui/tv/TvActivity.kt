package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

sealed interface TvDestination {
    data object Following : TvDestination
    data object Popular : TvDestination
    data object Categories : TvDestination
    data object Search : TvDestination
    data class Game(val id: String?, val slug: String?, val name: String?) : TvDestination
    data class Channel(val id: String?, val login: String?, val name: String?) : TvDestination
}

/**
 * Entry point of the Android TV interface: a side rail with sections and a back stack of screens.
 */
class TvActivity : ComponentActivity() {

    private val viewModel: TvBrowseViewModel by viewModels { TvBrowseViewModel.TvBrowseViewModelFactory }
    private val backStack = mutableStateListOf<TvDestination>()
    private var lastKeyTime = System.currentTimeMillis()
    private var lastAutoRefresh = System.currentTimeMillis()
    private var loggedIn by mutableStateOf(false)

    private var sessionBeforeLaunch: String? = null
    private var settingsLaunched = false

    private val accountResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val before = sessionBeforeLaunch
        sessionBeforeLaunch = null
        loggedIn = viewModel.isLoggedIn()
        if (before != null && before != viewModel.sessionKey()) {
            // other account: drop every loaded list, shown or not
            viewModel.reloadAll()
        }
        // the login screen only answers RESULT_OK on success, which the session check above already covers;
        // settings answer RESULT_OK on any change (columns, language, ...) that needs a new composition
        if (settingsLaunched && result.resultCode == RESULT_OK) {
            recreate()
        }
    }

    private fun launchForResult(target: Class<*>) {
        sessionBeforeLaunch = viewModel.sessionKey()
        settingsLaunched = target == TvSettingsActivity::class.java
        accountResultLauncher.launch(Intent(this, target))
    }

    private var crashText by mutableStateOf<String?>(null)
    private var exitConfirm by mutableStateOf(false)
    private val updateController = TvUpdateController(this)

    /** Side menu entries, so the left key from a grid always returns to the section being shown. */
    private val railFocus = mapOf<TvDestination, FocusRequester>(
        TvDestination.Following to FocusRequester(),
        TvDestination.Popular to FocusRequester(),
        TvDestination.Categories to FocusRequester(),
        TvDestination.Search to FocusRequester(),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvCrashLog.install(this)
        crashText = TvCrashLog.read(this)
        loggedIn = viewModel.isLoggedIn()
        TvPrefs.ensureForkUpdateUrl(this)
        savedInstanceState?.getBundle(STATE_BACK_STACK)?.let { saved ->
            var index = 0
            while (true) {
                val entry = saved.getBundle(index.toString()) ?: break
                entry.toDestination()?.let { backStack.add(it) }
                index++
            }
        }
        if (backStack.isEmpty()) {
            backStack.add(
                when (prefs().getString(TvPrefs.START_SCREEN, TvPrefs.START_AUTO)) {
                    TvPrefs.START_FOLLOWING -> TvDestination.Following
                    TvPrefs.START_POPULAR -> TvDestination.Popular
                    TvPrefs.START_CATEGORIES -> TvDestination.Categories
                    else -> if (loggedIn) TvDestination.Following else TvDestination.Popular
                }
            )
        }
        handleIntent(intent)
        if (savedInstanceState == null && TvUpdater.shouldAutoCheck(this)) {
            updateController.check(manual = false)
        }
        setContent {
            XtraTvTheme {
                TvApp()
            }
        }
        // reload the lists after two minutes without touching the remote
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(15.seconds)
                    val now = System.currentTimeMillis()
                    val interval = TvPrefs.autoRefreshMs(this@TvActivity)
                    if (interval > 0 && now - lastKeyTime > interval && now - lastAutoRefresh > interval) {
                        lastAutoRefresh = now
                        viewModel.requestRefresh()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // keep the screen being shown across recreate() (settings changes) and process death
        val saved = Bundle()
        backStack.forEachIndexed { index, destination -> saved.putBundle(index.toString(), destination.toBundle()) }
        outState.putBundle(STATE_BACK_STACK, saved)
    }

    private fun TvDestination.toBundle(): Bundle = when (this) {
        TvDestination.Following -> bundleOf(KEY_TYPE to "following")
        TvDestination.Popular -> bundleOf(KEY_TYPE to "popular")
        TvDestination.Categories -> bundleOf(KEY_TYPE to "categories")
        TvDestination.Search -> bundleOf(KEY_TYPE to "search")
        is TvDestination.Game -> bundleOf(KEY_TYPE to "game", KEY_ID to id, KEY_SLUG to slug, KEY_NAME to name)
        is TvDestination.Channel -> bundleOf(KEY_TYPE to "channel", KEY_ID to id, KEY_LOGIN to login, KEY_NAME to name)
    }

    private fun Bundle.toDestination(): TvDestination? = when (getString(KEY_TYPE)) {
        "following" -> TvDestination.Following
        "popular" -> TvDestination.Popular
        "categories" -> TvDestination.Categories
        "search" -> TvDestination.Search
        "game" -> TvDestination.Game(getString(KEY_ID), getString(KEY_SLUG), getString(KEY_NAME))
        "channel" -> TvDestination.Channel(getString(KEY_ID), getString(KEY_LOGIN), getString(KEY_NAME))
        else -> null
    }

    override fun onRestart() {
        super.onRestart()
        // back from the player or from the background
        if (TvPrefs.autoRefreshMs(this) > 0) {
            lastAutoRefresh = System.currentTimeMillis()
            viewModel.requestRefresh()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        val channelLogin = intent.getStringExtra(EXTRA_CHANNEL_LOGIN)
        if (channelId != null || channelLogin != null) {
            val destination = TvDestination.Channel(channelId, channelLogin, intent.getStringExtra(EXTRA_CHANNEL_NAME))
            // a restored back stack may already end with this channel
            if (backStack.lastOrNull() != destination) {
                backStack.add(destination)
            }
            intent.removeExtra(EXTRA_CHANNEL_ID)
            intent.removeExtra(EXTRA_CHANNEL_LOGIN)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastKeyTime = System.currentTimeMillis()
        return super.dispatchKeyEvent(event)
    }

    private fun navigate(destination: TvDestination) {
        backStack.add(destination)
    }

    private fun openSection(destination: TvDestination) {
        backStack.clear()
        backStack.add(destination)
    }

    private fun playStream(stream: Stream) {
        TvPlayerActivity.startStream(this, stream)
    }

    private fun playVideo(video: Video) {
        TvPlayerActivity.startVideo(this, video)
    }

    @Composable
    private fun TvApp() {
        val current = backStack.lastOrNull() ?: TvDestination.Popular
        BackHandler(enabled = backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
        BackHandler(enabled = backStack.size <= 1 && !exitConfirm) {
            exitConfirm = true
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(TvColors.Background),
        ) {
            SideRail(current = backStack.firstOrNull())
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 24.dp, end = 48.dp, top = 32.dp),
            ) {
                val actions = TvActions(
                    playStream = ::playStream,
                    playVideo = ::playVideo,
                    openChannel = { id, login, name -> navigate(TvDestination.Channel(id, login, name)) },
                    openGame = { id, slug, name -> navigate(TvDestination.Game(id, slug, name)) },
                    login = { launchForResult(LoginActivity::class.java) },
                )
                CompositionLocalProvider(LocalRailFocus provides backStack.firstOrNull()?.let { railFocus[it] }) {
                    when (current) {
                        TvDestination.Following -> FollowingScreen(viewModel, loggedIn, actions)
                        TvDestination.Popular -> PopularScreen(viewModel, actions)
                        TvDestination.Categories -> CategoriesScreen(viewModel, actions)
                        TvDestination.Search -> SearchScreen(viewModel, actions)
                        is TvDestination.Game -> GameScreen(viewModel, current, actions)
                        is TvDestination.Channel -> ChannelScreen(viewModel, current, actions)
                    }
                }
            }
        }
        if (exitConfirm) {
            ExitPanel()
        }
        TvUpdatePanel(updateController)
        crashText?.let { text ->
            CrashPanel(text) {
                TvCrashLog.clear(this@TvActivity)
                crashText = null
            }
        }
    }

    @Composable
    private fun ExitPanel() {
        val noButton = remember { FocusRequester() }
        BackHandler { exitConfirm = false }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { noButton.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(TvColors.Surface)
                    .padding(horizontal = 48.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.tv_exit_title),
                    color = TvColors.Text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    PillButton(text = stringResource(R.string.yes), onClick = { finish() })
                    PillButton(
                        text = stringResource(R.string.no),
                        onClick = { exitConfirm = false },
                        modifier = Modifier.focusRequester(noButton),
                    )
                }
            }
        }
    }

    @Composable
    private fun CrashPanel(text: String, onDismiss: () -> Unit) {
        val button = remember { FocusRequester() }
        BackHandler(onBack = onDismiss)
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { button.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE6000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TvColors.Surface)
                    .padding(32.dp),
            ) {
                Text(
                    text = stringResource(R.string.tv_crash_title),
                    color = TvColors.Text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.tv_crash_message),
                    color = TvColors.TextSecondary,
                    fontSize = 16.sp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = text,
                    color = TvColors.Text,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .background(TvColors.Background)
                        .padding(12.dp),
                )
                Spacer(Modifier.height(16.dp))
                PillButton(
                    text = stringResource(R.string.tv_crash_dismiss),
                    onClick = onDismiss,
                    modifier = Modifier.focusRequester(button),
                )
            }
        }
    }

    @Composable
    private fun SideRail(current: TvDestination?) {
        var expanded by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .background(Color(0xFF18181B))
                .onFocusChanged { expanded = it.hasFocus }
                .animateContentSize()
                .width(if (expanded) 230.dp else 80.dp)
                .padding(vertical = 32.dp, horizontal = 12.dp),
        ) {
            Text(
                text = if (expanded) "Xtra" else "X",
                color = TvColors.Accent,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp, bottom = 32.dp),
            )
            RailItem(R.drawable.ic_favorite_black_24dp, R.string.following, expanded, current == TvDestination.Following, railFocus.getValue(TvDestination.Following)) {
                openSection(TvDestination.Following)
            }
            RailItem(R.drawable.ic_trending_up_black_24dp, R.string.popular, expanded, current == TvDestination.Popular, railFocus.getValue(TvDestination.Popular)) {
                openSection(TvDestination.Popular)
            }
            RailItem(R.drawable.ic_games_black_24dp, R.string.tv_categories, expanded, current == TvDestination.Categories, railFocus.getValue(TvDestination.Categories)) {
                openSection(TvDestination.Categories)
            }
            RailItem(R.drawable.baseline_search_black_24, R.string.search, expanded, current == TvDestination.Search, railFocus.getValue(TvDestination.Search)) {
                openSection(TvDestination.Search)
            }
            Spacer(Modifier.weight(1f))
            RailItem(R.drawable.baseline_replay_black_24, R.string.refresh, expanded, false) {
                lastAutoRefresh = System.currentTimeMillis()
                viewModel.requestRefresh()
            }
            if (!loggedIn) {
                RailItem(R.drawable.baseline_person_black_24, R.string.log_in, expanded, false) {
                    launchForResult(LoginActivity::class.java)
                }
            }
            RailItem(R.drawable.baseline_settings_black_24, R.string.settings, expanded, false) {
                launchForResult(TvSettingsActivity::class.java)
            }
        }
    }

    @Composable
    private fun RailItem(
        @DrawableRes icon: Int,
        @StringRes label: Int,
        expanded: Boolean,
        selected: Boolean,
        focusRequester: FocusRequester? = null,
        onClick: () -> Unit,
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = TvFocus.pillShape(),
            colors = TvFocus.colors(transparent = true),
            border = TvFocus.border(rounded = true),
            scale = TvFocus.scale(1.03f),
        ) {
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = stringResource(label),
                    tint = if (selected) TvColors.Accent else TvColors.TextSecondary,
                    modifier = Modifier.size(24.dp),
                )
                if (expanded) {
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = stringResource(label),
                        fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "tv_channel_id"
        const val EXTRA_CHANNEL_LOGIN = "tv_channel_login"
        const val EXTRA_CHANNEL_NAME = "tv_channel_name"

        private const val STATE_BACK_STACK = "tv_back_stack"
        private const val KEY_TYPE = "type"
        private const val KEY_ID = "id"
        private const val KEY_SLUG = "slug"
        private const val KEY_LOGIN = "login"
        private const val KEY_NAME = "name"

        fun openChannel(context: Context, channelId: String?, channelLogin: String?, channelName: String?) {
            context.startActivity(
                Intent(context, TvActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_CHANNEL_ID, channelId)
                    putExtra(EXTRA_CHANNEL_LOGIN, channelLogin)
                    putExtra(EXTRA_CHANNEL_NAME, channelName)
                }
            )
        }
    }
}

class TvActions(
    val playStream: (Stream) -> Unit,
    val playVideo: (Video) -> Unit,
    val openChannel: (id: String?, login: String?, name: String?) -> Unit,
    val openGame: (id: String?, slug: String?, name: String?) -> Unit,
    val login: () -> Unit,
)
