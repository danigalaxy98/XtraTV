package com.github.andreyasadchy.xtra.ui.tv

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.tv.material3.Text
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.tv.upstream.TvPageModel
import com.github.andreyasadchy.xtra.ui.tv.upstream.TvUpstreamAccount

private val STREAM_COLUMNS: Int
    @Composable get() = TvPrefs.streamColumns(LocalContext.current)
private val GAME_COLUMNS: Int
    @Composable get() = STREAM_COLUMNS + 2

@Composable
private fun StreamGridItem(stream: Stream, actions: TvActions) {
    StreamCard(
        stream = stream,
        onClick = { actions.playStream(stream) },
        onLongClick = { actions.openChannel(stream.channelId, stream.channelLogin, stream.channelName) },
    )
}

private fun streamKey(stream: Stream): Any = stream.id ?: stream.channelId ?: stream.channelLogin.orEmpty()

@Composable
fun FollowingScreen(viewModel: TvBrowseViewModel, loggedIn: Boolean, actions: TvActions) {
    val items = viewModel.followedStreams.collectAsLazyPagingItems()
    Column {
        ListHeader(stringResource(R.string.following), KEY_FOLLOWING, viewModel)
        PagedGrid(
            pagingItems = items,
            refreshKey = KEY_FOLLOWING,
            state = viewModel,
            columns = STREAM_COLUMNS,
            emptyText = stringResource(R.string.tv_empty_following),
            key = ::streamKey,
            header = {
                if (!loggedIn) {
                    fullWidthItem {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.tv_login_hint),
                                color = TvColors.TextSecondary,
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f),
                            )
                            PillButton(text = stringResource(R.string.log_in), onClick = actions.login)
                        }
                    }
                }
            },
        ) { stream ->
            StreamGridItem(stream, actions)
        }
    }
}

@Composable
fun PopularScreen(viewModel: TvBrowseViewModel, actions: TvActions) {
    val items = viewModel.popularStreams.collectAsLazyPagingItems()
    Column {
        ListHeader(stringResource(R.string.popular), KEY_POPULAR, viewModel)
        PagedGrid(
            pagingItems = items,
            refreshKey = KEY_POPULAR,
            state = viewModel,
            columns = STREAM_COLUMNS,
            emptyText = stringResource(R.string.tv_empty_list),
            key = ::streamKey,
        ) { stream ->
            StreamGridItem(stream, actions)
        }
    }
}

@Composable
fun CategoriesScreen(viewModel: TvBrowseViewModel, actions: TvActions) {
    val items = viewModel.games.collectAsLazyPagingItems()
    Column {
        ListHeader(stringResource(R.string.tv_categories), KEY_CATEGORIES, viewModel)
        PagedGrid(
            pagingItems = items,
            refreshKey = KEY_CATEGORIES,
            state = viewModel,
            columns = GAME_COLUMNS,
            emptyText = stringResource(R.string.tv_empty_list),
            key = { game -> game.id ?: game.slug ?: game.name.orEmpty() },
        ) { game ->
            GameCard(game) { actions.openGame(game.id, game.slug, game.name) }
        }
    }
}

@Composable
fun GameScreen(viewModel: TvBrowseViewModel, game: TvDestination.Game, actions: TvActions) {
    val flow = remember(game) { viewModel.gameStreams(game.id, game.slug, game.name) }
    val items = flow.collectAsLazyPagingItems()
    val key = "game_${game.id ?: game.slug ?: game.name}"
    Column {
        ListHeader(game.name.orEmpty(), key, viewModel)
        PagedGrid(
            pagingItems = items,
            refreshKey = key,
            state = viewModel,
            columns = STREAM_COLUMNS,
            emptyText = stringResource(R.string.tv_empty_list),
            key = ::streamKey,
        ) { stream ->
            StreamGridItem(stream, actions)
        }
    }
}

