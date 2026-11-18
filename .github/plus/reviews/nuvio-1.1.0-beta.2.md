# Review: Nuvio 1.1.0-beta.2 into `plus-beta` (trial merge, not for publishing yet)

status: pending
tested_commit: 
approved_by: 

<!-- Approve by setting status: approved, the commit whose checks passed, your name, and ticking
every box with the decision written after it. Publishing is blocked while any box is open. -->

Nuvio `81f836d4` · common base `511ebd5f` · 132 files changed by Nuvio, 17 of them also by Nuvio Plus.

## Git conflicts (resolved in commit 67c37e60 — proposals, confirm each)

- [ ] `composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadsScreen.kt` — features: download-location
  - **Proposed:** adopt Nuvio's redesigned screen (summary, filters, action sheet, speeds, watched state); keep the Plus location / Wi-Fi card and season queue above the list, restyled with Nuvio's `SectionHeader` and spacing. Plus row code replaced by Nuvio's rows.
- [ ] `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt` — features: subtitle-style
  - **Proposed:** keep the Plus 0–600 position range; outline width uses Nuvio's 1–5 range and slider, through `normalizeSubtitleOutlineWidth`, which still sends the corrupted values (> 8) of the earlier modification to the default instead of clamping them to 5.
- [ ] `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/StyledSubtitle.kt` — features: subtitle-style, player-engines
  - **Proposed:** adopt Nuvio's cue blocks (top/bottom/side positioning, colored spans) with the Plus position range; brightness now also dims colors that come from the subtitle file.
  - **Open decision — outline thickness:** Nuvio halves the overlay outline (`/ 2f`) so width 2 matches the native renderer. Kept at the Plus thickness in this merge; compare both on real samples (SRT with outline 1–5, ExoPlayer and mpv, phone and tablet) before choosing.

## Nuvio Plus features touched by this release

### subtitle-style — Subtitle brightness, gray colors, wider position range, outline repair

- [ ] Decision (keep Plus / adopt Nuvio / combine), data kept (subtitle appearance JSON (brightness), subtitle bottom offset, outline width), tests: SubtitleAppearanceTest, PlusSubtitleMigrationTest
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt`
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/StyledSubtitle.kt`
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAppearance.kt`
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAppearanceControls.kt`
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/SubtitleAudioModels.kt`
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SubtitleAppearanceSettings.kt`
  - Nuvio code that may duplicate or alter this feature:
    ```
    (outlineEnabled && outlineWidth != SUBTITLE_OUTLINE_WIDTH_DEFAULT)
    ) { onStyleChanged(style.copy(outlineWidth = it)) }
    ?: SubtitleStyleState.DEFAULT.outlineWidth).coerceIn(subtitleOutlineWidthRange),
    assertFalse(SubtitleStyleState(outlineWidth = 4, outlineEnabled = false).usesStyledOverlay)
    assertFalse(SubtitleStyleState(outlineWidth = SUBTITLE_OUTLINE_WIDTH_DEFAULT).usesStyledOverlay)
    assertTrue(SubtitleStyleState(outlineWidth = 4).usesStyledOverlay)
    fun customOutlineWidthUsesStyledOverlay() {
    internal val subtitleOutlineWidthRange: IntRange = 1..5
    onValueChange = { onStyleChanged(style.copy(outlineWidth = it)) },
    outlineWidth = (PlayerSettingsStorage.loadSubtitleOutlineWidth()
    outlineWidth = style.outlineWidth.coerceIn(subtitleOutlineWidthRange),
    stringResource(Res.string.subtitle_outline_width), style.outlineWidth, subtitleOutlineWidthRange, 1,
    val outline = with(density) { (style.outlineWidth.dp * scale).toPx() } / 2f
    val outlineWidth: Int = SUBTITLE_OUTLINE_WIDTH_DEFAULT,
    value = style.outlineWidth, valueText = { it.toString() },
    ```

### player-engines — Brightness and offsets in ExoPlayer and mpv, HDR-safe subtitle dimming

- [ ] Decision (keep Plus / adopt Nuvio / combine), data kept (none), tests: device matrix: subtitles on ExoPlayer and mpv, SDR/HDR
  - changed by Nuvio: `composeApp/src/androidMain/kotlin/com/nuvio/app/features/player/PlayerEngine.android.kt`

### subtitle-gestures — Move subtitles by swipe or two fingers, pinch to resize

- [ ] Decision (keep Plus / adopt Nuvio / combine), data kept (font size and bottom offset (shared with subtitle-style)), tests: device matrix: gestures do not fight seek and brightness swipes
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeState.kt`

### download-location — Download folder (SAF), .part files with resume, Wi-Fi only, free space check (playback of content:// downloads included)

- [ ] Decision (keep Plus / adopt Nuvio / combine), data kept (download records (targetFolderUri, wifiOnly), download_preferences, transfer store documentUri), tests: PlusDownloadsMigrationTest, DownloadSubtitlesTest, DownloadsTransferWorkerTest, AndroidDownloadLifecycleTest
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadsRepository.kt`
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/downloads/DownloadsScreen.kt`
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerScreenRuntimeEffects.kt`

### library-downloads — Downloads tab in the library with posters and progress

- [ ] Decision (keep Plus / adopt Nuvio / combine), data kept (none (reads download records and the season queue)), tests: LibraryDownloadEntriesTest
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/MainAppContent.kt`
  - changed by Nuvio: `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryScreen.kt`

### arabic — Arabic texts for Plus features and the 46 texts missing in Nuvio 1.0.0

- [ ] Decision (keep Plus / adopt Nuvio / combine), data kept (none), tests: CI: build fails on duplicate resource names
  - changed by Nuvio: `composeApp/src/commonMain/composeResources/values/strings.xml`

## Nuvio CI and build files

- [ ] `.github/workflows/android-release.yml` — applied as is: Nuvio's own release workflow (manual), not used by Nuvio Plus
- [ ] `.github/workflows/update-store-source.yml` — applied as is: Nuvio's iOS sideload source; never triggered by Plus releases (published by the Actions token)
- [ ] `gradle/libs.versions.toml` — merged; check versions, signing and the Plus build properties

## Registry gaps (Plus files without a feature entry)

- `composeApp/src/commonMain/kotlin/com/nuvio/app/features/library/LibraryDownloadsButton.kt`

## Not merged automatically / still open

- [ ] Arabic: every new Nuvio text is translated (downloads, library filters, outline width, tracking
      consent, and the 55 Plex, Silo and server sign-in texts). Check them on a phone in Arabic.
- [ ] Device matrix (`docs/plus/device-test-matrix.md`) rows D2–D3, D5, D8–D14, D17, D19 on a phone with this build.
