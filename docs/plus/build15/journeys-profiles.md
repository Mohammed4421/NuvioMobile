# Build 15 — profile journeys: isolation, failures, restore (P3-03)

Scope: the Plus data that belongs to a profile and Nuvio's profile switching as it affects that data.
Sign-in and cloud sync are Nuvio's code and are covered only where the host can run them without an
account. Status in this file is what the tests check. The CI run ids go into the registers kept by
the coordinator.

## Where each kind of data lives (read from the code)

| Data | Stored | Per profile? | Code |
|---|---|---|---|
| Download list | `nuvio_downloads` → `downloads_payload_<profile>` | yes | `DownloadsStorage.android.kt` (`ProfileScopedKey.of`) |
| Running transfers | `files/download-transfers/*.json`, keyed by file name | no (device-wide; the item id ties a transfer to its list entry) | `AndroidDownloadStore` |
| Season queue | `nuvio_downloads` → `extra_season_queue`, one `profileId` per entry | yes, by entry | `SeasonQueueStorage`, `SeasonQueuePolicy.visible/next/busy/settle` |
| Pause all / paused seasons | `extra_season_queue_holds`, keyed by profile inside **one** value | yes, inside one value | `SeasonQueueStorage.encodeHolds` |
| Download preferences (folder, Wi-Fi only) | `extra_download_preferences` | **no, device-wide by design** ("Device-wide download settings", `DownloadsStorage.kt`) | `DownloadPreferences` |
| Subtitle language, style, appearance | `nuvio_player_settings` → `<key>_<profile>` | yes | `PlayerSettingsStorage.android.kt` |
| Library → Downloads tab | nothing of its own: built from the active profile's download list and visible queue | follows the two above | `LibraryScreen.kt`, `buildLibraryDownloadEntries` |
| Offline PIN check | `nuvio_profile_pin_cache` → `profile_pin_cache_<profile>` (salted SHA-256, bound to the index) | yes | `ProfileRepository.verifyPinLocally`, `ProfilePinCache.kt` |

A note on the task brief: it listed download preferences as per-profile data. They are not. They are
stored once for the device, on purpose, and the test below checks exactly that.

**Profile switch.** `ProfileRepository.selectProfile` first sets the active index. It then calls each
repository's `onProfileChanged` (`DownloadsRepository.onProfileChanged` reloads the list from disk,
and so does `PlayerSettingsRepository`). It does not cancel the handles of transfers that are running.
`SeasonDownloadQueue` follows the profile state and stops searches that belong to another profile.
The host tests make a profile active through Nuvio's own cold-start path
(`ProfileStorage` + `ProfileRepository.ensureLoaded`) and then call the same `onProfileChanged`. The
roughly 30 other repositories that `selectProfile` reloads are Nuvio's and are not exercised here.

**Deleting a profile** (`ProfileRepository.deleteProfile`). With an account it calls the server
(`sync_delete_profile_data`), which needs one. Offline it removes the PIN cache and the server entries
on the device. In neither case does it touch the Plus data: `downloads_payload_<n>`, the profile's
season-queue entries and its subtitle keys all stay. The files it downloaded stay too. That data is
**orphaned harmlessly**: no other profile shows it, starts it or is held by it (tests below). There is
one exception, finding F1.

## Six cases

Test files (all new in this PR):
- `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/downloads/ProfileDownloadsIsolationTest.kt` (**PD**). Uses the real `DownloadsRepository`, the real SharedPreferences store and the real Android transfer store and scheduler, under Robolectric on SDK 34.
- `composeApp/src/commonTest/kotlin/com/nuvio/app/features/downloads/ProfileSeasonQueueIsolationTest.kt` (**PQ**). Uses the real `SeasonQueueStorage` and `SeasonQueuePolicy`.
- `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/player/ProfileSubtitleSettingsIsolationTest.kt` (**PS**). Uses the real `PlayerSettingsRepository` and `PlayerSettingsStorage`.
- `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/profiles/ProfilePinOfflineTest.kt` (**PP**). Uses the real `ProfileRepository.verifyPin`, offline path.

Earlier evidence that this file builds on: the race harness `.github/plus/queue-race-harness` runs
the real `SeasonDownloadQueue.kt` and covers r1–r8, r13 (switch during fetch, resolve or hand-off),
r8/r17 (restart with two profiles) and r18 (pause all per profile). Unit tests:
`SeasonQueuePolicyTest` (F2 cases) and `SeasonQueueStorageTest`.

