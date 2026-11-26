package com.nuvio.app.features.tmdb

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentLanguageSurfacesTest {
    private fun item(imageUrl: String?) = ContinueWatchingItem(
        parentMetaId = "tt1",
        parentMetaType = "series",
        videoId = "tt1:1:2",
        title = "Saved title",
        subtitle = "S1 E2",
        imageUrl = imageUrl,
        poster = "saved-poster",
        background = "saved-backdrop",
        logo = "saved-logo",
        resumePositionMs = 1_000,
        durationMs = 10_000,
        progressFraction = 0.1f,
    )

    private val shown = MetaPreview(
        id = "tt1",
        type = "series",
        name = "Squid Game",
        poster = "en-poster",
        banner = "en-backdrop",
        logo = "en-logo",
    )

    @Test
    fun titleAndArtworkFollowTheShownValues() {
        val result = item(imageUrl = "saved-backdrop").withShown(shown)
        assertEquals("Squid Game", result.title)
        assertEquals("en-poster", result.poster)
        assertEquals("en-logo", result.logo)
        assertEquals("en-backdrop", result.background)
        assertEquals("en-backdrop", result.imageUrl)
        assertEquals("S1 E2", result.subtitle)
    }

    @Test
    fun anEpisodeThumbnailStays() {
        assertEquals("episode-still", item(imageUrl = "episode-still").withShown(shown).imageUrl)
        assertEquals("en-poster", item(imageUrl = "saved-poster").withShown(shown).imageUrl)
    }
}
