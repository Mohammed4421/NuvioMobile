# Build 16 — release readiness (S16, S17)

Status of every readiness condition and deliverable on the **release candidate**, commit
`da69725e565643a87fbae1fefee6eb767391b2c4` of `custom-subtitles` (the merge of #139, which froze the
scope). Every workflow was run by hand on that exact commit (S20-02). Later commits change only
`docs/plus/build15` (this report and the registers). The release run builds, signs and tests the
final head again before anything is published.

## Runs on the release candidate (`da69725e`)

| Workflow | Run | Result |
|---|---|---|
| Nuvio Plus checks (build, signing check, update check, every host test, emulator update on Android 9 and 14, reference screenshots) | [45723293617](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45723293617) | success |
| Nuvio device tests (Android 28, 34, 36) | [45723298134](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45723298134) | success |
| Download notification and interruption tests (Android 28 full, 34 and 36 pause) | [45723302766](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45723302766) | success |
| Static analysis (lint and detekt against Build 14) | [45723307327](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45723307327) | success |
| PortKit transfer tests (upstream and a fork) | [45723311777](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45723311777) | success |
| Playback compare (Build 14 vs candidate) | [45723315831](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45723315831) | success |
| Download compare (Build 14 vs candidate) | [45723320487](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45723320487) | success |
| Build 15 requirements gate (push) | [45723222651](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45723222651) | success |
| Performance compare (APK of the checks run above) | [45725683760](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45725683760) | see the register |
| Signed validation run with the real key (upgrade matrix), on `65a5e800` | [45642292281](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45642292281) | success; repeated by the release run on the final head |

## Readiness conditions (§16)

