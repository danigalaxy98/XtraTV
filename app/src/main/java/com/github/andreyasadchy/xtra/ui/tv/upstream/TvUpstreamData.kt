package com.github.andreyasadchy.xtra.ui.tv.upstream

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.graphql.type.VideoSort
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.repository.datasource.ChannelVideosDataSource
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.GameStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.GamesDataSource
import com.github.andreyasadchy.xtra.repository.datasource.SearchChannelsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.SearchStreamsDataSource
import com.github.andreyasadchy.xtra.repository.datasource.StreamsDataSource
import kotlinx.coroutines.flow.Flow

/**
 * The lists of the TV interface, built from the paging sources of the original. Their constructors are the part of
 * the original that changes most often: when one does, this is the only file to adapt.
 *
 * Every function takes a [track] callback that receives each paging source created, so the caller can invalidate
 * them all at once (e.g. after the account changed).
 * Part of the only layer of the TV interface that touches the original code (see CAMBIOS-TV.md, section 2).
 */
class TvUpstreamData(context: Context, private val config: PagingConfig) {

    private val applicationContext = context.applicationContext
    private val xtraModule get() = (applicationContext as XtraApp).xtraModule

    private val networkLibrary get() = TvUpstreamAccount.networkLibrary(applicationContext)
    private val enableIntegrity get() = TvUpstreamAccount.enableIntegrity(applicationContext)
    private val gqlHeaders get() = TvUpstreamAccount.gqlHeaders(applicationContext)
    private val helixHeaders get() = TvUpstreamAccount.helixHeaders(applicationContext)

    fun followedStreams(track: (PagingSource<*, *>) -> Unit): Flow<PagingData<Stream>> = Pager(config) {
        FollowedStreamsDataSource(
            userId = TvUpstreamAccount.userId(applicationContext),
            localChannelFollowsRepository = xtraModule.localChannelFollowsRepository,
            gqlHeaders = TvUpstreamAccount.gqlHeaders(applicationContext, true),
            graphQLRepository = xtraModule.graphQLRepository,
            helixHeaders = helixHeaders,
            helixRepository = xtraModule.helixRepository,
            enableIntegrity = enableIntegrity,
            networkLibrary = networkLibrary,
        ).also(track)
    }.flow

    fun popularStreams(track: (PagingSource<*, *>) -> Unit): Flow<PagingData<Stream>> = Pager(config) {
        StreamsDataSource(
            gqlQueryLanguages = null,
            gqlQuerySort = StreamSort.VIEWER_COUNT,
            gqlLanguages = null,
            gqlSort = "VIEWER_COUNT",
            tags = null,
            gqlHeaders = gqlHeaders,
            graphQLRepository = xtraModule.graphQLRepository,
            helixHeaders = helixHeaders,
            helixRepository = xtraModule.helixRepository,
            enableIntegrity = enableIntegrity,
            networkLibrary = networkLibrary,
        ).also(track)
    }.flow

    fun games(track: (PagingSource<*, *>) -> Unit): Flow<PagingData<Game>> = Pager(config) {
        GamesDataSource(
            tags = null,
            gqlHeaders = gqlHeaders,
            graphQLRepository = xtraModule.graphQLRepository,
            helixHeaders = helixHeaders,
            helixRepository = xtraModule.helixRepository,
            enableIntegrity = enableIntegrity,
            networkLibrary = networkLibrary,
        ).also(track)
    }.flow

    fun gameStreams(gameId: String?, gameSlug: String?, gameName: String?, track: (PagingSource<*, *>) -> Unit): Flow<PagingData<Stream>> = Pager(config) {
        GameStreamsDataSource(
            gameId = gameId,
            gameSlug = gameSlug,
            gameName = gameName,
            gqlQueryLanguages = null,
            gqlQuerySort = StreamSort.VIEWER_COUNT,
            gqlLanguages = null,
            gqlSort = "VIEWER_COUNT",
            tags = null,
            gqlHeaders = gqlHeaders,
            graphQLRepository = xtraModule.graphQLRepository,
            helixHeaders = helixHeaders,
            helixRepository = xtraModule.helixRepository,
            enableIntegrity = enableIntegrity,
            networkLibrary = networkLibrary,
        ).also(track)
    }.flow

    fun channelVideos(channelId: String?, channelLogin: String?, track: (PagingSource<*, *>) -> Unit): Flow<PagingData<Video>> = Pager(config) {
        ChannelVideosDataSource(
            channelId = channelId,
            channelLogin = channelLogin,
            gqlQueryType = null,
            gqlQuerySort = VideoSort.TIME,
            gqlType = null,
            gqlSort = "TIME",
            helixPeriod = "all",
            helixBroadcastTypes = "all",
            helixSort = "time",
            gqlHeaders = gqlHeaders,
            graphQLRepository = xtraModule.graphQLRepository,
            helixHeaders = helixHeaders,
            helixRepository = xtraModule.helixRepository,
            enableIntegrity = enableIntegrity,
            networkLibrary = networkLibrary,
        ).also(track)
    }.flow

    fun searchChannels(query: String, track: (PagingSource<*, *>) -> Unit): Flow<PagingData<User>> = Pager(config) {
        SearchChannelsDataSource(
            query = query,
            gqlHeaders = gqlHeaders,
            graphQLRepository = xtraModule.graphQLRepository,
            helixHeaders = helixHeaders,
            helixRepository = xtraModule.helixRepository,
            enableIntegrity = enableIntegrity,
            networkLibrary = networkLibrary,
        ).also(track)
    }.flow

    fun searchStreams(query: String, track: (PagingSource<*, *>) -> Unit): Flow<PagingData<Stream>> = Pager(config) {
        SearchStreamsDataSource(
            query = query,
            gqlHeaders = gqlHeaders,
            graphQLRepository = xtraModule.graphQLRepository,
            helixHeaders = helixHeaders,
            helixRepository = xtraModule.helixRepository,
            enableIntegrity = enableIntegrity,
            networkLibrary = networkLibrary,
        ).also(track)
    }.flow
}
