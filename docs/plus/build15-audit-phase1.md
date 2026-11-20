# Build 15 — independent audit, phase 1 (initial findings)

**Pinned baseline:** `custom-subtitles` at `18276c6944915b5b0110fd94d243cc94619281b9` (2026-11-20). This is a working review baseline, not a claim that the whole application has been audited.

**Release separation:** Build 14 was published as `custom-14` from `18276c69` (run 44727757154) before this change was merged; this change does not touch Build 14's release, signing or publication.

## Source inventory confirmed in this pass

- Repository tree inventory at the pinned SHA: **2,019 tracked blobs**, **1,459 Kotlin (`.kt`) files**, of which **319 live in the three `commonTest` / `androidHostTest` / `androidFullHostTest` trees** (these are file counts, not individual test case counts). Key source sets include `commonMain` (788 Kotlin files), `androidMain` (164), `androidFull` (13), and platform-specific iOS/desktop code; this inventory is not a line-by-line audit.
- The Plus feature registry, `.github/plus/features.json`, currently declares **12 features and 74 file-path references**. Every registered path exists at the pinned SHA. This verifies references, **not** that registry dependencies, semantic portability, or tests are complete.
- Reviewed the specific code and tests for lazy-list keys; located call sites in `HomeScreen.kt` (home section keys) and `SearchScreen.kt` (search section keys). Also inspected the Android 9 updater release-fix metadata, build/test gate state, the release acceptance matrix and the build signing boundary.

## Confirmed finding B15-UI-01 — generated Compose keys can collide

**Severity:** Low to medium (crash, but it needs a real key shaped like `name#0` next to a duplicated `name`; not seen in reports). Fixed because the fix is small and keeps every non-colliding key unchanged.

`composeApp/src/commonMain/kotlin/com/nuvio/app/core/ui/DuplicateSafeLazyKeys.kt` appends `#0`, `#1` to duplicate base keys but does not reserve *other real keys*. Reproduction using the repository's original implementation, compiled with `kotlinc`:

```
input = [section, section#0, section]
output = [section#0, section#0, section#1]
```

Two entries get an identical key. A lazy-list with these keys can throw a duplicate-key exception. The helper is used by `features/home/HomeScreen.kt` and `features/search/SearchScreen.kt`.

**Change in this PR:** Reserve all original keys, skip any previously generated key, and compute selector keys once. Preserve the old results when no collision exists. Add deterministic regression cases for suffix-shaped user keys, multiple duplicate groups, and mixed-type keys with the same `toString()`.

**Evidence:** The original implementation fails the local reproduction (`2/3` keys distinct; Compose rejects a reused lazy-list key with `IllegalArgumentException`). The revised implementation passed the same reproduction plus cases with suffix-shaped keys, two duplicate groups, mixed `Int`/`String` keys, and once-only selector evaluation under the local Kotlin compiler. **Android/Gradle CI has not been claimed as passing; it must run on this PR.**

## Additional findings / open investigations (no app changes made)

| ID | Status | Evidence and required next verification |
| --- | --- | --- |
| B15-CI-02 | Confirmed repository hygiene issue; low functional urgency | Root Git tree has gitlinks for `MPVKit`, `libass-android`, `vendor/TorrServer`, and `vendor/quickjs-kt`, but `.gitmodules` only declares `MPVKit`. GitHub Actions teardown logged `fatal: No url found for submodule path 'libass-android' in .gitmodules`, exit 128. The other two undeclared gitlinks were discovered through tree inspection, not a reproduced build failure. Resolve ownership and whether this gitlink is intentionally retained before changing it; cleanup warning alone did not prove an APK defect. |
| B15-UI-03 | Hypothesis; **not** a verified performance defect | `core/i18n/LocalizedUiText.kt` uses `runBlocking { provider() }` to read Compose resources from synchronous localized helpers. Inspect call paths and measure frame-time impact on the UI thread before considering a refactor. |
| B15-QA-04 | Coverage gap; device execution **unverified** | `docs/plus/device-test-matrix.md` requires physical-phone checks for playback (ExoPlayer/mpv), local/ASS subtitles, download resumption, profile transitions, Arabic/RTL, and update/data preservation, with tablet/HDR checks when available. Successful CI does not establish completion of these manual rows. |
| B15-PORT-05 | PortKit scope, **not yet implemented** | The 12-feature registry and dependencies are a starting manifest, not a compatible porting engine. Before transfer, establish target upstream SHAs, dependency closures, conflict detection, dry-run output, idempotence and an independent fixture-fork integration test. |

## Next audit gates

1. CI on this PR: full Kotlin host-test suite and project policy checks, with no unnecessary release sign/publish permissions.
2. Audit downloads (persisted season queue, SAF, pause/resume, profile isolation) and updater migration using real source and existing tests; reproduce a concrete failure before changing behavior.
3. Map UI navigation, Arabic/RTL, subtitle gestures and player engine boundaries; collect before/after screenshots and performance measurements before claiming improvements.
4. Scope PortKit to pinned compatible Nuvio targets; test dry-run, rollback and independent-fork fixtures before allowing writes.
5. Do not merge into stable, publish an APK, or modify signing without explicit owner approval and successful gates.

## Review (Claude, 2026-11-20)

- Reproduced the collision with the original helper and checked the fix with the patch's cases plus
  20,000 random lists over keys `x`, `x#0`, `x#1`, `y`, `y#0`, `x#0#0`: every list got unique keys and
  every key that was unique before stayed unchanged.
- The helper is Nuvio's own code (since `511ebd5f`); recorded in `.github/plus/features.json` so an
  upstream sync shows the change for review.
