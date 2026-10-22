package com.github.andreyasadchy.xtra.ui.tv.upstream

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.exoplayer.ExoPlayer
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.chat.ChatViewModel
import com.github.andreyasadchy.xtra.ui.main.MainViewModel
import com.github.andreyasadchy.xtra.ui.player.BasePlaybackService
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService
import kotlinx.coroutines.launch

/**
 * The playback of the original as the TV player needs it: the playback service (video, qualities, proxys, ads),
 * the chat fragment and its raids. The playback service and the chat are the parts of the original that change
 * most: when they do, this is the only file to adapt.
 * Part of the only layer of the TV interface that touches the original code (see CAMBIOS-TV.md, section 2).
 */

/** Events of the playback service that the TV player reacts to. */
interface TvPlaybackListener {
    fun started()
    fun loaded()
    fun changePlayerMode()
    fun toast(resId: Int, duration: Int)
    fun toast(text: CharSequence, duration: Int)
    fun updateVideoInfo()
    fun setPlayerSurface(secondaryPlayer: Boolean)
}

/** A video quality offered by the playback service. */
class TvQuality internal constructor(internal val raw: VideoQuality) {

    enum class Kind { AUTO, SOURCE, AUDIO_ONLY, OTHER }

    val name: String? get() = raw.name

    val kind: Kind
        get() = when (raw.name) {
            VideoQuality.AUTO_QUALITY -> Kind.AUTO
            VideoQuality.SOURCE_QUALITY -> Kind.SOURCE
            VideoQuality.AUDIO_ONLY_QUALITY -> Kind.AUDIO_ONLY
            else -> Kind.OTHER
        }
}

/** The running playback service. */
class TvPlaybackService internal constructor(private val service: ExoPlayerService) {

    val player: ExoPlayer? get() = service.player
    val secondaryPlayer: ExoPlayer? get() = service.secondaryPlayer

    val isStream: Boolean get() = service.type == BasePlaybackService.STREAM
    val started: Boolean get() = service.started
    val streamId: String? get() = service.streamId
    val channelId: String? get() = service.channelId
    val channelLogin: String? get() = service.channelLogin
    val channelName: String? get() = service.channelName
    val title: String? get() = service.title
    val gameName: String? get() = service.gameName
    val viewerCount: Int? get() = service.viewerCount

    /** Qualities without the "chat only" one, which makes no sense for the TV player. */
    val qualities: List<TvQuality>
        get() = service.qualities.orEmpty().filter { it.name != VideoQuality.CHAT_ONLY_QUALITY }.map(::TvQuality)
    val quality: TvQuality? get() = service.quality?.let(::TvQuality)

    private var listener: TvPlaybackListener? = null
    private var adapter: ExoPlayerService.Listener? = null

    fun setListener(listener: TvPlaybackListener) {
        this.listener = listener
        val adapter = object : ExoPlayerService.Listener {
            override fun started() = listener.started()
            override fun loaded() = listener.loaded()
            override fun changePlayerMode() = listener.changePlayerMode()
            override fun toast(resId: Int, duration: Int) = listener.toast(resId, duration)
            override fun toast(text: CharSequence, duration: Int) = listener.toast(text, duration)
            override fun updateVideoInfo() = listener.updateVideoInfo()
            override fun setPlayerSurface(secondaryPlayer: Boolean) = listener.setPlayerSurface(secondaryPlayer)
        }
        this.adapter = adapter
        service.serviceListener = adapter
    }

    /** Stops listening, unless another listener has been set since. */
    fun clearListener(listener: TvPlaybackListener) {
        if (this.listener === listener) {
            if (service.serviceListener === adapter) {
                service.serviceListener = null
            }
            this.listener = null
            adapter = null
        }
    }

    fun clearListener() {
        service.serviceListener = null
        listener = null
        adapter = null
    }

    fun setStopServiceTimer(start: Boolean) = service.setStopServiceTimer(start)
    fun changeQuality(quality: TvQuality) = service.changeQuality(quality.raw)
    fun togglePause() = service.togglePause()
    fun pause() = service.pause()
    fun stop() = service.stop()
    fun stopSelf() = service.stopSelf()
    fun seekBack() = service.seekBack()
    fun seekForward() = service.seekForward()
    fun seekTo(position: Long) = service.seekTo(position)
    fun restartPlayer() = service.restartPlayer()
}

