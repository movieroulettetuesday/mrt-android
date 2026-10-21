package com.movieroulettetuesday.app

import android.app.Application
import com.movieroulettetuesday.app.catalog.CatalogRepository

/** Process-wide singletons (no DI framework yet). */
class MrtApplication : Application() {
    /** Cache written by another versionCode is ignored, like iOS's CFBundleVersion check. */
    val catalogRepository: CatalogRepository by lazy {
        CatalogRepository.create(this, build = BuildConfig.VERSION_CODE.toString())
    }
}
