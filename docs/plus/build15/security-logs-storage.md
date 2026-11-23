# Build 15 — logs and sensitive storage audit (P6-05, P8-08, P6-11)

Scope: every logging call in the Android and common app code, where sign-ins, tokens and server
passwords are stored, and what backup or device transfer can copy. Base: `custom-subtitles` at
69023814a. Everything here is a static review of the sources; nothing was run on a device (see
"Not tested").

## Method

1. **Find every log call.** `.github/plus/tests/test_no_secret_logging.py` lexes the Kotlin sources
   of `composeApp/src/{commonMain,fullCommonMain,androidMain,androidFull}` and `androidApp/src/main`
   (strings, string templates, raw strings, comments) and reads every call to `android.util.Log`,
   Kermit loggers (`log`, `Logger`, `*Log`), `println`/`print`, `System.out/err` and
   `printStackTrace`, and the app's own log helpers (`downloadLog`, `logPhase`, `logSnapshot`, ...),
   with the whole argument list and trailing message lambda.
   Result on the base: **435 log calls in 87 files**, every one parsed (the test fails on a call it
   cannot parse).
2. **Flag the calls that can carry a secret.** A call is flagged when its code (arguments,
   templates, concatenations) names a credential-like identifier (token, password, secret, cookie,
   authorization, api key, bearer, credential, jwt, session id, private key), when literal text
   right before a template reads like a label (`token=$x`), when a literal holds `scheme://user:pass@`,
   or when it names a URL-like value (url, uri, href, link, endpoint, header, query), because a URL
   can carry userinfo or a token in its query or path. **40 calls in 19 files** were flagged.
3. **Read each flagged call and what feeds it** (the variable's origin, the redaction helper, the
   exception type and its message) and give a verdict: safe or leak. The verdicts are the
   `REVIEWED` list of the test, with the reason for each.
4. **Origin.** `git blame` of each call's first line: upstream authors = Nuvio's code; Plus
   authors (Mohammed4421, Claude) = Plus code. The table also says whether the file is listed in
   `.github/plus/features.json` (a file Plus changes), because a Plus-changed file can still hold
   Nuvio lines.
5. **Release builds.** Nothing removes log calls from release APKs: `composeApp/proguard-rules.pro`
   has no `-assumenosideeffects` rule for `android.util.Log`, and Kermit is used with its default
   configuration (no `setMinSeverity`, no custom writer anywhere in the sources), so debug and info
   lines reach logcat in the published APK too.

Who can read logcat: since Android 4.1 another app cannot read this app's log lines. They can be
read over adb (USB debugging on), in a bug report (`adb bugreport`, or one the user shares), on a
rooted device, and by some vendor log collectors. That is why the leaks below are Medium at most.

## Plus code

Plus code has **6 log calls**, all in the download engine; none takes a URL, header, token or
file path. The engine logs by design without URLs (`DOWNLOAD_LOG_TAG` comment).

| File:line | Logs | Verdict |
|---|---|---|
| `androidMain/.../downloads/AndroidDownloadTransfer.kt:209` | `downloadLog(message)` → `Log.i("NuvioDownload", message)` | safe: writes only what the callers below pass |
| `androidMain/.../downloads/AndroidDownloadTransfer.kt:111` | byte offset and partial-file length | safe |
| `androidMain/.../downloads/DocumentDownloads.kt:110` | exception class name only | safe |
| `androidMain/.../downloads/DownloadsTransferJobService.kt:29` | job id, first 8 chars of the transfer generation id | safe |
| `androidMain/.../downloads/DownloadsTransferJobService.kt:40` | job id, generation prefix, whether a run is active | safe |
| `androidMain/.../downloads/DownloadsTransferJobService.kt:123` | job id, stop reason, generation prefix | safe |

The playback credential code Plus added (`PlayerPlaybackNetworking.kt`, feature
`playback-credential-tls`) has no log call. **No leak in Plus code, so no Plus code was changed.**

## Flagged log sites (all Nuvio's code)

Paths are under `composeApp/src/`. "In features.json" = the file is one Plus changes; the line
itself is Nuvio's in every row (git blame).