/** The binding of an activity to the playback service. */
class TvPlaybackConnection internal constructor(
    private val activity: Activity,
    private val connection: ServiceConnection,
) {
    private var bound = true

    /** Releases the binding; the service keeps running. */
    fun unbind() {
        if (!bound) return
        bound = false
        try {
            activity.unbindService(connection)
        } catch (e: IllegalArgumentException) {
            // already unbound
        }
    }

    /** Stops the service itself, for when it never connected. */
    fun stopService() {
        activity.stopService(Intent(activity, ExoPlayerService::class.java))
    }
}

object TvUpstreamPlayer {

    /**
     * Stores what to play where the playback service reads it, the way the original's screens do before starting
     * it. Returns false when there is nothing to play.
     */
    suspend fun savePlaybackState(application: Application, stream: Stream?, video: Video?): Boolean {
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
            else -> return false
        }
        (application as XtraApp).xtraModule.playerRepository.savePlaybackStates(listOf(state))
        return true
    }

    /**
     * Starts the playback service and binds to it. [onConnected] runs once it is available; when [isClosed]
     * is already true by then, the service is stopped instead and [onConnected] is not called.
     * [onDisconnected] runs if the service dies.
     */
    fun bind(
        activity: Activity,
        listener: TvPlaybackListener,
        isClosed: () -> Boolean,
        onConnected: (TvPlaybackService) -> Unit,
        onDisconnected: () -> Unit,
    ): TvPlaybackConnection {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (isClosed()) {
                    (binder as? ExoPlayerService.ServiceBinder)?.getService()?.let { service ->
                        service.stop()
                        service.stopSelf()
                    }
                    try {
                        activity.unbindService(this)
                    } catch (e: IllegalArgumentException) {
                    }
                    return
                }
                val service = TvPlaybackService((binder as ExoPlayerService.ServiceBinder).getService())
                service.setListener(listener)
                service.setStopServiceTimer(false)
                onConnected(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) = onDisconnected()
        }
        val intent = Intent(activity, ExoPlayerService::class.java).apply {
            action = ExoPlayerService.INTENT_START
        }
        activity.startService(intent)
        activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        return TvPlaybackConnection(activity, connection)
    }

    // region Chat

    /** The live chat of a channel, to show next to the video. */
    fun newChat(channelId: String?, channelLogin: String?, channelName: String?, streamId: String?): Fragment {
        return ChatFragment.newInstance(channelId, channelLogin, channelName, streamId)
    }

    /** Tells the chat whether there is a network connection, so it reconnects by itself when it comes back. */
    fun setNetworkAvailable(activity: AppCompatActivity, available: Boolean) {
        // the same instance the chat fragment gets (it uses the activity's view models)
        ViewModelProvider(activity, MainViewModel.MainViewModelFactory)[MainViewModel::class.java].isNetworkAvailable.value = available
    }

    class Raid(val id: String, val channelId: String?, val channelLogin: String?, val channelName: String?, val channelImage: String?)

    /**
     * Raids: the chat only switches channel inside the phone player, so on TV the raid is read from the chat's view
     * model. [onRaid] is called for every raid the chat wants to open, when its countdown ends.
     * Call it before the activity's super.onCreate, so a chat restored after a recreation is watched too.
     */
    fun watchRaids(activity: AppCompatActivity, onRaid: (Raid) -> Unit) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) {
                if (f !is ChatFragment) return
                val chatViewModel = ViewModelProvider(f, ChatViewModel.ChatViewModelFactory)[ChatViewModel::class.java]
                f.lifecycleScope.launch {
                    f.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        chatViewModel.raid.collect { raid ->
                            if (raid != null && raid.openStream) {
                                val id = raid.raidId ?: raid.targetId ?: raid.targetLogin
                                if (id != null) {
                                    onRaid(Raid(id, raid.targetId, raid.targetLogin, raid.targetName, raid.targetImageURL))
                                }
                            }
                        }
                    }
                }
            }
        }, false)
    }

    // endregion
}
