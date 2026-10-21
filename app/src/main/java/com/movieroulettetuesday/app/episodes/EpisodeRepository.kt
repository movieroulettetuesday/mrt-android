package com.movieroulettetuesday.app.episodes

import com.movieroulettetuesday.app.catalog.HttpStatusException
import com.movieroulettetuesday.app.data.AtomicFile
import com.movieroulettetuesday.app.data.KeyValueStore
import com.movieroulettetuesday.app.net.Http
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

object EpisodeFeed {
    const val URL = "https://rss.buzzsprout.com/2117292.rss"
    /** iOS: 20 s per-request timeout for the RSS fetch. */
    const val TIMEOUT_MS = 20_000
    const val CACHE_FILE = "episodes-cache.json"
    /** A network refresh newer than this is reused when a screen appears (iOS: 5 min). */
    const val FRESHNESS_MS = 5 * 60 * 1000L

    fun httpGet(url: String): ByteArray = Http.get(url, TIMEOUT_MS, accept = "application/rss+xml, application/xml, text/xml, */*")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun loadCache(file: File?): CachedEpisodeFeed? =
        file?.takeIf { it.isFile }?.let { runCatching { json.decodeFromString<CachedEpisodeFeed>(it.readText()) }.getOrNull() }

    fun saveCache(file: File?, cache: CachedEpisodeFeed) {
        file ?: return
        runCatching { AtomicFile.write(file, json.encodeToString(CachedEpisodeFeed.serializer(), cache).toByteArray()) }
    }

    fun friendlyMessage(error: Throwable): String = when (error) {
        is ConnectException, is NoRouteToHostException -> "You’re offline. Showing saved episodes."
        is SocketTimeoutException -> "Loading episodes took too long. Pull down to try again."
        is UnknownHostException -> "Couldn’t load episodes. Pull down to try again."
        is RssFeedException -> "Couldn’t read the episode list. Pull down to try again."
        is HttpStatusException -> "Couldn’t load episodes (HTTP ${error.code}). Pull down to try again."
        else -> "Couldn’t load episodes. Pull down to try again."
    }
}

/** Port of `EpisodeStore`: cache first (instant offline), then refresh from Buzzsprout. */
class EpisodeRepository(
    private val cacheFile: File?,
    private val settings: KeyValueStore,
    private val fetch: ((String) -> ByteArray)? = EpisodeFeed::httpGet,
    private val feedUrl: String = EpisodeFeed.URL,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called with every applied episode list (widget snapshot). */
    var onEpisodesApplied: ((List<PodcastEpisode>) -> Unit)? = null,
) {
    sealed interface LoadState {
        data object Idle : LoadState
        data object Loading : LoadState
        data class Loaded(val fromCache: Boolean) : LoadState
        data class Failed(val message: String) : LoadState
    }

    data class State(
        val episodes: List<PodcastEpisode> = emptyList(),
        val byGuid: Map<String, PodcastEpisode> = emptyMap(),
        val feedTitle: String = "",
        val showArtworkURL: String? = null,
        val loadState: LoadState = LoadState.Idle,
        val lastFetchedAt: Long? = null,
        val lastError: String? = null,
        val lastViewedAt: Long? = null,
    ) {
        val newEpisodeCount: Int get() = NewEpisodeBadge.count(episodes, lastViewedAt)
        fun episode(guid: String): PodcastEpisode? = byGuid[guid]
    }

    private val _state = MutableStateFlow(State(lastViewedAt = settings.getLong(LAST_VIEWED_KEY)))
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private var lastNetworkSuccess: Long? = null

    /** Overlapping calls share one load; a fresh network result is reused unless [forceNetwork]. */
    suspend fun load(forceNetwork: Boolean = false) {
        if (mutex.isLocked) {
            mutex.withLock { }
            return
        }
        mutex.withLock {
            val last = lastNetworkSuccess
            if (!forceNetwork && last != null && clock() - last < EpisodeFeed.FRESHNESS_MS) return
            if (!forceNetwork && _state.value.episodes.isEmpty()) {
                withContext(io) { EpisodeFeed.loadCache(cacheFile) }?.let { apply(it, fromCache = true) }
            }
            val fetch = fetch
            if (fetch == null) {
                if (_state.value.episodes.isEmpty()) _state.update { it.copy(loadState = LoadState.Failed(OFFLINE_MESSAGE)) }
                return
            }
            _state.update { it.copy(loadState = LoadState.Loading) }
            val result = withContext(io) {
                runCatching {
                    val feed = RssFeedParser.parse(fetch(feedUrl))
                    CachedEpisodeFeed(clock(), feed.title, feed.episodes, feed.artworkURL).also { EpisodeFeed.saveCache(cacheFile, it) }
                }
            }
            result.onSuccess {
                lastNetworkSuccess = clock()
                apply(it, fromCache = false)
            }.onFailure { e ->
                val message = EpisodeFeed.friendlyMessage(e)
                _state.update { s ->
                    if (s.episodes.isEmpty()) s.copy(loadState = LoadState.Failed(message))
                    else s.copy(lastError = message, loadState = LoadState.Loaded(fromCache = true))
                }
            }
        }
    }

    /** Clears the badge (no-op before the first load, which sets the baseline instead). */
    fun markViewed() {
        val eps = _state.value.episodes
        if (eps.isEmpty()) return
        setLastViewed(NewEpisodeBadge.viewedTimestamp(eps, clock()))
    }

    private fun setLastViewed(value: Long) {
        settings.putLong(LAST_VIEWED_KEY, value)
        _state.update { it.copy(lastViewedAt = value) }
    }

    private fun apply(cache: CachedEpisodeFeed, fromCache: Boolean) {
        val unique = PodcastEpisode.uniqued(cache.episodes)
        _state.update {
            it.copy(
                episodes = unique,
                byGuid = PodcastEpisode.byGUID(unique),
                feedTitle = cache.title,
                showArtworkURL = cache.artworkURL,
                lastFetchedAt = cache.fetchedAt,
                lastError = null,
                loadState = LoadState.Loaded(fromCache),
            )
        }
        if (_state.value.lastViewedAt == null && unique.isNotEmpty()) {
            setLastViewed(NewEpisodeBadge.initialBaseline(unique, clock()))
        }
        onEpisodesApplied?.invoke(unique)
    }

    companion object {
        const val LAST_VIEWED_KEY = "episodes.lastViewedAt"
        const val GROUPING_KEY = "episodes.grouping"
        const val EXPANDED_KEY = "episodes.expandedGroups"
        const val OFFLINE_MESSAGE = "Couldn’t load episodes. Pull down to try again."
    }
}