| File:line | What it logs | In features.json | Verdict |
|---|---|---|---|
| `commonMain/.../addons/AddonRepository.kt:72` | count of stored addon URLs | no | safe |
| `commonMain/.../addons/AddonRepository.kt:156` | count of addons | no | safe |
| `commonMain/.../addons/AddonRepository.kt:175` | `addAddon() — rawUrl=$rawUrl`, the URL the user typed | no | **leak W5-LOG-01** |
| `commonMain/.../addons/AddonRepository.kt:215` | `removeAddon() — $manifestUrl` | no | **leak W5-LOG-01** |
| `commonMain/.../details/MetaDetailsRepository.kt:292` | `Fetching meta from: $url` (addon resource URL) | no | **leak W5-LOG-01** |
| `commonMain/.../details/MetaDetailsRepository.kt:323` | meta URL and `manifest.transportUrl` on failure | no | **leak W5-LOG-01** |
| `commonMain/.../streams/StreamSearchSessions.kt:243` | `Fetching streams from: $url` for every addon | no | **leak W5-LOG-01** |
| `commonMain/.../search/SearchRepository.kt:533` | Discover request, `url=$requestUrl` (catalog URL from the manifest URL) | no | **leak W5-LOG-01** |
| `commonMain/.../search/SearchRepository.kt:617` | same on failure | no | **leak W5-LOG-01** |
| `fullCommonMain/.../plugins/runtime/network/FetchBridge.kt:36` | `Fetch bridge error for $method $url`, any URL a plugin fetches | no | **leak W5-LOG-01** |
| `commonMain/.../collection/TmdbCollectionSourceResolver.kt:368` | TMDB endpoint path + throwable | no | safe: `api_key` is added by `buildTmdbUrl` to a separate `url` that is not logged; errors are `network_request_failed_http` (code only) or OkHttp IOExceptions (no query) |
| `commonMain/.../tmdb/TmdbMetadataService.kt:1202` | TMDB endpoint path + `error.message` | no | safe (same) |
| `commonMain/.../tmdb/TmdbService.kt:109` | TMDB endpoint path + `error.message` | no | safe (same) |
| `commonMain/.../library/LibraryRepository.kt:306, 332, 337, 351, 363, 377` | item id/type and `snapshot.token.profileId` | no | safe: `token` is `LibraryProfileToken(profileId: Int, generation: Long)` |
| `commonMain/.../library/LibraryRepository.kt:565` | `LibraryProfileToken` and revisions | no | safe (same) |
| `commonMain/.../trakt/TraktAuthRepository.kt:369` | `Failed to revoke Trakt token: ${error.message}` | no | safe: the token is in the POST body, not logged; the message is an HTTP code or IOException |
| `commonMain/.../trakt/TraktCommentsRepository.kt:85` | public `api.trakt.tv/.../comments` URL | no | safe: headers are not logged |
| `commonMain/.../trakt/TraktPublicListSourceResolver.kt:154` | Trakt API path | no | safe: client id is in a header, not logged |
| `commonMain/.../trakt/TraktScrobbleRepository.kt:241` | scrobble request: URL, headers through `redactedForLogs()`, body | no | **leak W5-LOG-04** (partial bearer token) |
| `commonMain/.../trakt/TraktScrobbleRepository.kt:283` | scrobble response: status, URL, all response headers, body | no | **leak W5-LOG-04** (headers unredacted) |
| `fullCommonMain/.../trailer/InAppYouTubeExtractor.kt:160` | public YouTube trailer URL | no | safe |
| `androidMain/.../p2p/P2pStreamingEngine.android.kt:250` | `diagnosticLoopbackUrl(stream.url)` = scheme, host, port | no | safe |
| `androidMain/.../p2p/P2pStreamingEngine.android.kt:293` | URL of the on-device torrent engine's local server | no | safe: loopback, no remote credential |
| `androidMain/.../player/HlsParameterSetInitDataSource.kt:50` | full HLS playlist URL | no | **leak W5-LOG-02** |
| `androidMain/.../player/HlsParameterSetInitDataSource.kt:96` | full HLS segment URL + exception | no | **leak W5-LOG-02** |
| `androidMain/.../player/PlayerEngine.android.kt:621` | `diagnosticPlaybackSource(sourceUrl)` = scheme, host, port; audio URL as a boolean | yes | safe |
| `androidMain/.../player/PlayerEngine.android.kt:1622` | same for libmpv | yes | safe |
| `androidMain/.../player/PlayerEngine.android.kt:1106, 1109, 1144` | subtitle URL through `redactedForLogs()` | yes | **leak W5-LOG-03** (userinfo and path kept) |
| `androidMain/.../player/SubtitleFileCache.kt:90, 107` | subtitle URL through `redactedForLogs()` | no | **leak W5-LOG-03** |
| `androidMain/.../player/PlayerSidecarSubtitles.kt:146, 162, 175` | subtitle URL unredacted | no | **leak W5-LOG-02** |

