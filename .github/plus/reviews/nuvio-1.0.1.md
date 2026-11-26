# Review: Nuvio 1.0.1 into `custom-subtitles`

status: approved

<!-- Review in the pull request: write each decision after its box and tick it, then set
status: approved (or rejected). The approval counts only when the owner merges the pull request
with the nuvio-plus/checks and nuvio-plus/review statuses green; the publish gate confirms that
with GitHub. Deleting this file does not skip the review. -->

Nuvio `3cfc311b4` · common base `511ebd5f` · 6 files changed by Nuvio, 1 of them also by Nuvio Plus.

## Nuvio Plus features touched by this release

### download-location — Download folder (SAF), .part files with resume, Wi-Fi only, free space check (playback of content:// downloads included)

- [x] Decision: combine. Nuvio's iOS change only marks the app's internal downloads folder as excluded from iCloud/computer backup; Plus's change in the same file (removeDeletedProfileTransfer) is elsewhere and unchanged; the Android SAF/Wi-Fi only/.part pipeline is not touched. Data kept (download records (targetFolderUri, wifiOnly), download_preferences, transfer store documentUri), tests: PlusDownloadsMigrationTest, DownloadSubtitlesTest, DownloadsTransferWorkerTest, AndroidDownloadLifecycleTest, DownloadFreeSpaceTest, PlusWifiOnlySchedulingTest
  - changed by Nuvio: `composeApp/src/iosMain/kotlin/com/nuvio/app/features/downloads/DownloadsPlatformDownloader.ios.kt`

### subtitle-parser-worst-case — Subtitle parsers stay fast on malformed files

- [x] Decision: keep Plus. False match: the lines listed come from Nuvio's TorBox cache matching (LocalDebridService) and the iOS backup flag; no subtitle parser code changed. Data kept (none), tests: composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/SubtitleParserWorstCaseTest.kt
  - Nuvio code that may duplicate or alter this feature:
    ```
    
    ) { hash ->
    ): Map<String, LocalDebridCachedItem> {
    ): Map<String, LocalDebridCachedItem>? {
    * Downloads can be many GB and can be downloaded again, so they must not be part of iCloud or
    * TorBox matches hashes through its alternative-hash index and answers with the
    * canonical hash as key, so an alias hit would otherwise read as a cache miss.
    * computer backups. The flag is set on the folder and covers everything inside it, files that
    * were downloaded before this change included.
    */
    .awaitAll()
    .distinct()
    .filter { it !in resolved }
    .filter { it.isNotBlank() }
    .map { hash ->
    ```

## Checked by hand (not flagged by the tool)

- [x] `PlayerLayout.playerTimelineBottomInsets`: on Android Nuvio now uses `safeDrawing` instead of `safeContent`, so the timeline no longer adds the gesture-navigation inset. Its only consumers are the player controls and the skip/next-episode overlays (`overlayBottomPadding`); Plus subtitle position, size and gestures do not read it. Adopt Nuvio.
- [x] `TraktLibraryRepository` (bounded refresh queue) and `LocalDebridService` (TorBox alternative hashes, with its test): no Plus changes in these files. Adopt Nuvio.
- [x] `iosApp/Configuration/Version.xcconfig`: iOS version only; the Android versionCode/name and identity of Nuvio Plus are unchanged.
