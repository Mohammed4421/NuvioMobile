package com.nuvio.app.features.profiles

import android.app.Application
import android.content.Context
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.pin_incorrect
import nuvio.composeapp.generated.resources.profile_pin_offline_verification_requires_online
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOST_TEST_USER = "host-test-user"

/** One cached profile, in the form Nuvio stores the profile list on the device. */
private fun hostProfile(index: Int, pin: Boolean = false, updatedAt: String = "t1"): String =
    """{"profile_index":$index,"name":"Profile $index","pin_enabled":$pin,"updated_at":"$updatedAt"}"""

/**
 * Makes [active] the active profile from the profile list cached on the device, as a cold start does
 * (ProfileRepository.ensureLoaded). It sets what every per-profile store reads
 * (ProfileRepository.activeProfileId); the caller then reloads the repositories under test, as
 * ProfileRepository.selectProfile does for each of them.
 */
private fun useHostProfiles(active: Int, profiles: List<String> = listOf(hostProfile(1), hostProfile(2))) {
    ProfileStorage.savePayload(
        """{"userId":"$HOST_TEST_USER","activeProfileIndex":$active,"profiles":[${profiles.joinToString(",")}]}""",
    )
    ProfileRepository.clearInMemory()
    ProfileRepository.ensureLoaded(HOST_TEST_USER)
    check(ProfileRepository.activeProfileId == active) { "profile $active is not active: ${ProfileRepository.activeProfileId}" }
}

/**
 * P3-03: a profile's PIN checked without the account server (signed out, or the server unreachable
 * falls back to it). Nuvio's code, unchanged in Build 15: these tests cover its offline path only;
 * setting, changing and clearing a PIN need the server (NOT TESTED on the host).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfilePinOfflineTest {
    private val json = Json { encodeDefaults = true }

    @BeforeTest
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_profile_cache", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("nuvio_profile_pin_cache", Context.MODE_PRIVATE).edit().clear().commit()
        ProfileStorage.initialize(context)
        ProfilePinCacheStorage.initialize(context)
        // The offline path is the one under test: no session, so nothing goes to the server.
        check(AuthRepository.state.value !is AuthState.Authenticated) { "a session exists: ${AuthRepository.state.value}" }
    }

    @AfterTest
    fun tearDown() {
        ProfileRepository.clearInMemory()
    }

    /** What the app keeps after a PIN was verified once online (ProfileRepository.rememberVerifiedPin). */
    private fun cachePin(profile: Int, pin: String, updatedAt: String = "t1") {
        val salt = generateProfilePinSalt()
        val payload = CachedProfilePinPayload(salt = salt, digest = hashProfilePin(profile, salt, pin), profileUpdatedAt = updatedAt)
        ProfilePinCacheStorage.savePayload(profile, json.encodeToString(CachedProfilePinPayload.serializer(), payload))
    }

    private fun verify(profile: Int, pin: String): PinVerifyResult = runBlocking { ProfileRepository.verifyPin(profile, pin) }

    private fun text(resource: StringResource): String = runBlocking { getString(resource) }

    private val bothLocked = listOf(hostProfile(1, pin = true), hostProfile(2, pin = true))

    @Test
    fun eachProfileOpensOnlyWithItsOwnPin() {
        cachePin(1, "1111")
        cachePin(2, "2222")
        useHostProfiles(1, bothLocked)

        assertTrue(verify(1, "1111").unlocked)
        assertTrue(verify(2, "2222").unlocked)
        // One profile's PIN never opens the other (the digest is bound to the profile index).
        val crossed = verify(2, "1111")
        assertFalse(crossed.unlocked)
        assertEquals(text(Res.string.pin_incorrect), crossed.message)
        assertFalse(verify(1, "2222").unlocked)
        assertFalse(verify(1, "").unlocked)
    }

    @Test
    fun aProfileWithoutAPinOpensAndALockedOneWithoutACacheStaysLocked() {
        useHostProfiles(1, listOf(hostProfile(1), hostProfile(2, pin = true)))

        assertTrue(verify(1, "").unlocked)
        // Never verified on this device: refused offline (fails closed), not opened.
        val locked = verify(2, "2222")
        assertFalse(locked.unlocked)
        assertEquals(text(Res.string.profile_pin_offline_verification_requires_online), locked.message)
    }

    @Test
    fun aDamagedPinCacheRefusesThatProfileOnlyAndIsDroppedOnTheNextStart() {
        cachePin(1, "1111")
        useHostProfiles(1, bothLocked)
        ProfilePinCacheStorage.savePayload(2, "{damaged")

        val damaged = verify(2, "2222")
        assertFalse(damaged.unlocked)
        assertEquals(text(Res.string.profile_pin_offline_verification_requires_online), damaged.message)
        assertTrue(verify(1, "1111").unlocked)

        useHostProfiles(1, bothLocked) // restart
        assertNull(ProfilePinCacheStorage.loadPayload(2))
        assertNotNull(ProfilePinCacheStorage.loadPayload(1))
        assertTrue(verify(1, "1111").unlocked)
        assertFalse(verify(2, "2222").unlocked)
    }

    @Test
    fun aPinChangedOnAnotherDeviceIsNotAcceptedFromTheOldCache() {
        cachePin(1, "1111")
        cachePin(2, "2222", updatedAt = "t1")
        // The profile list now says profile 2 changed after the cache was made.
        useHostProfiles(1, listOf(hostProfile(1, pin = true), hostProfile(2, pin = true, updatedAt = "t2")))

        assertNull(ProfilePinCacheStorage.loadPayload(2))
        assertFalse(verify(2, "2222").unlocked)
        assertNotNull(ProfilePinCacheStorage.loadPayload(1))
        assertTrue(verify(1, "1111").unlocked)
    }

    @Test
    fun theOfflinePinStillWorksAfterARestart() {
        cachePin(2, "2222")
        useHostProfiles(1, bothLocked)
        assertTrue(verify(2, "2222").unlocked)

        useHostProfiles(2, bothLocked) // cold start on the remembered profile 2
        assertTrue(verify(2, "2222").unlocked)
        assertFalse(verify(2, "0000").unlocked)
    }
}