Unflagged calls that were also read because they forward data built elsewhere:
`simklSyncLog.d { simklSyncResponseLogMessage(...) }` (`SimklApiClient.kt:218`) logs method, path,
query **keys** only, status and body length: safe. `MpvErrorLog` keeps libmpv lines for the error
screen with every `http(s)://` URL removed and does not write to logcat: safe.

## Findings

### W5-LOG-01 — Addon and plugin request URLs written to logcat (Medium, Nuvio)

Stremio-style addons keep the user's configuration in the URL path, and for debrid addons that
configuration includes the user's debrid API key (for example `https://<addon>/<provider>=<API key>/manifest.json`).
Nuvio logs the manifest URL when an addon is added or removed, and the resource URL of every meta,
stream and catalog request (info and debug level), and the plugin fetch bridge logs any failed
URL with its query. In a release APK these lines go to logcat (Method step 5), so anyone with
adb access or a shared bug report gets the key. Fix (upstream): log the host only, or the URL
through a helper that drops userinfo, path segments after the host and the query. Not changed
here: Nuvio's code.

### W5-LOG-02 — Stream and subtitle URLs logged unredacted (Low, Nuvio)

`HlsParameterSetInitDataSource` logs the full playlist and segment URL, and
`PlayerSidecarSubtitles` the full subtitle URL. Signed CDN links and debrid download links carry
their token in the query, and some sources carry `user:pass@`. The links usually expire, which
limits the harm. Fix (upstream): use `diagnosticPlaybackSource` or `redactedForLogs`.

### W5-LOG-03 — `redactedForLogs()` keeps userinfo and the path (Low, Nuvio)

`PlaybackUrlCredentials.kt:71` drops the query and fragment only. A subtitle URL with
`user:pass@host`, or a path-embedded key, is still written by `PlayerEngine.android.kt` (1106,
1109, 1144) and `SubtitleFileCache.kt` (90, 107). Fix (upstream): remove userinfo too.

### W5-LOG-04 — Trakt scrobble debug logs a partial token and raw response headers (Low, Nuvio)

`TraktScrobbleRepository` logs `Authorization: Bearer <first 6>...<last 4>` of the access token
(10 of 64 characters; not enough to use it) and every response header unredacted, which would
include any `set-cookie`. Debug level, still present in release.

### W5-STO-01 — Some credentials are stored in plain SharedPreferences (Low, Nuvio)

See the storage table: the Trakt access and refresh tokens, debrid API keys, the TMDB API key,
installed addon URLs (with their embedded keys) and download records with their request headers
are plain text in app-private SharedPreferences. Simkl, MDBList and media-server credentials are
encrypted with an AndroidKeyStore AES-GCM key. App-private storage is protected by the Android
sandbox on a non-rooted device (MASVS-STORAGE-1 is met on that baseline); the plain values are
exposed on a rooted device and to W5-STO-02.

### W5-STO-02 — No data extraction rules; device-to-device transfer can copy app data (Low, Nuvio)

`androidApp/src/main/AndroidManifest.xml` sets `android:allowBackup="false"` and no
`android:dataExtractionRules`; the app targets SDK 36. Android documents that for apps targeting
Android 12 or later, on some manufacturers' devices `allowBackup="false"` disables cloud backup
but **not device-to-device transfer**, and that a missing `<device-transfer>` section means that
mode transfers everything except `no-backup` and cache directories
(developer.android.com/identity/data/autobackup). A D2D move to a new phone could therefore copy
the plain SharedPreferences of W5-STO-01. The Keystore-encrypted values would arrive as
ciphertext without their key (Keystore keys do not transfer). Fix (upstream): add
`dataExtractionRules` excluding the credential preference files from `<device-transfer>` (and
`<cloud-backup>`). Not changed here: manifest application flags are Nuvio's and the change alters
what a phone migration keeps.

### W5-LOG-05 — Sentry breadcrumbs keep the URL path and userinfo (Low, Nuvio, only with a DSN)

`SentryNetworkBreadcrumbInterceptor` (on the addon HTTP client) sends each request URL to Sentry
with the query and fragment removed but path and userinfo kept, plus a `path` field. With the
configured addon URLs this sends W5-LOG-01's key off the device. It applies only when the build
has a `SENTRY_DSN` (an optional value of the Plus build secrets, not visible here) and the user
leaves crash reporting on (default on). Logcat lines are not sent: the Sentry Gradle plugin's
logcat instrumentation is off (`tracingInstrumentation.enabled = false`).

## Storage of sensitive data (Android)

All files are app-private (`Context.MODE_PRIVATE`, in `/data/data/<package>/shared_prefs/`).