/**
 * Cached GUID -> Apple track id map (iOS `AppleEpisodeLinks`). Refreshes at most hourly, and only
 * when the requested GUID isn't matched yet.
 */
class AppleEpisodeLinks(
    private val cacheFile: File?,
    private val fetch: ((String) -> ByteArray)? = { Http.get(it, 15_000, accept = "application/json") },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @kotlinx.serialization.Serializable
    private data class Cache(val fetchedAt: Long, val trackIds: Map<String, Long>)

    private val json = Json { ignoreUnknownKeys = true }
    private val _trackIds = MutableStateFlow<Map<String, Long>>(emptyMap())
    val trackIds: StateFlow<Map<String, Long>> = _trackIds.asStateFlow()
    private var fetchedAt: Long? = null
    private val mutex = Mutex()
    private var loadedCache = false

    private fun loadCacheOnce() {
        if (loadedCache) return
        loadedCache = true
        cacheFile?.takeIf { it.isFile }?.let { f ->
            runCatching { json.decodeFromString<Cache>(f.readText()) }.getOrNull()?.let {
                _trackIds.value = it.trackIds
                fetchedAt = it.fetchedAt
            }
        }
    }

    suspend fun ensure(guid: String, show: com.movieroulettetuesday.app.catalog.ShowInfo = com.movieroulettetuesday.app.catalog.ShowInfo.MRT) {
        mutex.withLock {
            withContext(io) { loadCacheOnce() }
            if (_trackIds.value[guid] != null) return
            fetchedAt?.let { if (clock() - it < MIN_REFRESH_MS) return }
            val fetch = fetch ?: return
            val url = AppleEpisodeLookup.lookupURL(show) ?: return
            fetchedAt = clock()
            val map = withContext(io) { runCatching { AppleEpisodeLookup.parse(fetch(url), show) }.getOrNull() } ?: return
            if (map.isEmpty()) return
            _trackIds.value = map
            withContext(io) {
                cacheFile?.let { f -> runCatching { AtomicFile.write(f, json.encodeToString(Cache.serializer(), Cache(fetchedAt ?: clock(), map)).toByteArray()) } }
            }
        }
    }

    companion object {
        const val MIN_REFRESH_MS = 60 * 60 * 1000L
        const val CACHE_FILE = "apple-episode-lookup.json"
    }
}
