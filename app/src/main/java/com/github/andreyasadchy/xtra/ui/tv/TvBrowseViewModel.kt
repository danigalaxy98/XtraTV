package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.tv.upstream.TvUpstreamAccount
import com.github.andreyasadchy.xtra.ui.tv.upstream.TvUpstreamData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

class TvBrowseViewModel(
    private val applicationContext: Context,
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

    private fun track(source: PagingSource<*, *>) {
        synchronized(trackedSources) { trackedSources.add(source) }
        source.registerInvalidatedCallback { synchronized(trackedSources) { trackedSources.remove(source) } }
    }

    /** Drops the loaded pages of every list (shown or not), e.g. after the account changed. */
    fun reloadAll() {
        reloadRequested.addAll(handledTicks.keys)
        val sources = synchronized(trackedSources) { trackedSources.toList() }
        sources.forEach { it.invalidate() }
    }

    /** Identifies the current account and tokens, to detect a session change. */
    fun sessionKey(): String = TvUpstreamAccount.sessionKey(applicationContext)

    /** Age after which a list reloads when shown again; 0 when automatic refresh is disabled. */
    fun staleMs(): Long = TvPrefs.autoRefreshMs(applicationContext)

    private val pagingConfig = PagingConfig(pageSize = 30, prefetchDistance = 8, initialLoadSize = 30)

    private val data = TvUpstreamData(applicationContext, pagingConfig)

    val followedStreams: Flow<PagingData<Stream>> = data.followedStreams(::track).cachedIn(viewModelScope)

    val popularStreams: Flow<PagingData<Stream>> = data.popularStreams(::track).cachedIn(viewModelScope)

    val games: Flow<PagingData<Game>> = data.games(::track).cachedIn(viewModelScope)

    private val gameStreamsCache = mutableMapOf<String, Flow<PagingData<Stream>>>()

    fun gameStreams(gameId: String?, gameSlug: String?, gameName: String?): Flow<PagingData<Stream>> {
        val key = gameId ?: gameSlug ?: gameName.orEmpty()
        return gameStreamsCache.getOrPut(key) {
            data.gameStreams(gameId, gameSlug, gameName, ::track).cachedIn(viewModelScope)
        }
    }

    private val channelVideosCache = mutableMapOf<String, Flow<PagingData<Video>>>()

    fun channelVideos(channelId: String?, channelLogin: String?): Flow<PagingData<Video>> {
        val key = channelId ?: channelLogin.orEmpty()
        return channelVideosCache.getOrPut(key) {
            data.channelVideos(channelId, channelLogin, ::track).cachedIn(viewModelScope)
        }
    }

    val searchQuery = MutableStateFlow("")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchChannels: Flow<PagingData<User>> = searchQuery.debounce(600).flatMapLatest { query ->
        if (query.isBlank()) flowOf(PagingData.empty()) else data.searchChannels(query, ::track)
    }.cachedIn(viewModelScope)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchStreams: Flow<PagingData<Stream>> = searchQuery.debounce(600).flatMapLatest { query ->
        if (query.isBlank()) flowOf(PagingData.empty()) else data.searchStreams(query, ::track)
    }.cachedIn(viewModelScope)

    fun isLoggedIn(): Boolean = TvUpstreamAccount.isLoggedIn(applicationContext)

    companion object {
        /** Lists older than this are reloaded when shown again or after the remote is left idle. */
        const val STALE_MS = 2 * 60 * 1000L

        val TvBrowseViewModelFactory = viewModelFactory {
            initializer {
                TvBrowseViewModel(this[APPLICATION_KEY]!!.applicationContext)
            }
        }
    }
}
