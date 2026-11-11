package com.movieroulettetuesday.app.actions

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * `mrt://` routes into [com.movieroulettetuesday.app.MainActivity]. Path segments are decoded.
 * Host is the verb (`spin`, `movie`, `episode`, `watchlist`, `wtw`, `suggest`, `latest`, `watched`).
 */
sealed class DeepLink {
    data object Spin : DeepLink()
    data class OpenMovie(val id: String) : DeepLink()
    data class OpenEpisode(val guid: String) : DeepLink()
    data object LatestEpisode : DeepLink()
    data object Watchlist : DeepLink()
    data class AddToWatchlist(val id: String) : DeepLink()
    data class MarkWatched(val id: String) : DeepLink()
    data class WhereToWatch(val id: String) : DeepLink()
    data class OpenOnProvider(val id: String, val provider: String) : DeepLink()
    data object Suggest : DeepLink()
    /** Title (and optional year) lookup for App Actions `GET_THING`. */
    data class FindMovie(val title: String, val year: Int? = null) : DeepLink()

    fun toUri(): Uri = when (this) {
        Spin -> uri(HOST_SPIN)
        is OpenMovie -> uri(HOST_MOVIE, id)
        is OpenEpisode -> uri(HOST_EPISODE, guid)
        LatestEpisode -> uri(HOST_LATEST)
        Watchlist -> uri(HOST_WATCHLIST)
        is AddToWatchlist -> uri(HOST_WATCHLIST, SEG_ADD, id)
        is MarkWatched -> uri(HOST_WATCHED, id)
        is WhereToWatch -> uri(HOST_WTW, id)
        is OpenOnProvider -> uri(HOST_MOVIE, id, SEG_PROVIDER, provider)
        Suggest -> uri(HOST_SUGGEST)
        is FindMovie -> {
            val b = Uri.Builder().scheme(SCHEME).authority(HOST_FIND).appendQueryParameter("title", title)
            if (year != null) b.appendQueryParameter("year", year.toString())
            b.build()
        }
    }

    fun intent(context: Context): Intent =
        Intent(Intent.ACTION_VIEW, toUri())
            .setClassName(context, ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    companion object {
        const val SCHEME = "mrt"
        const val ACTIVITY = "com.movieroulettetuesday.app.MainActivity"
        const val HOST_SPIN = "spin"
        const val HOST_MOVIE = "movie"
        const val HOST_EPISODE = "episode"
        const val HOST_LATEST = "latest"
        const val HOST_WATCHLIST = "watchlist"
        const val HOST_WATCHED = "watched"
        const val HOST_WTW = "wtw"
        const val HOST_SUGGEST = "suggest"
        const val HOST_FIND = "find"
        const val SEG_ADD = "add"
        const val SEG_PROVIDER = "provider"

        fun uri(host: String, vararg path: String): Uri {
            val b = Uri.Builder().scheme(SCHEME).authority(host)
            path.forEach { b.appendPath(it) }
            return b.build()
        }

        fun parse(intent: Intent?): DeepLink? {
            if (intent == null) return null
            val data = intent.data ?: return null
            if (data.scheme != SCHEME) return null
            return parse(data)
        }

        fun parse(uri: Uri): DeepLink? {
            if (uri.scheme != SCHEME) return null
            val host = uri.host?.lowercase()?.trim().orEmpty()
            val parts = uri.pathSegments.map { Uri.decode(it) }.filter { it.isNotEmpty() }
            return when (host) {
                HOST_SPIN -> if (parts.isEmpty()) Spin else null
                HOST_SUGGEST -> if (parts.isEmpty()) Suggest else null
                HOST_LATEST -> if (parts.isEmpty()) LatestEpisode else null
                HOST_WATCHLIST -> when {
                    parts.isEmpty() -> Watchlist
                    parts.size == 2 && parts[0].equals(SEG_ADD, ignoreCase = true) && parts[1].isNotBlank() ->
                        AddToWatchlist(parts[1])
                    else -> null
                }
                HOST_WATCHED -> parts.singleOrNull()?.takeIf { it.isNotBlank() }?.let(::MarkWatched)
                HOST_WTW -> parts.singleOrNull()?.takeIf { it.isNotBlank() }?.let(::WhereToWatch)
                HOST_EPISODE -> parts.singleOrNull()?.takeIf { it.isNotBlank() }?.let(::OpenEpisode)
                HOST_MOVIE -> when {
                    parts.size == 1 && parts[0].isNotBlank() -> OpenMovie(parts[0])
                    parts.size == 3 && parts[0].isNotBlank() && parts[1].equals(SEG_PROVIDER, ignoreCase = true) && parts[2].isNotBlank() ->
                        OpenOnProvider(parts[0], parts[2])
                    else -> null
                }
                HOST_FIND -> {
                    val title = uri.getQueryParameter("title")?.trim().orEmpty()
                    if (title.isEmpty()) return null
                    val year = uri.getQueryParameter("year")?.toIntOrNull()
                    FindMovie(title, year)
                }
                else -> null
            }
        }
    }
}
