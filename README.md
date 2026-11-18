<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="280" />

  <h2>Nuvio Plus</h2>

  <p>
    An unofficial fork of <a href="https://github.com/NuvioMedia/NuvioMobile">Nuvio Mobile</a> for Android,
    focused on <b>subtitles</b> and <b>downloads</b>.
    <br />
    نسخة معدّلة غير رسمية من Nuvio Mobile لأندرويد، تركّز على <b>الترجمة</b> و<b>التنزيلات</b>.
  </p>

  [![Latest build](https://img.shields.io/github/v/release/Mohammed4421/NuvioMobile?label=latest%20build&sort=date)](https://github.com/Mohammed4421/NuvioMobile/releases/latest)
  [![Build](https://github.com/Mohammed4421/NuvioMobile/actions/workflows/custom-android-build.yml/badge.svg?branch=custom-subtitles)](https://github.com/Mohammed4421/NuvioMobile/actions/workflows/custom-android-build.yml)
  [![License: GPL v3](https://img.shields.io/badge/license-GPLv3-blue.svg)](./LICENSE)

  [Download](https://github.com/Mohammed4421/NuvioMobile/releases/latest) ·
  [Changelog](./CHANGELOG.md) ·
  [Upstream Nuvio](https://github.com/NuvioMedia/NuvioMobile)

</div>

> [!NOTE]
> Nuvio Plus is not made or supported by the Nuvio team. Please report problems with this build
> here, not to the official project.
>
> هذه النسخة ليست من فريق Nuvio ولا يدعمها. أي مشكلة فيها تُبلَّغ هنا، لا في المشروع الرسمي.

## What's different / ما الذي يختلف

| | Official Nuvio | Nuvio Plus |
|---|---|---|
| Subtitle brightness | — | 10–100%, for HDR and dark scenes |
| Subtitle colors | 10 swatches | 13 (three grays added) |
| Subtitle position | 0–200 | 0–600 |
| Subtitle file from the device | — | SRT, VTT, ASS/SSA, TTML; Arabic legacy encodings detected |
| Move / resize subtitles by touch | — | Swipe or two-finger drag, pinch to resize |
| Download location | App storage only | App storage or any folder, SD card included |
| Download a season | One episode at a time | Rest of the season or the whole season |
| Downloads in the library | Button in the header | Own tab with posters and progress |
| Wi-Fi only downloads | — | ✓ |
| Free space check | — | Before the transfer starts |
| Installs next to the official app | — | ✓ (`com.nuvio.app.plus`, shown as "Nuvio Plus") |

## Features / الميزات

### Subtitles / الترجمة

- **Brightness / السطوع** — dims subtitles without making them transparent, in the player panel and in
  settings. Applied to text subtitles, image subtitles (PGS) in ExoPlayer and text subtitles in mpv.
  يخفّف إضاءة الترجمة دون أن يجعلها شفافة.
- **Load from device / تحميل من الجهاز** — a button next to the subtitles title opens the file picker.
  Files are converted to UTF-8 (Windows-1256 and other legacy encodings are detected), and the
  language is read from names such as `movie.ar.srt`.
- **Touch gestures / التحكم باللمس** — swipe up or down in the middle of the screen, or drag with two
  fingers, to move subtitles; pinch to resize them. The current value is shown while you move.
- **More colors and range / ألوان ومدى أوسع** — three gray swatches and a vertical position range of 0–600.

### Downloads / التنزيلات

- **Download location / مكان التنزيل** — keep app storage or pick any folder through the system picker,
  including an SD card. Files are written straight into the folder (no second copy), resume after a
  pause, and get their final name when complete:
  `Show/Show S01E02 - Title.mkv` with subtitle copies such as `Show S01E02 - Title.ar.srt` beside them.
  Files in a picked folder stay after the app is removed and open in any player.
- **Download a season / تنزيل موسم** — long-press a stream of an episode → *Download season* → *rest of the
  season* or *whole season*. Episodes download one after another; each source is looked up right
  before its turn (so debrid links are fresh) by matching the add-on, binge group, resolution and
  release type of your first pick. Up to three candidates are tried, cached debrid sources first.
  Episodes without a match wait in the *Season queue* for a retry. On Android 14+ the queue keeps going
  while the app is in the background.
- **Library tab / تبويب في المكتبة** — *Downloads* sits next to *Saved* and *Cloud* and shows each
  downloaded movie or show as a poster with its progress. Tap to play a movie or open a show's episodes.
- **Wi-Fi only / Wi-Fi فقط** and a **free space check** that stops early with a clear message.

## Install / التثبيت

1. Download the `arm64-v8a` APK from the [latest build](https://github.com/Mohammed4421/NuvioMobile/releases/latest)
   (most phones and tablets).
2. Install it. It sits next to the official Nuvio app and keeps its own settings and downloads.
3. To update, install the newer APK over it; settings and downloads stay.

> [!IMPORTANT]
> Builds 1–6 were signed with a different key each time: uninstall such a build once before installing
> build 8 or newer. From then on, every build installs as an update. Nuvio account sign-in works; Trakt sign-in needs your own Trakt API keys
> (see *Build*).
>
> البناءات 1–6 كانت تُوقَّع بمفتاح مختلف كل مرة؛ احذفها مرة واحدة قبل تثبيت البناء 8 أو أحدث، وبعدها كل بناء يُثبَّت كتحديث. حساب Nuvio يعمل، أما Trakt
> فيحتاج مفاتيح Trakt خاصة بك.

## Staying up to date / التحديثات

- **Nuvio updates:** every day the *Sync with Nuvio* workflow merges new Nuvio stable releases with the
  Nuvio Plus changes and builds an update. When Nuvio changed the same code as Nuvio Plus, or changed
  workflow files, nothing is published; a `sync-needed` pre-release lists the files to merge by hand.
- **In the app:** the update check follows this fork's releases, so a new build shows up in the app and
  installs over the current one.

تحديثات Nuvio الرسمية تُدمج تلقائيًا كل يوم وتُبنى نسخة جديدة، وتظهر لك داخل التطبيق. إذا عدّل فريق Nuvio
نفس الأجزاء التي عدّلتها Nuvio Plus لا يُنشر شيء، ويظهر إصدار تجريبي باسم `sync-needed` يحتاج دمجًا يدويًا.

## Known limits / حدود معروفة

- Subtitle brightness does not reach ASS subtitles drawn by libass (that renderer lives in a prebuilt
  library). Turning libass off applies it there too.
- If Android closes the app process completely, the episode in progress finishes, and the season queue
  continues the next time the app is opened.
- Changing the download location applies to new downloads; earlier files are not moved.

## Build / البناء

Every push to `custom-subtitles` builds a signed release APK with GitHub Actions and publishes it under
[Releases](https://github.com/Mohammed4421/NuvioMobile/releases). If a build fails, the error log is
published as a pre-release so it can be read without opening the Actions log.

Builds are signed with the key in `signing/`, so each one installs as an update. That key is public:
an APK from anywhere else could be signed with it too, so install Nuvio Plus only from this
repository's releases, or set your own key with the secrets below.

Optional repository secrets:

| Secret | Purpose |
|---|---|
| `CUSTOM_KEYSTORE_BASE64`, `CUSTOM_KEYSTORE_PASSWORD`, `CUSTOM_KEY_ALIAS` | Sign with your own private key instead of the public one in `signing/` |
| `CUSTOM_LOCAL_PROPERTIES` | Extra `local.properties` lines (Trakt, Simkl, Supabase keys and so on) |

Local build:

```bash
./gradlew :androidApp:assembleFullRelease \
  -Pnuvio.android.distribution=full \
  -Pnuvio.ios.distribution=appstore \
  -Pnuvio.applicationIdSuffix=.plus
```

`-Pnuvio.appLabel=...` changes the launcher name (default `Nuvio Plus`).

## Credits & license

Built on [Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile) by the Nuvio team, a Kotlin Multiplatform
and Compose Multiplatform app. All changes are listed in the [changelog](./CHANGELOG.md).

Licensed under the [GNU General Public License v3.0](./LICENSE), like the original project.
