package com.movieroulettetuesday.app.catalog

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App wiring for the movie list (iOS AppCatalog + the catalog parts of RouletteViewModel):
 * show the cached remote copy (or the bundled asset) at once, then refresh from GitHub in the
 * background. Refreshes closer than [minRefreshIntervalMs] are skipped (launch + pull-to-refresh).
 */
class CatalogRepository(
    private val cache: CatalogCache?,
    private val bundled: () -> ByteArray,
    private val refresher: CatalogRefresher?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minRefreshIntervalMs: Long = 15_000,
) {
    sealed interface State {
        data object Loading : State
        data class Loaded(val catalog: MovieCatalog) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    val catalog: MovieCatalog? get() = (_state.value as? State.Loaded)?.catalog

    private val loadMutex = Mutex()
    private val refreshMutex = Mutex()
    private var lastRefreshAt: Long? = null

    /** Loads once (cache, else bundled). Never touches the network. */
    suspend fun load(): MovieCatalog? = loadMutex.withLock {
        catalog?.let { return it }
        val loaded = withContext(io) { runCatching { LocalCatalog.load(cache, bundled) }.getOrNull() }
        if (loaded == null || loaded.categories.isEmpty()) {
            if (catalog == null) _state.value = State.Failed(LOAD_FAILED)
            return catalog
        }
        // A remote refresh may have landed first; it is newer, keep it.
        if (catalog == null) _state.value = State.Loaded(loaded.deduplicated())
        catalog
    }

    /** Best-effort GitHub refresh. Returns true when a new, different list was applied. */
    suspend fun refresh(): Boolean {
        val refresher = refresher ?: return false
        return refreshMutex.withLock {
            val now = clock()
            lastRefreshAt?.let { if (now - it < minRefreshIntervalMs) return false }
            lastRefreshAt = now
            val fresh = withContext(io) { runCatching { refresher.refresh(now) }.getOrNull() } ?: return false
            val clean = fresh.deduplicated()
            if (clean == catalog) return false
            _state.value = State.Loaded(clean)
            true
        }
    }

    companion object {
        const val LOAD_FAILED = "The movie list couldn’t be loaded. Check your connection and try again."

        fun create(context: Context, build: String): CatalogRepository {
            val app = context.applicationContext
            val cache = CatalogCache(File(app.filesDir, CatalogCache.FILE_NAME), build)
            return CatalogRepository(
                cache = cache,
                bundled = { app.assets.open(RemoteCatalog.BUNDLED_ASSET).use { it.readBytes() } },
                refresher = CatalogRefresher(RemoteCatalogSource(), cache),
            )
        }
    }
}
