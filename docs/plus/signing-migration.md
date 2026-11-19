# Moving Nuvio Plus to a new signing key

Status: **approved by the owner on 2026-11-19** (new key, Android 9 or later). The release key was
made on 2026-11-19 (run 44534502910): certificate
`99c0a98cc1700bd1186018e480eab670daaf55d4d8c5316d7cf03d788d431e35`, pinned in `release-identity`
and in the app's updater.

## Facts

- Package `com.nuvio.app.plus`. Builds 8–12 are signed (v2 only) with certificate
  `40335e3135578dd858dc434717b9344015b62872dcb861d52474c38a1f99f48a`, whose private key and password
  are in this public repository (`signing/nuvio-plus.p12`). That key is **compromised** for good;
  deleting the file would not change that.
- No APK signed with that key is ever published again (`release-identity`). The checks still sign
  test builds with it, so updates over build 12 can be tested on pull requests.

## The move (tested on emulators: run 44521306430 of `plus-study/key-rotation`)

Release APKs are signed with **APK Signature Scheme v3 only**, by the new key, and carry a v3
**lineage**: the old key's signature handing over to the new key (old key: rollback off). The old key
signs that lineage once and no APK. `apksigner` refuses a v2 signature by the new key next to a
lineage, and Android 8 and older verify only v1/v2, so release builds need **Android 9 or later**
(`-Pnuvio.minSdk=28`).

| Case (throwaway keys) | Android 8 (API 26) | Android 9 (28) | Android 13 (33) | Android 14 (34) |
|---|---|---|---|---|
| New-key APK with lineage over the old-key install | Refused (`INSTALL_PARSE_FAILED_NO_CERTIFICATES`) | **Update, data kept** | **Update, data kept** | **Update, data kept** |
| Then an APK signed with the old key alone | — | Refused | Refused | Refused |
| Then an attacker's lineage old key → their key | — | Refused | Refused | Refused |
| Then the next build with the new key | — | Update, data kept | Update, data kept | Update, data kept |
| An attacker's lineage over a phone that never moved | — | Accepted | Accepted | Accepted |

So: Android 9+ keeps its data and, once moved, trusts only the new key. Android 7–8 users keep build
12 (no further updates). A phone that has not moved yet still trusts the old key; moving soon is the
protection.

**The first move is manual.** Build 12's updater compares the whole certificate history and refuses a
rotated build (`UpdateArchiveCheck.signerDigests` at `28a3e4dd`). Users download the build once from
the release page and install it over the old app; Android keeps the data. Release notes say so.

## Where the key lives

- GitHub Environment **`plus-release`**: required reviewer = the owner; deployment branches
  `custom-subtitles`, `plus-beta`, `plus-release/key-ceremony`. Secrets:
  - `RELEASE_KEY_PASSWORD`: chosen by the owner (24+ characters), never seen by anyone else;
  - `RELEASE_KEYSTORE`: the key, PKCS12 encrypted with that password (base64).
- **Making the key** (`plus-release-key.yml`, once): a push to `plus-release/key-ceremony` starts a job
  in `plus-release` that waits for the owner's approval. It makes an EC P-256 key encrypted with the
  owner's password, the lineage (old → new) and the certificate, prints only the certificate, and
  keeps them one day as the artifact `nuvio-plus-release-key`. It refuses if a key already exists.
  The owner keeps that artifact and the password as the backup (a lost key ends updates for good)
  and pastes the key text into `RELEASE_KEYSTORE`.
- **Signing a release** (`custom-android-build.yml`, job `release-sign`): only after every check and
  the review gate, only on the publishing branches, only after the owner approves the deployment.
  No file of the commit runs in it (the checkout holds only the old public key); it makes the lineage
  (old key → release key, rollback off) and checks that it names exactly the pinned certificates,
  takes the unsigned APKs whose digests the checks recorded, signs them v3-only with that lineage,
  deletes the key files and keeps the signed APKs. The lineage is the same every time; the old key
  signs only it.
- **Before publishing:** `release-verify` (v3 only, the pinned certificate, the lineage, contents
  identical to the tested APKs) and `release-upgrade` (emulators: build 12 with data → release on
  Android 9 and 14 keeps the data and then refuses an old-key APK; Android 8 refuses the release and
  keeps build 12). `publish` uses only those APKs.
- The certificate is pinned in `release-identity` (`RELEASE_IDENTITY`) and in the app's updater
  (`UpdateArchiveCheck.trustedRotationTargets`), by a reviewed pull request after the key exists.

## Order

1. Merge the signing system (this change). Nothing can be published yet.
2. Owner: create the environment `plus-release`.
3. Owner: add `RELEASE_KEY_PASSWORD`.
4. Push `plus-release/key-ceremony`; owner approves the run.
5. Owner: keep the artifact and the password; paste the key text into `RELEASE_KEYSTORE`.
6. Pull request: pin the certificate (workflow and app). Merge.
7. The publish run waits for the owner's approval of `release-sign`, then verifies and tests the move
   on emulators; only if all of it passes does `publish` ask the owner a second time, and only then is
   Build 13 published.
