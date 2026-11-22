# Nuvio Plus — publishing a release

Merging pull requests into `custom-subtitles` (or `plus-beta`) **never publishes**. Every merge
starts *Build Custom APK*, which builds and checks the branch, then stops with
"Built and checked after the merge; not published". So a series of merges (for example the four
pull requests of one build) cannot release a partial build, and no run has to be cancelled by hand.

## Publishing

1. Merge every pull request of the build and wait until the last merge's *Build Custom APK* run is
   green.
2. Start the release by hand: Actions → *Build Custom APK* → *Run workflow*, branch
   `custom-subtitles`, and `release_commit` = the full commit of the branch head (shown by the last
   run's notice). Any other value, or a branch that moved on meanwhile, publishes nothing.
   Right before release signing (after approval 1) and right before publishing (after approval 2)
   the run reads the branch head from GitHub again: if the branch moved meanwhile, that commit is
   neither signed nor published.
3. The run repeats every check and the review gate (the head must be a tested pull request merged by
   the owner), then waits for the owner's approval of release signing (`plus-release`).
4. The signed APKs are verified (`release-verify`) and installed as an update on emulators: from the
   previous release and from build 12, with the app's own update check, data kept, and Android 8
   refused (`release-upgrade`).
5. Publishing waits for the owner's second approval, then makes the `custom-N` release.

Nothing above is skipped for a rescue build (`rollback.md`).

In a hand-started run the review gate also checks the release scope
(`docs/plus/build15/release-scope.json`, `.github/plus/release-scope-check.py`): every listed pull
request merged and part of the commit, the 1.1.0-beta.2 trial merge (#2) not, the branch not moved,
the requirements gate passed on the commit, and the mandatory requirement list and PortKit version
unchanged. A pull request merged for the build must be added to the manifest before the run.

A failed run (a failed check, a wrong `release_commit`, a moved branch, a release scope mismatch) makes no GitHub release: its
report is in the run's summary and in the `nuvio-plus-failure-report` artifact with the logs.

| Event | Checks | Review gate | Release signing | Update tests | Published |
|---|---|---|---|---|---|
| Merge (push) | yes | yes | no | no | never |
| Run by hand, `release_commit` = head | yes | yes | after approval 1 | yes | after approval 2 |
| Run by hand, anything else | yes | yes | no (fails) | no | never |
