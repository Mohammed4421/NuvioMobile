package com.nuvio.app.features.player

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileStorage
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** One cached profile, in the form Nuvio stores the profile list on the device. */
private fun hostProfile(index: Int): String = """{"profile_index":$index,"name":"Profile $index"}"""

/**
 * Makes [active] the active profile from the profile list cached on the device, as a cold start does
 * (ProfileRepository.ensureLoaded): this sets ProfileRepository.activeProfileId, which every
 * per-profile store reads.
 */
private fun useHostProfiles(active: Int, profiles: List<String> = listOf(hostProfile(1), hostProfile(2))) {
    ProfileStorage.savePayload("""{"userId":"host-test-user","activeProfileIndex":$active,"profiles":[${profiles.joinToString(",")}]}""")
    ProfileRepository.clearInMemory()
    ProfileRepository.ensureLoaded("host-test-user")
    check(ProfileRepository.activeProfileId == active) { "profile $active is not active: ${ProfileRepository.activeProfileId}" }
}

/**
 * P3-03: the Plus subtitle settings (language, style, appearance with brightness) are kept per
 * profile (`<key>_<profile>` in nuvio_player_settings). A switch is applied as
 * ProfileRepository.selectProfile applies it (the active profile id, then
 * PlayerSettingsRepository.onProfileChanged).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileSubtitleSettingsIsolationTest {
    private lateinit var settings: SharedPreferences

    @BeforeTest
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        settings = context.getSharedPreferences("nuvio_player_settings", Context.MODE_PRIVATE)
        settings.edit().clear().commit()
        context.getSharedPreferences("nuvio_profile_cache", Context.MODE_PRIVATE).edit().clear().commit()
        PlayerSettingsStorage.initialize(context)
        ProfileStorage.initialize(context)
        PlayerSettingsRepository.clearLocalState()
    }

    @AfterTest
    fun tearDown() {
        PlayerSettingsRepository.clearLocalState()
        ProfileRepository.clearInMemory()
    }

    private fun switchTo(profile: Int) {
        useHostProfiles(profile)
        PlayerSettingsRepository.onProfileChanged()
    }

    private data class Subtitles(val language: String, val style: SubtitleStyleState)

    private fun current() = PlayerSettingsRepository.uiState.value.let { Subtitles(it.preferredSubtitleLanguage, it.subtitleStyle) }

    private val styleOne = SubtitleStyleState(
        backgroundColor = Color.Black.copy(alpha = 0.6f),
        appearance = SubtitleAppearance(fontId = "serif", backgroundRadius = 12, shadowEnabled = true, shadowOpacity = 65, shadowSize = 4, brightness = 70),
    )
    private val styleTwo = SubtitleStyleState(appearance = SubtitleAppearance(fontId = "mono", brightness = 40))

    /** Profile 1 and 2 each with their own language and style; returns what each profile shows. */
    private fun setUpBoth(): Pair<Subtitles, Subtitles> {
        switchTo(1)
        PlayerSettingsRepository.setPreferredSubtitleLanguage("ar")
        PlayerSettingsRepository.setSubtitleStyle(styleOne)
        val one = current()
        switchTo(2)
        PlayerSettingsRepository.setPreferredSubtitleLanguage("en")
        PlayerSettingsRepository.setSubtitleStyle(styleTwo)
        val two = current()
        assertNotEquals(one, two)
        return one to two
    }

    @Test
    fun eachProfileKeepsItsOwnSubtitleSettingsAcrossSwitches() {
        switchTo(2)
        val untouched = current()
        val (one, two) = setUpBoth()

        assertEquals(styleOne.appearance, one.style.appearance)
        switchTo(1)
        assertEquals(one, current())
        switchTo(2)
        assertEquals(two, current())
        // Stored under each profile's own key.
        assertNotEquals(settings.getString("subtitle_appearance_1", null), settings.getString("subtitle_appearance_2", null))
        assertNotEquals(settings.getString("preferred_subtitle_language_1", null), settings.getString("preferred_subtitle_language_2", null))
        // A third profile that never set anything shows what profile 2 showed before it set anything.
        useHostProfiles(3, listOf(hostProfile(1), hostProfile(2), hostProfile(3)))
        PlayerSettingsRepository.onProfileChanged()
        assertEquals(untouched, current())
    }

    @Test
    fun aDamagedAppearanceInOneProfileFallsBackThereOnly() {
        val (one, _) = setUpBoth()
        settings.edit().putString("subtitle_appearance_2", "{damaged").commit()

        switchTo(2)
        assertEquals(SubtitleAppearance(), current().style.appearance)
        assertEquals("{damaged", settings.getString("subtitle_appearance_2", null))
        switchTo(1)
        assertEquals(one, current())
    }

    @Test
    fun aRestartBringsBackEachProfilesSubtitleSettings() {
        val (one, two) = setUpBoth()

        // Process restart: nothing in memory, a cold start on the remembered profile 2.
        PlayerSettingsRepository.clearLocalState()
        ProfileRepository.clearInMemory()
        useHostProfiles(2)
        PlayerSettingsRepository.ensureLoaded()
        assertEquals(two, current())
        switchTo(1)
        assertEquals(one, current())
    }
}
