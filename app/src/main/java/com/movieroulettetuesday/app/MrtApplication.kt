package com.movieroulettetuesday.app

import android.app.Application
import android.os.Build
import com.movieroulettetuesday.app.catalog.CatalogRepository
import com.movieroulettetuesday.app.data.KeyValueStore
import com.movieroulettetuesday.app.data.PrefsKeyValueStore
import com.movieroulettetuesday.app.episodes.AppleEpisodeLinks
import com.movieroulettetuesday.app.episodes.EpisodeFeed
import com.movieroulettetuesday.app.episodes.EpisodeRepository
import com.movieroulettetuesday.app.ui.ArtworkLoader
import com.movieroulettetuesday.app.roulette.SpinHistoryStore
import java.io.File

/** Process-wide singletons (no DI framework yet). */
class MrtApplication : Application() {
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
    }

    /** Local app settings (iOS UserDefaults / @AppStorage). */
    val settings: KeyValueStore by lazy { PrefsKeyValueStore.of(this) }

    /** Buzzsprout feed; offline (no fetch, no cache) under tests. */
    val episodeRepository: EpisodeRepository by lazy {
        EpisodeRepository(
            cacheFile = if (isUnderTest) null else File(filesDir, EpisodeFeed.CACHE_FILE),
            settings = settings,
            fetch = if (isUnderTest) null else EpisodeFeed::httpGet,
        )
    }

    val appleEpisodeLinks: AppleEpisodeLinks by lazy {
        if (isUnderTest) AppleEpisodeLinks(null, fetch = null) else AppleEpisodeLinks(File(cacheDir, AppleEpisodeLinks.CACHE_FILE))
    }

    val spinHistory: SpinHistoryStore by lazy {
        SpinHistoryStore(if (isUnderTest) null else File(filesDir, SpinHistoryStore.FILE_NAME))
    }
}
