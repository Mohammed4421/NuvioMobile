package com.nuvio.app.features.tmdb

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogContentLanguagesTest {
    private val prefs = ContentLanguagePreferences.EnglishTitlesArabicInfo

    @Test
    fun onlyExactIdsAreLookedUp() {
        assertEquals("tt0111161", exactTmdbIdCandidate("tt0111161"))
        assertEquals("tt0944947", exactTmdbIdCandidate("tt0944947:1:2"))
        assertEquals("1399", exactTmdbIdCandidate("tmdb:1399"))
        assertNull(exactTmdbIdCandidate("kitsu:12"))
        assertNull(exactTmdbIdCandidate("mal:5114"))
        assertNull(exactTmdbIdCandidate("1399"))
        assertNull(exactTmdbIdCandidate("ttabc"))
    }

    @Test
    fun cardTakesTheChosenLanguagesFromOneResponse() {
        val details = CardDetails(
            name = "لعبة الحبار",
            originalName = "오징어 게임",
            originalLanguage = "ko",
            overview = "وصف",
            translations = TmdbTranslationsResponse(
                listOf(
                    TmdbTranslation(iso6391 = "en", iso31661 = "US", data = TmdbTranslationData(name = "Squid Game", overview = "English")),
                ),
            ),
            images = TmdbImagesResponse(
                posters = listOf(TmdbImage(filePath = "/ko.jpg", iso6391 = "ko"), TmdbImage(filePath = "/en.jpg", iso6391 = "en")),
                logos = listOf(TmdbImage(filePath = "/logo-en.png", iso6391 = "en")),
            ),
        )
        val localized = details.toLocalized(prefs, "ar")
        assertEquals("Squid Game", localized.title)
        assertEquals("وصف", localized.description)
        assertEquals("https://image.tmdb.org/t/p/w500/en.jpg", localized.poster)
        assertEquals("https://image.tmdb.org/t/p/original/logo-en.png", localized.logo)
        assertNull(localized.backdrop)
    }

    @Test
    fun missingValuesAndAutomaticFieldsKeepTheAddonData() {
        val item = MetaPreview(id = "tt1", type = "movie", name = "Add-on name", poster = "addon-poster", logo = "addon-logo")
        val shown = item.withLocalized(
            CatalogContentLanguages.Localized(title = "English", poster = null, logo = "tmdb-logo"),
            ContentLanguagePreferences(titles = ContentLanguage.English),
        )
        assertEquals("English", shown.name)
        assertEquals("addon-poster", shown.poster)
        assertEquals("addon-logo", shown.logo)
    }
}
