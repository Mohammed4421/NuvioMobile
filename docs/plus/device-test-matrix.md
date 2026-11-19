# Nuvio Plus — acceptance matrix

What CI proves on every build, and what only a person on a real device can confirm. A release
candidate is accepted when every CI row passes and the device rows marked **required** pass on at
least one phone; the tablet and HDR rows are required when that hardware is available.

## Run by CI (automatic, every build)

| Area | Check | Where |
|---|---|---|
| Build | Release APKs for 4 ABIs | `plus-build.yml` › Build APKs |
| Unit and integration | All `testAndroidHostTest` tests (2167 on PR #6, Robolectric on Android 8, 14, 15, 16), real Plus settings | › Unit tests of Nuvio Plus |
| Season queue | Pause holds the queue, per-profile queues, storage readable by Build 12, damaged and newer data | `SeasonQueuePolicyTest`, `SeasonQueueStorageTest` |
| Migration | Settings and downloads saved by Nuvio or older Plus builds load | `PlusSubtitleMigrationTest`, `PlusDownloadsMigrationTest` |
| Updater | Stable/beta channels, late answers ignored, pending download cancelled, ABI and signature checks | `Plus*Updater*Test`, `UpdateArchiveCheckTest` |
| Signing | APKs signed outside Gradle, v2 only, the certificate of builds 8–12 | › Sign APKs |
| Update over previous | Same package and certificate (read from the APK Signing Block), higher version code | › Update check |
| Emulator (Android 14) | Install previous build, open, update in place, data kept, open new build, UI drawn, landscape, Back, Arabic start; screenshots kept | › Emulator test |
| Reviews | The newest Nuvio release merged has an approved, complete review record | › Nuvio merge reviews complete |
| Policy | Workflow security rules, review gate, release scripts | › Tests of the review gate and release scripts |

## On a real device (by a person)

| # | Area | Steps | Expected | Required |
|---|---|---|---|---|
| D1 | Update | Install build N-1, use it, install build N over it | Installs as an update; account, add-ons, downloads, settings kept | **required** |
| D2 | Playback ExoPlayer | Play an MKV/MP4 stream with SRT subtitles | Plays; subtitles shown; brightness, size, position change live | **required** |
| D3 | Playback mpv | Same with mpv as engine | Same | **required** |
| D4 | HDR10 / Dolby Vision | Play HDR10 and DV content on an HDR screen, subtitle brightness 50% | Video unchanged, subtitles dimmed, no washed-out picture | when HDR hardware is available |
| D5 | ASS subtitles | ASS file with libass on | Styled; note shown that brightness does not apply | **required** |
| D6 | Image subtitles | PGS track, ExoPlayer | Brightness applies | when content is available |
| D7 | Device subtitles | Load an SRT in Windows-1256 from Files | Arabic readable, language guessed from the name | **required** |
| D8 | Gestures | Swipe in the middle, two-finger drag, pinch | Subtitles move or resize; seek and brightness swipes still work | **required** |
| D9 | Download to app storage | Download a movie, pause, resume, play offline | Completes, plays offline | **required** |
| D10 | Download to a picked folder (SAF) | Pick a folder (and an SD card if present), download an episode | `Show/Show S01E02 - Title.mkv`, subtitles beside it, no `.part` left | **required** |
| D11 | Interrupted download | Turn off network mid-download, turn on | Resumes from the same `.part` | **required** |
| D12 | Wi-Fi only | Enable, start a download on mobile data | Waits for Wi-Fi | **required** |
| D13 | Season download | Download the rest of a season, leave the app | Episodes continue (Android 14+: in the background) | **required** |
| D14 | Library tab | Open Library › Downloads | Posters, progress, tap plays a movie / opens a show | **required** |
| D15 | Settings migration | Install over a build with custom subtitle settings | Values kept, outline width sane | **required** |
| D16 | Account | Sign in to Nuvio, change a setting, check another device | Signs in; setting syncs | **required** |
| D17 | Arabic / RTL | Phone language Arabic; walk through library, player, settings, downloads | Mirrored layout, no clipped or overlapping text | **required** |
| D18 | Tablet | Landscape tablet (11" and larger) | Layout fits, player panels usable, Back works | when a tablet is available |
| D19 | Navigation | Back from player, downloads, show downloads, settings | Returns to the previous screen, never exits unexpectedly | **required** |
| D20 | Performance | Scroll library and home for 1 minute; play 10 minutes | No visible stutter, no overheating, no crash | **required** |
| D21 | In-app update | With build N installed and N+1 published, open Settings › Updates | Offers N+1 (stable channel), installs over N | **required** |
| D22 | Pause a season episode (F1) | Download the rest of a season; pause the running episode from the downloads screen; wait 2 minutes; then pause it from the notification instead | The next episode does not start; the queue shows "Paused: the next episode waits until you resume it"; resuming continues the same episode, then the next | **required** |
| D23 | Skip a paused episode | Pause the running queue episode, remove it from the queue | The next episode starts; the paused download stays in the list, paused | **required** |
| D24 | Profiles during a season (F2) | Two profiles. In A, download the rest of a season; switch to B for 5 minutes; download something in B; switch back to A | B's downloads and queue show only B's items; A's queue continues in A; nothing of A appears in B, nothing is downloaded twice | **required** when two profiles are used |
| D25 | Restart with a queue | With a paused episode and waiting episodes, force-stop the app (Settings › Apps), open it | The same queue, the paused episode still paused, nothing started | **required** |
| D26 | Partial files | Pick a folder (SAF); start an episode; turn on airplane mode at about 50%; reopen after 1 minute; turn the network on | Resumes; at the end one finished file, no `.part` left, the file plays to the end | **required** |
| D27 | Folder permission removed | After D10, remove the app's access to the folder (Settings › Apps › Nuvio Plus › Storage, or pick another folder and delete the old one) | Downloads fail with a clear message or ask for the folder again; no crash; files already downloaded stay | **required** |
| D28 | Playback after the update | After D1, play a downloaded episode and a stream, both with subtitles (ExoPlayer and mpv) | Plays; subtitle settings kept (size, position, brightness, colours) | **required** |
| D29 | Update check refuses a foreign APK | Settings › Updates with an update downloaded; replace the downloaded file by any other APK of the same name (developers only) | "signed with a different key" message; nothing installed | when a developer can do it |

Record results per build in the review pull request or the release discussion: device, Android
version, engine, result, notes. Nothing in this table is claimed by CI.
