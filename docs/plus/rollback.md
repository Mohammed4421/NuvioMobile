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
4. **Signing.** The certificate is not changed by a rollback. A change of key is a separate plan
   with its own approval (users reinstall once, and lose local data unless it is exported).
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

**Going back after build 13:**
- **Preferred: a forward release.** Revert on `plus-fix/rollback-13` and publish it as build 14, which
  installs over 13. Keep `SeasonQueueStorage` (reading and writing) even when reverting F1 or F2, so
  the queue keeps being read.
- **Reinstalling Build 12** is impossible as an update (lower version code). Only an uninstall allows
  it, and that erases all app data anyway.
- **Stopping the spread of a bad build at once:** mark its release as a pre-release (or delete it).
  The stable updater then stops offering it. Phones that already updated keep it until the next build.
