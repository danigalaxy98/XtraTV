package com.github.andreyasadchy.xtra.ui.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ActivityTvPlayerBinding
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.player.BasePlaybackService
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService
import com.github.andreyasadchy.xtra.ui.player.PlayerViewModel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Fullscreen player for Android TV: video, optional chat panel on the right and an info/controls overlay
 * that appears when a remote key is pressed.
 */
@OptIn(UnstableApi::class)
class TvPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvPlayerBinding
    private val viewModel: PlayerViewModel by viewModels { PlayerViewModel.PlayerViewModelFactory }

    private var service: ExoPlayerService? = null
    private var connection: ServiceConnection? = null
    private var closed = false
    private var infoLoaded = false
    private var consumedKeyCode = -1

    private var isStream = true
    private var channelId: String? = null
    private var channelLogin: String? = null
    private var channelName: String? = null

    // overlay state
    private var overlayVisible by mutableStateOf(false)
    private var qualityMenuVisible by mutableStateOf(false)
    private var lastInteraction by mutableLongStateOf(0L)
    private var title by mutableStateOf<String?>(null)
    private var gameName by mutableStateOf<String?>(null)
    private var avatarUrl by mutableStateOf<String?>(null)
    private var viewerCount by mutableStateOf<Int?>(null)
    private var startedAt by mutableStateOf<Long?>(null)
    private var isPlaying by mutableStateOf(true)
    private var chatVisible by mutableStateOf(false)
    private var chatAvailable by mutableStateOf(false)
    private var isFollowing by mutableStateOf<Boolean?>(null)
    private var qualities by mutableStateOf<List<VideoQuality>>(emptyList())
    private var currentQuality by mutableStateOf<VideoQuality?>(null)
    private var position by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private var progress by mutableFloatStateOf(0f)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@TvPlayerActivity.isPlaying = isPlaying
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                binding.aspectRatioLayout.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
            }
        }
    }

    private val serviceListener = object : ExoPlayerService.Listener {
        override fun started() {
            onPlaybackStarted()
        }

        override fun loaded() {
            updateQualities()
        }

        override fun changePlayerMode() {
            updateQualities()
        }

        override fun toast(resId: Int, duration: Int) {
            Toast.makeText(this@TvPlayerActivity, resId, duration).show()
        }

        override fun toast(text: CharSequence, duration: Int) {
            Toast.makeText(this@TvPlayerActivity, text, duration).show()
        }

        override fun updateVideoInfo() {
            service?.let {
                title = it.title
                gameName = it.gameName
            }
        }

        override fun setPlayerSurface(secondaryPlayer: Boolean) {
            val service = service ?: return
            if (secondaryPlayer) {
                service.player?.setVideoSurfaceView(null)
                service.player?.removeListener(playerListener)
                service.secondaryPlayer?.setVideoSurfaceView(binding.playerSurface)
                service.secondaryPlayer?.addListener(playerListener)
            } else {
                service.secondaryPlayer?.setVideoSurfaceView(null)
                service.secondaryPlayer?.removeListener(playerListener)
                service.player?.setVideoSurfaceView(binding.playerSurface)
                service.player?.addListener(playerListener)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityTvPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val stream = intent.getParcelableExtraCompat<Stream>(EXTRA_STREAM)
        val video = intent.getParcelableExtraCompat<Video>(EXTRA_VIDEO)
        isStream = stream != null
        channelId = stream?.channelId ?: video?.channelId
        channelLogin = stream?.channelLogin ?: video?.channelLogin
        channelName = stream?.channelName ?: video?.channelName
        title = stream?.title ?: video?.title
        gameName = stream?.gameName ?: video?.gameName
        avatarUrl = stream?.channelImage ?: video?.channelImage
        viewerCount = stream?.viewerCount
        startedAt = stream?.createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }

        binding.chatContainer.updateLayoutParams {
            width = (resources.displayMetrics.widthPixels * 0.3f).toInt()
        }
        chatAvailable = isStream && !prefs().getBoolean(C.CHAT_DISABLE, false)
        chatVisible = chatAvailable && prefs().getBoolean(C.KEY_CHAT_OPENED, true)
        binding.chatContainer.isVisible = chatVisible

        binding.overlay.setContent {
            XtraTvTheme {
                PlayerOverlay()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    qualityMenuVisible -> qualityMenuVisible = false
                    overlayVisible -> overlayVisible = false
                    else -> finish()
                }
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.stream.collectLatest { info ->
                        if (info != null) {
                            info.title?.let { title = it }
                            info.gameName?.let { gameName = it }
                            info.viewerCount?.let { viewerCount = it }
                            info.channelImage?.let { avatarUrl = it }
                            info.createdAt?.let { date ->
                                Instant.parseOrNull(date)?.toEpochMilliseconds()?.takeIf { it > 0 }?.let { startedAt = it }
                            }
                        }
                    }
                }
                launch {
                    viewModel.isFollowing.collectLatest { isFollowing = it }
                }
                launch {
                    viewModel.follow.collectLatest { pair ->
                        if (pair != null) {
                            val (following, error) = pair
                            val message = when {
                                !error.isNullOrBlank() -> error
                                following -> getString(R.string.now_following, channelName ?: channelLogin)
                                else -> getString(R.string.unfollowed, channelName ?: channelLogin)
                            }
                            Toast.makeText(this@TvPlayerActivity, message, Toast.LENGTH_SHORT).show()
                            viewModel.follow.value = null
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            if (savedInstanceState == null) {
                val state = when {
                    stream != null -> PlaybackState(
                        type = BasePlaybackService.STREAM,
                        streamId = stream.id,
                        channelId = stream.channelId,
                        channelLogin = stream.channelLogin,
                        channelName = stream.channelName,
                        channelImage = stream.channelImage,
                        gameId = stream.gameId,
                        gameSlug = stream.gameSlug,
                        gameName = stream.gameName,
                        title = stream.title,
                        thumbnail = stream.thumbnail,
                        createdAt = stream.createdAt,
                        viewerCount = stream.viewerCount,
                    )
                    video != null -> PlaybackState(
                        type = BasePlaybackService.VIDEO,
                        videoId = video.id,
                        channelId = video.channelId,
                        channelLogin = video.channelLogin,
                        channelName = video.channelName,
                        channelImage = video.channelImage,
                        gameId = video.gameId,
                        gameSlug = video.gameSlug,
                        gameName = video.gameName,
                        title = video.title,
                        thumbnail = video.thumbnail,
                        createdAt = video.createdAt,
                        durationSeconds = video.durationSeconds,
                        videoType = video.type,
                        videoAnimatedPreviewURL = video.animatedPreviewURL,
                    )
                    else -> null
                }
                if (state == null) {
                    finish()
                    return@launch
                }
                (application as XtraApp).xtraModule.playerRepository.savePlaybackStates(listOf(state))
            }
            bindPlaybackService()
        }
    }

    private fun bindPlaybackService() {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (closed) return
                val service = (binder as ExoPlayerService.ServiceBinder).getService()
                this@TvPlayerActivity.service = service
                service.serviceListener = serviceListener
                service.setStopServiceTimer(false)
                (service.secondaryPlayer ?: service.player)?.let { player ->
                    player.setVideoSurfaceView(binding.playerSurface)
                    player.addListener(playerListener)
                    isPlaying = player.isPlaying
                }
                if (service.started) {
                    onPlaybackStarted()
                }
                updateQualities()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val intent = Intent(this, ExoPlayerService::class.java).apply {
            action = ExoPlayerService.INTENT_START
        }
        startService(intent)
        bindService(intent, connection, BIND_AUTO_CREATE)
        this.connection = connection
    }

    private fun onPlaybackStarted() {
        val service = service ?: return
        if (infoLoaded) return
        infoLoaded = true
        channelId = service.channelId ?: channelId
        channelLogin = service.channelLogin ?: channelLogin
        channelName = service.channelName ?: channelName
        val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        if (service.type == BasePlaybackService.STREAM) {
            viewModel.loadStreamInfo(
                channelId = channelId,
                channelLogin = channelLogin,
                viewerCount = service.viewerCount,
                loop = true,
                networkLibrary = networkLibrary,
                helixHeaders = TwitchApiHelper.getHelixHeaders(this),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(this),
                enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            )
            if (chatAvailable && supportFragmentManager.findFragmentById(R.id.chatContainer) == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.chatContainer, ChatFragment.newInstance(channelId, channelLogin, channelName, service.streamId))
                    .commit()
            }
        }
        viewModel.isFollowingChannel(
            tokenPrefs().getString(C.USER_ID, null),
            channelId,
            channelLogin,
            prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0,
            networkLibrary,
            TwitchApiHelper.getGQLHeaders(this, true),
            TwitchApiHelper.getHelixHeaders(this),
        )
    }

    private fun updateQualities() {
        val service = service ?: return
        qualities = service.qualities.orEmpty().filter { it.name != VideoQuality.CHAT_ONLY_QUALITY }
        currentQuality = service.quality
    }

    private fun showOverlay() {
        lastInteraction = System.currentTimeMillis()
        overlayVisible = true
    }

    private fun toggleChat() {
        chatVisible = !chatVisible
        binding.chatContainer.isVisible = chatVisible
        prefs().edit { putBoolean(C.KEY_CHAT_OPENED, chatVisible) }
    }

    private fun toggleFollow() {
        val userId = tokenPrefs().getString(C.USER_ID, null)
        val setting = prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0
        val networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val enableIntegrity = prefs().getBoolean(C.ENABLE_INTEGRITY, false)
        if (isFollowing == true) {
            viewModel.deleteFollowChannel(userId, channelId, setting, networkLibrary, TwitchApiHelper.getGQLHeaders(this, true), enableIntegrity)
        } else {
            viewModel.saveFollowChannel(
                userId, channelId, channelLogin, channelName, setting,
                prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                !prefs().getBoolean(C.UI_ACTIVATE_NOTIFICATIONS_WHEN_FOLLOWING, true),
                viewModel.stream.value?.createdAt,
                networkLibrary, TwitchApiHelper.getGQLHeaders(this, true), enableIntegrity,
            )
        }
    }

    private fun seek(forward: Boolean) {
        if (forward) service?.seekForward() else service?.seekBack()
        showOverlay()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && keyCode == consumedKeyCode) {
            consumedKeyCode = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            lastInteraction = System.currentTimeMillis()
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (event.repeatCount == 0) service?.togglePause()
                    consumedKeyCode = keyCode
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (!isStream) seek(keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                    consumedKeyCode = keyCode
                    return true
                }
            }
            if (!overlayVisible) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (!isStream) {
                            seek(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                        } else if (event.repeatCount == 0) {
                            showOverlay()
                        }
                        consumedKeyCode = keyCode
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                        if (event.repeatCount == 0) showOverlay()
                        consumedKeyCode = keyCode
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun PlayerOverlay() {
        val firstButton = remember { FocusRequester() }
        LaunchedEffect(overlayVisible, lastInteraction, qualityMenuVisible) {
            if (overlayVisible && !qualityMenuVisible) {
                delay(5.seconds)
                overlayVisible = false
            }
        }
        LaunchedEffect(overlayVisible) {
            if (overlayVisible) {
                delay(50.milliseconds)
                try {
                    firstButton.requestFocus()
                } catch (e: IllegalStateException) {
                }
                while (true) {
                    val player = service?.player
                    if (player != null && !isStream) {
                        position = player.currentPosition.coerceAtLeast(0)
                        duration = player.duration.coerceAtLeast(0)
                        progress = if (duration > 0) position.toFloat() / duration else 0f
                    }
                    delay(500.milliseconds)
                }
            }
        }
        AnimatedVisibility(visible = overlayVisible, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                // top: channel info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(avatarUrl, 64)
                    Spacer(Modifier.width(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = channelName ?: channelLogin.orEmpty(),
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        title?.let {
                            Text(
                                text = it,
                                color = TvColors.Text,
                                fontSize = 18.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = listOfNotNull(
                                gameName,
                                viewerCount?.takeIf { isStream }?.let {
                                    pluralStringResource(R.plurals.viewers, it, TwitchApiHelper.formatCount(it, true))
                                },
                                startedAt?.takeIf { isStream }?.let { uptimeText(it) },
                            ).joinToString("  ·  "),
                            color = TvColors.TextSecondary,
                            fontSize = 16.sp,
                        )
                    }
                }
                // bottom: progress (videos) and buttons
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                ) {
                    if (!isStream) {
                        ProgressBar()
                        Spacer(Modifier.height(16.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (!isStream) {
                            PillButton(
                                text = stringResource(if (isPlaying) R.string.pause else R.string.resume),
                                icon = if (isPlaying) R.drawable.baseline_pause_black_48 else R.drawable.baseline_play_arrow_black_48,
                                onClick = {
                                    service?.togglePause()
                                    showOverlay()
                                },
                                modifier = Modifier.focusRequester(firstButton),
                            )
                        }
                        if (chatAvailable) {
                            PillButton(
                                text = stringResource(R.string.chat),
                                icon = if (chatVisible) R.drawable.baseline_speaker_notes_black_24 else R.drawable.baseline_speaker_notes_off_black_24,
                                selected = chatVisible,
                                onClick = {
                                    toggleChat()
                                    showOverlay()
                                },
                                modifier = if (isStream) Modifier.focusRequester(firstButton) else Modifier,
                            )
                        }
                        if (qualities.isNotEmpty()) {
                            PillButton(
                                text = currentQuality?.let { qualityName(it) } ?: stringResource(R.string.quality),
                                icon = R.drawable.baseline_settings_black_24,
                                onClick = { qualityMenuVisible = true },
                                modifier = if (isStream && !chatAvailable) Modifier.focusRequester(firstButton) else Modifier,
                            )
                        }
                        if (isFollowing != null) {
                            PillButton(
                                text = stringResource(if (isFollowing == true) R.string.following else R.string.follow),
                                icon = if (isFollowing == true) R.drawable.baseline_favorite_black_24 else R.drawable.baseline_favorite_border_black_24,
                                selected = isFollowing == true,
                                onClick = {
                                    toggleFollow()
                                    showOverlay()
                                },
                            )
                        }
                        PillButton(
                            text = stringResource(R.string.tv_channel),
                            icon = R.drawable.baseline_person_black_24,
                            onClick = { TvActivity.openChannel(this@TvPlayerActivity, channelId, channelLogin, channelName) },
                            modifier = if (isStream && !chatAvailable && qualities.isEmpty()) Modifier.focusRequester(firstButton) else Modifier,
                        )
                    }
                }
                if (qualityMenuVisible) {
                    QualityMenu(Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }

    @Composable
    private fun ProgressBar() {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(position / 1000), color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x55FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(TvColors.Accent),
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(formatDuration(duration / 1000), color = Color.White, fontSize = 14.sp)
        }
    }

    @Composable
    private fun QualityMenu(modifier: Modifier) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(50.milliseconds)
            try {
                first.requestFocus()
            } catch (e: IllegalStateException) {
            }
        }
        Surface(
            modifier = modifier
                .padding(end = 48.dp)
                .widthIn(min = 260.dp)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            LazyColumn(modifier = Modifier.padding(12.dp)) {
                item {
                    Text(
                        text = stringResource(R.string.quality),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                items(qualities) { quality ->
                    val isCurrent = quality.name == currentQuality?.name
                    PillButton(
                        text = qualityName(quality),
                        selected = isCurrent,
                        icon = if (isCurrent) R.drawable.baseline_circle_24 else null,
                        onClick = {
                            service?.changeQuality(quality)
                            currentQuality = quality
                            qualityMenuVisible = false
                            showOverlay()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .then(if (quality == qualities.first()) Modifier.focusRequester(first) else Modifier),
                    )
                }
            }
        }
    }

    @Composable
    private fun qualityName(quality: VideoQuality): String {
        return when (quality.name) {
            VideoQuality.AUTO_QUALITY -> stringResource(R.string.auto)
            VideoQuality.SOURCE_QUALITY -> stringResource(R.string.source)
            VideoQuality.AUDIO_ONLY_QUALITY -> stringResource(R.string.audio_only)
            else -> quality.name.orEmpty()
        }
    }

    private fun uptimeText(startedAt: Long): String {
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        return "⏱ " + formatDuration(seconds)
    }

    override fun onStop() {
        super.onStop()
        // leaving the player (home button, opening the channel...) ends playback on TV
        if (!isChangingConfigurations) {
            closePlayer()
            finish()
        }
    }

    override fun onDestroy() {
        closePlayer()
        super.onDestroy()
    }

    private fun closePlayer() {
        if (closed) return
        closed = true
        service?.let { service ->
            service.pause()
            service.stop()
            service.player?.removeListener(playerListener)
            service.secondaryPlayer?.removeListener(playerListener)
            service.serviceListener = null
        }
        viewModel.deletePlaybackStates()
        connection?.let {
            try {
                unbindService(it)
            } catch (e: IllegalArgumentException) {
            }
        }
        connection = null
        service?.stopSelf()
        service = null
    }

    companion object {
        private const val EXTRA_STREAM = "tv_stream"
        private const val EXTRA_VIDEO = "tv_video"

        fun startStream(context: Context, stream: Stream) {
            context.startActivity(Intent(context, TvPlayerActivity::class.java).putExtra(EXTRA_STREAM, stream))
        }

        fun startVideo(context: Context, video: Video) {
            context.startActivity(Intent(context, TvPlayerActivity::class.java).putExtra(EXTRA_VIDEO, video))
        }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key)
    }
}
