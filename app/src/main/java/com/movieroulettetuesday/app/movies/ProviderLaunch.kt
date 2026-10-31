package com.movieroulettetuesday.app.movies

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import com.movieroulettetuesday.app.catalog.Movie
import com.movieroulettetuesday.app.catalog.StreamingInfo
import com.movieroulettetuesday.app.net.QueryEncoding
import java.util.Locale

/**
 * Opens a Where-to-Watch provider the way tvOS `TVProviderLaunching` does, on Android:
 * the provider's app when it is installed, otherwise an https search or listing.
 * An unknown provider opens the movie's TMDB watch page, or a web search, and never toasts.
 *
 * Phone packages are listed before the Android TV package of the same service. The manifest
 * `<queries>` block must name every package in [PACKAGES] or API 30+ will hide the install.
 */
object ProviderLaunch {
    data class Plan(
        /** Play-store application ids, phone first. Empty when we have no app to try. */
        val packages: List<String>,
        /** Tried with [Intent.setPackage] while one of [packages] is installed. */
        val appUris: List<String>,
        /** Always https. Browser listing used when no app handles the tap. */
        val webUri: String,
    )

    /** Every package [plan] can ask about. Keep in step with AndroidManifest `<queries>`. */
    val PACKAGES: List<String> = listOf(
        "com.netflix.mediaclient",
        "com.netflix.ninja",
        "com.amazon.avod.thirdpartyclient",
        "com.amazon.amazonvideo.livingroom",
        "com.wbd.stream",
        "com.apple.atve.androidtv.appletv",
        "com.disney.disneyplus",
        "com.hulu.plus",
        "com.cbs.ott",
        "com.peacocktv.peacockandroid",
        "com.tubitv",
        "com.google.android.youtube",
        "com.google.android.youtube.tv",
        "com.google.android.apps.youtube.unplugged",
        "com.mubi",
        "tv.pluto.android",
        "com.dramafever.shudder",
        "com.google.android.videos",
        "air.com.vudu.air.DownloaderTablet",
        "com.plexapp.android",
    )

    fun plan(provider: StreamingInfo.Provider, movie: Movie): Plan {
        val title = movie.title
        val route = route(provider)
        if (route == null) {
            return Plan(emptyList(), emptyList(), fallback(title, provider.name, WhereToWatchDisplay.of(movie).link))
        }
        val web = route.web(title)
        return Plan(route.packages, route.appUris(title), web)
    }

    /**
     * Intents in the order [open] tries them. [launchIntent] is the app's launcher activity,
     * included only when [installedPackage] is non-null.
     */
    fun candidateIntents(plan: Plan, installedPackage: String?, launchIntent: Intent?): List<Intent> {
        val out = ArrayList<Intent>(plan.appUris.size + 2)
        if (installedPackage != null) {
            for (uri in plan.appUris) out += view(uri, installedPackage)
            if (launchIntent != null) out += launchIntent
        }
        out += view(plan.webUri, null)
        return out
    }

    /** First package from [plan] that is installed, phone package before the TV package. */
    fun installedPackage(context: Context, plan: Plan): String? =
        plan.packages.firstOrNull { isInstalled(context, it) }

    /** Starts the first intent something accepts. Returns false with no toast when nothing does. */
    fun open(context: Context, plan: Plan): Boolean {
        val installed = installedPackage(context, plan)
        val launch = installed?.let { context.packageManager.getLaunchIntentForPackage(it) }
        for (intent in candidateIntents(plan, installed, launch)) {
            if (start(context, intent)) return true
        }
        return false
    }

    fun open(context: Context, provider: StreamingInfo.Provider, movie: Movie): Boolean =
        open(context, plan(provider, movie))

