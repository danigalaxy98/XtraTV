package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.XtraModule
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
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

class TvBrowseViewModel(
    private val applicationContext: Context,
    private val xtraModule: XtraModule,
) : ViewModel() {

    /** Incremented to ask every visible list to reload. */
    val refreshTick = MutableStateFlow(0)

    /** Last successful load time per list, used for the "updated" label and stale checks. */
    val lastUpdated = mutableStateMapOf<String, Long>()

    /** Lists that are reloading right now. */
    val refreshing = mutableStateMapOf<String, Boolean>()

    fun requestRefresh() {
        refreshTick.value++
    }

    /** Last tick handled by each list, so a list shown again still catches up with a reload asked meanwhile. */
    private val handledTicks = mutableMapOf<String, Int>()

    /** Lists whose next finished load comes from an explicit reload (not from cached pages shown again). */
    private val reloadRequested = mutableSetOf<String>()

    /** True when [key] has not reloaded for [tick] yet; the first call for a key only registers it. */
    fun takePendingTick(key: String, tick: Int): Boolean {
        val handled = handledTicks.put(key, tick)
        return handled != null && handled < tick
    }

    fun markReloadRequested(key: String) {
        reloadRequested.add(key)
    }

    fun consumeReloadRequested(key: String): Boolean = reloadRequested.remove(key)

    private val trackedSources = mutableSetOf<PagingSource<*, *>>()

    private fun <S : PagingSource<*, *>> S.tracked(): S {
        synchronized(trackedSources) { trackedSources.add(this) }
        registerInvalidatedCallback { synchronized(trackedSources) { trackedSources.remove(this) } }
        return this
    }

    /** Drops the loaded pages of every list (shown or not), e.g. after the account changed. */
    fun reloadAll() {
        reloadRequested.addAll(handledTicks.keys)
        val sources = synchronized(trackedSources) { trackedSources.toList() }
        sources.forEach { it.invalidate() }
    }

    /** Identifies the current account and tokens, to detect a session change. */
    fun sessionKey(): String {
        return listOf(
            applicationContext.tokenPrefs().getString(C.USER_ID, null),
            TwitchApiHelper.getGQLHeaders(applicationContext, true)[C.HEADER_TOKEN],
            TwitchApiHelper.getHelixHeaders(applicationContext)[C.HEADER_TOKEN],
        ).joinToString("|")
    }

    /** Age after which a list reloads when shown again; 0 when automatic refresh is disabled. */
    fun staleMs(): Long = TvPrefs.autoRefreshMs(applicationContext)

    private val pagingConfig = PagingConfig(pageSize = 30, prefetchDistance = 8, initialLoadSize = 30)

    private val networkLibrary get() = applicationContext.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
    private val enableIntegrity get() = applicationContext.prefs().getBoolean(C.ENABLE_INTEGRITY, false)
    private val gqlHeaders get() = TwitchApiHelper.getGQLHeaders(applicationContext)
    private val helixHeaders get() = TwitchApiHelper.getHelixHeaders(applicationContext)

    val followedStreams: Flow<PagingData<Stream>> = Pager(pagingConfig) {
        FollowedStreamsDataSource(
            userId = applicationContext.tokenPrefs().getString(C.USER_ID, null),
            localChannelFollowsRepository = xtraModule.localChannelFollowsRepository,
            gqlHeaders = TwitchApiHelper.getGQLHeaders(applicationContext, true),
            graphQLRepository = xtraModule.graphQLRepository,
            helixHeaders = helixHeaders,
            helixRepository = xtraModule.helixRepository,
            enableIntegrity = enableIntegrity,
            networkLibrary = networkLibrary,
        ).tracked()
    }.flow.cachedIn(viewModelScope)

    val popularStreams: Flow<PagingData<Stream>> = Pager(pagingConfig) {
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
        ).tracked()
    }.flow.cachedIn(viewModelScope)

    val games: Flow<PagingData<Game>> = Pager(pagingConfig) {
        GamesDataSource(
            tags = null,
            gqlHeaders = gqlHeaders,
            graphQLRepository = xtraModule.graphQLRepository,
            helixHeaders = helixHeaders,
            helixRepository = xtraModule.helixRepository,
            enableIntegrity = enableIntegrity,
            networkLibrary = networkLibrary,
        ).tracked()
    }.flow.cachedIn(viewModelScope)

    private val gameStreamsCache = mutableMapOf<String, Flow<PagingData<Stream>>>()

    fun gameStreams(gameId: String?, gameSlug: String?, gameName: String?): Flow<PagingData<Stream>> {
        val key = gameId ?: gameSlug ?: gameName.orEmpty()
        return gameStreamsCache.getOrPut(key) {
            Pager(pagingConfig) {
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
                ).tracked()
            }.flow.cachedIn(viewModelScope)
        }
    }

    private val channelVideosCache = mutableMapOf<String, Flow<PagingData<Video>>>()

    fun channelVideos(channelId: String?, channelLogin: String?): Flow<PagingData<Video>> {
        val key = channelId ?: channelLogin.orEmpty()
        return channelVideosCache.getOrPut(key) {
            Pager(pagingConfig) {
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
                ).tracked()
            }.flow.cachedIn(viewModelScope)
        }
    }

    val searchQuery = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchChannels: Flow<PagingData<User>> = searchQuery.debounce(600).flatMapLatest { query ->
        if (query.isBlank()) {
            flowOf(PagingData.empty())
        } else {
            Pager(pagingConfig) {
                SearchChannelsDataSource(
                    query = query,
                    gqlHeaders = gqlHeaders,
                    graphQLRepository = xtraModule.graphQLRepository,
                    helixHeaders = helixHeaders,
                    helixRepository = xtraModule.helixRepository,
                    enableIntegrity = enableIntegrity,
                    networkLibrary = networkLibrary,
                ).tracked()
            }.flow
        }
    }.cachedIn(viewModelScope)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchStreams: Flow<PagingData<Stream>> = searchQuery.debounce(600).flatMapLatest { query ->
        if (query.isBlank()) {
            flowOf(PagingData.empty())
        } else {
            Pager(pagingConfig) {
                SearchStreamsDataSource(
                    query = query,
                    gqlHeaders = gqlHeaders,
                    graphQLRepository = xtraModule.graphQLRepository,
                    helixHeaders = helixHeaders,
                    helixRepository = xtraModule.helixRepository,
                    enableIntegrity = enableIntegrity,
                    networkLibrary = networkLibrary,
                ).tracked()
            }.flow
        }
    }.cachedIn(viewModelScope)

    fun isLoggedIn(): Boolean {
        return !TwitchApiHelper.getGQLHeaders(applicationContext, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(applicationContext)[C.HEADER_TOKEN].isNullOrBlank()
    }

    companion object {
        /** Lists older than this are reloaded when shown again or after the remote is left idle. */
        const val STALE_MS = 2 * 60 * 1000L

        val TvBrowseViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                TvBrowseViewModel(application.applicationContext, application.xtraModule)
            }
        }
    }
}
