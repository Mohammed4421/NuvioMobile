# Build 15 — release readiness (S16, S17)

Status of every readiness condition and deliverable, with where it lives. Written before the release
candidate exists: the lines marked **on the candidate** are filled from the release run of the final
commit (S20-02); nothing here is reused from an older commit after the source changed.

## Readiness conditions (§16)

| ID | Condition | State | Evidence / where |
|---|---|---|---|
| S16-01 | Approved mandatory requirements done | Open | `requirements.json` (gate output: implementation / verification / acceptance) |
| S16-02 | CI gates pass on the final commit | On the candidate | Required checks of ruleset 27721857 on the final commit |
| S16-03 | No open release blockers | Open | `risks.json`: 0002 closed by the owner's ruleset (re-read 15:27), others listed below |
| S16-04 | Critical function and upgrade tests pass | On the candidate | Release run: upgrade 14/13/12 → 15 with the real key, Android 8 refusal |
| S16-05 | Data, signing and update safety | On the candidate | Release run: certificate SHA-256 `99c0a98c…1e35`, package `com.nuvio.app.plus`, data kept |
| S16-06 | Visual polish | Done for the reviewed screens | `visual-review.md` (72 reference images, six setups); P4-11 comparison against approved baselines in every PR; labels asserted (P4-09); fixes B15-UI-02, 04, 05, 06, 08, 09. Dialogs and animations: owner's device |
| S16-07 | No performance regression outside limits | Done on the emulator baseline (NVP15-CHG-0004) | `performance-compare.yml` runs, `performance-limits.json`; frame timing and real playback: owner's device |
| S16-08 | Mandatory PortKit tests pass | Every PR | "Transfer, build and test (upstream and a fork)" (required check) |
| S16-09 | Every unrun test and remaining risk documented | Done | `untested` lists in `requirements.json`, `risks.json`, the six-case matrix in `journeys.md` |
| S16-10 | Source protection and release controls | Done | Ruleset 27721857: pull request + 6 required checks, no deletion, no force push (re-read 2026-11-21 15:27) |
| S16-11 | Build 14 vs Build 15 comparison | Done | `CHANGELOG.md` (Build 15 section), `security-surface.json` (equal to Build 14), lint/detekt compare, performance compare |
| S16-12 | Final owner approval | Pending | Asked only after the release run passes |

## Deliverables (§17)

| ID | Deliverable | Where |
|---|---|---|
| S17-01 | Source and commit SHA | Final commit of `custom-subtitles` (on the candidate) |
| S17-02 | Final release after approvals | GitHub release, only after the owner approves |
| S17-03 | Audit report | `audit.md`, `findings.json`, `inventory.md` |
| S17-04 | Architecture decision records | `architecture.md` |
| S17-05 | Functional regression report | `journeys.md` (six-case matrix with runs) |
| S17-06 | Visual review and screenshot baselines | `visual-review.md`; baselines in `screenshot-baselines/` compared in each PR (P4-11); findings B15-UI-* |
| S17-07 | Performance before/after | `baseline.json` (performance), `performance-compare.yml` runs |
| S17-08 | Security and data migration report | `security.md`; migration tests in the host tests (`PlusDownloadsMigrationTest`, `PlusSubtitleMigrationTest`) |
| S17-09 | Device testing matrix | `docs/plus/device-test-matrix.md`; device rows in `journeys.md` |
| S17-10 | PortKit package, tests, matrix | `portkit/`, `portkit/reports/compatibility-matrix.md` |
| S17-11 | Release readiness report | This file; release scope `release-scope.json`, checked by the release run (S12-02) |
| S17-12 | Known limitations and deferred issues | `known-issues.md`, deferred findings in `findings.json` |
| S17-13 | Upgrade, rollback and maintenance guide | `docs/plus/release.md`, `docs/plus/rollback.md`, `docs/plus/signing-migration.md` |

## What blocks approval (P8-13)

The owner is asked for the final approval only when every **critical** row below has passed on the
final commit. An untested or failed critical row is a blocker, never a "known limitation".
Non-critical gaps are listed for the owner's record and do not block.

| Kind | Check | Where it runs | Blocks approval |
|---|---|---|---|
| Update safety | Install over Build 14, 13 and 12 signed with the real key; installs as an update | Release run (`release-upgrade-test.sh`) | **yes** |
| Update safety | Same package `com.nuvio.app.plus` and certificate SHA-256 `99c0a98c…1e35`; v2 signature only | Release run (sign and verify steps) | **yes** |
| Update safety | Android 8 refuses the APK; a foreign-signed APK is refused by the in-app updater | Release run (`release-upgrade-test.sh`); `UpdateArchiveCheckTest` | **yes** |
| Data protection | Settings, subtitle settings, add-ons, downloads and the season queue kept after the update | Release run; `PlusDownloadsMigrationTest`, `PlusSubtitleMigrationTest`, `SeasonQueueStorageTest` | **yes** |
| Data protection | Download files complete and unchanged after network loss and process death | `download-interruptions-test.sh` (Android 9, 14, 16) | **yes** |
| Data protection | No credentials sent to another site on redirect | `PlaybackRedirectCredentialsTest` | **yes** |
| Non-critical | Android 17 (emulator image fault), HDR/DV, the owner's sources, gestures, SD card, Resume from the notification on 14+, real performance | Owner's device | no (owner's record) |

## Owner's checks on the final release (NVP15-CHG-0003)

Only a phone can show these; they stay NOT TESTED until the owner reports them:

1. Your usual sources play (ExoPlayer and mpv), including ones with tokens.
2. HDR / Dolby Vision picture on your screen.
3. Resume a paused download from the notification on Android 14+.
4. Subtitle gestures (move, resize) feel right.
5. Download to an SD card or a chosen folder.
6. Start time and smoothness in daily use.
7. If your phone runs Android 17: open, play, subtitles and a download (not testable on the emulator, see `device-test-matrix.md`).
