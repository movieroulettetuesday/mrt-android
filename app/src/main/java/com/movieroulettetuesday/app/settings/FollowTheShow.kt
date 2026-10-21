package com.movieroulettetuesday.app.settings

import com.movieroulettetuesday.app.catalog.ShowInfo
import com.movieroulettetuesday.app.episodes.PodcastLinks

data class FollowLink(val id: String, val title: String, val url: String, val tileHex: String)

/** The show's official places, in the iOS Settings order (ShowLinks.swift `FollowTheShow`). */
object FollowTheShow {
    fun links(show: ShowInfo = ShowInfo.MRT): List<FollowLink> = listOfNotNull(
        PodcastLinks.appleShow(show)?.let { FollowLink("apple", "Apple Podcasts", it, "#9B4DE0") },
        PodcastLinks.spotifyShow(show)?.let { FollowLink("spotify", "Spotify", it, "#1DB954") },
        PodcastLinks.overcastShow(show)?.let { FollowLink("overcast", "Overcast", it, "#FC7E0F") },
        PodcastLinks.buzzsproutShow(show)?.let { FollowLink("buzzsprout", "Buzzsprout (podcast page)", it, "#3A9CA6") },
        FollowLink("youtube", "YouTube  @theMrtPodcast", "https://www.youtube.com/@theMrtPodcast", "#FF0000"),
        FollowLink("instagram", "Instagram  @themrtpodcast", "https://www.instagram.com/themrtpodcast", "#C13584"),
        FollowLink("x", "X  @TheMRTPodcast", "https://x.com/TheMRTPodcast", "#3C3C43"),
        FollowLink("facebook", "Facebook", "https://www.facebook.com/people/The-Movie-Roulette-Tuesday-Podcast/100092512094792/", "#1877F2"),
    )
}
