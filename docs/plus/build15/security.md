# Build 15 — security report (P6)

Scope: what a personal-use media app can leak or lose. Each line names its evidence; anything not
tested says so.

## Network and credentials (P6-02, B15-SEC-04)

| Point | State | Evidence |
|---|---|---|
| Token, cookie or key header sent only to a server with a valid certificate | Fixed in Build 15 (#44) | `PlaybackTlsTest`, including real badssl.com servers |
| Token in the link (`?token=`, `?api_key=`, `user:pass@`) | Fixed in Build 15 (#50) | `PlaybackRedirectCredentialsTest`, `PlaybackTlsTest` |
| Credentials after a redirect to another site | Fixed in Build 15 (#50): only the same site, never https→http | `PlaybackRedirectCredentialsTest` (two local test servers, dummy tokens) |
| libmpv | Verifies every certificate (`tls-verify=yes`, bundled CA file) | `PlayerEngine.android.kt` |
| Streams without credentials | Unchanged: any certificate accepted (sources with broken certificates keep playing) | B15-SEC-04 decision |
| Local network (NAS, home server) | Self-signed certificates accepted, also with credentials | `isLocalNetworkHost` tests |
| Cleartext (http) streams | Allowed, as in Build 14 (B15-SEC-02, KEEP) | security-surface check |
| Not covered | Credentials inside the URL path (Xtream `/live/user/pass/…`); HLS segments on other hosts receive the headers the source gave for the stream | — |

## Stored data (P6-05)

| Data | Where | Protection |
|---|---|---|
| Trakt / Simkl / MDBList sign-in, server passwords | App-private storage; several use Android Keystore-backed encryption (`MdbListAuthPersistence`, `ServerStorage`, `SimklPlatform`) | Android app sandbox |
| API keys typed in settings (MDBList, IntroDB) | App-private SharedPreferences, plain | Android app sandbox; Nuvio's design, unchanged |
| Download state with stream headers | App-private storage, so a resumed download keeps its source | Android app sandbox |
| Backups | `android:allowBackup="false"` | Cloud backup is disabled. Device-to-device transfer on some manufacturers' devices can still copy app-private preferences (W5-STO-02). |

Downloaded videos in a user-chosen folder are the user's own files. The [logs and storage audit](security-logs-storage.md) scanned 435 Android and common log calls in 87 files at its audited revision and traced 40 flagged calls. The runtime contents of device logs and vendor device-to-device transfers remain untested.

## Dependencies (P6-06)

Every build checks the release APK's resolved libraries against osv.dev and stops on a HIGH,
CRITICAL or unknown-severity vulnerability. Stable build of 346fb82d (run 44894545343, job
136817677447): 353 libraries, 0 blocking, 0 reviewed, 0 low or moderate. Not covered: native
libraries (libmpv, FFmpeg), which come from Nuvio's prebuilt packages.

## Attack surface (P6-03)

Exported components, permissions and manifest flags of the release APK are compared with the
reviewed list (`security-surface.json`) in every build: equal to Build 14.

## Deep links and add-on confirmation (P6-02)

| Point | State | Evidence |
|---|---|---|
| Exported entry points | Launcher activity and the `nuvio://` deep links (auth, add-on install, title details); equal to Build 14 | security-surface check (P6-03) |
| Add-on install from a link | A link only opens a confirmation; nothing is installed until the user confirms, and then once | `AddonLinkConfirmationTest.aLinkAloneInstallsNothing`, `confirmingInstallsTheLinkedAddonOnce` |
| What the user is shown | The add-on's host, not its path or token | `AddonLinkConfirmationTest.theUserIsShownTheHostNotThePathOrToken` |
| Links that look like an install but are not | The reserved auth link and a `stremio://` link without a host are not treated as installs | `AppUrlBridgeTest` (`does not treat reserved auth link…`, `does not treat non-host stremio link…`) |
| Not covered | A malicious add-on that the user confirms is trusted like any add-on (Nuvio's model) | — |

## Content and files from external sources (P6-04)

| Source | Protection | Evidence |
|---|---|---|
| Subtitle file picked on the device | Read through the system picker only; at most 10 MB (`MaxLocalSubtitleBytes`); empty or non-subtitle files refused; stored as UTF-8 in the app cache under a sanitised, unique name (no path from the file name is used) | `LocalSubtitleFormatTest`, `DeviceSubtitleCacheTest`, `SubtitleCharsetDetectorTest` |
| Malformed or hostile subtitle text | Parsed in linear time; an unclosed tag does not swallow the text after it | `SubtitleParserWorstCaseTest` (also a PortKit feature, verified on both targets) |
| Font picked on the device | Only `.ttf`/`.otf`, size limit, table directory checked before Android loads it | `SubtitleAppearanceTest.fontImportRejectsTruncatedTablesAndUnsupportedContainers`, `SubtitleFontImportTest` |
| Downloaded update APK | Installed only if it is this package, newer, signed with the installed or pinned key, and readable; an unreadable installed app refuses instead of skipping the check | `UpdateArchiveCheckTest` (16 cases) |
| Streams and their credentials | See "Network and credentials" | `PlaybackTlsTest`, `PlaybackRedirectCredentialsTest` |
| Not covered | Media containers and codecs are parsed by ExoPlayer and libmpv/FFmpeg (native code, not audited here); add-on responses beyond Nuvio's own tests (P3-05) | — |

## GitHub Actions permissions (P6-07)

Checked on every pull request by `test_workflow_policy.py` (job "Tests of the review gate and release scripts"):

| Rule | Test |
|---|---|
| Every workflow's default token is read-only | `test_the_workflow_default_token_is_read_only` |
| Only listed jobs may write, and only what they need | `test_only_listed_jobs_may_write`, `test_callers_grant_what_the_called_jobs_ask` |
| Jobs that run Gradle or other code of the commit cannot write or sign | `test_jobs_that_run_project_code_cannot_write_or_sign` |
| Secrets are never inherited; only the release workflow passes one | `test_secrets_are_never_inherited`, `test_only_the_publishing_workflow_passes_secrets` |
| External actions pinned by commit; runners pinned; no `pull_request_target`; no expressions inside scripts | `test_external_actions_are_pinned_by_commit`, `test_runners_are_pinned`, `test_pull_request_target_is_not_used`, `test_no_expressions_inside_scripts` |
| Checkouts keep no credentials unless the job pushes | `test_checkouts_keep_no_credentials_unless_the_job_pushes` |

## Test code and signing secrets (P6-08)

| Rule | Test |
|---|---|
| No signing secret reaches the build workflow or the scripts it runs; the checks get only the public app settings | `test_no_signing_secret_reaches_files_of_the_commit`, `test_only_public_app_settings_pass` |
| The test key's presence is the only thing read about the old keystore secret | `test_the_signing_key_secret_is_only_tested_for_presence` |
| Only the release-signing job reads the release key, in the protected environment, in one step that runs no file of the commit and prints nothing of the key | `test_only_release_signing_reads_the_release_key` |
| The release identity check runs no code of the commit | `test_the_release_identity_check_runs_no_code_of_the_commit` |
| The checks themselves must have used the public test key | `test_the_checks_must_have_used_the_test_key` |

## APK integrity and signing keys (P6-09)

| Rule | Evidence |
|---|---|
| Release certificate pinned (SHA-256 `99c0a98c…431e35`); the public test key never publishes | `test_the_release_key_is_pinned`, `test_the_public_key_never_publishes` |
| Release signs only the APKs the checks built, v3 only with the key lineage (rollback flag not set), minimum Android 9 | `test_release_signing_needs_the_review_gate_and_signs_v3_only` |
| After signing: the certificate, the lineage and the APK contents are verified against the checked build; v2-only, another key or changed contents are refused | `release-verify.py`, `test_release_verify.py` (8 cases), `apk-certs.py`, `test_apk_certs.py` |
| Publishing waits for the owner after every test; a moved branch is neither signed nor published | `test_publishing_waits_for_the_owner_after_every_test`, `test_a_branch_that_moved_is_neither_signed_nor_published` |
| The app refuses an update with another key, another package, an older version or a damaged file | `UpdateArchiveCheckTest` |
| Upgrade from Builds 12, 13 and 14 with the real key | release run of the candidate (P6-14..16), not yet run for Build 16 |

## OWASP MASVS (P6-11), coverage stated honestly

| MASVS area | Covered here | Not covered |
|---|---|---|
| STORAGE | Cloud backup disabled; storage and log review in the [logs and storage audit](security-logs-storage.md) | Runtime logs, vendor device-to-device transfer and device compromise (root) |
| CRYPTO | Keystore use reviewed where Nuvio uses it | No cryptographic audit |
| NETWORK | Certificate rules, redirects, tokens (above) | Certificate pinning (not used; sources are arbitrary servers) |
| PLATFORM | Exported components and permissions checked each build | — |
| CODE | Lint and detekt compared with Build 14 each PR; OSV on libraries | Native code |
| RESILIENCE | Not applicable to a personal build | — |
| PRIVACY | No new data collection in Build 15 | Nuvio's own telemetry settings unchanged |
