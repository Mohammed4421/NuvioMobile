package com.nuvio.app.features.home

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.tmdb.ContentLanguage
import com.nuvio.app.features.tmdb.ContentLanguagePreferences

/**
 * Nuvio Plus: the home hero shows the title, description and artwork in the content languages
 * chosen in the TMDB settings. The hero already loads each item's details (TMDB-enriched, so in
 * those languages); this takes them from there, so no request is added. A field left on Auto
 * keeps what the catalog gave, exactly as before.
 */
internal fun MetaPreview.withContentLanguages(
    details: MetaDetails?,
    prefs: ContentLanguagePreferences,
): MetaPreview {
    if (details == null || prefs.isDefault) return this
    fun chosen(choice: ContentLanguage) = choice != ContentLanguage.Auto
    val artwork = chosen(prefs.backdropsAndLogos)
    return copy(
        name = details.name.takeIf { chosen(prefs.titles) && it.isNotBlank() } ?: name,
        description = details.description?.takeIf { chosen(prefs.descriptions) && it.isNotBlank() } ?: description,
        poster = details.poster?.takeIf { chosen(prefs.posters) && it.isNotBlank() } ?: poster,
        banner = details.background?.takeIf { artwork && it.isNotBlank() } ?: banner,
        logo = details.logo?.takeIf { artwork && it.isNotBlank() } ?: logo,
    )
}
