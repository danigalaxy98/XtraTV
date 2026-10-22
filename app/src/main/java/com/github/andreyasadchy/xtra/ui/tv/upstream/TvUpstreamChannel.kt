package com.github.andreyasadchy.xtra.ui.tv.upstream

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.bundleOf
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerViewModel
import com.github.andreyasadchy.xtra.ui.player.PlayerViewModel
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.StateFlow

/**
 * What the TV interface knows about a channel: its live stream, who it is and whether the account follows it, taken
 * from the view models of the original (the player's for the TV player, the channel page's for the channel screen).
 * Part of the only layer of the TV interface that touches the original code (see CAMBIOS-TV.md, section 2).
 */
abstract class TvChannelModel(protected val context: Context) {

    abstract val stream: StateFlow<Stream?>

    /** True when the account follows the channel; null while unknown (or when it cannot be known). */
    abstract val isFollowing: StateFlow<Boolean?>

    /** Result of the last follow or unfollow: whether it follows now and the error, if any. */
    abstract val followResult: StateFlow<Pair<Boolean, String?>?>

    /** Call once the result has been shown. */
    abstract fun clearFollowResult()

    protected abstract fun checkFollowing(userId: String?, channelId: String?, channelLogin: String?, setting: Int, networkLibrary: String?)
    protected abstract fun deleteFollow(userId: String?, channelId: String?, setting: Int, networkLibrary: String?, enableIntegrity: Boolean)
    protected abstract fun saveFollow(
        userId: String?, channelId: String?, channelLogin: String?, channelName: String?, setting: Int,
        liveNotificationsEnabled: Boolean, disableNotifications: Boolean, networkLibrary: String?, enableIntegrity: Boolean,
    )

    private val followSetting get() = context.prefs().getString(C.UI_FOLLOW_BUTTON, "0")?.toIntOrNull() ?: 0

    fun checkFollowing(channelId: String?, channelLogin: String?) {
        checkFollowing(TvUpstreamAccount.userId(context), channelId, channelLogin, followSetting, TvUpstreamAccount.networkLibrary(context))
    }

    fun unfollow(channelId: String?) {
        deleteFollow(TvUpstreamAccount.userId(context), channelId, followSetting, TvUpstreamAccount.networkLibrary(context), TvUpstreamAccount.enableIntegrity(context))
    }

    fun follow(channelId: String?, channelLogin: String?, channelName: String?) {
        saveFollow(
            TvUpstreamAccount.userId(context), channelId, channelLogin, channelName, followSetting,
            context.prefs().getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
            !context.prefs().getBoolean(C.UI_ACTIVATE_NOTIFICATIONS_WHEN_FOLLOWING, true),
            TvUpstreamAccount.networkLibrary(context), TvUpstreamAccount.enableIntegrity(context),
        )
    }

    protected val gqlHeadersWithToken get() = TvUpstreamAccount.gqlHeaders(context, true)
    protected val helixHeaders get() = TvUpstreamAccount.helixHeaders(context)
}

/** The channel being watched in the TV player. */
class TvPlayerModel private constructor(
    context: Context,
    private val viewModel: PlayerViewModel,
) : TvChannelModel(context) {

    override val stream: StateFlow<Stream?> get() = viewModel.stream
    override val isFollowing: StateFlow<Boolean?> get() = viewModel.isFollowing
    override val followResult: StateFlow<Pair<Boolean, String?>?> get() = viewModel.follow

    override fun clearFollowResult() {
        viewModel.follow.value = null
    }

    /** Keeps the live stream information up to date (title, game, viewers). */
    fun loadStreamInfo(channelId: String?, channelLogin: String?, viewerCount: Int?) {
        viewModel.loadStreamInfo(
            channelId = channelId,
            channelLogin = channelLogin,
            viewerCount = viewerCount,
            loop = true,
            networkLibrary = TvUpstreamAccount.networkLibrary(context),
            helixHeaders = helixHeaders,
            gqlHeaders = TvUpstreamAccount.gqlHeaders(context),
            enableIntegrity = TvUpstreamAccount.enableIntegrity(context),
        )
    }

    /** Forgets what was playing, so the playback service does not resume it. */
    fun deletePlaybackStates() = viewModel.deletePlaybackStates()

    override fun checkFollowing(userId: String?, channelId: String?, channelLogin: String?, setting: Int, networkLibrary: String?) {
        viewModel.isFollowingChannel(userId, channelId, channelLogin, setting, networkLibrary, gqlHeadersWithToken, helixHeaders)
    }

    override fun deleteFollow(userId: String?, channelId: String?, setting: Int, networkLibrary: String?, enableIntegrity: Boolean) {
        viewModel.deleteFollowChannel(userId, channelId, setting, networkLibrary, gqlHeadersWithToken, enableIntegrity)
    }

    override fun saveFollow(
        userId: String?, channelId: String?, channelLogin: String?, channelName: String?, setting: Int,
        liveNotificationsEnabled: Boolean, disableNotifications: Boolean, networkLibrary: String?, enableIntegrity: Boolean,
    ) {
        viewModel.saveFollowChannel(
            userId, channelId, channelLogin, channelName, setting, liveNotificationsEnabled, disableNotifications,
            viewModel.stream.value?.createdAt, networkLibrary, gqlHeadersWithToken, enableIntegrity,
        )
    }

    companion object {
        fun of(activity: ComponentActivity): TvPlayerModel {
            return TvPlayerModel(activity, ViewModelProvider(activity, PlayerViewModel.PlayerViewModelFactory)[PlayerViewModel::class.java])
        }
    }
}