| ID | Condition | State | Evidence |
|---|---|---|---|
| S16-01 | Approved mandatory requirements done | Done | `requirements.json`: every PRE-RELEASE REQUIRED item VERIFIED or ACCEPTED; `release-readiness-check.py` runs in the publish job, after signing and the upgrade matrix and before anything is published |
| S16-02 | CI gates pass on the final commit | Done on the candidate | Runs above; the required checks of ruleset 27721857 on every merge |
| S16-03 | No open release blockers | Done | `risks.json`: no open risk blocks the release. RISK-0004 (P0) is the owner's tap on Resume on Android 14+, deferred to PRV-05 by the owner (NVP15-CHG-0005); RISK-0003/0006/0015 are the owner's post-release checks; RISK-0010/0011 are P2 and deferred with reasons |
| S16-04 | Critical function and upgrade tests pass | Done | Journeys on the candidate (device, notification, Plus checks); upgrade 12/13/14/15 → 16 with the real key on Android 9 and 14, Android 8 refused (signed run 45642292281), repeated in the release run |
| S16-05 | Data, signing and update safety proven | Done | Signed run: v3 signature by `99c0a98c…`, lineage `40335e31 → 99c0a98c`, same contents as tested, data kept; in-app update check refuses foreign, old-key and damaged APKs; `test_scope_guard.py`: package `com.nuvio.app`, build files equal to Build 14 |
| S16-06 | Visual polish meets the review criteria | Done | 84 reference shots reviewed by eye and compared with approved baselines (#119, #124), contrast ≥ 4.5:1, touch areas ≥ 48 dp; P4 gate PASS; TalkBack is PRV-08 |
| S16-07 | No performance regression outside limits | Done | P5 gate PASS (owner, 2026-11-25); playback and download compare on the candidate; performance compare on the candidate's APK |
| S16-08 | Mandatory PortKit tests pass | Done | PortKit transfer tests on the candidate (required check on every merge) |
| S16-09 | Every unrun test and remaining risk documented | Done | `untested` lists in `requirements.json`, `risks.json`, PRV-01..08, the six-case matrix in `journeys.md` |
| S16-10 | Source protection and release controls | Done | `ruleset-27721857.json`: pull request, six required checks, merge commits only, no deletion or force push, bypass limited to pull requests; `plus-release` approval for signing and for publishing |
| S16-11 | Build 14 vs Build 16 comparison | Done | `CHANGELOG.md`, lint/detekt compare, playback/download/performance compare on the candidate, `scope-guard.json` (dependencies unchanged) |
| S16-12 | Final owner approval | Accepted in advance | NVP15-CHG-0007: signing then publishing of Build 16 approved by the owner on condition that every PRE-RELEASE REQUIRED item holds; given at the release run through the `plus-release` environment |

## Deliverables (§17)

| ID | Deliverable | Where |
|---|---|---|
| S17-01 | Source and commit SHA | Release candidate `da69725e`; the release names its exact commit (`test_a_release_names_the_exact_commit_it_publishes`) |
| S17-02 | Final release after approvals | GitHub release Build 16, published by the release run only after the owner's `plus-release` approval |
| S17-03 | Audit report | `audit.md`, `findings.json`, `inventory.md` |
| S17-04 | Architecture decision records | `architecture.md` (§5 criterion per decision; guarded by `test_architecture.py`) |
| S17-05 | Functional regression report | `journeys.md` (six-case matrix), runs on the candidate above |
| S17-06 | Visual review and screenshot baselines | `visual-review.md`, `screenshot-baselines/` (84 shots, compared in every PR) |
| S17-07 | Performance before/after | `baseline.json`, playback/download/performance compare runs |
| S17-08 | Security and data migration report | `security.md`, `security-logs-storage.md`, migration tests, the signed upgrade matrix with representative data |
| S17-09 | Device testing matrix | `docs/plus/device-test-matrix.md`; Android 9, 14 and 16 emulators on the candidate |
| S17-10 | PortKit package, tests and matrix | `portkit/`, `portkit/reports/compatibility-matrix.md`, PortKit run on the candidate |
| S17-11 | Release readiness report | This file; `release-scope.json` (checked by the release run) |
| S17-12 | Known limitations and deferred issues | `known-issues.md`, deferred findings in `findings.json`, `risks.json`, PRV-01..08 |
| S17-13 | Upgrade, rollback and maintenance guide | `docs/plus/release.md`, `docs/plus/rollback.md`, `docs/plus/signing-migration.md` |

## What blocks approval (P8-13)

An untested or failed **critical** row blocks the release; it is never a "known limitation".

| Kind | Check | Where it runs | Blocks | State |
|---|---|---|---|---|
| Update safety | Install over Builds 15, 14, 13 and 12 signed with the real key, as an update | Release run (`release-upgrade-test.sh`) | **yes** | PASS on `65a5e800`; repeated on the final head |
| Update safety | Package `com.nuvio.app.plus`, certificate `99c0a98c…`, v3 lineage from the Build 12 key | Release run (sign and verify) | **yes** | PASS on `65a5e800`; repeated |
| Update safety | Android 8 refuses; foreign, old-key and damaged APKs refused | Release run; `UpdateArchiveCheckTest`; emulator update check | **yes** | PASS (candidate and signed run) |
| Data protection | Settings, subtitle settings, add-ons, profiles, downloads, the season queue and a partial file kept after the update | Release run; migration tests | **yes** | PASS on `65a5e800`; repeated |
| Data protection | Download files complete and unchanged after network loss and process death | `download-interruptions-test.sh` | **yes** | PASS on the candidate |
| Data protection | No credentials sent to another site on redirect | `PlaybackRedirectCredentialsTest` | **yes** | PASS on the candidate |
| Non-critical | Android 17, HDR/DV, the owner's sources and accounts, gestures, SD card, Resume from the notification on 14+, real performance, TalkBack | Owner's device (PRV-01..08) | no | Post-release |

## Owner's checks after installing the release (POST-RELEASE VALIDATION)

Only a phone can show these; they stay NOT TESTED until the owner reports them:

1. Your usual sources and add-ons play (ExoPlayer and mpv), including ones with tokens (PRV-04, RISK-0006).
2. HDR / Dolby Vision on your screen (PRV-01).
3. Look and smoothness in daily use (PRV-02); start time, memory and smoothness (PRV-06).
4. Subtitle gestures (move, resize) feel right (PRV-03).
5. Resume a paused download from the notification on Android 14+; download to an SD card (PRV-05).
6. If your phone runs Android 17: open, play, subtitles and a download (PRV-07).
7. TalkBack reads the Plus screens and moves through them in a sensible order (PRV-08).
8. Sign in, sync, PIN online and deleting a profile on the server with your account (RISK-0015).