@Composable
fun SearchScreen(viewModel: TvBrowseViewModel, actions: TvActions) {
    var text by rememberSaveable { mutableStateOf(viewModel.searchQuery.value) }
    val channels = viewModel.searchChannels.collectAsLazyPagingItems()
    val streams = viewModel.searchStreams.collectAsLazyPagingItems()
    val keyboard = LocalSoftwareKeyboardController.current
    val channelKeys = rememberUniqueItemKey(channels) { user -> user.id ?: user.login.orEmpty() }
    Column {
        ScreenTitle(stringResource(R.string.search))
        var focused by remember { mutableStateOf(false) }
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                viewModel.searchQuery.value = it.trim()
            },
            singleLine = true,
            textStyle = TextStyle(color = TvColors.Text, fontSize = 22.sp),
            cursorBrush = SolidColor(TvColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(TvColors.Surface, RoundedCornerShape(12.dp))
                .border(
                    BorderStroke(3.dp, if (focused) TvColors.Accent else TvColors.Surface),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.tv_search_hint),
                            color = TvColors.TextSecondary,
                            fontSize = 22.sp,
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.height(24.dp))
        PagedGrid(
            pagingItems = streams,
            refreshKey = KEY_SEARCH,
            state = viewModel,
            columns = STREAM_COLUMNS,
            emptyText = stringResource(if (text.isBlank()) R.string.tv_search_prompt else R.string.tv_no_results),
            key = ::streamKey,
            header = {
                if (channels.itemCount > 0) {
                    fullWidthItem {
                        Column {
                            SectionLabel(stringResource(R.string.tv_channels))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                contentPadding = PaddingValues(vertical = 8.dp),
                            ) {
                                items(count = channels.itemCount, key = channelKeys) { index ->
                                    channels[index]?.let { user ->
                                        Box(Modifier.width(180.dp)) {
                                            ChannelCard(user) { actions.openChannel(user.id, user.login, user.name) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    fullWidthItem { SectionLabel(stringResource(R.string.live)) }
                }
            },
        ) { stream ->
            StreamGridItem(stream, actions)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TvColors.Text,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun ChannelScreen(viewModel: TvBrowseViewModel, channel: TvDestination.Channel, actions: TvActions) {
    val context = LocalContext.current
    val channelModel = TvPageModel.of(channel.id, channel.login, channel.name)
    val userId = TvUpstreamAccount.userId(context)
    LaunchedEffect(channel) {
        channelModel.load()
        channelModel.checkFollowing(channel.id, channel.login)
    }
    val stream by channelModel.stream.collectAsState()
    val user by channelModel.user.collectAsState()
    val isFollowing by channelModel.isFollowing.collectAsState()
    val followResult by channelModel.followResult.collectAsState()
    LaunchedEffect(followResult) {
        followResult?.let { (following, error) ->
            val name = user?.name ?: channel.name ?: channel.login
            val message = when {
                !error.isNullOrBlank() -> error
                following -> context.getString(R.string.now_following, name)
                else -> context.getString(R.string.unfollowed, name)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            channelModel.clearFollowResult()
        }
    }
    val channelId = channel.id ?: user?.id
    val videosFlow = remember(channelId, channel.login) { viewModel.channelVideos(channelId, channel.login) }
    val videos = videosFlow.collectAsLazyPagingItems()
    val key = "channel_${channelId ?: channel.login}"
    PagedGrid(
        pagingItems = videos,
        refreshKey = key,
        state = viewModel,
        columns = STREAM_COLUMNS,
        emptyText = stringResource(R.string.tv_no_videos),
        key = { video -> video.id.orEmpty() },
        header = {
            fullWidthItem {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Avatar(user?.profileImage, 96)
                    Spacer(Modifier.width(24.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user?.name ?: channel.name ?: channel.login.orEmpty(),
                            color = TvColors.Text,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        user?.followerCount?.let {
                            Text(
                                text = pluralStringResource(R.plurals.followers, it, TvUpstreamAccount.formatCount(it)),
                                color = TvColors.TextSecondary,
                                fontSize = 16.sp,
                            )
                        }
                    }
                    if (isFollowing != null && userId != channelId) {
                        PillButton(
                            text = stringResource(if (isFollowing == true) R.string.unfollow else R.string.follow),
                            icon = if (isFollowing == true) R.drawable.baseline_favorite_black_24 else R.drawable.baseline_favorite_border_black_24,
                            selected = isFollowing == true,
                            onClick = {
                                if (isFollowing == true) {
                                    channelModel.unfollow(channelId)
                                } else {
                                    channelModel.follow(channelId, channel.login, user?.name ?: channel.name)
                                }
                            },
                        )
                    }
                }
            }
            val liveStream = stream?.takeIf { !it.id.isNullOrBlank() }
            if (liveStream != null) {
                fullWidthItem { SectionLabel(stringResource(R.string.tv_live_now)) }
                item {
                    StreamCard(
                        stream = liveStream,
                        onClick = { actions.playStream(liveStream) },
                        onLongClick = { actions.playStream(liveStream) },
                    )
                }
                fullWidthItem { Spacer(Modifier.height(1.dp)) }
            }
            fullWidthItem { SectionLabel(stringResource(R.string.videos)) }
        },
    ) { video ->
        VideoCard(video) { actions.playVideo(video) }
    }
}

private const val KEY_FOLLOWING = "following"
private const val KEY_POPULAR = "popular"
private const val KEY_CATEGORIES = "categories"
private const val KEY_SEARCH = "search"
