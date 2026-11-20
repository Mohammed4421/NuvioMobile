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
| B15-REL-01 | Release notes | Android 9 note missing in the Arabic part of custom-14 notes (K-02) | Release page | P3 | FIX by hand on the release page (session cannot edit releases); future notes carry the changelog text | SPECIFIED |

## Areas reviewed and still to review

| Area | State |
|---|---|
| Updater / identity / signing | Reviewed in Build 14 (device tests on Android 9 and 14, release gate) |
| Season downloads, profiles | Race harness in CI (r1–r9); E1/r2 in Build 14 |
| Subtitle parsing (player, auto-sync, local import) | Reviewed (B15-SUB-01..03) |
| Add-on links | Confirmation since Build 14 |
| Player engines, HDR, gestures | NEEDS PHYSICAL DEVICE |
| UI polish, RTL screenshots | Not started (P4) |
| Performance | Not measured (P5) |
| PortKit | Not started (P7) |
