package com.nuvio.app.features.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Nuvio Plus: the language of each part of a title's information, chosen separately and
 * independent of the app's interface language. Applied in one place, TMDB enrichment
 * ([TmdbMetadataService]), so every screen that shows enriched information follows it.
 *
 * [ContentLanguage.Auto] means "TMDB's preferred language" (the existing single setting), so a
 * user who never opens these settings sees exactly what they saw before.
 */
enum class ContentLanguage(val storageValue: String, val tmdbCode: String?) {
    Auto("auto", null),
    English("en", "en-US"),
    Arabic("ar", "ar"),
    Original("original", null),
    ;

    companion object {
        fun fromStorage(value: String?): ContentLanguage =
            entries.firstOrNull { it.storageValue == value?.trim() } ?: Auto
    }
}

data class ContentLanguagePreferences(
    val titles: ContentLanguage = ContentLanguage.Auto,
    val descriptions: ContentLanguage = ContentLanguage.Auto,
    val episodeTitles: ContentLanguage = ContentLanguage.Auto,
    val genres: ContentLanguage = ContentLanguage.Auto,
    val posters: ContentLanguage = ContentLanguage.Auto,
    val backdropsAndLogos: ContentLanguage = ContentLanguage.Auto,
    /** Used when the chosen language has no text or image; [ContentLanguage.Original] is not offered. */
    val fallback: ContentLanguage = ContentLanguage.Auto,
) {
    val isDefault: Boolean get() = this == ContentLanguagePreferences()

    /** Part of every cache key, so results for different choices never mix. */
    fun signature(): String = if (isDefault) "" else
        listOf(titles, descriptions, episodeTitles, genres, posters, backdropsAndLogos, fallback)
            .joinToString(".") { it.storageValue }

    fun encode(): String = signature()

    companion object {
        /** English titles, episode names and artwork; Arabic descriptions and genres. */
        val EnglishTitlesArabicInfo = ContentLanguagePreferences(
            titles = ContentLanguage.English,
            descriptions = ContentLanguage.Arabic,
            episodeTitles = ContentLanguage.English,
            genres = ContentLanguage.Arabic,
            posters = ContentLanguage.English,
            backdropsAndLogos = ContentLanguage.English,
            fallback = ContentLanguage.English,
        )

        fun decode(value: String?): ContentLanguagePreferences {
            val parts = value?.split('.').orEmpty()
            if (parts.size != 7) return ContentLanguagePreferences()
            val choices = parts.map { ContentLanguage.fromStorage(it) }
            return ContentLanguagePreferences(
                titles = choices[0],
                descriptions = choices[1],
                episodeTitles = choices[2],
                genres = choices[3],
                posters = choices[4],
                backdropsAndLogos = choices[5],
                fallback = choices[6].takeUnless { it == ContentLanguage.Original } ?: ContentLanguage.Auto,
            )
        }
    }
}

/**
 * The TMDB language code to ask for: Auto is the user's TMDB language, Original the title's own
 * language (known only once its details are loaded; null before).
 */
internal fun ContentLanguage.resolve(baseLanguage: String, originalLanguage: String?): String? = when (this) {
    ContentLanguage.Auto -> normalizeTmdbLanguage(baseLanguage)
    ContentLanguage.Original -> originalLanguage?.trim()?.takeIf(String::isNotBlank)?.lowercase()
    else -> tmdbCode
}

/** Primary language codes to try in order: the choice, then the fallback, then the base language. */
internal fun languageOrder(
    choice: ContentLanguage,
    prefs: ContentLanguagePreferences,
    baseLanguage: String,
    originalLanguage: String?,
): List<String> =
    listOfNotNull(
        choice.resolve(baseLanguage, originalLanguage),
        prefs.fallback.resolve(baseLanguage, originalLanguage),
        normalizeTmdbLanguage(baseLanguage),
    ).map { it.substringBefore('-').lowercase() }.distinct()

@Serializable
internal data class TmdbTranslationsResponse(
    val translations: List<TmdbTranslation> = emptyList(),
)

@Serializable
internal data class TmdbTranslation(
    @SerialName("iso_639_1") val iso6391: String? = null,
    @SerialName("iso_3166_1") val iso31661: String? = null,
    val data: TmdbTranslationData? = null,
)

