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
   (most phones and tablets). Android 9 or later.
2. Install it. It sits next to the official Nuvio app and keeps its own settings and downloads.
3. To update from build 13 or later, use Settings › Updates in the app, or install the newer APK over
   it; settings and downloads stay.

> [!IMPORTANT]
> **Build 13 changed the signing key**, because the key of builds 8–12 was published in this
> repository. Coming from build 12 or earlier, download build 13 or later once from the Releases page
> and install it over the old app, without uninstalling it: on Android 9 and later your data is kept,
> and from then on the phone refuses anything signed with the old key. The old app's updater cannot
> do this step. Android 7 and 8 cannot install build 13 or later.
>
> **غيّر البناء 13 مفتاح التوقيع** لأن مفتاح البناءات 8–12 كان منشورًا في هذا المستودع. من البناء 12
> أو أقدم: نزّل البناء 13 أو أحدث من صفحة الإصدارات مرة واحدة وثبّته فوق التطبيق القديم دون حذفه؛ على
> أندرويد 9 وأحدث تبقى بياناتك، وبعدها يرفض الجوال أي ملف موقّع بالمفتاح القديم. محدّث التطبيق القديم لا
> يستطيع هذه الخطوة. أندرويد 7 و8 لا يستطيعان تثبيت البناء 13 وما بعده.
>
> Nuvio account sign-in works; Trakt sign-in needs your own Trakt API keys (see *Build*).
> حساب Nuvio يعمل، أما Trakt فيحتاج مفاتيح Trakt خاصة بك.

## Staying up to date / التحديثات

Nuvio releases reach Nuvio Plus only through review (`.github/workflows/plus-pipeline.yml`, daily):

1. For a new Nuvio release, a review branch `plus-review/nuvio-<tag>` is made from `custom-subtitles`
   (stable releases) or `plus-beta` (betas, never automatic) and the release is merged there only.
2. A review record lists conflicts, touched Nuvio Plus features and possible duplicates; the branch is
   built and checked (never published) and a pull request is opened.
3. Nothing reaches `custom-subtitles` until the owner merges that pull request. A merge never
   publishes: a release is started by hand for the exact branch head and needs the signing and
   publishing approvals described under *Build* (`docs/plus/release.md`).

The in-app update check follows this fork's releases.

تحديثات Nuvio لا تصل إلا بعد مراجعة: لكل إصدار جديد يُنشأ فرع مراجعة ويُدمج الإصدار فيه وحده، ويُبنى
ويُختبر دون نشر، ثم يُفتح طلب دمج. لا يدخل شيء الفرع المستقر إلا بدمج المالك، ولا يُنشر شيء إلا بموافقتيه
على التوقيع والنشر. إصدارات Beta لا تُدمج تلقائيًا.

## Known limits / حدود معروفة

- Subtitle brightness does not reach ASS subtitles drawn by libass (that renderer lives in a prebuilt
  library). Turning libass off applies it there too.
- If Android closes the app process completely, the episode in progress finishes, and the season queue
  continues the next time the app is opened.
- Changing the download location applies to new downloads; earlier files are not moved.

## Build / البناء

Every push to `custom-subtitles` builds the APKs and runs every check (unit tests, update check,
emulator). A release is published only for a pull request the owner merged, after the owner approves
the signing with the release key and, once the signed APKs passed the emulator move tests, the
publishing. A failed build publishes its error log as a `build-failed-N` pre-release (no APK).

**Signing.** Releases since build 13 are signed with a release key that only the protected
`plus-release` environment holds (certificate SHA-256
`99c0a98cc1700bd1186018e480eab670daaf55d4d8c5316d7cf03d788d431e35`), with APK Signature Scheme v3
and a lineage from the old key. The key in `signing/` is the **old, public** key of builds 8–12: it
signs only test builds in CI and that lineage, never a release. Install Nuvio Plus only from this
repository's releases or the app's updater. Details: [docs/plus/signing-migration.md](docs/plus/signing-migration.md).

Optional repository secret:

| Secret | Purpose |
|---|---|
| `CUSTOM_LOCAL_PROPERTIES` | Extra `local.properties` lines (Trakt, Simkl, Supabase keys and so on); every value ends up inside the APK |

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
