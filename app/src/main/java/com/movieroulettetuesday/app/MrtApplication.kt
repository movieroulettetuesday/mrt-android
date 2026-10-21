package com.movieroulettetuesday.app

import android.app.Application
import android.os.Build
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.community.CommunityConfig
import com.movieroulettetuesday.app.community.CommunityService
import com.movieroulettetuesday.app.community.CommunityStore
import com.movieroulettetuesday.app.community.InstallId
import com.movieroulettetuesday.app.community.PollOverrides
import com.movieroulettetuesday.app.community.WebCommunityStore
import com.movieroulettetuesday.app.data.KeyValueStore
import com.movieroulettetuesday.app.data.PrefsKeyValueStore
import com.movieroulettetuesday.app.episodes.AppleEpisodeLinks
import com.movieroulettetuesday.app.episodes.EpisodeFeed
import com.movieroulettetuesday.app.episodes.EpisodeRepository
import com.movieroulettetuesday.app.ui.ArtworkLoader
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import com.movieroulettetuesday.app.movies.MyServices
import com.movieroulettetuesday.app.movies.PersonalRatingsStore
import com.movieroulettetuesday.app.settings.AppPreferences
import com.movieroulettetuesday.app.episodes.RssFeedParser
import com.movieroulettetuesday.app.notifications.NewEpisodeChecker
import com.movieroulettetuesday.app.notifications.NewEpisodeNotifier
import com.movieroulettetuesday.app.notifications.NewEpisodeSchedule
import com.movieroulettetuesday.app.notifications.NewEpisodeWatermarkStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide singletons (no DI framework yet). */
class MrtApplication : Application() {
    /** App-lifetime work (catalog refresh after a feed fetch). */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)

    /** Unit tests (Robolectric) stay on the bundled list: deterministic and offline (iOS: bundledOnly). */
    val isUnderTest: Boolean get() = Build.FINGERPRINT == "robolectric"

    /** Cache written by another versionCode is ignored, like iOS's CFBundleVersion check. */
    val catalogRepository: CatalogRepository by lazy {
        CatalogRepository.create(this, build = BuildConfig.VERSION_CODE.toString(), networkEnabled = !isUnderTest)
    }

    override fun onCreate() {
        super.onCreate()
        ArtworkLoader.enabled = !isUnderTest
        ArtworkLoader.diskDir = File(cacheDir, "artwork")
        if (!isUnderTest && preferences.newEpisodeAlerts.value) NewEpisodeSchedule.schedule(this) // KEEP: no reset
        val config = if (isUnderTest) CommunityConfig.disabled
        else CommunityConfig.fromBuild(BuildConfig.MRT_CLOUDKIT_API_TOKEN, BuildConfig.MRT_RELAY_URL)
        if (config.canRead) {
            community = createCommunity(WebCommunityStore(config, installId = { InstallId(settings).value }))
            // Jackpot averages: one bulk read once the catalog has loaded (iOS 56606a6).
            appScope.launch {
                val loaded = catalogRepository.state.first { it is CatalogRepository.State.Loaded } as CatalogRepository.State.Loaded
                refreshListenerAverages(loaded.catalog)
            }
        }
    }

    private suspend fun refreshListenerAverages(catalog: com.movieroulettetuesday.app.catalog.MovieCatalog?) {
        val c = community ?: return
        catalog ?: return
        c.refreshListenerAverages(catalog.movies.map { it.id })
    }

    /** Listener averages for `RatingWeightedSelector` (swap in when iOS 1.0.1 ships; default stays iOS main). */
    val listenerAverages: () -> Map<String, Double> = { community?.knownAverages().orEmpty() }

    /**
     * Listener polls + ratings (Option A: CloudKit Web Services reads, relay writes). null = this
     * build has no CloudKit API token, so every community UI stays hidden. Tests can set it.
     */
    var community: CommunityService? = null

    fun createCommunity(store: CommunityStore): CommunityService {
        val overrides = runCatching { assets.open("polls.json").use { PollOverrides.decode(it.readBytes().decodeToString()) } }.getOrNull()
        return CommunityService(store, settings, overrides)
    }

    val appVersion: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** Local app settings (iOS UserDefaults / @AppStorage). */
    val settings: KeyValueStore by lazy { PrefsKeyValueStore.of(this) }

    /** Buzzsprout feed; offline (no fetch, no cache) under tests. */
    val episodeRepository: EpisodeRepository by lazy {
        EpisodeRepository(
            cacheFile = if (isUnderTest) null else File(filesDir, EpisodeFeed.CACHE_FILE),
            settings = settings,
            fetch = if (isUnderTest) null else EpisodeFeed::httpGet,
        ).also { repo ->
            // iOS ContentView: episodes already visible in the app never alert later.
            repo.onEpisodesApplied = {
                if (preferences.newEpisodeAlerts.value) episodeWatermarks.markSeen(it)
                widgetBridge.publishLatestEpisode(it)
            }
            // iOS ContentView: the catalog refreshes at launch and after each feed fetch (no foreground trigger).
            repo.onNetworkFetched = {
                appScope.launch {
                    if (catalogRepository.refresh()) widgetBridge.catalogUpdated()
                    refreshListenerAverages((catalogRepository.state.value as? CatalogRepository.State.Loaded)?.catalog)
                }
            }
        }
    }

    val episodeWatermarks: NewEpisodeWatermarkStore by lazy { NewEpisodeWatermarkStore(settings) }
    val episodeNotifier: NewEpisodeNotifier by lazy { NewEpisodeNotifier(this) }

    /** Background new-episode check; replaceable in tests. */
    var newEpisodeChecker: NewEpisodeChecker
        get() = checkerOverride ?: NewEpisodeChecker(
            preferences, episodeWatermarks,
            fetch = {
                if (isUnderTest) error("offline under tests")
                withContext(Dispatchers.IO) { RssFeedParser.parse(EpisodeFeed.httpGet(EpisodeFeed.URL)).episodes }
            },
            notifier = episodeNotifier,
        ).also { checkerOverride = it }
        set(value) { checkerOverride = value }
    private var checkerOverride: NewEpisodeChecker? = null

    /** Home-screen widget data (latest episode, last spin); no widget refresh under tests. */
    val widgetBridge: com.movieroulettetuesday.app.widget.WidgetBridge by lazy {
        if (isUnderTest) {
            com.movieroulettetuesday.app.widget.WidgetBridge(
                com.movieroulettetuesday.app.widget.WidgetSnapshotStore(File(cacheDir, "test-" + com.movieroulettetuesday.app.widget.WidgetSnapshotStore.FILE_NAME)),
                refresh = {},
            )
        } else {
            com.movieroulettetuesday.app.widget.WidgetBridge.create(this)
        }
    }

    /** A widget tap asks the UI to open this movie (iOS `mrt://movie/<id>`). */
    val pendingMovie = MutableStateFlow<String?>(null)

    /** A tapped notification asks the UI to open this episode (iOS AppRouter). */
    val pendingEpisode = MutableStateFlow<String?>(null)

    val appleEpisodeLinks: AppleEpisodeLinks by lazy {
        if (isUnderTest) AppleEpisodeLinks(null, fetch = null) else AppleEpisodeLinks(File(cacheDir, AppleEpisodeLinks.CACHE_FILE))
    }

    val personalRatings: PersonalRatingsStore by lazy {
        PersonalRatingsStore(
            if (isUnderTest) null else File(filesDir, PersonalRatingsStore.FILE_NAME),
        )
    }

    /** Settings › My Services (provider ids), mirrored into a flow for the Movies tab. */
    val myServices: MutableStateFlow<MyServices> by lazy {
        MutableStateFlow(
            MyServices.parse(settings.getString(MyServices.STORAGE_KEY)),
        )
    }

    fun setMyServices(services: MyServices) {
        settings.putString(MyServices.STORAGE_KEY, services.serialized)
        myServices.value = services
    }

    val preferences: AppPreferences by lazy { AppPreferences(settings) }

    /** wheel-spin.m4a player; silent under tests. */
    val spinSound: com.movieroulettetuesday.app.roulette.SpinFeedback by lazy {
        if (isUnderTest) com.movieroulettetuesday.app.roulette.SpinFeedback.None else com.movieroulettetuesday.app.roulette.SpinSoundPlayer(this)
    }

    val spinHistory: SpinHistoryStore by lazy {
        SpinHistoryStore(if (isUnderTest) null else File(filesDir, SpinHistoryStore.FILE_NAME))
    }
}
