package com.nuvio.app.features.tmdb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.core.cache.BoundedCache
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Nuvio Plus: content languages for titles that come from add-ons (home rows, catalogs, search,
 * library, recommendations). Add-ons send their own names and posters; when the user has chosen
 * content languages, each card shown on screen asks TMDB once for that title (one request: details
 * with translations and images) and shows the chosen title, poster, backdrop and logo.
 *
 * - Nothing changes, and nothing is requested, while every choice is Automatic.
 * - Only titles with an id TMDB can match exactly (IMDb `tt…` or TMDB ids) are changed; anime or
 *   other ids keep the add-on's data, so no title is ever matched by name.
 * - Only cards that are composed (on screen) are looked up; a few at a time; results, failures
 *   included, are kept in a bounded cache, so scrolling back costs nothing.
 * - A missing translation keeps the add-on's own text or image; nothing is invented.
 */
internal object CatalogContentLanguages {
    private val log = Logger.withTag("CatalogContentLanguages")
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = BoundedCache<String, Localized>(CACHE_SIZE)
    private val permits = Semaphore(CONCURRENT_REQUESTS)

    data class Localized(
        val title: String? = null,
        val description: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val logo: String? = null,
    )

    private val nothing = Localized()

    fun clear() = cache.clear()

    fun cached(item: MetaPreview, settings: TmdbSettings): MetaPreview? =
        cacheKey(item, settings)?.let { key -> cache[key] }?.let { item.withLocalized(it, settings.contentLanguages) }

    suspend fun localize(item: MetaPreview, settings: TmdbSettings): MetaPreview {
        val key = cacheKey(item, settings) ?: return item
        val localized = cache[key] ?: permits.withPermit {
            cache[key] ?: lookUp(item, settings).also { cache[key] = it }
        }
        return item.withLocalized(localized, settings.contentLanguages)
    }

    private fun cacheKey(item: MetaPreview, settings: TmdbSettings): String? {
        val prefs = settings.contentLanguages
        if (prefs.isDefault || !settings.enabled || !prefs.changesCards()) return null
        val type = mediaType(item.type) ?: return null
        if (exactTmdbIdCandidate(item.id) == null) return null
        return "$type:${item.id}:${normalizeTmdbLanguage(settings.language)}:${prefs.signature()}"
    }

    private suspend fun lookUp(item: MetaPreview, settings: TmdbSettings): Localized = withContext(Dispatchers.Default) {
        try {
            val type = mediaType(item.type) ?: return@withContext nothing
            val tmdbId = TmdbService.ensureTmdbId(item.id, type) ?: return@withContext nothing
            val apiKey = TmdbSettingsRepository.effectiveApiKey().takeIf(String::isNotBlank) ?: return@withContext nothing
            val prefs = settings.contentLanguages
            val base = normalizeTmdbLanguage(settings.language)
            // The original language is not known before the request: when a choice needs it,
            // the images include the languages titles are most often made in.
            val wantsOriginal = listOf(prefs.posters, prefs.backdropsAndLogos, prefs.fallback).contains(ContentLanguage.Original)
            val imageLanguages = imageLanguagesParameter(
                languageOrder(prefs.posters, prefs, base, null) +
                    languageOrder(prefs.backdropsAndLogos, prefs, base, null) +
                    (if (wantsOriginal) ORIGINAL_IMAGE_LANGUAGES else emptyList()),
                base,
            )
            val query = mapOf(
                "language" to base,
                "append_to_response" to "translations,images",
                "include_image_language" to imageLanguages,
            )
            val response = json.decodeFromString<CardDetails>(
                httpGetText(buildTmdbUrl(endpoint = "$type/$tmdbId", apiKey = apiKey, query = query)),
            )
            response.toLocalized(prefs, base)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log.w { "Card language lookup failed (${error::class.simpleName})" }
            nothing
        }
    }

    private fun mediaType(type: String): String? = when (type.trim().lowercase()) {
        "movie", "film" -> "movie"
        "series", "tv", "show", "tvshow" -> "tv"
        else -> null
    }

