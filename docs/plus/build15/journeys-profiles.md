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

**Deleting a profile, after PR #101.** The old F1 behaviour is fixed: when an
authenticated deletion succeeds locally (anonymous profile) or after the server
confirms a deletion, `ProfileRepository.applyProfileSnapshot` calls
`ProfileDeletedDataCleanup.clearConfirmedSlot(index)` **before** publishing a
profile index to a new owner. The cleanup clears that index's stored Plus
download list, uncompleted transfers and partial files, season-queue entries
and holds, and subtitle appearance. It keeps completed downloaded media files
on disk by the owner's decision. It never erases another profile's records.
`ProfileDeletedDataCleanupTest` and `ProfileDeletedQueueCleanupTest` verify
these local invariants on #101; the signed-in server RPC, remote snapshot
propagation and second-device behaviour remain **NOT TESTED** without a test
account/server. The historical finding F1 below is retained as closed evidence.

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

## Findings and follow-up (PR #94 baseline; PR #101 remediation)

- **F1 — CLOSED by PR #101 (NVP15-RISK-0012): deleted-index Plus data.**
  Before the fix, `createProfile` reused a free index but that index's Plus
  data remained. PR #101 added cleanup on the confirmed deletion/snapshot path.
  Android host tests now cover downloads, active transfer detach, unfinished
  partial-file cleanup and subtitle preferences; common tests cover season
  queue entries/holds. All mandatory CI workflows for PR #101 passed on
  `6245b2ae639b1103f606d88e38dbcd995c62ed44` (including Nuvio Plus
  checks run 45151305429 and device tests run 45151305641). **No real account
  server or second-device deletion test was run**; these remain separate gaps.
  Completed media files stay on disk without appearing in a reused profile.

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
- **`deleteProfile` end to end with a live account:** the signed-in path requires the
  server. The anonymous path requires an initialized authenticated anonymous session. The
  confirmed-deletion cleanup hook and storage effects are covered by PR #101 host/common
  tests; end-to-end server deletion, remote sync and a second device remain untested.
- **`ProfileRepository.selectProfile` end to end:** it reloads about 30 Nuvio repositories, some of
  which touch the network. The tests apply the switch the way it reaches the Plus repositories.
- **The PIN screen and the profile picker UI:** existing `ProfileSelectionRoutingTest` (Nuvio); the
  feel and the look need a device.
- **Damaged download preferences read again in the same process:** `DownloadPreferences` loads
  once per process. Decoding is covered by `PlusDownloadsMigrationTest`.
