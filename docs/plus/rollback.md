# Nuvio Plus — safe rollback

Android never installs a lower version code over a higher one, and an older build may not read data
written by a newer one. So a rollback is a **new forward release**, not reinstalling an old APK.

1. **Keep history.** Every published build stays on the Releases page with its APKs, notes, checks
   and the failure pre-releases with their logs. Do not delete releases.
2. **Recovery release.** Revert the bad change on a `plus-fix/rollback-<build>` branch (`git revert`,
   not a reset), push it, let the checks pass, merge it into `custom-subtitles`. The build gets the
   next build number, so its version code is higher than the bad build and installs over it.
3. **Data.** Before releasing the recovery build, check what the bad build wrote: new preference keys,
   new fields in download records, the season queue, the transfer store. The recovery code must read
   them (unknown JSON fields are ignored; new enum values are not). Add a migration test for any data
   the bad build changed.
4. **Signing.** A recovery release is signed like every release since build 13: with the release
   key `99c0a98c…1e35` in the `plus-release` environment, through `release-sign`, the emulator move
   tests and the owner's two approvals. **Never with the old public key** (`signing/nuvio-plus.p12`):
   `release-identity` refuses to publish anything it signed, and phones that moved to build 13 or later
   refuse it anyway. No rollback changes the key or the package `com.nuvio.app.plus`.
5. **Updater.** Users on the bad build get the recovery build through the in-app updater like any
   other update; nothing else is needed.
6. **Upstream merges.** To undo a Nuvio merge, revert the merge commit (`git revert -m 1 <merge>`),
   and record the decision in that release's review file.

## Data written since build 13, and going back

What build 13 (#3, #5, #6) writes, and what an older build does with it:

| Stored data | Build 13 writes | Build 12 reading it | Proof |
|---|---|---|---|
| Season queue (`extra_season_queue`) | Only Build 12's four states. A paused episode is stored as `Downloading` plus `"paused": true`; also `profileId` | Reads every entry (it ignores unknown keys). The paused episode counts as Downloading, so there it leaves the queue and the next one starts, as in Build 12. Profiles share the queue again (Build 12 behaviour) | `SeasonQueueStorageTest.build12ReadsTheQueueBuild13Writes` decodes build 13's output with Build 12's exact model and decoder |
| Unreadable queue data (`extra_season_queue_unreadable`) | Only when something could not be read | Ignores it | — |
| Downloads list, settings, account | Unchanged format | Unchanged | Existing migration tests |

Why this matters: Build 12 decodes the whole queue at once. Any state it does not know makes it start
empty and overwrite the stored queue (`SeasonQueueStorageTest.aStateBuild12DoesNotKnowWouldEraseItsWholeQueue`).
Storing a literal `Paused`, as the first F1 change did, would have erased the queue on any way back.

**Rules for every later change:**
- Never write a stored value an older published build cannot read. Add fields; never add enum values
  to stored data without mapping them to old ones.
- A test must decode the new format with the previous build's model, copied into the test as
  `SeasonQueueStorageTest` does.

**Going back after build 13 (the key change):**
- **Build 13 is the floor.** Build 12 is signed with the old key: a phone with build 13 or later
  refuses it as an update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, proven on Android 9 and 14 in the
  `release-upgrade` job) and only an uninstall, which erases the app's data, would allow it. Going back
  to build 12 is not a rollback option.
- **Only a forward release.** Revert on `plus-fix/rollback-<build>` and publish it as the next build
  number: same package, same release key, a higher version code. It must read everything the bad build
  wrote (point 3), with a test; `SeasonQueueStorage` stays (reading and writing) even when reverting
  F1 or F2.
- **Its gates are the same as any release:** every check, the review gate, `release-sign`,
  `release-verify`, the emulator moves (from the previous build and from build 12) and the owner's
  approvals. A rollback is no reason to skip one.
- **Stopping the spread of a bad build at once:** mark its release as a pre-release. The stable
  updater then stops offering it; phones that already updated keep it until the recovery build.
  Do not delete the release or its APKs (point 1).
