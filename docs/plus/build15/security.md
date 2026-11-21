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
| Backups | `android:allowBackup="false"` | Not copied to cloud or adb backup |

Downloaded videos in a user-chosen folder are the user's own files. Logs were not audited line by
line (not tested).

## Dependencies (P6-06)

Every build checks the release APK's resolved libraries against osv.dev and stops on a HIGH,
CRITICAL or unknown-severity vulnerability. Stable build of 346fb82d (run 44894545343, job
136817677447): 353 libraries, 0 blocking, 0 reviewed, 0 low or moderate. Not covered: native
libraries (libmpv, FFmpeg), which come from Nuvio's prebuilt packages.

## Attack surface (P6-03)

Exported components, permissions and manifest flags of the release APK are compared with the
reviewed list (`security-surface.json`) in every build: equal to Build 14.

## OWASP MASVS (P6-11), coverage stated honestly

| MASVS area | Covered here | Not covered |
|---|---|---|
| STORAGE | Backup off; secrets in app storage; nothing sensitive on shared storage | Device compromise (root) |
| CRYPTO | Keystore use reviewed where Nuvio uses it | No cryptographic audit |
| NETWORK | Certificate rules, redirects, tokens (above) | Certificate pinning (not used; sources are arbitrary servers) |
| PLATFORM | Exported components and permissions checked each build | — |
| CODE | Lint and detekt compared with Build 14 each PR; OSV on libraries | Native code |
| RESILIENCE | Not applicable to a personal build | — |
| PRIVACY | No new data collection in Build 15 | Nuvio's own telemetry settings unchanged |
