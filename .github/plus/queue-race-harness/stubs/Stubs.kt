// Test doubles for the dependencies of the real SeasonDownloadQueue.kt (compiled unchanged).
// Same packages and the same members the queue uses; behaviour is controlled by the tests.
@file:Suppress("unused")

package com.nuvio.app.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

enum class AppVisibility { Foreground, Background }

object AppForegroundMonitor {
    val flow = MutableSharedFlow<AppVisibility>()
    fun events(): Flow<AppVisibility> = flow
}
