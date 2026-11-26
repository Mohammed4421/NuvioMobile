package com.nuvio.app.features.home

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.tmdb.ContentLanguage
import com.nuvio.app.features.tmdb.ContentLanguagePreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeHeroContentLanguagesTest {
    private val preview = MetaPreview(
        id = "tt1",
        type = "series",
        name = "Catalog name",
        poster = "catalog-poster",
        banner = "catalog-banner",
        logo = "catalog-logo",
        description = "Catalog description",
    )
    private val details = MetaDetails(
        id = "tt1",
        type = "series",
        name = "Squid Game",
        poster = "en-poster",
        background = "en-backdrop",
        logo = "en-logo",
        description = "وصف عربي",
    )

    @Test
    fun defaultChoicesKeepTheCatalogAsBefore() {
        assertEquals(preview, preview.withContentLanguages(details, ContentLanguagePreferences()))
    }

    @Test
    fun chosenFieldsComeFromTheEnrichedDetails() {
        val hero = preview.withContentLanguages(details, ContentLanguagePreferences.EnglishTitlesArabicInfo)
        assertEquals("Squid Game", hero.name)
        assertEquals("وصف عربي", hero.description)
        assertEquals("en-poster", hero.poster)
        assertEquals("en-backdrop", hero.banner)
        assertEquals("en-logo", hero.logo)
    }

    @Test
    fun fieldsLeftOnAutoAndMissingValuesKeepTheCatalog() {
        val hero = preview.withContentLanguages(
            details.copy(logo = null, description = " "),
            ContentLanguagePreferences(descriptions = ContentLanguage.Arabic, backdropsAndLogos = ContentLanguage.English),
        )
        assertEquals("Catalog name", hero.name)
        assertEquals("Catalog description", hero.description)
        assertEquals("catalog-poster", hero.poster)
        assertEquals("en-backdrop", hero.banner)
        assertEquals("catalog-logo", hero.logo)
        assertEquals(preview, preview.withContentLanguages(null, ContentLanguagePreferences.EnglishTitlesArabicInfo))
    }
}
