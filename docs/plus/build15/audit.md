# Build 15 — audit register (P1)

One row per finding. Decision: KEEP / FIX / REFACTOR / REMOVE / DEFER. Status:
DISCOVERED → SPECIFIED → IMPLEMENTED → VERIFIED → ACCEPTED. Evidence is a test, a CI run or a
measurement that anyone can repeat. Known issues from before Build 15 are in `known-issues.md`.

| ID | Area | Finding | Evidence | Severity | Decision | Status |
|---|---|---|---|---|---|---|
| B15-UI-01 | Lists | Generated lazy-list keys could equal a real key (`section`, `section#0`, `section` → `section#0` twice; Compose rejects it) | Reproduced with the original helper; 20,000 random lists after the fix | P2 | FIX (#15) | VERIFIED (CI run 44735358734, post-merge 44738682357) |
| B15-SUB-01 | Subtitles | Both subtitle parsers quadratic on malformed input (unclosed `<b `, `{`, `<p>`, `<tt `): 60 KB → 5.2 s; 1 MB → minutes; runs while a download is prepared | Scaling run; 60,000-file old/new comparison (0 differences on well-formed input) | P1 | FIX (#17) | VERIFIED (CI run 44749841046, post-merge 44753001824) |
| B15-SUB-02 | Subtitles | Local subtitle import: size cap, empty-file check, ASCII cache name, decode in `runCatching` on IO | Code review of `LocalSubtitlePicker.android.kt` | — | KEEP | VERIFIED (review) |
| B15-SUB-03 | Subtitles | Imported subtitles accumulate in `cache/device-subtitles` (one file per import); two imports in one millisecond shared a name | Code review: a device subtitle is never saved as a preference (`selectDeviceSubtitle`), so only the current process refers to its copies; `DeviceSubtitleCacheTest` (20 repeated imports kept, restart removes only the earlier run's files) | P3 | FIX: each import removes copies of earlier runs, keeps this run's | IMPLEMENTED |
| B15-DL-01 | Downloads | Free-space check counts old progress when a `.part` document was deleted outside the app (K-03) | Code review of `executeToFolder` | P3 | FIX (#52) | IMPLEMENTED |
| B15-CI-02 | Repository | Gitlinks without `.gitmodules` entries (K-05) | CI teardown log | P3 | DEFER (Nuvio's tree; no build effect) | SPECIFIED |
| B15-UI-03 | Localization | `runBlocking` resource reads in `LocalizedUiText` (Nuvio's code) | Code review only | — | DEFER until measured on a device (P5) | DISCOVERED |
| B15-SUB-04 | Subtitles | Manual sync on a local subtitle: claim "loader is HTTP-only" is wrong (Android reads `file:` in `httpGetTextWithHeaders`); the real defect is the offline early return in `loadSubtitleAutoSyncCues` (`PlayerScreenRuntimeSubtitleActions.kt` l.75 @ b6d8776e) that blocks a local file with no network. iOS has no `file:` path | `LocalSubtitleSyncCuesTest` (local file + HTTP) | P1 | FIX (#24); iOS DEFER | IMPLEMENTED |
| B15-SUB-05 | Subtitles | Subtitle gestures save every frame: `updateSubtitleGesture` (l.401–409 @ b6d8776e) calls `setSubtitleStyle` (l.687–709: 12 `apply()` writes + sync publish) | `SubtitleGestureSessionTest` (drag, cancel, lock, rotation/dispose keep the final value) | P2 | FIX (#25): live preview, one save at gesture end | IMPLEMENTED |
| B15-SUB-06 | Subtitles | Unknown extension silently renamed `.srt` (`LocalSubtitlePicker.android.kt` l.36, l.76 @ b6d8776e) | `LocalSubtitleFormatTest` (content detection; non-subtitle refused; Windows-1256, ASS, SRT kept) | P2 | FIX (#26) | IMPLEMENTED |
| B15-DL-02 | Downloads | Claimed race in `SeasonDownloadQueue` / `DownloadPreferences` (no Mutex). All writers run on Main: queue scope `Dispatchers.Main` (l.183), job service `Main.immediate`, preferences set from UI; search is the only `Default` work and returns to Main | Harness r1–r13 incl. two seasons at once, double start, pause/resume, remove during finish, profile switch, restart: no lost update, no duplicate enqueue | — | KEEP (risk unproven; no code change) | VERIFIED (harness) |
| B15-DL-03 | Downloads | A paused season episode held every later episode of the profile (`SeasonQueuePolicy.busy` counted Paused; by design since Build 13, reversed by the owner's decision). No "pause season" / "pause all" in the app; resuming a paused episode while the queue ran started a second download | Policy test and harness r11 fail on the old code; `SeasonQueuePolicyTest`, `SeasonQueueStorageTest`, `SeasonPauseAndroidTest`, harness r11, r14–r21, emulator update with a seeded paused season | P2 | FIX (#28) | VERIFIED |
| B15-EXT-01 | Localization | External claim: `%1$s` broken in `values-ar/strings_ratings.xml` | File read @ b6d8776e and b9369bb: placeholders correct (4 × `%1$s`) | — | KEEP (claim contradicted) | VERIFIED (review) |
| B15-EXT-02 | Player | External claim: `touchGesturesEnabled` ignored | Checked through `playerSurfaceDragGestures` (`touchGesturesEnabledState`); covered by `PlayerSurfaceGesturesTest` | — | KEEP (claim contradicted) | VERIFIED (review) |
| B15-EXT-03 | Architecture | External suggestion: move player state to a ViewModel | Design preference, no defect shown | — | KEEP (out of scope) | — |
| B15-REL-01 | Release notes | Android 9 note missing in the Arabic part of custom-14 notes (K-02) | Release page | P3 | FIX by hand on the release page (session cannot edit releases); future notes carry the changelog text | SPECIFIED |
| B15-SEC-01 | Signing | `signing/nuvio-plus.p12` (builds 8-12 key) is in the public repository | Documented in the release workflows; used only as the v3 rotation's old signer; Build 14 release run 44727757154 refused an update signed by it alone on Android 9 and 14 | — | KEEP (by design: rotation proof) | VERIFIED (release run 44727757154) |
| B15-SEC-02 | Network | Plain http allowed for every host (`usesCleartextTraffic`, base-config cleartext), from upstream Nuvio | `security-surface.py` on the published Build 14 APK | Low | KEEP (accepted risk: http add-ons and local servers; any change fails the surface check until reviewed) | IMPLEMENTED |
| B15-SEC-03 | Repository | The ruleset of `custom-subtitles` blocks only deletion and force push: no required pull request or checks | Ruleset 27721857 read 2026-11-21 | High | FIX (owner applied; 6 required checks, re-read 15:27) | VERIFIED (ruleset-27721857.json, 2026-11-25) |
| B15-SEC-04 | Player networking | Playback client accepts every TLS certificate and host name (`PlayerPlaybackNetworking.kt`, Nuvio's code) and sends the stream's `Authorization` header over it | Lint `TrustAllX509TrustManager` (run 44866405817); source read | High | FIX (#44, #50) | IMPLEMENTED |
| B15-AND-01 | Manifest | App label moved to the `appLabel` placeholder; debug-only package visibility | Diff review. **Corrected:** releases do override the label: `androidApp/build.gradle.kts` sets the fixed name "Nuvio Plus" whenever `-Pnuvio.applicationIdSuffix=.plus` is passed (`plus-build.yml`), so the release launcher name is not translated (by design: a distinct name next to the official app). No permission added | — | KEEP | VERIFIED (review) |
| B15-BLD-01 | Build | Gradle changes: release minSdk 28 (v3-only signing), `.plus` id and label, update-source code generation, Guava 33.7.2 (security), Robolectric jars through Gradle | Diff review of both `build.gradle.kts`; CI builds and update checks on every pull request. Low risk noted: the update repository name is written into generated Kotlin unescaped; its value comes from CI settings, not users | — | KEEP | VERIFIED (review) |
| B15-WP-01 | Watch progress | Test fixture changed from a 1 s to a 50 min clip at the same 94 % position | Cherry-pick of Nuvio's own fix 618622d8 (a newer guard treats 1 s clips as errors); assertion unchanged | — | KEEP | VERIFIED (review) |
| B15-AR-01 | Arabic | Every English string has Arabic and the same placeholders | New `test_arabic_strings.py`: 2,811 English strings, 0 missing, 0 placeholder mismatches | — | KEEP; guarded by the new test | VERIFIED (test) |
| B15-ADD-01 | Add-ons | Link confirmation state is `remember`ed, not saved | Activity handles rotation itself (`configChanges`), so it survives rotation; lost only on process death, where nothing is installed (safe) | P3 | KEEP | VERIFIED (review) |
| B15-ADD-02 | Add-ons | A refresh asked for just after an add-on finished loading is dropped: the job leaves `activeRefreshJobs` after it shows as idle | AddonJourneyTest failure on #121 (run 45662059350) | P2 | FIX | IMPLEMENTED |
| B15-LIB-01 | Library | Downloads tab replaces Nuvio's downloads button (button file deleted) | Diff review of `LibraryScreen.kt`; `LibraryDownloadEntriesTest`; open: look and RTL (P4), chip count includes paused downloads | — | KEEP; visuals in P4 | IMPLEMENTED |
| B15-STR-01 | Streams | Season download sheet (rest of season / whole season) | Diff review of `StreamsScreen.kt`; queue paths in the race harness | — | KEEP; visuals in P4 | IMPLEMENTED |
| B15-SET-01 | Settings | Subtitle brightness slider and bottom-offset range | Diff review of `SubtitleAppearanceSettings.kt`; effect on the picture needs a device | — | KEEP; NEEDS PHYSICAL DEVICE | IMPLEMENTED |
| B15-UI-02 | Subtitles (Arabic) | Colour codes (#FFFFFF) shown reversed in RTL | Reference screenshot (#47) | P2 | FIX (#47) | IMPLEMENTED |
| B15-UI-04 | Playback settings (Arabic) | Decoder strings said فك التشفير (decryption) for decoding | Reference screenshots (#49) | P2 | FIX (#49) | IMPLEMENTED |
| B15-UI-05 | Player panels (Arabic) | Delay and gain values shown as `0.000s+`, `dB 0` | Reference screenshots (#49) | P2 | FIX (#67; #54 recorded it with no code change) | IMPLEMENTED |
| B15-UI-06 | Library downloads | Phone card cut the percentage ("Downloading • …") | Reference screenshot (#49) | P2 | FIX (#49) | IMPLEMENTED |
| B15-UI-07 | Settings (Nuvio) | Each `SettingsSwitchRow` switch is a second, unlabelled screen-reader stop | P4-09 report (#68) | P2 | DEFER (Nuvio's component) | DISCOVERED |
| B15-UI-08 | Library downloads | Tappable poster had no label (title and state were on its parent) | P4-09 report (#68) | P2 | FIX (#68) | IMPLEMENTED |
| B15-UI-09 | Subtitle/audio panels, downloads settings | Stepper − and + and the Wi-Fi only switch had no label | P4-09 report (#68) | P2 | FIX (#68) | IMPLEMENTED |
| B15-NUV-01 | Nuvio areas | `catch (e: Throwable)` also catches coroutine cancellation (~40 in coroutines) | initial-pass.md | P3 | DEFER (Nuvio's code) | DISCOVERED |
| B15-NUV-02 | Nuvio areas | `runBlocking { getString() }` in repositories and notifications | initial-pass.md | P3 | DEFER (Nuvio's code) | DISCOVERED |
| B15-DL-05 | Downloads | Resume appended another version or a cut range (review GEM-DL-01/02) | Code; `AndroidDownloadTransferTest` | P1 | FIX (#58) | IMPLEMENTED |
| B15-PLY-03 | Player | Pending subtitle choice taken before subtitle tracks exist (GEM-PLY-01) | Code; device tests | P1 | FIX (#58) | IMPLEMENTED |
| B15-PLY-04 | Player | Subtitle change while buffering paused the video (GEM-PLY-02, 3 sites) | Code | P1 | FIX (#58) | IMPLEMENTED |
| B15-SUB-07 | Subtitles | Arabic line starting with a Latin word laid out LTR in mpv text (GEM-SUB-01) | `SubtitleLineDirectionTest` | P2 | FIX (#58) | IMPLEMENTED |
| B15-SUB-08 | Subtitles | Long ASS header, vector drawings, numeric entities (GEM-SUB-02) | `SubtitleParserReviewTest` | P2 | FIX (#58) | IMPLEMENTED |

## Audit axes (P1-03)

Each axis of the spec with the findings behind it, or "reviewed, none" with what was checked.
`test_audit_findings.py` checks that every axis is here and every finding named exists.

| Axis | Findings or result |
|---|---|
| Architecture | B15-EXT-03 (and decisions A-01..A-06 in `architecture.md`) |
| Behaviour | B15-SUB-04, B15-SUB-06, B15-SUB-07, B15-SUB-08, B15-DL-03, B15-DL-05, B15-PLY-03, B15-PLY-04, B15-EXT-02, B15-WP-01, B15-ADD-01 |
| Performance | B15-SUB-01, B15-SUB-05, B15-UI-03, B15-NUV-02 |
| Security | B15-SEC-01, B15-SEC-02, B15-SEC-03, B15-SEC-04, B15-AND-01, B15-BLD-01 |
| Concurrency | B15-DL-01, B15-DL-02, B15-PLY-01, B15-NUV-01, B15-ADD-02 |
| UX | B15-UI-01, B15-UI-06, B15-LIB-01, B15-STR-01, B15-SET-01, B15-REL-01 |
| Accessibility, localisation, RTL | B15-UI-02, B15-UI-04, B15-UI-05, B15-UI-07, B15-UI-08, B15-UI-09, B15-AR-01, B15-EXT-01 |
| Storage and migration | B15-SUB-02, B15-SUB-03, B15-DL-05 |
| Testability | B15-CI-02 |
| Upstream compatibility | B15-EXT-03, B15-UI-07, B15-NUV-01 (and A-02/A-03 trial merge in `architecture.md`) |
| Portability | reviewed, none: PortKit transfers each feature to a clean upstream and a fork and builds and tests it (P7, `portkit/`) |

## Deep audit: player engines, HDR and sign-in storage (P1-02)

Read on 2026-11-22 at `custom-subtitles` a3047e63 (`PlayerEngine.android.kt`, `MpvSurfaceView.android.kt`,
`PlayerTrackSelection.kt`, `PlayerScreenRuntimeEffects.kt`, `PlaybackMediaItems.android.kt`, the sign-in
storage of Trakt, SIMKL and accounts). Behaviour on emulators is covered by the device tests (both engines:
subtitles, surface lifecycle, network drop, smoothness); what a still emulator cannot show is named.

| Path | What the code does | Result |
|---|---|---|
| ExoPlayer decoders | Extension renderer priority from settings, decoder fallback on, `setMapDV7ToHevc` from settings (off by default) | Sound. Dolby Vision 7 on a device without a DV decoder needs that setting: device check |
| Dolby audio | `DolbyFreeMediaCodecSelector` hides the platform Dolby AC-3/E-AC-3 decoders, so the bundled ffmpeg renderer decodes them | Sound (avoids a crash and a retry); passthrough to an amplifier: device check |
| Dolby Vision and libass | The libass video-effect pipeline is not built for Dolby Vision video; tunnelled playback draws ASS as an overlay | Sound (effects would break DV and tunnelling) |
| Subtitle selection | Text tracks stay disabled until the app's own selection plan picks one (forced-only or normal); the plan is tested | Sound; `SubtitlePlaybackTest` shows captions with the default setting |
| Threads | ExoPlayer touched only on the main thread (snapshot loop pinned, #63); libmpv commands on one `NuvioLibmpv` thread | Sound after B15-PLY-01 and the Android 16 fix |
| libmpv video | `vo` from settings, `gpu-next` by default (libplacebo: tone mapping and Dolby Vision 5 reshaping); `hwdec` auto or no; `tls-verify=yes` with the bundled CA file; `ytdl=no` | Sound. HDR is tone-mapped to SDR by libmpv on Android; HDR output to the screen is ExoPlayer's: device check |
| HDR labels | DV, HDR10 and HLG read from the codec string and the colour transfer; the base codec of DV resolved from `dvhe`/`dvav`/`dav1` | Sound |
| Local file header | A few header bytes read on `Dispatchers.IO` while the caller waits when called on the main thread | Brief; B15-NUV-02 |
| Sign-in tokens | Trakt, SIMKL and account tokens in app-private `SharedPreferences` (`MODE_PRIVATE`); `allowBackup="false"`; no token value in logs (initial pass) | Acceptable: protected by the app sandbox, not encrypted at rest (Nuvio's design) |

No new defect found. Device checks (owner's final-release list): HDR10/HLG/Dolby Vision on the screen with
ExoPlayer, Dolby Vision 7 with and without the mapping setting, Dolby audio passthrough, hardware decoding with
libmpv.

## Areas reviewed and still to review

| Area | State |
|---|---|
| Updater / identity / signing | Reviewed in Build 14 (device tests on Android 9 and 14, release gate) |
| Season downloads, profiles | Race harness in CI (r1–r13; B15-DL-02); E1/r2 in Build 14 |
| Subtitle parsing (player, auto-sync, local import) | Reviewed (B15-SUB-01..03) |
| Add-on links | Confirmation since Build 14 |
| Player engines, HDR, gestures | NEEDS PHYSICAL DEVICE |
| UI polish, RTL screenshots | Reference screenshots in every PR (#47, #49); fixes B15-UI-02, 04, 05, 06 |
| Performance | Not measured (P5) |
| PortKit | P7 step 1: foundation (registry, levels, policy, inventory) |

## Findings in the full format (spec §4)

### B15-PLY-01 — libmpv snapshot loop sets a view property after a dispatcher switch

| Field | Value |
|---|---|
| Files | `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt` (Nuvio's code, line 1652 at `8c8098a8`) |
| Evidence | `SubtitlePlaybackTest.libmpvStylesAndClearsTimedCaptions` failed once on the Android 14 emulator with `CalledFromWrongThreadException … Calling: NuvioLibmpv` at line 1652 (run of `a0ed03fd`, PR #34); the same test passed on the same app code in the previous run and on Android 9 |
| Root cause (hypothesis, not proven) | The loop calls `view.snapshot()`, which runs `withContext(mpvDispatcher)`, then sets `view.keepScreenOn`. The coroutine resumes on its caller's dispatcher: in the app that is the main thread (Compose's `AndroidUiDispatcher`); under Compose's test scheduler it may resume elsewhere. Which one applies here is not established |
| User impact | If it can happen in the app: a crash while playing with libmpv. If test-only: none |
| Severity | High if reproducible in the app; Low if test-only — **unclassified until measured** |
| Alternatives | (a) set the view property on `Dispatchers.Main.immediate` explicitly (Nuvio 1.1.0-beta.2 does so for its snapshot callbacks, not for this loop); (b) leave Nuvio's code as is and fix the test environment; (c) report upstream |
| Chosen | (a), in both effects that follow `snapshot()` (#34, `2a8ef3c3`): in the app the effects already resume on the main thread, so `Main.immediate` does not dispatch there |
| Compatibility / maintenance | (a) changes a Nuvio file (adds a conflict line on upstream sync); (b) none |
| Tests required | Repeated runs of `SubtitlePlaybackTest` on Android 14; a check of the dispatcher the effect runs on in the app |
| Priority (§14) | P1 Must Fix if it reproduces in the app |
| Decision / status | FIX — IMPLEMENTED (device tests passed on Android 14 and 9 after the fix; one run, so not yet VERIFIED for an intermittent failure) |