/** A channel page of the TV interface. */
class TvPageModel private constructor(
    context: Context,
    private val viewModel: ChannelPagerViewModel,
) : TvChannelModel(context) {

    override val stream: StateFlow<Stream?> get() = viewModel.stream
    override val isFollowing: StateFlow<Boolean?> get() = viewModel.isFollowing
    override val followResult: StateFlow<Pair<Boolean, String?>?> get() = viewModel.follow

    /** The channel's profile (name, picture, followers). */
    val user: StateFlow<User?> get() = viewModel.user

    override fun clearFollowResult() {
        viewModel.follow.value = null
    }

    /** Loads the channel's profile and its live stream, if it has one. */
    fun load() {
        viewModel.loadStream(
            TvUpstreamAccount.networkLibrary(context),
            TvUpstreamAccount.gqlHeaders(context),
            helixHeaders,
            TvUpstreamAccount.enableIntegrity(context),
        )
    }

    override fun checkFollowing(userId: String?, channelId: String?, channelLogin: String?, setting: Int, networkLibrary: String?) {
        viewModel.isFollowingChannel(userId, channelId, channelLogin, setting, networkLibrary, gqlHeadersWithToken, helixHeaders)
    }

    override fun deleteFollow(userId: String?, channelId: String?, setting: Int, networkLibrary: String?, enableIntegrity: Boolean) {
        viewModel.deleteFollowChannel(userId, channelId, setting, networkLibrary, gqlHeadersWithToken, enableIntegrity)
    }

    override fun saveFollow(
        userId: String?, channelId: String?, channelLogin: String?, channelName: String?, setting: Int,
        liveNotificationsEnabled: Boolean, disableNotifications: Boolean, networkLibrary: String?, enableIntegrity: Boolean,
    ) {
        viewModel.saveFollowChannel(
            userId, channelId, channelLogin, channelName, setting, liveNotificationsEnabled, disableNotifications,
            networkLibrary, gqlHeadersWithToken, enableIntegrity,
        )
    }

    companion object {
        /** The page model of a channel, kept while the channel stays on the back stack. */
        @Composable
        fun of(channelId: String?, channelLogin: String?, channelName: String?): TvPageModel {
            val context = LocalContext.current
            val owner = LocalViewModelStoreOwner.current
            val extras = remember(channelId, channelLogin, channelName) {
                MutableCreationExtras(
                    (owner as? HasDefaultViewModelProviderFactory)?.defaultViewModelCreationExtras ?: CreationExtras.Empty
                ).apply {
                    this[DEFAULT_ARGS_KEY] = bundleOf(
                        "channelId" to channelId,
                        "channelLogin" to channelLogin,
                        "channelName" to channelName,
                    )
                }
            }
            val viewModel: ChannelPagerViewModel = viewModel(
                key = "tv_channel_${channelId ?: channelLogin}",
                factory = ChannelPagerViewModel.ChannelPagerViewModelFactory,
                extras = extras,
            )
            return remember(viewModel) { TvPageModel(context, viewModel) }
        }
    }
}
