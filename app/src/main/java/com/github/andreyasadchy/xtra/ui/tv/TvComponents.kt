package com.github.andreyasadchy.xtra.ui.tv

import androidx.annotation.DrawableRes
import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import java.util.Date
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = TvColors.Text,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** Focus target for the left key from the first grid column: the side menu entry of the current section. */
val LocalRailFocus = staticCompositionLocalOf<FocusRequester?> { null }

/** Extra modifier that grids hand to the card of each item (used to route the left key to the side menu). */
val LocalCardModifier = compositionLocalOf<Modifier> { Modifier }

/**
 * Title of a list screen with its refresh status: "Updating…" with a moving bar while it reloads,
 * then "✓ Updated at 21:34", highlighted for a few seconds when a reload has just finished.
 */
@Composable
fun ListHeader(title: String, refreshKey: String, state: TvBrowseViewModel) {
    val refreshing = state.refreshing[refreshKey] == true
    val lastUpdated = state.lastUpdated[refreshKey]
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        ScreenTitle(title, modifier = Modifier.padding(bottom = 0.dp)) {
            RefreshStatus(lastUpdated, refreshing)
        }
        Spacer(Modifier.height(8.dp))
        RefreshBar(visible = refreshing)
    }
}

@Composable
fun RefreshStatus(lastUpdated: Long?, refreshing: Boolean) {
    val context = LocalContext.current
    val initial = remember { lastUpdated }
    var highlight by remember { mutableStateOf(false) }
    LaunchedEffect(lastUpdated) {
        if (lastUpdated != null && lastUpdated != initial) {
            highlight = true
            delay(3.seconds)
            highlight = false
        }
    }
    val color by animateColorAsState(if (highlight) TvColors.Accent else TvColors.TextSecondary, label = "refreshStatus")
    val text = when {
        refreshing -> stringResource(R.string.tv_updating)
        lastUpdated != null -> stringResource(R.string.tv_updated_at, DateFormat.getTimeFormat(context).format(Date(lastUpdated)))
        else -> return
    }
    Text(
        text = text,
        color = if (refreshing) TvColors.Text else color,
        fontSize = 15.sp,
        fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
    )
}