@Serializable
internal data class TmdbTranslationData(
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
)

/**
 * The first non-blank text among [languages] (primary codes, in order). Within a language a
 * translation for the usual region (US for English) wins. Null when none has text: the caller
 * then keeps what it already had, never an invented or machine-translated text.
 */
internal fun List<TmdbTranslation>.textIn(
    languages: List<String>,
    select: (TmdbTranslationData) -> String?,
): String? {
    for (language in languages) {
        val candidates = filter { it.iso6391.equals(language, ignoreCase = true) }
            .sortedByDescending { it.iso31661 == preferredRegion(language) }
        candidates.firstNotNullOfOrNull { translation ->
            translation.data?.let(select)?.trim()?.takeIf(String::isNotBlank)
        }?.let { return it }
    }
    return null
}

private fun preferredRegion(language: String): String? = when (language) {
    "en" -> "US"
    "pt" -> "PT"
    "es" -> "ES"
    else -> null
}

/**
 * Image choice of §4.6: the requested languages in order, then an image without text
 * (no language), then any image.
 */
internal fun List<TmdbImage>.selectByLanguages(languages: List<String>): String? {
    val usable = filter { !it.filePath.isNullOrBlank() }
    if (usable.isEmpty()) return null
    for (language in languages) {
        usable.firstOrNull { it.iso6391.equals(language, ignoreCase = true) }?.let { return it.filePath?.trim() }
    }
    return (usable.firstOrNull { it.iso6391 == null } ?: usable.first()).filePath?.trim()
}

/** Every language an images request must include for [languages] plus text-free images. */
internal fun imageLanguagesParameter(languages: List<String>, baseLanguage: String): String =
    (languages + tmdbImageLanguages(baseLanguage).split(',') + "null").distinct().joinToString(",")

/**
 * Title in the first of [languages] that has one. The title's own language uses the original
 * title (TMDB often leaves that translation empty), so an English film asked for in English shows
 * its English title, and a Korean series asked for in English shows its English title, never the
 * Korean original. Falls back to the original title, then to what the details request returned.
 */
internal fun titleFor(
    languages: List<String>,
    originalLanguage: String?,
    originalTitle: String?,
    translations: List<TmdbTranslation>,
    detailsTitle: String?,
): String? {
    val original = originalLanguage?.substringBefore('-')?.lowercase()
    for (language in languages) {
        val text = if (language == original) {
            originalTitle?.trim()?.takeIf(String::isNotBlank)
                ?: translations.textIn(listOf(language)) { it.title ?: it.name }
        } else {
            translations.textIn(listOf(language)) { it.title ?: it.name }
        }
        if (text != null) return text
    }
    return originalTitle?.trim()?.takeIf(String::isNotBlank) ?: detailsTitle?.trim()?.takeIf(String::isNotBlank)
}

/**
 * Description in the first of [languages] that has one: the details request already returned it in
 * [detailsLanguage]; other languages come from the translations of the same response.
 */
internal fun descriptionFor(
    languages: List<String>,
    detailsLanguage: String,
    detailsOverview: String?,
    translations: List<TmdbTranslation>,
): String? {
    val requested = detailsLanguage.substringBefore('-').lowercase()
    for (language in languages) {
        val text = if (language == requested) {
            detailsOverview?.trim()?.takeIf(String::isNotBlank)
                ?: translations.textIn(listOf(language)) { it.overview }
        } else {
            translations.textIn(listOf(language)) { it.overview }
        }
        if (text != null) return text
    }
    return detailsOverview?.trim()?.takeIf(String::isNotBlank)
}

/** TMDB's placeholder when an episode has no name in a language ("Episode 3", "الحلقة 3"). */
internal fun isPlaceholderEpisodeName(name: String?, episodeNumber: Int): Boolean {
    val text = name?.trim().orEmpty()
    if (text.isEmpty()) return true
    val digits = text.filter(Char::isDigit)
    val words = text.filterNot(Char::isDigit).trim().lowercase()
    return digits == episodeNumber.toString() && words in placeholderEpisodeWords
}

private val placeholderEpisodeWords = setOf("episode", "الحلقة", "حلقة", "épisode", "episodio", "folge", "bölüm")
