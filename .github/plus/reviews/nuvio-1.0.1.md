# Review: Nuvio 1.0.1 into `custom-subtitles`

status: pending

<!-- Review in the pull request: write each decision after its box and tick it, then set
status: approved (or rejected). The approval counts only when the owner merges the pull request
with the nuvio-plus/checks and nuvio-plus/review statuses green; the publish gate confirms that
with GitHub. Deleting this file does not skip the review. -->

Nuvio `3cfc311b` · common base `511ebd5f` · 6 files changed by Nuvio, 0 of them also by Nuvio Plus.

## Nuvio Plus features touched by this release

### subtitle-parser-worst-case — Subtitle parsers stay fast on malformed files

- [ ] Decision (keep Plus / adopt Nuvio / combine), data kept (none), tests: composeApp/src/commonTest/kotlin/com/nuvio/app/features/player/SubtitleParserWorstCaseTest.kt
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