/** Thin indeterminate bar shown while content is being reloaded. */
@Composable
fun RefreshBar(visible: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (visible) TvColors.SurfaceFocused else Color.Transparent),
    ) {
        if (visible) {
            val transition = rememberInfiniteTransition(label = "refreshBar")
            val offset by transition.animateFloat(
                initialValue = -0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
                label = "refreshBarOffset",
            )
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .offset(x = maxWidth * offset)
                        .width(maxWidth * 0.3f)
                        .fillMaxHeight()
                        .background(TvColors.Accent),
                )
            }
        }
    }
}
@Composable
fun MessageBox(text: String, buttonText: String? = null, onButtonClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, color = TvColors.TextSecondary, fontSize = 20.sp)
        if (buttonText != null) {
            Spacer(Modifier.height(24.dp))
            PillButton(text = buttonText, onClick = onButtonClick)
        }
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    selected: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = TvFocus.pillShape(),
        colors = TvFocus.colors(),
        border = TvFocus.border(rounded = true),
        scale = TvFocus.scale(1.08f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = if (selected) TvColors.Accent else LocalContentColor.current,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Grid backed by paging data that shows loading, error and empty states,
 * and refreshes itself when [refreshTick] changes or when its data is older than [STALE_MS].
 */
@Composable
fun <T : Any> PagedGrid(
    pagingItems: LazyPagingItems<T>,
    refreshKey: String,
    state: TvBrowseViewModel,
    columns: Int,
    emptyText: String,
    modifier: Modifier = Modifier,
    header: (LazyGridScope.() -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    val tick by state.refreshTick.collectAsState()
    val startTick = remember { tick }
    LaunchedEffect(tick) {
        if (tick != startTick) {
            pagingItems.refresh()
        }
    }
    LaunchedEffect(refreshKey) {
        val last = state.lastUpdated[refreshKey]
        val stale = state.staleMs()
        if (last != null && stale > 0 && System.currentTimeMillis() - last > stale) {
            pagingItems.refresh()
        }
    }
    val refreshState = pagingItems.loadState.refresh
    var wasLoading by remember { mutableStateOf(false) }
    LaunchedEffect(refreshState) {
        val loading = refreshState is LoadState.Loading
        state.refreshing[refreshKey] = loading
        // only a finished reload counts as an update (showing cached data again does not)
        if (wasLoading && refreshState is LoadState.NotLoading) {
            state.lastUpdated[refreshKey] = System.currentTimeMillis()
        }
        wasLoading = loading
    }
    DisposableEffect(refreshKey) {
        onDispose { state.refreshing[refreshKey] = false }
    }
    val railFocus = LocalRailFocus.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        header?.invoke(this)
        when {
            pagingItems.itemCount == 0 && refreshState is LoadState.Loading -> fullWidthItem {
                MessageBox(stringResource(R.string.loading))
            }
            pagingItems.itemCount == 0 && refreshState is LoadState.Error -> fullWidthItem {
                MessageBox(
                    text = stringResource(R.string.tv_error_loading),
                    buttonText = stringResource(R.string.tv_retry),
                    onButtonClick = { pagingItems.retry() },
                )
            }
            pagingItems.itemCount == 0 && refreshState is LoadState.NotLoading -> fullWidthItem {
                MessageBox(emptyText)
            }
            else -> items(count = pagingItems.itemCount) { index ->
                val cardModifier = if (railFocus != null && index % columns == 0) {
                    Modifier.focusProperties { left = railFocus }
                } else {
                    Modifier
                }
                CompositionLocalProvider(LocalCardModifier provides cardModifier) {
                    pagingItems[index]?.let { itemContent(it) }
                }
            }
        }
    }
}

fun LazyGridScope.fullWidthItem(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun Thumbnail(url: String?, modifier: Modifier = Modifier, ratio: Float = 16f / 9f) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(TvColors.Background),
    )
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun CardText(title: String?, subtitle: String?, third: String? = null) {
    Column(modifier = Modifier.padding(12.dp)) {
        Text(
            text = title.orEmpty(),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                color = TvColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!third.isNullOrBlank()) {
            Text(
                text = third,
                color = TvColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun StreamCard(stream: Stream, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = LocalCardModifier.current,
        shape = TvFocus.cardShape(),
        colors = TvFocus.colors(),
        border = TvFocus.border(),
        scale = TvFocus.scale(),
    ) {
        Column {
            Box {
                Thumbnail(stream.thumbnail)
                Badge(
                    text = stringResource(R.string.live).uppercase(),
                    color = TvColors.Live,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
                stream.viewerCount?.let { count ->
                    Badge(
                        text = pluralStringResource(R.plurals.viewers, count, TwitchApiHelper.formatCount(count, true)),
                        color = Color(0xCC000000),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                    )
                }
            }
            CardText(stream.channelName ?: stream.channelLogin, stream.title, stream.gameName)
        }
    }
}

@Composable
fun VideoCard(video: Video, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = LocalCardModifier.current,
        shape = TvFocus.cardShape(),
        colors = TvFocus.colors(),
        border = TvFocus.border(),
        scale = TvFocus.scale(),
    ) {
        Column {
            Box {
                Thumbnail(video.thumbnail)
                video.durationSeconds?.let { seconds ->
                    Badge(
                        text = formatDuration(seconds.toLong()),
                        color = Color(0xCC000000),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    )
                }
            }
            CardText(
                title = video.title,
                subtitle = video.gameName,
                third = video.viewCount?.let { pluralStringResource(R.plurals.views, it, TwitchApiHelper.formatCount(it, true)) },
            )
        }
    }
}

@Composable
fun GameCard(game: Game, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = LocalCardModifier.current,
        shape = TvFocus.cardShape(),
        colors = TvFocus.colors(),
        border = TvFocus.border(),
        scale = TvFocus.scale(),
    ) {
        Column {
            Thumbnail(game.boxArt, ratio = 3f / 4f)
            CardText(
                title = game.name,
                subtitle = game.viewerCount?.let { pluralStringResource(R.plurals.viewers, it, TwitchApiHelper.formatCount(it, true)) },
            )
        }
    }
}

@Composable
fun ChannelCard(user: User, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TvFocus.cardShape(),
        colors = TvFocus.colors(),
        border = TvFocus.border(),
        scale = TvFocus.scale(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(user.profileImage, 80)
            Spacer(Modifier.height(8.dp))
            Text(
                text = user.name ?: user.login.orEmpty(),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (user.isLive == true) {
                Spacer(Modifier.height(6.dp))
                Badge(stringResource(R.string.live).uppercase(), TvColors.Live)
            }
        }
    }
}

@Composable
fun Avatar(url: String?, sizeDp: Int) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(TvColors.SurfaceFocused),
    )
}

fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
