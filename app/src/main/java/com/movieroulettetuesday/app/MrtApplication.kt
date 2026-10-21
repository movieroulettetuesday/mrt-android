package com.movieroulettetuesday.app

import android.app.Application
import android.os.Build
import com.movieroulettetuesday.app.catalog.CatalogRepository
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

    val spinHistory: SpinHistoryStore by lazy {
        SpinHistoryStore(if (isUnderTest) null else File(filesDir, SpinHistoryStore.FILE_NAME))
    }
}
