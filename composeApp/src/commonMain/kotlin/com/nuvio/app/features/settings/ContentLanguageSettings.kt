package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.tmdb.ContentLanguage
import com.nuvio.app.features.tmdb.ContentLanguagePreferences
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.plus_content_languages_arabic
import nuvio.composeapp.generated.resources.plus_content_languages_auto
import nuvio.composeapp.generated.resources.plus_content_languages_backdrops
import nuvio.composeapp.generated.resources.plus_content_languages_choose
import nuvio.composeapp.generated.resources.plus_content_languages_custom
import nuvio.composeapp.generated.resources.plus_content_languages_descriptions
import nuvio.composeapp.generated.resources.plus_content_languages_english
import nuvio.composeapp.generated.resources.plus_content_languages_episode_titles
import nuvio.composeapp.generated.resources.plus_content_languages_fallback
import nuvio.composeapp.generated.resources.plus_content_languages_fallback_description
import nuvio.composeapp.generated.resources.plus_content_languages_footer
import nuvio.composeapp.generated.resources.plus_content_languages_genres
import nuvio.composeapp.generated.resources.plus_content_languages_original
import nuvio.composeapp.generated.resources.plus_content_languages_posters
import nuvio.composeapp.generated.resources.plus_content_languages_preset
import nuvio.composeapp.generated.resources.plus_content_languages_preset_description
import nuvio.composeapp.generated.resources.plus_content_languages_preset_english_arabic
import nuvio.composeapp.generated.resources.plus_content_languages_title
import nuvio.composeapp.generated.resources.plus_content_languages_titles
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Nuvio Plus: the content-language section of the TMDB settings. One row per part of a title's
 * information, each with the same choices, and a one-tap setting for English titles with Arabic
 * information. Every choice is saved for the active profile at once.
 */
internal fun LazyListScope.contentLanguageSettingsContent(
    isTablet: Boolean,
    settings: TmdbSettings,
) {
    val prefs = settings.contentLanguages
    item {
        SettingsSection(
            title = stringResource(Res.string.plus_content_languages_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsChipRow(
                    title = stringResource(Res.string.plus_content_languages_preset),
                    description = stringResource(Res.string.plus_content_languages_preset_description),
                    isTablet = isTablet,
                    footer = stringResource(Res.string.plus_content_languages_footer),
                ) {
                    Chip(
                        label = stringResource(Res.string.plus_content_languages_auto),
                        selected = prefs.isDefault,
                        onClick = { TmdbSettingsRepository.setContentLanguages(ContentLanguagePreferences()) },
                    )
                    Chip(
                        label = stringResource(Res.string.plus_content_languages_preset_english_arabic),
                        selected = prefs == ContentLanguagePreferences.EnglishTitlesArabicInfo,
                        onClick = {
                            TmdbSettingsRepository.setContentLanguages(ContentLanguagePreferences.EnglishTitlesArabicInfo)
                        },
                    )
                    if (!prefs.isDefault && prefs != ContentLanguagePreferences.EnglishTitlesArabicInfo) {
                        Chip(
                            label = stringResource(Res.string.plus_content_languages_custom),
                            selected = true,
                            onClick = {},
                        )
                    }
                }
                ContentLanguageRow(isTablet, Res.string.plus_content_languages_titles, prefs.titles) {
                    prefs.copy(titles = it)
                }
                ContentLanguageRow(isTablet, Res.string.plus_content_languages_descriptions, prefs.descriptions) {
                    prefs.copy(descriptions = it)
                }
                ContentLanguageRow(isTablet, Res.string.plus_content_languages_episode_titles, prefs.episodeTitles) {
                    prefs.copy(episodeTitles = it)
                }
                ContentLanguageRow(isTablet, Res.string.plus_content_languages_genres, prefs.genres) {
                    prefs.copy(genres = it)
                }
                ContentLanguageRow(isTablet, Res.string.plus_content_languages_posters, prefs.posters) {
                    prefs.copy(posters = it)
                }
                ContentLanguageRow(isTablet, Res.string.plus_content_languages_backdrops, prefs.backdropsAndLogos) {
                    prefs.copy(backdropsAndLogos = it)
                }
                ContentLanguageRow(
                    isTablet = isTablet,
                    title = Res.string.plus_content_languages_fallback,
                    selected = prefs.fallback,
                    description = Res.string.plus_content_languages_fallback_description,
                ) { prefs.copy(fallback = it) }
            }
        }
    }
}

@Composable
private fun ContentLanguageRow(
    isTablet: Boolean,
    title: StringResource,
    selected: ContentLanguage,
    description: StringResource = Res.string.plus_content_languages_choose,
    choices: List<ContentLanguage> = ContentLanguage.entries,
    update: (ContentLanguage) -> ContentLanguagePreferences,
) {
    SettingsGroupDivider(isTablet = isTablet)
    SettingsChipRow(
        title = stringResource(title),
        description = stringResource(description),
        isTablet = isTablet,
    ) {
        choices.forEach { choice ->
            Chip(
                label = stringResource(choice.labelRes()),
                selected = selected == choice,
                onClick = { TmdbSettingsRepository.setContentLanguages(update(choice)) },
            )
        }
    }
}

private fun ContentLanguage.labelRes(): StringResource = when (this) {
    ContentLanguage.Auto -> Res.string.plus_content_languages_auto
    ContentLanguage.English -> Res.string.plus_content_languages_english
    ContentLanguage.Arabic -> Res.string.plus_content_languages_arabic
    ContentLanguage.Original -> Res.string.plus_content_languages_original
}
