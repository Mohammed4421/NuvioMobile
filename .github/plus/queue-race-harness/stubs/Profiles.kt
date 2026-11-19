package com.nuvio.app.features.profiles

import com.nuvio.app.features.downloads.DownloadsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Profile(val profileIndex: Int)
data class ProfileState(val activeProfile: Profile? = null)

/** Mirrors the real selectProfile order: the index first, then the state, then each repository (downloads last). */
object ProfileRepository {
    private var activeProfileIndex = 1
    private val _state = MutableStateFlow(ProfileState(Profile(1)))
    val state: StateFlow<ProfileState> = _state
    val activeProfileId: Int get() = activeProfileIndex

    fun selectProfile(profileIndex: Int) {
        activeProfileIndex = profileIndex
        _state.value = _state.value.copy(activeProfile = Profile(profileIndex))
        DownloadsRepository.onProfileChanged()
    }

    /** A cold start already on [profileIndex] (the remembered profile). */
    fun startOn(profileIndex: Int) {
        activeProfileIndex = profileIndex
        _state.value = ProfileState(Profile(profileIndex))
    }
}
