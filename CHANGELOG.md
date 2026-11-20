# Changelog — Nuvio Plus

Changes on top of [Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile) at
[`511ebd5`](https://github.com/NuvioMedia/NuvioMobile/commit/511ebd5f175dd39e31b9cc0e7b259259e887cf6f).
Newest first.

## Next build — Build 15: audit and polish

### Fixed
- **Subtitle files picked from the device no longer pile up.** Each pick kept a copy in the app's
  cache. Now each pick removes the copies left from earlier app sessions (nothing refers to them
  after a restart); copies from the current session stay, so a subtitle being shown is never
  removed. Two picks in the same millisecond no longer write to the same file.
- **A subtitle file with another extension is read in its real format.** A file picked from the
  device as `.txt` (or with no extension) was always treated as SRT, so an ASS or VTT file showed
  nothing. Its format is now read from the text, and a file that is no subtitle is refused with a
  message.
- **A malformed subtitle could freeze a download or the subtitle list.** Subtitles from add-ons are
  read when a download is prepared and in the player. A file with an unclosed tag, brace or paragraph
  repeated made the reader rescan the rest of the file from every position: 60 KB took 5 seconds,
  1 MB minutes, holding up the download. It now reads such files in a fraction of a second; normal
  files give exactly the same lines (60,000 random files compared). Text after a broken tag such as
  `<b ` is no longer dropped.
- **Lists could crash when two sections shared a key that looked like a generated one.** The helper
  that makes repeated list keys unique (home, search, catalogs, collections) could give two entries the
  same key, for example `section`, `section#0`, `section`. It now never reuses a real or generated key;
  lists without such keys keep exactly the keys they had.

## Build 14 — stability: updates, downloads, project safety

### Fixed
- **In-app updates on Android 9.** The app's update check could not read the signature of a
  downloaded update on Android 9 and refused every update as unsigned (builds 12 and 13). Fixed in
  this build. **On Android 9, install this build once from this page** (download the APK and open it;
  your data stays); later updates then work from inside the app. Android 10 and newer were not affected.

### Changed
- **Every release is tested as an update before it is published**: on Android 9 and 14 emulators, from
  the previous release and from build 12, with app data kept. The app's own update check runs on the
  emulator's real Android and must accept the release and refuse an APK with another key, one with the
  old public key and a damaged download. Pull requests run the same check against the published
  release.
- **A merge never publishes a release.** Merged pull requests are built and checked only; a release
  is started by hand for the exact commit and still needs every check and both of the owner's
  approvals, so merging the parts of a build one by one cannot release a partial build. Signing and
  publishing each read the branch head again and refuse a commit the branch has moved past.
- **Failed builds are reported in their run, not as releases.** The report and logs are kept as a
  run artifact; the Releases page lists only published builds (older failure pages stay).
- **Release notes say how to update from build 13 and from build 12**, in English and Arabic.
- **Project documentation describes the new signing key.** The README, the rollback rules and the
  feature list no longer present the old public key as the release key; a rescue build is always
  signed with the current key and has a higher version code.

- **Season downloads: a clear message when the profile changes while a season is being prepared**
  ("The profile changed while the download was being prepared. Try again from the profile you
  want."), instead of the generic "nothing to add". Nothing is queued in either profile.
- **Season downloads: an episode stopped by a profile switch goes back to Waiting**, not "needs a
  choice". Back in its profile it is searched again and downloaded once.

### Added
- **Checks for known vulnerabilities in the app's libraries.** Every full check lists the libraries
  in the release APK and asks the OSV database about them; a known high or critical vulnerability
  stops the build until it is fixed or reviewed with a written reason and an end date.

### Security
- **Guava updated from 33.3.1 to 33.7.2** (Android edition), fixing a high-severity issue in which
  reading crafted serialized data could use up memory (GHSA-xxph-c9ww-hj94). Found by the new
  vulnerability check.

- **Add-on links ask before installing.** A stremio:// or nuvio:// link from a web page or another
  app used to add the add-on at once, without asking. Now the app shows where the add-on comes from
  and adds it only if you confirm, because an add-on sees what you watch and supplies the streams.

### Removed
- **The "accept a new signing certificate" switch of the build workflows.** It belonged to the key
  change of build 13; another key change would need its own reviewed plan.

## Build 13 — new signing key, safer updates and season downloads

### Fixed
- **Pausing a season download pauses the season.** Pausing an episode that came from "download
  season" (or pausing all downloads) used to start the next episode at once. The paused episode now
  keeps its place, and the season continues when you resume it. The downloads screen says why the
  next episode waits; removing the paused episode from the queue skips it.
- **Each profile has its own season queue.** Switching profiles while a season was downloading
  could drop the running episode from the queue and download the next one into the other profile's
  downloads. Every profile now keeps and continues only its own queue. Queues saved by earlier builds
  are kept. Switching profiles while "download season" is still loading the episode list queues
  nothing (it used to queue the season into the profile switched to), and a profile switch no longer
  starts the same episode twice, which restarted its download.
- **The season queue is no longer lost by a damaged entry.** It is read entry by entry, and anything
  unreadable is kept aside instead of erasing the queue. The queue is also stored so that earlier
  builds can still read it.

### Changed
- **New signing key; Android 9 or later.** The key that signed builds 8–12 was public, so anyone
  could make an "update" of the app. This build is signed with a new, protected key and carries
  Android's proof that the old key handed over to it. Install it once from the release page over the
  old app: on Android 9 and later your data is kept, and from then on the phone refuses anything
  signed with the old key. The old app's updater cannot install it. Android 7 and 8 cannot install
  this build and stay on build 12.
- **The in-app updater refuses an update it cannot fully check.** A damaged or unsigned download, or
  an update this app cannot compare with its own signature, is refused with a message (also in
  Arabic) instead of being handed to the installer.
- **Safer builds.** The signing key is never available to builds of code that has not been reviewed.
  A release is published only for a reviewed pull request that passed every check, after the owner
  approves the signing and the publishing. The update check now really compares signing certificates.

## Build 12 — safe upstream sync v2, updater channels, strict tests

### Changed
- **Nuvio releases are prepared for review, never merged on their own.** Each release gets a
  `plus-review/nuvio-<tag>` branch, a review record that maps Nuvio's changes to the Nuvio Plus
  feature registry (`.github/plus/features.json`), checks, and a draft pull request. Stable Nuvio
  releases target `custom-subtitles`, betas the separate `plus-beta` branch. Review branches are
  never rewritten; Nuvio CI files are listed for review instead of being dropped silently.
- **Publishing requires** a merged pull request, every unit test passing (with the real Nuvio Plus
  settings), the update check, the emulator tests and every review record approved for a tested
  commit. Nuvio's own test results are shown next to them for diagnosis only.
- **In-app updates** respect the Stable and Beta channels for Nuvio Plus releases (`custom-N` and
  `custom-N-beta`), offer only an APK built for the device's ABI, and check a downloaded update
  (same app, same signing key, higher version) before the installer opens.
- Emulator test: besides installing over the previous build and opening it, checks that the UI is
  drawn, survives landscape rotation and Back, and starts in Arabic; screenshots are kept.

### Fixed
- The emulator test looked for the new APK in the wrong folder and never installed it.
- A Nuvio test with a 1-second video (treated as a placeholder clip by a newer rule) is replaced by
  Nuvio's own fix.

## Build 12 — gated updates and review fixes

### Changed
- **Nuvio updates go through staging.** The newest stable Nuvio release is merged into `plus-staging`,
  built and checked there, and reaches `custom-subtitles` only when every check passes. Conflicts,
  failed checks and code that overlaps Nuvio Plus features stop the update and are reported as a
  `sync-…` pre-release. Betas are never merged automatically.
- **No APK is published unless every check passes:** the build, all unit tests of the app, an update
  check against the published build (package, signing certificate, version code) and an emulator test
  that installs the previous build, updates it in place, checks that app data stays and opens the app.
  Failed checks publish only a `build-failed-…` pre-release with the errors. The unit tests of the
  Nuvio release a build is based on run alongside; a test may fail only if it fails there too.
- The scheduled pipeline also lives on the repository's default branch, where GitHub runs schedules.
- Build numbers continue from the last published build in every workflow.

### Fixed
- **Season queue:** each episode's source search is its own job. Removing an episode stops only its
  search (before, it could stop another episode's search and stall the queue), and an episode removed
  while its link was being resolved is never downloaded, also in the background.
- **Library downloads:** a finished movie plays even while another copy downloads; a movie and a show
  with the same id no longer share a poster; a failed episode stays visible next to a running one;
  posters read their title and state to screen readers; grouping is a single pass.
- The free space check no longer stops a download when the storage reports no size at all (it
  failed the download tests, which run without real storage).
- The last 16 texts still in English are translated (ratings and a subtitle sync message).

### Added
- Regression tests for settings and downloads saved by the official app or older builds (subtitle
  appearance, the repaired outline width, download records, season queue, download preferences), for
  the library tab and for the in-app updater.

## Build 11 — follows Nuvio automatically

### Added
- **Automatic Nuvio updates.** Every day the fork checks Nuvio's stable branch (`main`); new releases
  are merged with the Nuvio Plus changes and built. A merge that conflicts with Nuvio Plus changes, or
  that touches workflow files, is not published: it shows up as a `sync-needed` pre-release (and an
  issue, when issues are enabled) to merge by hand.
- **In-app updates from this fork.** The update check follows Nuvio Plus releases instead of the
  official app, offers the next build and installs it over the current one.

### Changed
- The version name follows Nuvio's version, e.g. `1.0.0-plus.11`.
- Nuvio's issue housekeeping workflows run only in the official repository.

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