    private fun isInstalled(context: Context, packageName: String): Boolean =
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun start(context: Context, intent: Intent): Boolean =
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }

    private fun view(uri: String, packageName: String?): Intent =
        Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            if (packageName != null) setPackage(packageName)
        }

    private class Route(
        val packages: List<String>,
        val appUris: (String) -> List<String>,
        val web: (String) -> String,
    )

    /** Same id / name checks as tvOS `TVProviderLaunching.kind`, then a few Android storefronts. */
    private fun route(provider: StreamingInfo.Provider): Route? {
        val name = provider.name.lowercase(Locale.ROOT)
        val id = provider.id
        if (id == 8 || name.contains("netflix")) return netflix
        if (id == 9 || id == 10 || name.contains("prime") || name.contains("amazon video") || name == "amazon") return prime
        if (id == 1899 || id == 384 || name == "max" || name.contains("hbo")) return max
        if (id == 2 || id == 350 || name.contains("apple")) return apple
        if (id == 337 || name.contains("disney")) return disney
        if (id == 15 || name.contains("hulu")) return hulu
        if (id == 531 || name.contains("paramount")) return paramount
        if (id == 386 || id == 387 || name.contains("peacock")) return peacock
        if (id == 73 || name.contains("tubi")) return tubi
        if (id == 258 || name.contains("criterion")) return criterion
        if (name.contains("youtube tv")) return youtubeTv
        if (id == 192 || name.contains("youtube")) return youtube
        if (id == 11 || name.contains("mubi")) return mubi
        if (id == 300 || name.contains("pluto")) return pluto
        if (id == 99 || name.contains("shudder")) return shudder
        if (id == 361 || name.contains("tcm")) return tcm
        if (id == 3 || name.contains("google play")) return googlePlay
        if (id == 7 || id == 332 || name.contains("fandango") || name == "vudu") return fandango
        if (id == 538 || id == 2077 || name.contains("plex")) return plex
        return null
    }

    private fun q(title: String) = QueryEncoding.value(title)

    private fun https(url: String, appUris: (String) -> List<String> = { listOf(urlFor(url, it)) }, packages: List<String>): Route =
        Route(packages, appUris, web = { urlFor(url, it) })

    /** [template] contains `{q}`, replaced with the encoded title. */
    private fun urlFor(template: String, title: String) = template.replace("{q}", q(title))

    private val netflix = Route(
        packages = listOf("com.netflix.mediaclient", "com.netflix.ninja"),
        appUris = { t ->
            val search = "https://www.netflix.com/search?q=${q(t)}"
            listOf(search, "nflx://www.netflix.com/search?q=${q(t)}")
        },
        web = { t -> "https://www.netflix.com/search?q=${q(t)}" },
    )
    private val prime = Route(
        packages = listOf("com.amazon.avod.thirdpartyclient", "com.amazon.amazonvideo.livingroom"),
        appUris = { t ->
            listOf(
                "https://app.primevideo.com/search?phrase=${q(t)}",
                "https://www.amazon.com/s?i=instant-video&k=${q(t)}",
                "aiv://aiv/search?query=${q(t)}",
            )
        },
        web = { t -> "https://app.primevideo.com/search?phrase=${q(t)}" },
    )
    private val max = https(
        "https://play.max.com/search?q={q}",
        packages = listOf("com.wbd.stream"),
    )
    private val apple = Route(
        packages = listOf("com.apple.atve.androidtv.appletv"),
        appUris = { t -> listOf("https://tv.apple.com/us/search?term=${q(t)}", "videos://") },
        web = { t -> "https://tv.apple.com/us/search?term=${q(t)}" },
    )
    private val disney = Route(
        packages = listOf("com.disney.disneyplus"),
        appUris = { t -> listOf("https://www.disneyplus.com/search?q=${q(t)}", "disneyplus://") },
        web = { t -> "https://www.disneyplus.com/search?q=${q(t)}" },
    )
    private val hulu = Route(
        packages = listOf("com.hulu.plus"),
        appUris = { t -> listOf("https://www.hulu.com/search?q=${q(t)}", "hulu://") },
        web = { t -> "https://www.hulu.com/search?q=${q(t)}" },
    )
    private val paramount = https(
        "https://www.paramountplus.com/search/?q={q}",
        packages = listOf("com.cbs.ott"),
    )
    private val peacock = https(
        "https://www.peacocktv.com/search?q={q}",
        packages = listOf("com.peacocktv.peacockandroid"),
    )
    private val tubi = Route(
        packages = listOf("com.tubitv"),
        appUris = { t -> listOf("https://tubitv.com/search/${q(t)}") },
        web = { t -> "https://tubitv.com/search/${q(t)}" },
    )
    private val criterion = https("https://www.criterionchannel.com/search?q={q}", packages = emptyList())
    private val youtubeTv = https(
        "https://tv.youtube.com/search?q={q}",
        packages = listOf("com.google.android.apps.youtube.unplugged"),
    )
    private val youtube = Route(
        packages = listOf("com.google.android.youtube", "com.google.android.youtube.tv"),
        appUris = { t ->
            val search = "https://www.youtube.com/results?search_query=${q(t)}"
            listOf(search, "youtube://www.youtube.com/results?search_query=${q(t)}")
        },
        web = { t -> "https://www.youtube.com/results?search_query=${q(t)}" },
    )
    private val mubi = https(
        "https://mubi.com/en/search/films?query={q}",
        packages = listOf("com.mubi"),
    )
    private val pluto = https(
        "https://pluto.tv/search?q={q}",
        packages = listOf("tv.pluto.android"),
    )
    private val shudder = https(
        "https://www.shudder.com/search?q={q}",
        packages = listOf("com.dramafever.shudder"),
    )
    private val tcm = https("https://www.tcm.com/search?q={q}", packages = emptyList())
    private val googlePlay = https(
        "https://play.google.com/store/search?q={q}&c=movies",
        packages = listOf("com.google.android.videos"),
    )
    private val fandango = https(
        "https://athome.fandango.com/content/browse/search?searchString={q}",
        packages = listOf("air.com.vudu.air.DownloaderTablet"),
    )
    private val plex = https(
        "https://watch.plex.tv/search?q={q}",
        packages = listOf("com.plexapp.android"),
    )

    /** TMDB watch page when we have one, otherwise "{title} on {provider}". */
    private fun fallback(title: String, providerName: String, watchLink: String?): String {
        if (watchLink != null && watchLink.startsWith("https://")) return watchLink
        val query = "$title on $providerName".trim()
        return "https://www.google.com/search?q=${q(query)}"
    }
}
