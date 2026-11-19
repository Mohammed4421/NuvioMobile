# Signing key study (phase 5, 2026-11-19)

**Facts:**
- Every Nuvio Plus build from 8 to 12 is signed, v2 only, with certificate
  `40335e3135578dd858dc434717b9344015b62872dcb861d52474c38a1f99f48a`.
- That certificate's private key and password are in the public repository
  (`signing/nuvio-plus.p12`, `nuvio-plus-public`). It is an **exposed private key**.
- Anyone can sign an APK that Android accepts as an update of Nuvio Plus, and that APK can read the
  app's data: account session, debrid and Trakt tokens.
- The app supports Android 7.0 and later (minSdk 24).

Nothing here created a production key, rotated anything or published anything.

## Experiment

- **Workflow:** `plus-study-key-rotation.yml` on the branch `plus-study/key-rotation` (never
  merged). Run [44451382158](https://github.com/Mohammed4421/NuvioMobile/actions/runs/44451382158),
  commit `623fdc8a`, 2026-11-19.
- **Keys:** throwaway keys made in the job. `k1` stands for today's key, `k2` for a new key, `k3`
  for a stranger. The app body is the custom-12 x86_64 APK, re-signed with them; the repository
  keystore was not used.
- **Rotation:** `apksigner rotate` from k1 to k2, then `sign --lineage --rotation-min-sdk-version 28`.
  - *strict*: the old key's rollback capability is off.
  - *lenient*: it is on.
- **Each case:** a fresh install of the k1 APK with a marker file in its data, then `adb install -r`
  of the next APK. "Data kept" means the marker survived and `firstInstallTime` was unchanged.

| Case | Android 8 (API 26) | Android 9 (API 28) | Android 14 (API 34) |
|---|---|---|---|
| Rotated APK (k1 → k2) over the k1 install, strict | Success, data kept | Success, data kept | Success, data kept |
| Same, lenient | Success, data kept | Success, data kept | Success, data kept |
| **Then an APK signed with the old key k1 alone** (what a holder of the exposed key can make), strict | **Success (accepted)** | Refused: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Refused: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` |
| Same, lenient | Success | Success | Success |
| Stranger key k3 over k1 (control) | Refused | Refused | Refused |
| New key k2 without the lineage over k1 (control) | Refused | Refused | Refused |

**What this shows:**
- **Rotation keeps the app and its data on all three versions.**
- With the strict lineage, Android 9 and later **stop trusting the old key** once the rotated build is
  installed.
- Android 7 and 8 do not know v3 rotation. They verify the v2 signature, which the rotated APK still
  carries for them, made with the old key. So they keep trusting the exposed key: rotation does not
  protect them.
- A new key without a lineage is refused everywhere, so a plain key change forces an uninstall.

## Options compared

| Option | Users keep the app and data (builds 8–12 installed) | Closes the exposure | Cost |
|---|---|---|---|
| **A. Keep the exposed key for now** | Yes, all versions | No | The owner's explicit approval per the publishing rule |
| **B. v3 rotation, strict** (recommended) | Yes, all versions (proved above on 26, 28, 34) | Android 9+: yes, once the rotated build is installed. Android 7–8: no | A new private key held outside CI's reach (below); one transition build; the app's own update check pins the new certificate |
| C. New key, no rotation | No: uninstall; settings, downloads and sign-ins lost | Yes | Users reinstall |
| D. New application id plus data migration | Both apps side by side; data moves only through a new export/import feature | Yes, for the new app | An export/import feature that does not exist; two apps |

**Remaining risk with B:**
- A device that never installs the transition build stays on the old key, and an Android 7–8 device
  stays exposed for good.
- The rotated APK still contains a v2 signature made with the exposed key, for older Android. That
  does not weaken Android 9+, which uses the v3 block.
- Only users who update through the app or the release page are protected.

**Pinning in the app:** `UpdateArchiveCheck.trustedRotationTargets` (phase 1) accepts a different
certificate only when it is listed there and the installed certificate is in the APK's lineage. An
APK made with the exposed key and a made-up lineage to another key is therefore refused by the app's
own check. Android's installer would still accept it if the user installed it by hand.

## Keeping the new key away from untrusted code (design for B)

- The new private key lives only in a protected GitHub **Environment** (for example `plus-release`):
  - deployment branches: `custom-subtitles` and `plus-beta` only;
  - required reviewer: the owner, who approves each publish run;
  - its secrets are released only to a job that names the environment, after approval.
- The signing job:
  - runs no file of the commit: no checkout; the APKs come from the artifact whose digests the
    checks recorded;
  - signs with apksigner inline, using the lineage file;
  - checks the result against the pinned new certificate.
- `release-identity` then pins the new certificate. `plus-build.yml` keeps signing with no secret.
- The old exposed key is used only to sign the lineage, once, offline.

## Decisions needed (not taken)

1. Approve option B, or keep A, for now.
2. If B: who creates and stores the new key and its backup (a lost key ends updates for good).
3. The transition build, and when to stop publishing builds that only carry the old key.