    private val ORIGINAL_IMAGE_LANGUAGES =
        listOf("en", "ar", "ja", "ko", "zh", "hi", "es", "fr", "de", "it", "pt", "ru", "tr", "th")
    private const val CACHE_SIZE = 600
    private const val CONCURRENT_REQUESTS = 4
}

/** True when a choice affects what a card shows (title, poster, backdrop or logo). */
private fun ContentLanguagePreferences.changesCards(): Boolean =
    titles != ContentLanguage.Auto || posters != ContentLanguage.Auto ||
        backdropsAndLogos != ContentLanguage.Auto || descriptions != ContentLanguage.Auto

/** An id TMDB matches exactly: an IMDb id or a TMDB id; anything else is left alone. */
internal fun exactTmdbIdCandidate(id: String): String? {
    val normalized = id.trim().removePrefix("tmdb:").substringBefore(':').substringBefore('/')
    return normalized.takeIf {
        (it.startsWith("tt", ignoreCase = true) && it.length > 2 && it.drop(2).all(Char::isDigit)) ||
            (id.trim().startsWith("tmdb:") && it.isNotEmpty() && it.all(Char::isDigit))
    }
}

internal fun MetaPreview.withLocalized(
    localized: CatalogContentLanguages.Localized,
    prefs: ContentLanguagePreferences,
): MetaPreview {
    fun chosen(choice: ContentLanguage) = choice != ContentLanguage.Auto
    return copy(
        name = localized.title?.takeIf { chosen(prefs.titles) } ?: name,
        description = localized.description?.takeIf { chosen(prefs.descriptions) } ?: description,
        poster = localized.poster?.takeIf { chosen(prefs.posters) } ?: poster,
        banner = localized.backdrop?.takeIf { chosen(prefs.backdropsAndLogos) } ?: banner,
        logo = localized.logo?.takeIf { chosen(prefs.backdropsAndLogos) } ?: logo,
    )
}

@Serializable
internal data class CardDetails(
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    val overview: String? = null,
    val translations: TmdbTranslationsResponse? = null,
    val images: TmdbImagesResponse? = null,
)

internal fun CardDetails.toLocalized(
    prefs: ContentLanguagePreferences,
    baseLanguage: String,
): CatalogContentLanguages.Localized {
    val original = originalLanguage
    val translations = translations?.translations.orEmpty()
    val images = images ?: TmdbImagesResponse()
    fun order(choice: ContentLanguage) = languageOrder(choice, prefs, baseLanguage, original)
    return CatalogContentLanguages.Localized(
        title = titleFor(order(prefs.titles), original, originalTitle ?: originalName, translations, title ?: name),
        description = descriptionFor(order(prefs.descriptions), baseLanguage, overview, translations),
        poster = images.posters.selectByLanguages(order(prefs.posters)).toImageUrl("w500"),
        backdrop = images.backdrops.selectByLanguages(order(prefs.backdropsAndLogos)).toImageUrl("w1280"),
        logo = images.logos.selectByLanguages(order(prefs.backdropsAndLogos)).toImageUrl("original"),
    )
}

private fun String?.toImageUrl(size: String): String? =
    this?.trim()?.takeIf(String::isNotBlank)?.let { "https://image.tmdb.org/t/p/$size$it" }

/**
 * The card's item in the chosen content languages: the add-on's item at first (or at once from
 * the cache), the TMDB values as soon as they arrive. Returns [item] unchanged while every
 * choice is Automatic.
 */
@Composable
fun rememberContentLanguagePreview(item: MetaPreview): MetaPreview {
    val settings by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    if (settings.contentLanguages.isDefault) return item
    val initial = remember(item, settings) { CatalogContentLanguages.cached(item, settings) ?: item }
    val localized by produceState(initialValue = initial, item, settings) {
        value = CatalogContentLanguages.localize(item, settings)
    }
    return localized
}
