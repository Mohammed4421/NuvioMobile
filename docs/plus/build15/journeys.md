# Build 15 — user-journey matrix (P3)

Every journey a user takes, Nuvio Plus's first. Derived from the app's navigation (23 routes in
`composeApp/src/commonMain`), not from memory. For each: what the user does, what must happen, the
exceptional paths, and the evidence that exists today. Measured at `custom-subtitles` 8c8098a8.

Evidence kinds: **unit** (host tests, every pull request), **harness** (season-queue race harness,
every pull request), **emulator** (run in CI on Android 9 and 14), **device-test, not run** (an
instrumented test exists in `androidApp/src/androidTest` but no workflow runs it), **NEEDS PHYSICAL
DEVICE** (only a real phone can show it), **none**.

Finding P3-F1: of the instrumented tests in `androidApp/src/androidTest`, CI runs only
`UpdateArchiveDeviceTest` (`.github/plus/update-device-check.sh`). `BackgroundDownloadTest`,
`SubtitlePlaybackTest`, `SubtitleSettingsTest` and `MpvSurfaceTest` exist but run nowhere.

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
| J-13 | Wi-Fi only (Plus) | No transfer on mobile data | Switching networks | unit (work constraints) | Emulator network switch not run |
| J-14 | Restart during downloads | Partial files kept, queue continues | Process death | harness r8, r17; `restore()` tests | — |
| J-15 | Library → Downloads tab → play offline (Plus) | Finished items play; show opens episodes | Copy still downloading | unit `LibraryDownloadEntriesTest` | Look (P4) |
| J-16 | Install an add-on from a link (Plus) | Confirmation first, then install | Cancel, bad link | unit `AddonLinkConfirmationTest` | — |
| J-17 | Arabic interface (RTL) | Every text translated, mirrored layout | Long words, numbers | `test_arabic_strings.py` (#32) | Layout on screens (P4) |
| J-18 | Phone and tablet, both orientations | Usable layouts | Rotation during playback | none | P3-03 / P4 screenshots |
| J-19 | Profiles | Downloads and queue per profile | Switch mid-download | harness r1–r13, r18 | — |
| J-20 | Search, collections, Trakt / Simkl / MDBList (Nuvio) | As Nuvio | Sign-in expiry | Nuvio's unit tests | Not reviewed in Build 15 (Nuvio's code) |

## Next steps (closing P3)

1. **P3-F1:** run the four existing instrumented tests on the CI emulator, or record why one cannot run there.
2. Network-loss and Wi-Fi-only journeys (J-09, J-13) on the emulator with the network switched off mid-transfer.
3. Device list for the owner: J-03–J-08, J-11, J-12 (rows marked NEEDS PHYSICAL DEVICE).
