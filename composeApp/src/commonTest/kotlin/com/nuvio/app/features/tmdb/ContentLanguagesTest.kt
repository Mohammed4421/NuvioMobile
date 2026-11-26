package com.nuvio.app.features.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentLanguagesTest {
    private val prefs = ContentLanguagePreferences.EnglishTitlesArabicInfo

    private fun translation(language: String, region: String? = null, title: String? = null, overview: String? = null) =
        TmdbTranslation(iso6391 = language, iso31661 = region, data = TmdbTranslationData(title = title, overview = overview))

    @Test
    fun defaultsChangeNothingAndAreStoredAsBefore() {
        val defaults = ContentLanguagePreferences()
        assertTrue(defaults.isDefault)
        assertEquals("", defaults.signature())
        assertEquals(defaults, ContentLanguagePreferences.decode(null))
        assertEquals(defaults, ContentLanguagePreferences.decode("garbage"))
    }

    @Test
    fun choicesSurviveEncoding() {
        assertEquals(prefs, ContentLanguagePreferences.decode(prefs.encode()))
        assertEquals("en.ar.en.ar.en.en.en", prefs.signature())
        // Every field, the fallback included, can be the title's own language.
        assertEquals(ContentLanguage.Original, ContentLanguagePreferences.decode("auto.auto.auto.auto.auto.auto.original").fallback)
    }

    @Test
    fun languageOrderIsChoiceThenFallbackThenTmdbLanguage() {
        assertEquals(listOf("en", "fr"), languageOrder(ContentLanguage.English, prefs, "fr-FR", null))
        assertEquals(listOf("ar", "en", "fr"), languageOrder(ContentLanguage.Arabic, prefs, "fr-FR", null))
        assertEquals(listOf("ko", "en", "fr"), languageOrder(ContentLanguage.Original, prefs, "fr-FR", "ko"))
        assertEquals(listOf("fr"), languageOrder(ContentLanguage.Auto, ContentLanguagePreferences(), "fr-FR", null))
        val originalFallback = ContentLanguagePreferences(titles = ContentLanguage.Arabic, fallback = ContentLanguage.Original)
        assertEquals(listOf("ar", "ja", "en"), languageOrder(ContentLanguage.Arabic, originalFallback, "en-US", "ja"))
    }

    @Test
    fun listRequestsFollowTheTitleLanguageWhenOneIsNamed() {
        assertEquals("en-US", prefs.listRequestLanguage("ar-SA"))
        assertEquals("ar", ContentLanguagePreferences(titles = ContentLanguage.Arabic).listRequestLanguage("en-US"))
        assertEquals("fr-FR", ContentLanguagePreferences().listRequestLanguage("fr-FR"))
        assertEquals("fr-FR", ContentLanguagePreferences(titles = ContentLanguage.Original).listRequestLanguage("fr-FR"))
    }

    @Test
    fun englishTitleOfAKoreanSeriesIsTheEnglishTranslationNotTheOriginal() {
        val title = titleFor(
            languages = listOf("en", "ar"),
            originalLanguage = "ko",
            originalTitle = "오징어 게임",
            translations = listOf(translation("en", "US", title = "Squid Game"), translation("ar", title = "لعبة الحبار")),
            detailsTitle = "لعبة الحبار",
        )
        assertEquals("Squid Game", title)
    }

    @Test
    fun englishTitleOfAnEnglishFilmIsItsOriginalTitle() {
        val title = titleFor(
            languages = listOf("en", "ar"),
            originalLanguage = "en",
            originalTitle = "Inception",
            translations = listOf(translation("en", "US", title = ""), translation("ar", title = "استهلال")),
            detailsTitle = "استهلال",
        )
        assertEquals("Inception", title)
    }

    @Test
    fun missingTranslationFallsBackAndNeverInventsText() {
        assertEquals(
            "La Casa de Papel",
            titleFor(listOf("en", "ar"), "es", "La Casa de Papel", emptyList(), "La Casa de Papel"),
        )
        assertEquals(
            "English overview",
            descriptionFor(listOf("ar", "en"), "ar", null, listOf(translation("en", "US", overview = "English overview"))),
        )
        assertNull(descriptionFor(listOf("ar", "en"), "ar", null, emptyList()))
    }

    @Test
    fun arabicDescriptionComesFromTheDetailsRequest() {
        assertEquals("وصف", descriptionFor(listOf("ar", "en"), "ar", "وصف", listOf(translation("en", overview = "x"))))
    }

    @Test
    fun imagesPreferTheLanguageThenTextFreeThenAny() {
        val images = listOf(
            TmdbImage(filePath = "/ar.jpg", iso6391 = "ar"),
            TmdbImage(filePath = "/none.jpg", iso6391 = null),
            TmdbImage(filePath = "/en.jpg", iso6391 = "en"),
        )
        assertEquals("/en.jpg", images.selectByLanguages(listOf("en", "ar")))
        assertEquals("/none.jpg", images.selectByLanguages(listOf("de")))
        assertEquals("/fr.jpg", listOf(TmdbImage(filePath = "/fr.jpg", iso6391 = "fr")).selectByLanguages(listOf("en")))
        assertNull(emptyList<TmdbImage>().selectByLanguages(listOf("en")))
    }

    @Test
    fun placeholderEpisodeNamesAreRecognised() {
        assertTrue(isPlaceholderEpisodeName("Episode 3", 3))
        assertTrue(isPlaceholderEpisodeName("الحلقة 3", 3))
        assertTrue(isPlaceholderEpisodeName("", 1))
        assertFalse(isPlaceholderEpisodeName("Pilot", 1))
        assertFalse(isPlaceholderEpisodeName("Episode 3", 4))
    }
}