| Case | Downloads | Season queue | Subtitle settings | Download preferences | PIN (offline) |
|---|---|---|---|---|---|
| **N** normal use | PD `switchingDuringAnActiveDownloadKeepsEachProfilesItemsApart` (also checks the library downloads tab per profile) | harness r1–r8, r13; `SeasonQueuePolicyTest.switchingProfilesKeepsTheOtherQueueUntouched` | PS `eachProfileKeepsItsOwnSubtitleSettingsAcrossSwitches` | PD `downloadPreferencesAreSharedByEveryProfileAndKeptAcrossSwitches` (shared by design) | PP `eachProfileOpensOnlyWithItsOwnPin`, `aProfileWithoutAPinOpensAndALockedOneWithoutACacheStaysLocked` |
| **F** expected failure | PD `aDamagedListInOneProfileNeitherWipesNorLeaksTheOther`, `aProfileWithNothingStoredStartsEmptyAndWritesNothing`, `aNewDownloadInADamagedProfileReplacesOnlyThatProfilesUnreadableList` (F2) | PQ `aDamagedEntryOfOneProfileLeavesTheOtherProfilesQueueWhole`, `damagedHoldsReleaseEveryProfilesHoldsButKeepEachEpisodesOwnPause` (F3) | PS `aDamagedAppearanceInOneProfileFallsBackThereOnly` | covered by `PlusDownloadsMigrationTest` (decoding); a damaged value is not re-read in-process (singleton) — NOT TESTED here | PP `aDamagedPinCacheRefusesThatProfileOnlyAndIsDroppedOnTheNextStart`, `aPinChangedOnAnotherDeviceIsNotAcceptedFromTheOldCache` |
| **I** cancel / interrupt | PD `pausingOrCancellingInOneProfileLeavesTheOtherProfilesDownloadRunning` | harness r7 (remove then late answer), r11–r16 | — (no operation to interrupt) | — | — |
| **R** resume / restore | PD `comingBackWhileItStillRunsContinuesTheSameTransferInItsOwnProfile`; the switch-back half of the N test (a download that finished while away is restored as Completed in its own profile) | PQ `aRestartReturnsEachProfilesQueueAndPausesWhicheverProfileIsActive` (resume all releases one profile only) | PS `aRestartBringsBackEachProfilesSubtitleSettings` | — | PP `theOfflinePinStillWorksAfterARestart` |
| **P** persistence after restart | PD `aRestartBringsBackEachProfilesOwnList` | PQ `aRestartReturns…`, `aQueueFromBeforeProfilesGoesToTheProfileActiveAtTheFirstStartOnly`; harness r8, r17 | PS `aRestartBringsBackEachProfilesSubtitleSettings` | PD `downloadPreferences…` (stored value read back) | PP `theOfflinePinStillWorksAfterARestart`, `aDamagedPinCache…` (restart half) |
| **D** no damage to related features | Every PD test also checks the other profile's stored list | PQ `aDeletedProfilesEpisodesStayStoredButNeverReachAnotherProfile` | PS damage test checks profile 1 is unchanged | — | PP damage tests check the other profile's cache is kept |

"Restart" in these host tests means fresh in-memory state over the same storage:
`DownloadsRepository.clearLocalState`, `PlayerSettingsRepository.clearLocalState`,
`ProfileRepository.clearInMemory`, then a cold-start load. `SeasonDownloadQueue` is a singleton that
cannot be restarted inside one process. Its restart is covered by harness r8 and r17, which use a
fresh class loader. Here, the PQ tests decode what was stored and decide with the real policy.

## Findings (Nuvio's code; no behaviour changed in this PR)

- **F1 — a deleted profile's index is reused together with its local Plus data.**
  `createProfile` takes the smallest free index (`(1..MAX_PROFILES) - existing`), and
  `deleteProfile` leaves `downloads_payload_<n>`, the queue entries with `profileId = n` and the
  subtitle keys `_<n>`. So a new profile that gets index n sees the deleted profile's download list
  and its waiting season episodes, and the queue will search for those episodes under it. The test
  `PQ aDeletedProfilesEpisodesStayStoredButNeverReachAnotherProfile` shows the queue side (its last
  assertion). The downloads side follows from the key and needs no test of its own. A fix would be
  to clear the Plus data for that index on delete, or when the index is reused. That is a behaviour
  change and is left for the owner to decide.
- **F2 — an unreadable download list is replaced on that profile's first write.**
  `DownloadsCodec.decodeItems` returns an empty list for a broken payload, and `persist()` later
  writes the list held in memory. The loss stays inside that profile. The season queue, unlike this,
  keeps unreadable data under `season_queue_unreadable`.
- **F3 — the holds of all profiles share one stored value.** If that value cannot be read, no profile
  stays held by "pause all" or by a paused season, and the episodes waiting in them become free to
  start. Episodes that were already paused stay paused, because their pause is stored in the entry.
- **Switching does not cancel running transfers** (`DownloadsRepository.onProfileChanged`). The
  transfer goes on. Its progress is not written into the other profile's list, and its result reaches
  its own profile when that profile is active again (`restoreItem` reads it from the transfer store).
  The tests show this. It is not a defect.

## NOT TESTED (with reasons)

- **Sign-in, sign-out, session expiry, account switch:** need the Nuvio account server
  (`AuthRepository`, Supabase). Not reachable from host tests, and no test account in CI.
- **Cloud sync of profiles and settings** (`pullProfiles`, `pushProfiles`, `ProfileSettingsSync`):
  need the server.
- **Setting, changing or clearing a PIN; online PIN verification; lock-out after wrong tries**
  (`retryAfterSeconds`): server RPCs. Only the offline check is covered (PP).
- **`deleteProfile` itself:** the signed-in path needs the server. The offline path
  (`isAnonymous`) needs an anonymous session that `AuthRepository` creates only through
  `initialize()`, which starts the server session job. What deletion leaves behind is stated above
  from the code, and the queue side is tested (PQ).
- **`ProfileRepository.selectProfile` end to end:** it reloads about 30 Nuvio repositories, some of
  which touch the network. The tests apply the switch the way it reaches the Plus repositories.
- **The PIN screen and the profile picker UI:** existing `ProfileSelectionRoutingTest` (Nuvio); the
  feel and the look need a device.
- **Damaged download preferences read again in the same process:** `DownloadPreferences` loads
  once per process. Decoding is covered by `PlusDownloadsMigrationTest`.
