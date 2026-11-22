# Build 15 — structure decisions (P2)

Rule (owner, Build 15): no restructuring of sound parts; a change of structure needs a proven defect
or a measurement. Each decision below names its evidence. Measured at `custom-subtitles` 8c8098a8.

## Measurements

| Measure | Value | How to repeat |
|---|---|---|
| Plus-only Kotlin files (non-test) | 26 files, 2,718 lines | `git diff --numstat --diff-filter=A 511ebd5f HEAD -- '*.kt'` |
| Nuvio Kotlin files Plus modifies (non-test) | 45 files, +1,379 / −109 lines | same with `--diff-filter=M` |
| Every Plus change owned by a registered feature | yes (enforced) | `test_portkit.py` `test_every_plus_change_is_owned_by_a_feature` |
| Nuvio commits after 1.0.0 up to 1.1.0-beta.2 | 71 | `git rev-list --count 511ebd5f..1.1.0-beta.2` |
| Files changed by both Nuvio (to 1.1.0-beta.2) and Plus | 17 of 132 / 180 | `comm -12` of both `git diff --name-only` lists |
| **Conflicting files in a trial merge** with 1.1.0-beta.2 | **5 files, 9 conflicts, ~100 lines** | `git merge-tree --write-tree --name-only origin/custom-subtitles 1.1.0-beta.2` (in memory; nothing merged) |

1.1.0-beta.2 is a pre-release and is **not** merged into Build 15 (owner's rule); it is only the newest
upstream code available to measure the conflict surface against.

### Conflicts in the trial merge

| File (Nuvio's) | Conflicts | Lines in conflict | Plus feature |
|---|---|---|---|
| `features/player/StyledSubtitle.kt` | 3 | 39 | subtitle-style |
| `features/player/PlayerSettingsRepository.kt` | 2 | 13 | subtitle-style |
| `MainAppContent.kt` | 1 | 22 | library-downloads, addon-link-confirmation |
| `features/downloads/DownloadsScreen.kt` | 2 | 18 | download-location |
| `README.md` | 1 | 8 | — |

## Decisions

| ID | Part | Decision | Evidence | Condition / next step |
|---|---|---|---|---|
| A-01 | Overall layout (Plus in own files, small hooks in Nuvio's) | KEEP | 26 Plus files carry most code; 5 of 45 modified Nuvio files conflict with 71 upstream commits | Re-measure at every upstream sync |
| A-02 | Subtitle style inside `StyledSubtitle.kt` and `PlayerSettingsRepository.kt` | DEFER to the next upstream sync (report below) | Conflict surface grew: 7 conflicts, 87 lines on 2026-11-22 | Do as the first step of the Build 16 sync, under the P2-02 constraints and the acceptance in the report |
| A-03 | Plus sections inside `DownloadsScreen.kt` and `DownloadsRepository.kt` | DEFER to the next upstream sync (report below) | 5 conflicts, 153 lines on 2026-11-22 (the P4 polish added to it) | Same as A-02 |
| A-04 | `MainAppContent.kt` hooks | KEEP | 1 conflict, 22 lines: navigation hooks that have to live there | Resolve at sync time |
| A-05 | `SeasonDownloadQueue` (Plus-owned singleton, ~900 lines) | KEEP | No defect; race harness r1–r21 and policy tests cover it | Revisit only on a proven defect |
| A-06 | `runBlocking` in `LocalizedUiText` (Nuvio's, B15-UI-03) | DEFER | Not measured | Decide after P5 frame measurements |

No restructuring is done in Build 15.

## Before/After report: A-02 and A-03 (P2-03)

Re-measured 2026-11-22 at `custom-subtitles` 3533f9d0 against 1.1.0-beta.2, in memory
(`git merge-tree --write-tree origin/custom-subtitles 1.1.0-beta.2`, then counting conflict hunks and
the lines inside them). Nothing was merged.

| File (Nuvio's) | Conflicts then (8c8098a8) | Conflicts now (3533f9d0) | Lines now |
|---|---|---|---|
| `StyledSubtitle.kt` | 3 | 5 | 80 |
| `PlayerSettingsRepository.kt` | 2 | 2 | 7 |
| `DownloadsScreen.kt` | 2 | 4 | 138 |
| `DownloadsRepository.kt` | 0 | 1 | 15 |
| `MainAppContent.kt` | 1 | 1 | 19 |
| `README.md` | 1 | 1 | 5 |

**Before.** Plus logic for subtitle direction marks, brightness and outline lives inside Nuvio's
`StyledSubtitle.kt` and `PlayerSettingsRepository.kt`; the season queue, pause and location sections
live inside Nuvio's `DownloadsScreen.kt` (+302 lines) and `DownloadsRepository.kt`.

**After (planned).** Each Plus block moves to a Plus-owned file (`PlusSubtitleStyle.kt`,
`PlusDownloadsSections.kt`), and each Nuvio file keeps one call per hook. Expected: conflicts with
upstream fall to the hook lines only.

**Alternatives.** (1) Keep as is and resolve at every sync: cost grows with each Plus change to
those files (it did between the two measurements). (2) Refactor now, inside Build 15. (3) Refactor as
the first step of the next upstream sync (chosen).

**Why not in Build 15.** The cost the refactor removes is paid only when upstream is merged, and
upstream is not merged into Build 15 (owner's rule: no 1.1.0-beta.2 or other pre-release). Moving
subtitle rendering and the downloads screen now would put the two most-used viewing paths at risk
just before release for no change the user sees (§5: no refactor without a criterion that applies
to this build).

**Risks of the refactor.** Subtitle rendering on both engines (RTL marks, brightness, outline);
the downloads screen states (Paused, Searching, Failed, Empty) and Arabic/RTL layout; the PortKit
registry (file ownership moves).

**Android and KMP impact.** Both files are `commonMain`: the move touches Android and iOS together;
no platform API changes, no data format change (stored subtitle settings and the season queue are
untouched, so no migration).

**Acceptance when done.** Under P2-02: behaviour unchanged (SubtitlePlaybackTest,
DeviceSubtitleStatesTest, SubtitleAppearanceTest, season queue tests, reference screenshots in
Arabic and English unchanged pixel for pixel or reviewed), moves separate from any behaviour change,
every caller reviewed, PortKit ownership updated, and the trial merge shows fewer conflict lines.
