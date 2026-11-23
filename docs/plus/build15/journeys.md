# Build 15 — user-journey matrix (P3)

Every journey a user takes, Nuvio Plus's first. Derived from the app's navigation (23 routes in
`composeApp/src/commonMain`), not from memory. For each: what the user does, what must happen, the
exceptional paths, and the evidence that exists today. Measured at `custom-subtitles` 8c8098a8.

Evidence kinds: **unit** (host tests, every pull request), **harness** (season-queue race harness,
every pull request), **emulator** (run in CI on Android 9 and 14), **device-test, not run** (an
instrumented test exists in `androidApp/src/androidTest` but no workflow runs it), **NEEDS PHYSICAL
DEVICE** (only a real phone can show it), **none**.

Finding P3-F1 (closed): `SubtitlePlaybackTest`, `SubtitleSettingsTest` and `MpvSurfaceTest` now run on
every pull request on Android 9 and 14 (`nuvio-device-tests.sh`); `BackgroundDownloadTest` runs in the
download notification test. Font import on Android 9 stays NOT TESTED (picker needs Android 10+).

| ID | Journey | Must happen | Exceptional paths | Evidence today | Gap |
|---|---|---|---|---|---|
| J-01 | Update from inside the app (Plus) | Newer build found, verified, installed; data kept | Wrong key, damaged file, old key, Android 9 | emulator (update check, API 28/34) every PR; unit `PlusAppUpdaterControllerTest`, `UpdateArchiveCheckTest` | Release-to-release upgrade 14→15 not run yet (P6-02) |
| J-02 | Upgrade keeps user data | Settings, queue, downloads survive | Paused episode with partial file | emulator update with seeded season queue (#28) | 14→15 on the candidate (P6-02) |
| J-03 | Home → details → streams → play (Nuvio) | Sources listed, playback starts | No add-ons, no network, dead link | unit tests in `features/player` (Nuvio) | none end-to-end; NEEDS PHYSICAL DEVICE for playback |
| J-04 | Play with ExoPlayer / mpv, add-on subtitles | Picture, sound, subtitles in sync | Engine switch, HDR, stall | unit (`PlayerEngineSwitchTrackPreferenceTest`, `PlaybackStallResumeTest` …); device-test, not run (`MpvSurfaceTest`, `SubtitlePlaybackTest`) | NEEDS PHYSICAL DEVICE (HDR, engines) |
| J-05 | Load a subtitle from the device (Plus) | Real format kept, shown | Huge, empty, not a subtitle | unit `LocalSubtitleSyncCuesTest`; #26 tests | NEEDS PHYSICAL DEVICE (file picker) |
| J-06 | Subtitle style, brightness, position (Plus) | Changes show and persist | Restart, migration | unit `SubtitleAppearanceTest`, `PlusSubtitleMigrationTest`, `SubtitleAppearanceUiTest`; device-test, not run (`SubtitleSettingsTest`) | NEEDS PHYSICAL DEVICE (look) |
| J-07 | Move / resize subtitles with fingers (Plus) | Follows fingers; saved once | Player closes mid-gesture | unit `SubtitleGestureSessionTest`, `PlayerSurfaceGesturesTest` | NEEDS PHYSICAL DEVICE (feel) |
| J-08 | Manual subtitle sync offline (Plus) | Lines load without network | Offline, local file | unit (#24) | NEEDS PHYSICAL DEVICE |
| J-09 | Download a film or an episode (Plus) | Starts, progresses, finishes, plays offline | Network lost, app killed, no space | unit `AndroidDownloadLifecycleTest`, `AndroidDownloadTransferTest`, `DownloadsTransferWorkerTest`; device-test, not run (`BackgroundDownloadTest`) | Network loss on an emulator not run |
| J-10 | Download a season (rest / whole) (Plus) | One at a time, in order, matching source | Source missing, profile switch, restart | harness r1–r21; unit `SeasonQueuePolicyTest`, `SeasonStreamMatcherTest` | — |
| J-11 | Pause episode / season / all, resume (Plus, D-01) | As the owner's contract | Notification while hidden on Android 14+ | harness r11–r21; `SeasonPauseAndroidTest` (#28) | NEEDS PHYSICAL DEVICE (notification, background) |
| J-12 | Download into a chosen folder / SD card (Plus) | Files land there, readable | Folder removed, permission lost | unit `AndroidDownloadTransferTest` (SAF paths) | NEEDS PHYSICAL DEVICE (SD card) |
| J-13 | Wi-Fi only (Plus) | No transfer on mobile data | Switching networks | unit (work constraints); emulator `download-interruptions-test.sh` C on Android 9, 14, 16 (run 45013362722): no request and no byte in 30 s on metered mobile data, starts on Wi-Fi, exact SHA-256 | — (owner's phone: PRV-05) |
| J-14 | Restart during downloads | Partial files kept, queue continues | Process death | harness r8, r17; `restore()` tests | — |
| J-15 | Library → Downloads tab → play offline (Plus) | Finished items play; show opens episodes | Copy still downloading | unit `LibraryDownloadEntriesTest` | Look (P4) |
| J-16 | Install an add-on from a link (Plus) | Confirmation first, then install | Cancel, bad link | unit `AddonLinkConfirmationTest` | — |
| J-17 | Arabic interface (RTL) | Every text translated, mirrored layout | Long words, numbers | `test_arabic_strings.py` (#32) | Layout on screens (P4) |
| J-18 | Phone and tablet, both orientations | Usable layouts | Rotation during playback | none | P3-03 / P4 screenshots |
| J-19 | Profiles | Downloads and queue per profile | Switch mid-download | harness r1–r13, r18 | — |
| J-20 | Search, collections, Trakt / Simkl / MDBList (Nuvio) | As Nuvio | Sign-in expiry | Nuvio's unit tests | Not reviewed in Build 15 (Nuvio's code) |

## Next steps (closing P3)

1. **P3-F1:** run the four existing instrumented tests on the CI emulator, or record why one cannot run there.
2. ~~Network-loss and Wi-Fi-only journeys (J-09, J-13) on the emulator~~: network loss in run 44937898514, Wi-Fi only in run 45013362722 (Android 9, 14, 16).
3. Device list for the owner: J-03–J-08, J-11, J-12 (rows marked NEEDS PHYSICAL DEVICE).

## Six-case matrix (P3, 2026-11-21)

Cases: **N** normal use · **F** expected failure · **I** cancel / interrupt · **R** resume / restore ·
**P** persistence after restart · **D** no damage to related features. Evidence at `a0b3332c` (the
last commit of #49, merged as 46814605):

- **E1** Nuvio device tests, run 44904776284 (Android 9 and 14 emulators)
- **E2** Download notification test, run 44904776282 (Android 9 full; Android 14 pause only)
- **E3** Nuvio Plus checks, run 44904776470 (2241 host tests, race harness, update device check on Android 9 and 14, reference screenshots)
- **E4** Nuvio Plus checks, run 45104217755 on 8a080aff (#99): host journey tests `AddonJourneyTest`, `LibraryDownloadsJourneyTest`, and the profile isolation tests of `journeys-profiles.md` (Robolectric, SDK 34)

| Domain | N | F | I | R | P | D | Not covered here |
|---|---|---|---|---|---|---|---|
| Downloads / storage | PASS E2 (real transfer, SHA equal), E3 | PASS E3 (HTTP errors, 416, no space `DownloadFreeSpaceTest`) | PASS E2 (pause from the notification) | PASS E2 Android 9 (same byte, SHA equal); Android 14 NOT TESTED (real tap) | PASS E3 (harness r8, r17; process recreation) | PASS E3 (season policy, profiles) | Network loss and Wi-Fi only on an emulator; SD card / SAF on removable storage (device) |
| Player / subtitles | PASS E1 (ExoPlayer and mpv play with styled captions) | PASS E3 (`PlaybackTlsTest`, `PlaybackRedirectCredentialsTest`) | PASS E1 (`MpvSurfaceTest`: stop / resume) | PASS E1 (seek restored after surface recreation) | PASS E3 (style migration and persistence tests) | PASS E3 | HDR / Dolby Vision, real codecs, gesture feel (device) |
| Profiles / accounts | PASS E3, E4 (downloads, queue, subtitle settings and offline PIN per profile) | PASS E4 (damaged list, queue entry, appearance or PIN cache stays in its profile) | PASS E3, E4 (switch, pause or cancel during a download) | PASS E4 (back to a profile while its transfer runs; offline PIN after restart) | PASS E3, E4 | PASS E3, E4 | **F1 CLOSED** by #101: confirmed profile deletion clears that index's Plus list/queue/subtitle preferences; local host/common tests passed (45151305429). Signed-in server delete and second-device sync remain NOT TESTED. PIN online, sign-in and sync are Nuvio's code and need the server (`journeys-profiles.md`) |
| Library / navigation | PASS E3, E4 (local playback; series opens episodes, back) | PASS E4 (paused, failed, missing never play) | PASS E4 (transfer interrupted by app exit) | PASS E4 (activity recreation, return from background) | PASS E4 (list and tab after restart) | PASS E3, E4 (deleting one keeps the others) | The look and feel of transitions on a device |
| Add-ons / networking | PASS E3, E4 (install from address and confirmed link, disable, reorder) | PASS E4 (15 malformed manifests, HTTP errors, dropped connection, real 60 s timeout) | PASS E3, E4 (cancel in flight, dismissed link) | PASS E4 (retry; broken manifest keeps the add-on and recovers) | PASS E4 (order, enabled state, address per profile) | PASS E3, E4 | `selectProfile` not driven; no manifest size limit (NVP15-RISK-0011) (`journeys-addons.md`) |
| Updater / identity | PASS E3 (update device check) | PASS E3 (wrong key, damaged archive) | PASS #105 (truncated download keeps previous APK) | PASS #105 (retry downloads clean replacement) | PASS E3 (data kept on the emulator update) | PASS E3 | 14→15, 13→15, 12→15 with the real key: in the release run before publishing |

The six case columns cover the scenarios named in each cell. Open engineering gaps are recorded in the final column and in the requirement register. The rows that require the owner's phone are post-release checks (NVP15-CHG-0003).
