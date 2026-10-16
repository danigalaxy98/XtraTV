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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
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

    private val accountResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // login state or settings may have changed, rebuild the lists
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loggedIn = viewModel.isLoggedIn()
        if (backStack.isEmpty()) {
            backStack.add(if (loggedIn) TvDestination.Following else TvDestination.Popular)
        }
        handleIntent(intent)
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
                    if (now - lastKeyTime > TvBrowseViewModel.STALE_MS && now - lastAutoRefresh > TvBrowseViewModel.STALE_MS) {
                        lastAutoRefresh = now
                        viewModel.requestRefresh()
                    }
                }
            }
        }
    }

    override fun onRestart() {
        super.onRestart()
        // back from the player or from the background
        lastAutoRefresh = System.currentTimeMillis()
        viewModel.requestRefresh()
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
            backStack.add(TvDestination.Channel(channelId, channelLogin, intent.getStringExtra(EXTRA_CHANNEL_NAME)))
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
                    login = { accountResultLauncher.launch(Intent(this@TvActivity, LoginActivity::class.java)) },
                )
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
            RailItem(R.drawable.ic_favorite_black_24dp, R.string.following, expanded, current == TvDestination.Following) {
                openSection(TvDestination.Following)
            }
            RailItem(R.drawable.ic_trending_up_black_24dp, R.string.popular, expanded, current == TvDestination.Popular) {
                openSection(TvDestination.Popular)
            }
            RailItem(R.drawable.ic_games_black_24dp, R.string.tv_categories, expanded, current == TvDestination.Categories) {
                openSection(TvDestination.Categories)
            }
            RailItem(R.drawable.baseline_search_black_24, R.string.search, expanded, current == TvDestination.Search) {
                openSection(TvDestination.Search)
            }
            Spacer(Modifier.weight(1f))
            RailItem(R.drawable.baseline_replay_black_24, R.string.refresh, expanded, false) {
                lastAutoRefresh = System.currentTimeMillis()
                viewModel.requestRefresh()
            }
            if (!loggedIn) {
                RailItem(R.drawable.baseline_person_black_24, R.string.log_in, expanded, false) {
                    accountResultLauncher.launch(Intent(this@TvActivity, LoginActivity::class.java))
                }
            }
            RailItem(R.drawable.baseline_settings_black_24, R.string.settings, expanded, false) {
                accountResultLauncher.launch(Intent(this@TvActivity, SettingsActivity::class.java))
            }
        }
    }

    @Composable
    private fun RailItem(@DrawableRes icon: Int, @StringRes label: Int, expanded: Boolean, selected: Boolean, onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            modifier = Modifier
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
