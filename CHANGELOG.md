# Changelog — Nuvio Plus

Changes on top of [Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile) at
[`511ebd5`](https://github.com/NuvioMedia/NuvioMobile/commit/511ebd5f175dd39e31b9cc0e7b259259e887cf6f).
Newest first.

## Build 10 — review fixes

### Fixed
- **Downloads in a picked folder are only marked complete under their real name.** When the storage
  provider does not rename files, the `.part` file is copied to the final name, checked for size and
  then removed. If that also fails, the file stays as `.part` and still plays in the app.
- **Season downloads keep the quality of the first episode.** Resolution and release type (HDR,
  Dolby Vision, HEVC, remux, AV1) now have to match within a binge group too, so a 4K Dolby Vision
  pick no longer continues with 1080p SDR episodes.

### Added
- A note under subtitle brightness, in the player and in settings, saying where it applies: text
  subtitles and, in ExoPlayer, image subtitles; ASS subtitles drawn by libass keep their brightness.
- Arabic translations for the 30 settings and player texts that were still in English in the
  original app (audio delay, downmix, fallback sources, Continue Watching action and others).

## Build 8 — Downloads in the library, updates in place

(Build 7 did not compile; its changes ship in build 8.)

### Added
- **Downloads tab in the library**, next to *Saved* and *Cloud*. Downloaded movies and shows appear as
  posters, one per title. Running downloads show a progress bar and a badge (downloading, paused,
  failed, waiting in the season queue). Tapping a movie plays it, tapping a show opens its episodes,
  long-pressing opens the title's page. A *Manage downloads* card opens location, Wi-Fi only, running
  downloads and the season queue. The download button in the library header is gone.

### Changed
- **Updates install over the previous build.** Every build is signed with the same key and gets a
  higher version number. Builds 1–6 have to be uninstalled once; from build 8 on, install the new APK
  directly.
- A season download keeps going when the stream list is closed right after choosing it.
- Removing an episode from the season queue while its source is being searched stops that search, so
  the episode is not downloaded afterwards.
- CI runs this fork's unit tests and reports the result in the release notes.

## Build 6 — subtitle outline repair

### Fixed
- **Huge black shapes around subtitles.** An earlier third-party build saved subtitle brightness in the
  outline width setting, and settings sync carried it to the Nuvio account. After signing in, the outline
  was drawn dozens of times too thick (spiky black shapes over the preview and the video). Outline widths
  outside 0–8 now fall back to the default and are corrected in storage, so sync sends the fixed value.

## Build 5 — Nuvio account

### Fixed
- **Nuvio account sign-in.** Builds now connect to the official Nuvio account server (`api.nuvio.tv`),
  so signing in, sync and the other account features work.

## Build 4 — Nuvio Plus

### Changed
- The launcher shows **Nuvio Plus**, so it can be told apart from the official app.
- Release files are named `NuvioPlus-build<N>-<abi>.apk`, with notes taken from this changelog.
- README and changelog describe the fork.

## Build 3 — background season downloads

### Fixed
- **Season downloads in the background (Android 14+).** Android does not let an app start a new
  download job while it is hidden, so remaining episodes would have failed once the app was left.
  The job that finishes an episode now looks up the next one and runs it itself. A chained episode
  stops at once when it is paused or removed from the notification or the app.

### Changed
- The season queue starts new downloads only while the app is visible (or on Android 13 and older)
  and resumes when the app comes back.
- Up to three matching sources are tried per episode. Sources a debrid service reports as not cached
  are skipped; cached ones are preferred.

### Added
- Free space check: a download stops before transferring, with a message such as
  "Not enough free space: 4.2 GB more is needed", when the target storage is too small.

## Build 2 — download location and season downloads

### Added
- **Download location.** App storage, or any folder picked through the system picker (SD cards
  included). Transfers write straight into the folder as `Name.part`, resume after pauses and lost
  connections, and are renamed when complete. Episodes go into a folder named after the show; subtitle
  copies (`Name.ar.srt`) are saved beside the video. Playback works in ExoPlayer and mpv; deleting a
  download removes the video and its subtitle copies.
- **Wi-Fi only** option for new downloads.
- **Download season.** From the stream actions of an episode: download the rest of the season or all
  of it. Episodes run one at a time and each source is looked up right before its turn, matching the
  add-on, binge group, resolution and release type of the first pick. Already downloaded or running
  episodes are skipped and failed ones retried. Episodes without a match stay in the *Season queue*
  with retry and remove buttons. The queue is saved and survives restarts.
- CI publishes the error log as a pre-release when a build fails.

## Build 1 — subtitles

### Added
- **Subtitle brightness** (10–100%), stored with the subtitle appearance and synced like it.
  Applied to the Compose subtitle overlay, ExoPlayer's subtitle view (text and bitmap subtitles, through
  a color filter layer) and mpv subtitle colors.
- **Load subtitle from device.** System file picker; the file is converted to UTF-8 with charset
  detection (Windows-1256 and other legacy encodings) and its language read from the file name.
  Attached to ExoPlayer or mpv without going through AutoSync, which reads over HTTP.
- **Touch gestures.** Vertical swipe in the middle strip or two-finger drag moves subtitles; pinch
  resizes them. The value is shown while moving and saved.
- Three gray subtitle colors; vertical position range widened from 0–200 to 0–600.
- Separate package (`com.nuvio.app.plus`), so the build installs next to the official app.

### Replaced
An earlier third-party modification of this app stored subtitle brightness in the outline-width setting,
which broke settings sync with the official app, and dimmed image subtitles with a wrong formula
(`%` instead of `/`). Both are reimplemented properly here.
