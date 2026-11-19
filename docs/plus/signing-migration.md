# Moving Nuvio Plus to a new signing key

Status: **proposed, waiting for the owner's approval.** Nothing here has created a key, rotated
anything or published anything.

## Facts

- Package: `com.nuvio.app.plus`. Android 7.0 and later (minSdk 24).
- Builds 8–12 are signed (v2 only) with certificate
  `40335e3135578dd858dc434717b9344015b62872dcb861d52474c38a1f99f48a`. Its private key and password
  are in this public repository (`signing/nuvio-plus.p12`): the key is **compromised**. Anyone can
  sign an APK that Android installs as an update of those builds, with access to the app's data
  (account session, debrid and Trakt tokens).
- Removing the file does not help: the key is public for good.
- Since this change, a build signed with that key is never published (`release-identity`).

## How Android treats a key change (evidence: `key-rotation-study.md`, run 44451382158)

| | Android 7–8 (API 24–27) | Android 9+ (API 28+) |
|---|---|---|
| Knows APK Signature Scheme v3 rotation | No: checks the v2 signature only | Yes |
| Update signed with a new key **and** a lineage (old → new) | Only if the v2 block is signed with the **old** key | Accepted, data kept |
| After that, an APK signed with the old key alone (strict lineage) | Accepted | **Refused** |
| New key without a lineage | Refused (uninstall needed) | Refused |

v3.1 (Android 13+) only lets a rotation target Android 13+ while older versions keep the old key; it
does not help here.

## Options

| | A. Rotation, old key still signs v2 (study's B) | **B. Rotation, new key only (recommended)** | C. New application id |
|---|---|---|---|
| What the public APK carries | v2 signed by the **compromised** key, v3 by the new key + lineage | v2 and v3 signed by the **new** key; v3 has the lineage | New key only |
| Use of the old key | Signs every release (for Android 7–8) | Signs the lineage once (a certificate, not an APK) | None |
| Android 9+ users of build 12 | In place, data kept, then protected | In place, data kept, then protected | Second app; data does not move (build 12 has no export) |
| Android 7–8 users of build 12 | In place, data kept, **never protected** | Cannot update in place: reinstall, data not synced to the account is lost | Second app; old one stays exposed |
| Old build left on phones | Protected on 9+ after update | Protected on 9+ after update | Stays exposed until uninstalled |

**Why B:** it never ships an APK signed with the compromised key (the rule for this release), it keeps
data on Android 9+, and on Android 7–8 nothing can keep data without trusting the compromised key.
C loses everyone's data and leaves the exposed app installed.

**What B cannot do:**
- Android 7–8: reinstall needed. What the Nuvio account syncs returns after signing in; downloads and
  local-only settings do not.
- A phone that never installs the move keeps trusting the old key.
- The lineage is signed with the old key, so its holder can also make a lineage to their own key.
  The app's own updater pins the new certificate (`UpdateArchiveCheck.trustedRotationTargets`), so it
  refuses such an APK; Android's installer would accept it if a user installed it by hand **before**
  installing the real move. After the real move, Android 9+ refuses it.

## The first update from build 12 is manual

Build 12's updater compares the full certificate history of the downloaded APK with the installed
one (`UpdateArchiveCheck.signerDigests` at `28a3e4dd`): `{old, new}` differs from `{old}`, so it
refuses the rotated build ("other signer"). Users install build 13 once from the release page;
Android 9+ keeps their data. Build 13's updater accepts later releases signed with the pinned new key.
(Derived from the code; the emulator test below must confirm it.)

## Keeping the new key away from untrusted code

- **Storage:** a GitHub Environment `plus-release`:
  - deployment branches: `custom-subtitles`, `plus-beta`;
  - required reviewer: the owner (each publish waits for the owner's approval in GitHub);
  - secrets: `RELEASE_KEYSTORE` (PKCS12, password-encrypted, base64) and `RELEASE_KEYSTORE_PASSWORD`.
- **Signing job** (publishing workflow only, after every check and the review gate):
  - names the environment, so the secrets exist only there and only after approval;
  - runs no file of the commit (no checkout): it takes the unsigned APKs whose digests the checks
    recorded, checks that their contents equal the tested APKs, signs inline with `apksigner`
    (v2 + v3, lineage file from the repository), deletes the key file, and verifies the result
    against the pinned certificate;
  - never prints the key; the keystore exists only in the job's temporary folder.
- Pull request builds keep signing test builds with no secret.
- **Lineage file** (old → new, strict: the old key loses rollback): public data, committed once.
- **Backup:** the encrypted keystore and its password in two places the owner controls. A lost key
  ends updates for good.

## The owner's part (once)

1. Approve option B.
2. Create the environment `plus-release` (or let the session create it).
3. Put a long random password in its secret `RELEASE_KEYSTORE_PASSWORD` (a generator page is
   provided; keep the password in a password manager).
4. Approve the one-time key run: it makes the key in GitHub's runner, encrypted with that password,
   plus the lineage and the certificate. Download its artifact as the backup and paste its text into
   `RELEASE_KEYSTORE`.

Everything else (the key run, the signing job, the pins, tests, pull requests) is prepared in code.

## Tests before any release

On emulators (Android 7.0, 8.0, 9, 11, 13, 14, 15):
- Build 12 installed with data, then the build 13 APK (new key + lineage): 9+ data kept; 7–8 refused.
- Then an APK signed with the old key alone, and one with an attacker's lineage: refused on 9+.
- Then the next build signed with the new key: accepted.
- Build 13's updater: accepts the pinned new key, refuses the old key and unknown keys (unit tests).
