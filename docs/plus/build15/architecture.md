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
| A-02 | Subtitle style inside `StyledSubtitle.kt` and `PlayerSettingsRepository.kt` | REFACTOR CANDIDATE | Largest conflict surface: 5 conflicts, 52 lines (subtitle-style) | Move Plus logic into a Plus-owned file behind one call in each Nuvio file. **Only after** P4 reference screenshots exist, so rendering can be compared before/after; acceptance: conflict lines with 1.1.0-beta.2 drop, existing subtitle tests and screenshots unchanged |
| A-03 | Plus sections inside `DownloadsScreen.kt` (+198 lines) | REFACTOR CANDIDATE | 2 conflicts, 18 lines; largest Plus block inside a Nuvio file | Extract the queue and pause sections into a Plus file; same conditions as A-02 |
| A-04 | `MainAppContent.kt` hooks | KEEP | 1 conflict, 22 lines: navigation hooks that have to live there | Resolve at sync time |
| A-05 | `SeasonDownloadQueue` (Plus-owned singleton, ~900 lines) | KEEP | No defect; race harness r1–r21 and policy tests cover it | Revisit only on a proven defect |
| A-06 | `runBlocking` in `LocalizedUiText` (Nuvio's, B15-UI-03) | DEFER | Not measured | Decide after P5 frame measurements |

No restructuring is done in this change.
