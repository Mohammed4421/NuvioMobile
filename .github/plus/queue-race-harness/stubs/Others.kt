package com.nuvio.app.features.addons

import com.nuvio.app.features.streams.Addon
import com.nuvio.app.features.streams.Manifest
import kotlinx.coroutines.flow.MutableStateFlow

data class AddonsUiState(val addons: List<Addon>)
fun List<Addon>.enabledAddons(): List<Addon> = this

object AddonRepository {
    val uiState = MutableStateFlow(AddonsUiState(listOf(Addon(Manifest("addon", "Addon"), "Addon"))))
}
