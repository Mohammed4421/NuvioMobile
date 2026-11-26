package com.nuvio.app.features.tmdb

import android.app.Application
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Nuvio Plus content languages: saved per profile, read back after a restart, absent = unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContentLanguagesRepositoryTest {
    @BeforeTest
    fun initialize() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_tmdb_settings", Context.MODE_PRIVATE).edit().clear().commit()
        TmdbSettingsStorage.initialize(context)
        TmdbSettingsRepository.onProfileChanged()
    }

    @AfterTest
    fun clearState() {
        initialize()
    }

    @Test
    fun existingUsersKeepAutomaticLanguagesUntilTheyChoose() {
        assertTrue(TmdbSettingsRepository.snapshot().contentLanguages.isDefault)
        assertNull(TmdbSettingsStorage.loadContentLanguages())
    }

    @Test
    fun choicesSurviveARestart() {
        TmdbSettingsRepository.setContentLanguages(ContentLanguagePreferences.EnglishTitlesArabicInfo)
        TmdbSettingsRepository.onProfileChanged()
        assertEquals(
            ContentLanguagePreferences.EnglishTitlesArabicInfo,
            TmdbSettingsRepository.snapshot().contentLanguages,
        )
        // The single TMDB language setting is untouched.
        assertEquals("en", TmdbSettingsRepository.snapshot().language)
    }

    @Test
    fun contentLanguagesStayOutOfTheAccountSync() {
        TmdbSettingsRepository.setContentLanguages(ContentLanguagePreferences.EnglishTitlesArabicInfo)
        assertNull(TmdbSettingsStorage.exportToSyncPayload()["plus_content_languages"])
    }
}