| Data | Store (file / key) | Protection | Origin |
|---|---|---|---|
| Trakt access + refresh token, username | `nuvio_trakt_auth` / `trakt_auth_payload_<profile>` (JSON) | plain | Nuvio |
| Simkl access token, PKCE verifier | `nuvio_simkl_auth` | AES-GCM, AndroidKeyStore key `nuvio.simkl.credentials.v1` | Nuvio |
| MDBList credentials | `nuvio_mdblist_auth` | AES-GCM, AndroidKeyStore | Nuvio |
| Media server URLs, users, passwords | `nuvio_servers` | AES-GCM, AndroidKeyStore key `com.nuvio.media.servers.credentials.v1` | Nuvio |
| Debrid API keys (Real-Debrid, TorBox, others) | `nuvio_debrid_settings` / `debrid_*_api_key` | plain | Nuvio |
| TMDB API key (user's own) | `TmdbSettingsStorage` / `tmdb_api_key` | plain | Nuvio |
| Installed addon URLs (may embed keys) | `nuvio_addons` / `installed_manifest_urls_<profile>` | plain | Nuvio |
| Download records: source URL, request and response headers | `nuvio_downloads` | plain | Nuvio (Plus changes the file's other fields, not these) |
| Profile PIN | `nuvio_profile_pin_cache` | salted hash, not the PIN | Nuvio |
| Nuvio account session (Supabase) | supabase-kt `Auth` default session manager | library default, not reviewed | Nuvio |
| Plus: download folder, season queue, update channel | `nuvio_downloads`, season queue storage, updater preferences | plain, no credential | Plus |

Plus adds no credential store.

## Backup and extraction rules

| Point | State |
|---|---|
| `android:allowBackup` | `false` (androidApp manifest; also checked on the release APK by `.github/plus/security-surface.py`) |
| `android:dataExtractionRules` / `fullBackupContent` | absent: see W5-STO-02 |
| FileProvider paths | cache `updates/`, cache `subtitles/`, files `downloads/` (no preference files) |
| Release minify | on by default; no log stripping (Method step 5) |

## MASTG tests executed

| Test | How | Result |
|---|---|---|
| MASTG-TEST-0231 References to Logging APIs (MASVS-STORAGE-2) | Static: every logging API reference listed and read (Method) | **Plus code: pass** (6 calls, none sensitive). **Nuvio code: fail** (W5-LOG-01 to 04) |
| MASTG-TEST-0262 References to Backup Configurations Not Excluding Sensitive Data (draft) | Static: manifest flags and xml rules read | **Fail (Low)**: no data extraction rules, W5-STO-02 |
| MASTG-TEST-0001 Testing Local Storage for Sensitive Data (v1) | Static: every SharedPreferences store holding a credential read | Plain credentials in app-private storage, W5-STO-01 |
| MASTG-TEST-0203 Runtime Use of Logging APIs | — | **NOT TESTED**: needs the app running on a device with real sign-ins and addons while logcat is captured; no device run with credentials was part of this work |
| MASTG-TEST-0009 Testing Backups for Sensitive Data (v1, runtime) | — | **NOT TESTED**: a D2D transfer needs two devices |

## Guard against regressions

`.github/plus/tests/test_no_secret_logging.py` (runs in "Nuvio Plus checks" with the other
`.github/plus/tests`):

- `Repository.test_no_unreviewed_log_call_touches_a_credential_or_url` fails on any flagged log
  call that is not in `REVIEWED` with the same call text, so a new or changed call needs a review.
- `Repository.test_known_leaks_are_nuvio_findings_in_the_report` allows a leaking call only as
  Nuvio code with a finding described here; Plus code can only be `safe`.
- `Repository.test_every_reviewed_entry_still_matches_a_call` removes stale allow-list entries.
- `Scanner.*` feed synthetic snippets (a logged token, a Kermit lambda with a password, a cookie
  header concatenation, `token=$x`, `https://user:pass@`, multi-line calls, comments) and
  `Scanner.test_a_synthetic_leak_in_the_repository_scan_fails` adds a leaking file to the real
  repository scan and expects exactly that file to fail.
- Checked by hand on the base: changing the Plus call `AndroidDownloadTransfer.kt:111` to also log
  `${request.url}` makes the repository test fail with that line (change reverted).

Limits: the scan reads the call site; a helper that builds a message from an object (like
`simklSyncResponseLogMessage`) is flagged only through its own log call, so such helpers were read
by hand. Logging inside libraries (Media3, libmpv, supabase-kt, Ktor, OkHttp) is not in the
sources and was not audited; the app installs no HTTP logging interceptor or Ktor `Logging` plugin.
