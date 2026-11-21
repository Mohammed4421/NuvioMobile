# Known issues before Build 15

Recorded at the Build 14 baseline (`18276c69`) so that Build 15 is neither blamed for them nor hides a
new regression behind them. Severity: P0 release blocker … P3 optional.

| ID | Area | Issue | Evidence | Severity | Status |
|---|---|---|---|---|---|
| K-01 | Updater | Builds 12 and 13 refuse every update on Android 9 (archive read without `GET_SIGNATURES`) | PR #14, device log on Android 9 | P1 | Fixed in 14; users of 12/13 on Android 9 install 14 once by hand (release notes, English part) |
| K-02 | Release notes | The Android 9 note is only in the English part of the custom-14 notes | custom-14 release page | P3 | Open |
| K-03 | Downloads | If a `.part` file is deleted outside the app, the free-space check still counts its old progress; the download then fails on a full disk instead of before it; no data loss | `docs/plus/execution-tracker.md` B14-3 | P3 | Open |
| K-04 | Lists | Generated lazy-list keys could collide with a real key shaped like `name#0` | Build 15 audit phase 1 (B15-UI-01) | P2 | Fix in PR #15 |
| K-05 | Repository | Gitlinks `libass-android`, `vendor/TorrServer`, `vendor/quickjs-kt` have no `.gitmodules` entry; checkout teardown logs `No url found for submodule path` (exit 128, job still passes) | CI logs; B15-CI-02 | P3 | Open |
| K-06 | Testing | Not tested on a physical device: playback engines, HDR, SD-card downloads, the add-on confirmation dialog | `docs/plus/device-test-matrix.md` | — | NEEDS PHYSICAL DEVICE |
| K-07 | UI thread | `LocalizedUiText` reads resources with `runBlocking` | B15-UI-03 | — | Hypothesis; measure before changing |
| K-08 | Subtitles | The subtitle parsers (Nuvio's and auto-sync's) took quadratic time on malformed files: 60 KB of `<b ` 5 s, 60 KB of `{` 0.6 s, 1 MB of unclosed `<p>` minutes; used while a download is prepared | Build 15 fuzz/scaling run (B15-SUB-01) | P1 | Fixed in Build 15 (linear scan; 0 differences on 60,000 well-formed random files) |
