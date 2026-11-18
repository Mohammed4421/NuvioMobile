# Changelog — Nuvio Plus

Changes on top of [Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile) at
[`511ebd5`](https://github.com/NuvioMedia/NuvioMobile/commit/511ebd5f175dd39e31b9cc0e7b259259e887cf6f).
Newest first.

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
