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
