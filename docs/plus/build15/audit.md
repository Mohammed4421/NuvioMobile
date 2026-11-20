# Build 15 — audit register (P1)

One row per finding. Decision: KEEP / FIX / REFACTOR / REMOVE / DEFER. Status:
DISCOVERED → SPECIFIED → IMPLEMENTED → VERIFIED → ACCEPTED. Evidence is a test, a CI run or a
measurement that anyone can repeat. Known issues from before Build 15 are in `known-issues.md`.

| ID | Area | Finding | Evidence | Severity | Decision | Status |
|---|---|---|---|---|---|---|
| B15-UI-01 | Lists | Generated lazy-list keys could equal a real key (`section`, `section#0`, `section` → `section#0` twice; Compose rejects it) | Reproduced with the original helper; 20,000 random lists after the fix | P2 | FIX (#15) | VERIFIED (CI run 44735358734, post-merge 44738682357) |
| B15-SUB-01 | Subtitles | Both subtitle parsers quadratic on malformed input (unclosed `<b `, `{`, `<p>`, `<tt `): 60 KB → 5.2 s; 1 MB → minutes; runs while a download is prepared | Scaling run; 60,000-file old/new comparison (0 differences on well-formed input) | P1 | FIX (#17) | VERIFIED (CI run 44749841046, post-merge 44753001824) |
| B15-SUB-02 | Subtitles | Local subtitle import: size cap, empty-file check, ASCII cache name, decode in `runCatching` on IO | Code review of `LocalSubtitlePicker.android.kt` | — | KEEP | VERIFIED (review) |
| B15-SUB-03 | Subtitles | Imported subtitles accumulate in `cache/device-subtitles` (one file per import) | Code review | P3 | DEFER (cache dir; Android clears it under pressure) | SPECIFIED |
| B15-DL-01 | Downloads | Free-space check counts old progress when a `.part` document was deleted outside the app (K-03) | Code review of `executeToFolder` | P3 | DEFER (no data loss; needs a SAF provider test to change safely) | SPECIFIED |
| B15-CI-02 | Repository | Gitlinks without `.gitmodules` entries (K-05) | CI teardown log | P3 | DEFER (Nuvio's tree; no build effect) | SPECIFIED |
| B15-UI-03 | Localization | `runBlocking` resource reads in `LocalizedUiText` (Nuvio's code) | Code review only | — | DEFER until measured on a device (P5) | DISCOVERED |
| B15-SUB-04 | Subtitles | Manual sync on a local subtitle: claim "loader is HTTP-only" is wrong (Android reads `file:` in `httpGetTextWithHeaders`); the real defect is the offline early return in `loadSubtitleAutoSyncCues` (`PlayerScreenRuntimeSubtitleActions.kt` l.75 @ b6d8776e) that blocks a local file with no network. iOS has no `file:` path | `LocalSubtitleSyncCuesTest` (local file + HTTP) | P1 | FIX (#24); iOS DEFER | IMPLEMENTED |
| B15-SUB-05 | Subtitles | Subtitle gestures save every frame: `updateSubtitleGesture` (l.401–409 @ b6d8776e) calls `setSubtitleStyle` (l.687–709: 12 `apply()` writes + sync publish) | `SubtitleGestureSessionTest` (drag, cancel, lock, rotation/dispose keep the final value) | P2 | FIX (#25): live preview, one save at gesture end | IMPLEMENTED |
| B15-SUB-06 | Subtitles | Unknown extension silently renamed `.srt` (`LocalSubtitlePicker.android.kt` l.36, l.76 @ b6d8776e) | `LocalSubtitleFormatTest` (content detection; non-subtitle refused; Windows-1256, ASS, SRT kept) | P2 | FIX (#26) | IMPLEMENTED |
| B15-DL-02 | Downloads | Claimed race in `SeasonDownloadQueue` / `DownloadPreferences` (no Mutex). All writers run on Main: queue scope `Dispatchers.Main` (l.183), job service `Main.immediate`, preferences set from UI; search is the only `Default` work and returns to Main | Harness r1–r13 incl. two seasons at once, double start, pause/resume, remove during finish, profile switch, restart: no lost update, no duplicate enqueue | — | KEEP (risk unproven; no code change) | VERIFIED (harness) |
| B15-DL-03 | Downloads | A paused season episode held every later episode of the profile (`SeasonQueuePolicy.busy` counted Paused; by design since Build 13, reversed by the owner's decision). No "pause season" / "pause all" in the app; resuming a paused episode while the queue ran started a second download | Policy test and harness r11 fail on the old code; `SeasonQueuePolicyTest`, `SeasonQueueStorageTest`, `SeasonPauseAndroidTest`, harness r11, r14–r21, emulator update with a seeded paused season | P2 | FIX (season-pause PR): episode pause skips, season/all holds, Resuming waits its turn. Owner's rule 2026-11-20: "pause all" is per profile, covers every download of it, holds new downloads (added paused, `DownloadEnqueueResult.Held`) and never resumes one the user paused on its own (r19–r21) | IMPLEMENTED |
| B15-EXT-01 | Localization | External claim: `%1$s` broken in `values-ar/strings_ratings.xml` | File read @ b6d8776e and b9369bb: placeholders correct (4 × `%1$s`) | — | KEEP (claim contradicted) | VERIFIED (review) |
| B15-EXT-02 | Player | External claim: `touchGesturesEnabled` ignored | Checked through `playerSurfaceDragGestures` (`touchGesturesEnabledState`); covered by `PlayerSurfaceGesturesTest` | — | KEEP (claim contradicted) | VERIFIED (review) |
| B15-EXT-03 | Architecture | External suggestion: move player state to a ViewModel | Design preference, no defect shown | — | KEEP (out of scope) | — |
| B15-REL-01 | Release notes | Android 9 note missing in the Arabic part of custom-14 notes (K-02) | Release page | P3 | FIX by hand on the release page (session cannot edit releases); future notes carry the changelog text | SPECIFIED |

## Areas reviewed and still to review

| Area | State |
|---|---|
| Updater / identity / signing | Reviewed in Build 14 (device tests on Android 9 and 14, release gate) |
| Season downloads, profiles | Race harness in CI (r1–r13; B15-DL-02); E1/r2 in Build 14 |
| Subtitle parsing (player, auto-sync, local import) | Reviewed (B15-SUB-01..03) |
| Add-on links | Confirmation since Build 14 |
| Player engines, HDR, gestures | NEEDS PHYSICAL DEVICE |
| UI polish, RTL screenshots | Not started (P4) |
| Performance | Not measured (P5) |
| PortKit | P7 step 1: foundation (registry, levels, policy, inventory) |
