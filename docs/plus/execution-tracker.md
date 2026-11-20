# Nuvio Plus execution tracker

One section per issue, with every field of the master directive. Status words: PASS, FAIL,
BLOCKED, NOT TESTED, NEEDS PHYSICAL DEVICE, OWNER DECISION. BLOCKED and NOT TESTED are not passes.

## Baseline (checked live before phase 1, 2026-11-18)

| Item | Value |
|---|---|
| `custom-subtitles` | `28a3e4dd6e615d2ce8665ff8469ec0be6aea1ab9` (= release `custom-12`) |
| `cmp-rewrite` (default) | `6749350e9b8c32aecb6b33346685291b8da3d901` |
| `plus-fix/safe-sync-v2` | `68442adff954c104f061d9ca35bd22fa6e6960fb` (merged as PR #1) |
| `plus-review/nuvio-1.1.0-beta.2` | `b861fe81355dff43b8e7bb39442f39d916b0ad73` (PR #2, draft, base `plus-fix/safe-sync-v2`) |
| `plus-beta` | does not exist |
| Releases | custom-1…6, custom-8…12, build-failed-7, build-failed-12 |
| Concurrent work | none: no other branches, no open pull request besides #2 |

Matches the reference state of the directive. Phase 1 branch: `plus-fix/security-foundation`, from
`custom-subtitles@28a3e4dd`.

---

## Phase 1 — Security foundation

### SEC-1 · Unreviewed code built with a writing token and inherited secrets (directive S1)

1. **ID:** SEC-1
2. **Severity:** High
3. **Verification status:** Confirmed in the files.
4. **Evidence:** `plus-build.yml@28a3e4dd` line 37 `permissions: contents: write` for every job,
   including `build`, `plus-tests` and `nuvio-tests`, which run Gradle on the commit. `actions/checkout`
   kept the token in `.git/config` (default `persist-credentials: true`). `plus-ci.yml`,
   `plus-sync.yml` (`checks`) and `plus-pipeline.yml` used `secrets: inherit`. `plus-sync.yml`
   builds the merge of an unreviewed Nuvio release through `plus-build.yml`.
5. **Root cause:** One workflow did build, test and publish, so every job got the publish token.
6. **Selected solution:** Read-only default token in every Plus workflow. Jobs that run code get
   `contents: read` and `persist-credentials: false`. Write access only in: sync `prepare` (push
   the review branch; runs no Nuvio code) and `pull-request`, and the publishing workflow's
   `publish` and `report-failure`. The `report` job may only set commit statuses. No `secrets:
   inherit`; only `custom-android-build.yml` (push to `custom-subtitles`/`plus-beta`) passes the four
   secrets the build needs. `CUSTOM_LOCAL_PROPERTIES` reaches only publishing builds.
7. **Rejected alternatives:** A separate repository for review builds (more moving parts, same
   effect). `pull_request_target` (would give pull request code the base branch's token and secrets).
8. **Modified files:** `.github/workflows/{plus-build,plus-ci,plus-sync,plus-pipeline,custom-android-build}.yml`,
   `.github/plus/prepare-build.sh`; default branch: `.github/workflows/plus-pipeline.yml`.
9. **Added tests:** `test_workflow_policy.py` (secrets never inherited; only the publishing
   workflow passes secrets; read-only default; only listed jobs write; Gradle jobs cannot write
   and never see the key; checkouts keep no credentials).
10. **Test results:** Policy tests PASS (14/14) on the new files; on the old files 11 fail and 1
    errors, one for each rule broken. CI run: see "Phase 1 results".
11. **Regression risk:** A job that needs write access and lost it would fail visibly (not silently).
12. **Remaining risks:** `CUSTOM_LOCAL_PROPERTIES` values (Trakt, Simkl client keys) are compiled
    into the APK by design and can be read from it. The publishing build still runs Gradle on the
    reviewed commit with that secret.
13. **Commit / PR:** `0d83908c`, PR (see Phase 1 results).
14. **Final status:** Done, pending owner review.

### SEC-2 · Signing inside the Gradle build; no binding between built, tested and published APKs (S1.6–S1.11)

1. **ID:** SEC-2
2. **Severity:** High
3. **Verification status:** Confirmed.
4. **Evidence:** `prepare-build.sh@28a3e4dd` wrote `NUVIO_RELEASE_STORE_*` into
   `local.properties` before Gradle ran, so a private key (when set) would sit beside any Gradle
   or plugin code. Publish took whatever `nuvio-plus-apk` artifact the run had.
5. **Root cause:** Signing delegated to Gradle's `signingConfig`.
6. **Selected solution:**
   - Gradle signs with a **throwaway build-only key** made in the build job (random password,
     2-day validity) and discarded with the job. The Android Gradle plugin refuses to package a
     release build without a key (run `291736fd`: `SigningConfig "release" is missing required
     property "storeFile"`), so an unsigned build was not possible.
   - Job `sign` re-signs with `.github/plus/sign-apks.sh` (apksigner, **v2 only**, matching
     build 12). apksigner rewrites the APK Signing Block. The script refuses APKs with v1 signatures,
     which would survive, and requires exactly one certificate, v2 only.
   - It does a sparse checkout of only the script, the certificate reader and the public key, and
     runs no Gradle.
   - The SHA-256 list of the unsigned APKs is passed as a job output and checked by `sign`. The list
     of the signed APKs is passed as an output and checked by `publish`, along with the certificate
     of every APK.
   - Job outputs cannot be changed by other jobs, so an artifact swapped by another job is refused.
7. **Rejected alternatives:**
   - A throwaway Gradle key followed by re-signing: leaves two signing steps and keeps a key next to Gradle.
   - Signing with v2 and v3: changes the scheme set without need; v3 belongs to the rotation study (phase 5).
8. **Modified files:** `plus-build.yml`, `custom-android-build.yml`, `prepare-build.sh` (now refuses
   signing settings), new `sign-apks.sh`, new `apk-certs.py`.
9. **Added tests:** Policy tests (key only in `sign`; publish needs checks and gate; emulator needs
   sign, update check, tests). In CI, the existing update check and emulator test cover signing: the
   update check compares the certificate with custom-12, and the emulator installs the new APK as an
   update over custom-12.
10. **Test results:** See "Phase 1 results".
11. **Regression risk:** The certificate or scheme differs from earlier builds → the update check
    fails and nothing is published (fail closed).
12. **Remaining risks:** The signing key itself is public (S4, phase 5); this change protects a
    future private key, not the current one.
13. **Commit / PR:** `0d83908c`, `c70685b0`, `8fd7eba2`.
14. **Final status:** Done; CI PASS on `3ecae669`.

### SEC-3 · Review gate could be bypassed (S2)

1. **ID:** SEC-3
2. **Severity:** High
3. **Verification status:** Confirmed by reproducing each case: the old `review-gate.py` run on test repositories.
4. **Evidence:** Old gate, Nuvio 1.1.0 merged in every case:
   - record missing or deleted → `exit 0 "All Nuvio reviews are approved."`;
   - `status: rejected` → exit 0;
   - `approved_by: anyone` and `tested_commit` set to the old baseline → exit 0.
5. **Root cause:** It trusted the files present and their text: `continue` on rejected, a glob over
   existing records, an ancestor-only check of `tested_commit`, and free-text `approved_by`.
6. **Selected solution:** Two modes.
   - `records` (status `nuvio-plus/review` on every pull request):
     - The required record is worked out from Git: the newest Nuvio tag merged since `custom-12`.
     - Rejected releases that are merged block.
     - Older records that exist must be finished.
     - Missing upstream tags block.
   - `publish` (before every release) also checks with the GitHub API:
     - HEAD is the merge commit of a pull request into this branch, merged by an approver (default:
       the repository owner).
     - That pull request's head has `nuvio-plus/checks` and `nuvio-plus/review` = success, created
       by `github-actions[bot]`.
     - Each required record came through such a pull request and is unchanged since.
     - An API failure blocks.

   `tested_commit` and `approved_by` are dropped from the template, so neither is trusted any more.
7. **Rejected alternatives:**
   - A GitHub pull request approval: the owner cannot approve their own pull requests.
   - GPG-signed approval commits: heavy for a single owner.
8. **Modified files:** `review-gate.py`, `upstream-review.py`, `reviews/README.md`, `custom-android-build.yml`, `plus-build.yml`.
9. **Added tests:** `test_review_gate.py`, 20 cases on real Git repositories with a fake GitHub API.
   - Missing, deleted, pending, open box, rejected merged, rejected not merged, older unfinished,
     approved, missing tags, no baseline.
   - Direct push, merged by someone else, missing or failing statuses, a status not set by Actions.
   - Record changed after approval, API down, publish without a record, non-sync change.
10. **Test results:** PASS 20/20 locally; CI: see "Phase 1 results".
11. **Regression risk:** A legitimate publish could be blocked if GitHub's API is down (intended
    fail-closed; rerun).
12. **Remaining risks:** See SEC-9: anything acting with the owner's token, including Claude
    sessions, is indistinguishable from the owner to GitHub.
13. **Commit / PR:** `8eaac696`.
14. **Final status:** Done, pending owner review.

### SEC-4 · No branch protection; CI not on pull requests (S2)

1. **ID:** SEC-4
2. **Severity:** High
3. **Verification status:** Confirmed: `GET /repos/…/branches/custom-subtitles/protection` → 404. `plus-ci.yml` ran only on push.
4. **Evidence:** As above. Pull requests opened by the sync's `GITHUB_TOKEN` start no
   `pull_request` workflows, so a required check fed only by them would never report.
5. **Root cause:** Protection not set; checks attached to workflow runs, not to the pull request head.
6. **Selected solution:**
   - `plus-ci.yml` also runs on `pull_request` into `custom-subtitles` and `plus-beta`, with no secrets
     and a read-only token.
   - Every run reports commit statuses on the pull request head. The sync reports them on the
     review branch head itself.
   - Ruleset values for the owner are in `docs/plus/github-settings.md`:
     - Settings: 0 required approvals, both statuses required, up-to-date branches, no force pushes,
       no deletion.
     - This does not lock out a single owner.
7. **Rejected alternatives:** Requiring 1 approval locks the owner out of their own pull requests.
8. **Modified files:** `plus-ci.yml`, `plus-build.yml` (`report` job), `plus-sync.yml`, docs.
9. **Added tests:** Policy test for `pull_request_target` absence. The ruleset is checked by hand after it is set (doc §3).
10. **Test results:** BLOCKED for the ruleset itself (owner setting). Statuses: see "Phase 1 results".
11. **Regression risk:** Until the ruleset is on, direct pushes still publish (the publish gate now
    refuses them: a direct push has no merged pull request).
12. **Remaining risks:** The owner, as admin, can still change the ruleset.
13. **Commit / PR:** `0d83908c`, `de53c765`.
14. **Final status:** Code done; **OWNER DECISION**: apply the ruleset.

### SEC-5 · Supply chain: unpinned actions, `ubuntu-latest`, expressions in scripts (S3)

1. **ID:** SEC-5
2. **Severity:** Medium
3. **Verification status:** Confirmed.
4. **Evidence:**
   - Plus workflows used `@v6`, `@v5`, `@v7`, `@v8` and `@v2`.
   - All Plus jobs used `runs-on: ubuntu-latest`.
   - `${{ inputs.channel }}`, `${{ steps.number.outputs.build }}` and `${{ inputs.note }}` were placed
     directly in `run:` scripts.
5. **Root cause:** Defaults.
6. **Selected solution:**
   - Pin actions to the commits their tags pointed to (the code that built build 12), listed in
     `docs/plus/actions.md`.
   - Add Dependabot for `github-actions`, targeting `custom-subtitles`.
   - Run on `ubuntu-24.04`.
   - Pass every value through `env`.
   - Allow only plain version tags from Nuvio.
   - Keep Nuvio changes to `signing/` out of the sync merge, as `.github` already was, and list them
     in the record.
7. **Rejected alternatives:** Upgrading actions now (no evidence of need; directive: no random upgrades).
8. **Modified files:** All five Plus workflows, `upstream-review.py`, `.github/dependabot.yml`, docs.
9. **Added tests:** Policy tests (pinned actions, pinned runners, no `${{` in scripts).
10. **Test results:** PASS locally; CI: see "Phase 1 results".
11. **Regression risk:** `ubuntu-24.04` is what `ubuntu-latest` resolved to for build 12.
12. **Remaining risks:** Nuvio's own workflows in this repository still use tags, but they run only
    on Nuvio events, dispatch or PRs to `cmp-rewrite`, with no Plus secrets.
13. **Commit / PR:** `0d83908c`, `f3f7492d`.
14. **Final status:** Done.

### SEC-6 · Manual run could publish from any branch (new finding)

1. **ID:** SEC-6
2. **Severity:** High
3. **Verification status:** Confirmed in the file.
4. **Evidence:** `custom-android-build.yml@28a3e4dd` has `workflow_dispatch` with `publish: true`
   and `channel: … github.ref_name == 'plus-beta' && 'beta' || 'stable'`. A manual run on any branch
   published a stable `custom-N` from that branch, subject only to the review records present there.
5. **Root cause:** No check of the ref.
6. **Selected solution:**
   - The `branch` job fails unless the ref is `refs/heads/custom-subtitles` or `refs/heads/plus-beta`.
   - The publish gate also needs a merged pull request into that branch.
7. **Rejected alternatives:** Removing `workflow_dispatch`, which is needed for `allow_key_change` (phase 5).
8. **Modified files:** `custom-android-build.yml`.
9. **Added tests:** The gate test "direct push" covers the second barrier.
10. **Test results:** PASS (gate). The branch job: NOT TESTED in a live run, because a manual
    dispatch needs the owner (my token cannot reach the Actions API).
11. **Regression risk:** None for pushes to the two branches.
12. **Remaining risks:** None known.
13. **Commit / PR:** `0d83908c`.
14. **Final status:** Done.

### SEC-7 · UpdateArchiveCheck failed open (S6)

1. **ID:** SEC-7
2. **Severity:** Medium (Android still verifies the signature; the check is the app's own barrier)
3. **Verification status:** Confirmed in code.
4. **Evidence:** `UpdateArchiveCheck.kt@28a3e4dd`:
   - Line 41: `?: return null` when the installed package info could not be read, so the check passed.
   - Line 34: `installedSigners.isNotEmpty() &&` skipped the certificate comparison when the installed
     certificates were empty.
5. **Root cause:** Written to avoid blocking updates, at the cost of failing open.
6. **Selected solution:** Fail closed.
   - New problems `InstalledUnreadable` (own package, version or certificates unreadable) and
     `Unsigned` (APK without readable certificates), each with an English and Arabic message.
   - A reader exception is treated as unreadable.
   - A different certificate is accepted only as a pinned v3 rotation: one signer each side, the
     installed certificate earlier in the update's lineage, the lineage ending with the new
     certificate, and the new certificate in `trustedRotationTargets`, which is empty in this build.
   - This prepares phase 5 without trusting whoever holds the current (public) key.
   - Multi-signer APKs must match exactly.
   - Android's own installer checks are unchanged.
7. **Rejected alternatives:** Accepting any lineage that contains the installed key. Anyone with the
   public key could rotate to their own key.
8. **Modified files:** `UpdateArchiveCheck.kt`, `AndroidAppUpdaterPlatform.kt`, `values/strings.xml`,
   `values-ar/strings.xml`.
9. **Added tests:**
   - `UpdateArchiveCheckTest` (14 tests): same key, other package, older or same version, other key,
     damaged APK, unsigned, installed unreadable (5 forms), throwing readers, rotation not pinned or
     pinned, lineage without the installed key, forged lineage order, after rotation, multi-signer.
   - `UpdateArchiveCheckPackageManagerTest` (Robolectric, Android 8): certificates read through the
     PackageManager, a package without certificates, a damaged APK file, the app's own certificate
     unreadable.
10. **Test results:** See "Phase 1 results".
11. **Regression risk:** If some Android version returned no `signingInfo` for the app itself, in-app
    updates would stop there with a clear message, and the user installs from the release page.
12. **Remaining risks:** The Android 9+ `SigningInfo` path is not unit tested (Robolectric cannot
    build `SigningInfo` here) → **NEEDS PHYSICAL DEVICE**:
    - On an Android 9+ phone, update from build 13 to 14 through the app.
    - Expected: the installer opens.
    - Then, with an APK signed by another key renamed into the download folder, expected: the
      "different key" message.
13. **Commit / PR:** `276c3077`.
14. **Final status:** Done; unit tests PASS in CI; device test NEEDS PHYSICAL DEVICE.

### SEC-8 · Signing identity of build 12 (evidence for S4, gathered to keep it unchanged)

1. **ID:** SEC-8 (input to phase 5, nothing changed)
2. **Severity:** Critical (the key is public), handled in phase 5
3. **Verification status:** Confirmed.
4. **Evidence:**
   - `custom-12` arm64 APK: one APK Signature Scheme **v2** signer, certificate SHA-256
     `40335e3135578dd858dc434717b9344015b62872dcb861d52474c38a1f99f48a`.
   - No v1 (`META-INF`) and no v3 block.
   - `signing/nuvio-plus.p12` (password in the repository) holds the same certificate and its
     private key.
5. **Root cause:** Chosen in build 8 so builds update over each other without secrets.
6. **Selected solution in phase 1:** Keep the same certificate and scheme (`sign-apks.sh`: v2 only).
   Rotation is studied in phase 5.
7. **Rejected alternatives:** Changing the key now (forbidden; users would reinstall).
8. **Modified files:** none for the key.
9. **Added tests:** The CI update check against custom-12 and the emulator update over custom-12.
10. **Test results:** See "Phase 1 results".
11. **Regression risk:** none.
12. **Remaining risks:** Anyone can sign an update of Nuvio Plus; phase 5.
13. **Commit / PR:** —
14. **Final status:** Recorded; phase 5.

### SEC-9 · Commit identity and who can act as the owner (new finding)

1. **ID:** SEC-9
2. **Severity:** Medium
3. **Verification status:** Confirmed.
4. **Evidence:**
   - The commits of PR #1 (`c3382bb2`…`68442adf`) are authored and committed as
     `github-actions[bot]`, from a leftover local Git setting of an earlier simulation of the sync.
   - PR #1 was opened and merged as `Mohammed4421`: my GitHub access acts with the owner's identity.
5. **Root cause:** A local `git config user.name` set while simulating the bot; the session's GitHub
   access uses the owner's credentials.
6. **Selected solution:**
   - Phase 1 commits are authored as `Claude (Nuvio Plus maintenance) <noreply@anthropic.com>`.
   - The new gate trusts statuses created by GitHub Actions, never commit authors.
   - History is not rewritten (no force push).
7. **Rejected alternatives:** Rewriting PR #1's history (force push, forbidden).
8. **Modified files:** none.
9. **Added tests:** The gate test "status not set by GitHub Actions".
10. **Test results:** PASS.
11. **Regression risk:** none.
12. **Remaining risks:** GitHub cannot tell an action by a Claude session from one by the owner.
    "Merged by the owner" therefore also covers merges made through a session. The barrier there is
    procedural (sessions are told not to merge). **OWNER DECISION**: limit what the connected GitHub
    access may do (for example, no merge rights on this repository) if that matters.
13. **Commit / PR:** —
14. **Final status:** Recorded.

### SEC-10 · The CI certificate comparison compared nothing (new finding)

1. **ID:** SEC-10
2. **Severity:** High (a check reported as passing did not run)
3. **Verification status:** Confirmed for this runner image. Very likely for build 12, which ran the
   same script on the same image family.
4. **Evidence:**
   - `check-update-compat.sh@28a3e4dd` read the certificate with
     `sed -n 's/^Signer #1 certificate SHA-256 digest: //p'`.
   - The runner's apksigner prints `V2 Signer: certificate SHA-256 digest: 40335e31…` (notice in run
     `3ecae669`, job "Sign APKs").
   - The match is therefore empty for both APKs, `"" != ""` is false, and the script wrote "same
     package and signature" without comparing certificates. In build 12 only the emulator's
     update-install actually proved the certificate.
5. **Root cause:** Parsing a tool's human-readable output, and an empty value not treated as an error.
6. **Selected solution:**
   - `.github/plus/apk-certs.py` reads the certificates from the APK Signing Block itself. It
     reproduces build 12's identity: `v2 40335e31…f48a`.
   - It is used by the sign job, the update check and the publish job.
   - An unreadable certificate, package or version code now fails the check.
7. **Rejected alternatives:** Fixing the `sed` pattern (breaks again with the next apksigner format).
8. **Modified files:** `check-update-compat.sh`, `sign-apks.sh`, `custom-android-build.yml`, new
   `apk-certs.py`.
9. **Added tests:** `test_apk_certs.py`:
   - v2 reading; v2 and v3 together.
   - Exactly one current certificate: rotation and two signers fail.
   - Not a ZIP, unsigned, no scheme block, a length prefix that runs past the data.
10. **Test results:** PASS. CI `3ecae669`: "Update over custom-12: same package and signature,
    version code 1012 → 1013", now with a real certificate comparison.
11. **Regression risk:** none; any parse failure blocks.
12. **Remaining risks:** `--current` refuses rotated APKs (v2 old key + v3 new key) on purpose until phase 5.
13. **Commit / PR:** `d2ad930b`, `15f80e4f`.
14. **Final status:** Done.

### CI-1 · Release build exceeded the runner's memory after a full compile (found while testing)

1. **ID:** CI-1
2. **Severity:** Medium (blocks publishing whenever app code changes)
3. **Verification status:** Confirmed by outcome.
   - Runs `c70685b0` and `a18a0c00` were killed while packaging: "runner lost communication" after
     54 minutes, then exit 143.
   - With the change below, the build finished in about 11 minutes in every later run.
4. **Evidence:** `gradle.properties`: Gradle `-Xmx12288M` plus a separate Kotlin daemon `-Xmx8192M`
   on a 16 GB runner. Build 12 likely reused compiled outputs from the build cache. This branch
   changes app code, so it compiles everything and then runs R8.
5. **Root cause:** Memory settings sized for a developer machine.
6. **Selected solution:** In the CI build only: Kotlin compiles inside Gradle
   (`-Pkotlin.compiler.execution.strategy=in-process`) with a 10 GB heap. On failure the step reports
   the end of the log, the kernel's out-of-memory lines and free memory. The repository's
   `gradle.properties` is unchanged.
7. **Rejected alternatives:** Larger paid runners; changing Nuvio's `gradle.properties` (conflicts on every sync).
8. **Modified files:** `plus-build.yml`.
9. **Added tests:** none (CI behaviour).
10. **Test results:** PASS: build 10–11 minutes in runs `56cb197c`, `dc9d56aa`, `8fd7eba2`, `99751263`, `3ecae669`.
11. **Regression risk:** A slower build if memory is tight; no effect on the APK.
12. **Remaining risks:** Downloads from the Gradle plugin portal and Maven failed once each (runs
    `15f80e4f`, `99751263`). These are infrastructure failures and passed on rerun. A rerun needs a
    push or the owner's "Re-run".
13. **Commit / PR:** `56cb197c`.
14. **Final status:** Done.

### SEC-11 · The sign job ran files of the commit while holding the signing secrets (owner review of PR #3)

1. **ID:** SEC-11
2. **Severity:** High (for any future private key; no private key exists today)
3. **Verification status:** Confirmed in PR #3 at `cf140746`.
4. **Evidence:** `plus-build.yml` job `sign` set `CUSTOM_KEYSTORE_BASE64/PASSWORD/KEY_ALIAS` in its
   environment and ran `.github/plus/sign-apks.sh` and `apk-certs.py` from the commit being
   published. A change to either file would have reached the key. "No Gradle" was not a trust
   boundary.
5. **Root cause:** Trusting that the commit is reviewed, while the files that see the key are in it.
6. **Selected solution:**
   - No signing secret reaches `plus-build.yml` at all: its `secrets:` accepts only
     `CUSTOM_LOCAL_PROPERTIES`, and `sign-apks.sh` only knows the public key.
   - A new job, `release-identity`, in the publishing workflow runs no file of the commit: no
     checkout, no actions, `permissions: {}`. It receives only whether a private key exists
     (`secrets.CUSTOM_KEYSTORE_BASE64 != ''`, a true/false value, never the key).
   - Signing with a private key is impossible until phase 5. The plan for then is a job in a
     protected GitHub Environment (allowed branches only, approval by the owner) that runs no file of
     the commit.
7. **Rejected alternatives:**
   - Pinning the signing script by SHA from the same workflow: the pin lives in the commit too.
   - Implementing private-key signing now: it means a key change, which is forbidden.
8. **Modified files:** `plus-build.yml`, `sign-apks.sh`, `custom-android-build.yml`.
9. **Added tests:** `test_no_signing_secret_reaches_files_of_the_commit`,
   `test_the_signing_key_secret_is_only_tested_for_presence`,
   `test_the_release_identity_check_runs_no_code_of_the_commit`.
10. **Test results:** PASS on the fix. On PR #3 (`cf140746`) these and the tests of SEC-12 and
    SEC-13 give 4 failures and 3 errors.
11. **Regression risk:** none for today's builds (they never had a private key).
12. **Remaining risks:** The workflow files themselves come from the commit on the publishing branch.
    Only the ruleset (reviewed pull requests) and, in phase 5, an Environment approval protect them.
13. **Commit / PR:** see PR #3.
14. **Final status:** Done.

### SEC-12 · Publishing silently fell back to the public key (owner review of PR #3)

1. **ID:** SEC-12
2. **Severity:** High (risk accepted without a decision)
3. **Verification status:** Confirmed.
4. **Evidence:** `sign-apks.sh@cf140746`: "the private key in CUSTOM_KEYSTORE_BASE64 when the
   publishing workflow passes one, otherwise the public key". Merging PR #3 would have published
   build 13 with the public key automatically. Adding a private key would have changed the identity
   at once; the update check would have stopped that, unless `allow_key_change` was set.
5. **Root cause:** A fallback chosen for convenience in build 8.
6. **Selected solution:** `release-identity` stops publishing unless **all** of these hold:
   - The certificate is the pinned one of builds 8–12, `40335e31…f48a`. Any other certificate stops
     publishing, `allow_key_change` included, until phase 5.
   - No private key secret exists.
   - The owner has set the repository variable `PLUS_PUBLIC_KEY_RELEASES=allowed`, a deliberate
     decision documented in `github-settings.md` §4.

   Build 12 users keep receiving updates (same key, no data loss) once the owner decides. Without
   the decision nothing is published.
7. **Rejected alternatives:**
   - A new key now: users would reinstall and lose data.
   - Blocking all publishing until phase 5: the owner may prefer updates with a known risk.
8. **Modified files:** `custom-android-build.yml`, `docs/plus/github-settings.md`.
9. **Added tests:** `test_publishing_with_the_public_key_needs_the_owners_decision`. It runs the job's
   script for five cases: allowed; no decision; private key present; other certificate; no
   certificate.
10. **Test results:** PASS.
11. **Regression risk:** Merging PR #3 no longer publishes build 13 by itself (intended).
12. **Remaining risks:** As long as the public key is used, anyone can make an APK Android accepts as
    an update. That ends only in phase 5.
13. **Commit / PR:** see PR #3.
14. **Final status:** Done; **OWNER DECISION** on the variable.

### SEC-13 · Unchecked `CUSTOM_LOCAL_PROPERTIES` reached Gradle (owner review of PR #3)

1. **ID:** SEC-13
2. **Severity:** Medium
3. **Verification status:** Confirmed: the value is passed whole. The secret is most likely empty today.
4. **Evidence:**
   - `prepare-build.sh@cf140746` appended the secret as it was, with any names.
   - The build reads 17 names from it (`composeApp/build.gradle.kts`: Trakt, Simkl, MDBList,
     Premiumize, TMDB, Sentry, links). Every one is compiled into the APK as a constant.
   - Build 12's code has no 64-character Trakt id and no Sentry address, so the secret was most
     likely not set.
   - Any other name (for example `NUVIO_RELEASE_*` or a server key) would have reached Gradle, and
     the signing names would have changed the build key.
5. **Root cause:** No list of accepted names.
6. **Selected solution:**
   - The **first step** of the build job, before the checkout, so no code of the commit has run,
     checks every line against the list of the 17 public setting names. It writes only accepted
     lines to a file and stops the build, naming the setting but never its value, for anything else.
   - Account-server settings (`NUVIO_SUPABASE_*`) cannot be overridden.
   - Only the publishing workflow passes the secret.
   - Documentation says every value is public once in the APK, `TRAKT_CLIENT_SECRET` included (as in
     Nuvio's official app).
7. **Rejected alternatives:** Filtering inside `prepare-build.sh`: it is a file of the commit and runs
   after other code of the commit.
8. **Modified files:** `plus-build.yml`, `prepare-build.sh`, `custom-android-build.yml`, `github-settings.md`.
9. **Added tests:**
   - `test_the_extra_properties_are_checked_before_any_code_of_the_commit`: first step, and no later
     step sees the secret.
   - `test_only_public_app_settings_pass`: accepted lines kept; `NUVIO_RELEASE_*`, a service key,
     `NUVIO_SUPABASE_URL`, lowercase, no `=`, and an empty name all rejected; a value is never printed.
10. **Test results:** PASS.
11. **Regression risk:** A secret with an unexpected name stops the publishing build with a clear message.
12. **Remaining risks:** Accepted values are public by design. If the owner ever puts a private value
    under an accepted name, it ends up in the APK.
13. **Commit / PR:** see PR #3.
14. **Final status:** Done.

### PR-4 · Dependency of PR #4 on PR #3 and merge order

- **PR #4** changes only the copy of `plus-pipeline.yml` on `cmp-rewrite`: it adds `statuses: write`
  and removes `secrets: inherit`.
- **#4 first, then #3 (correct).**
  - The current sync (`plus-sync@custom-subtitles`) asks for less than PR #4 grants, and passing no
    secrets changes nothing, since it signs with the public key anyway.
  - After #3, the new sync's checks need `statuses: write`, which #4 grants.
- **#3 alone (breaks).** GitHub refuses to start the scheduled sync, because the called jobs would
  ask for more than the caller grants. `test_callers_grant_what_the_called_jobs_ask` covers the
  `custom-subtitles` copy.
- **Merging #4 triggers nothing:** no workflow on `cmp-rewrite` starts on push. On the pull request
  itself, Nuvio's own debug build ran with no Plus secrets and posted a comment. That is harmless.
- **After both are merged:**
  - Pull requests into `custom-subtitles` get `nuvio-plus/checks` and `nuvio-plus/review` (proved on
    PR #3).
  - Merging runs every check, the review gate and `release-identity`.
  - Nothing is published until the owner sets `PLUS_PUBLIC_KEY_RELEASES`.
- **Not provable before merging:** a live publish and a scheduled sync run on the new files.

### CI-2 · Failed downloads and duplicate runs (owner's request, 2026-11-19)

1. **ID:** CI-2
2. **Severity:** Medium
3. **Verification status:** Confirmed.
4. **Evidence:**
   - Run `0913035f` (push): `could not resolve plugin artifact 'io.sentry.android.gradle…:6.14.0'`.
     It also failed earlier in `15f80e4f`; a Robolectric jar failed in `99751263`. Every other run
     downloaded the same files.
   - `setup-gradle` writes its cache only on the default branch, where nothing is built, so every run
     downloads everything.
   - Every push to a branch with an open pull request started two full runs (push and pull request).
5. **Root cause:** Unreliable downloads with no retry; a duplicate trigger.
6. **Selected solution:**
   - `.github/plus/gradle-retry.sh` runs Gradle again, at most three times, only when the log shows a
     failed download and no compile error or failing test.
   - It is used by all three Gradle calls.
   - `plus-ci` runs only on pull requests and by hand.
7. **Rejected alternatives:**
   - Writing the Gradle cache on review branches: more moving parts.
   - Changing Nuvio's `settings.gradle.kts`: conflicts on every sync, and Maven Central is already listed there.
8. **Modified files:** `gradle-retry.sh`, `plus-build.yml`, `plus-ci.yml`.
9. **Added tests:** `test_gradle_retry.py` (6), and policy tests: every Gradle call uses the retry;
   `plus-ci` has no push trigger.
10. **Test results:** PASS locally (51/51). CI: BLOCKED, see CI-3.
11. **Regression risk:** A run whose downloads keep failing takes up to 2 minutes longer before failing.
12. **Remaining risks:** none known.
13. **Commit / PR:** `f089976e`, PR #3.
14. **Final status:** Done; CI result pending CI-3.

### CI-3 · GitHub stopped running Actions for the repository (2026-11-19, from 00:16 UTC)

1. **ID:** CI-3
2. **Severity:** Blocking
3. **Verification status:** Confirmed by behaviour; the cause is not visible to me.
4. **Evidence:**
   - The pull request run of `0913035f` was cancelled after 12 minutes with the annotation
     `repository invocation blocked`.
   - For `f089976e`, pushed at 00:26 UTC, GitHub created no Actions check suite in 8 minutes; before,
     jobs appeared within seconds.
   - Other repositories report the same message when GitHub blocks Actions for a repository: runs stay
     queued and are cancelled after about 11 minutes, and support lifts the block.
   - The earlier exit 143 (`a18a0c00`) was a cancellation and may have the same cause.
5. **Root cause:** Unknown (GitHub side). The many runs of this branch on 18–19 November may have
   contributed; CI-2 halves the runs per change.
6. **Selected solution:** None possible from here; only the owner can see GitHub's notice and
   contact support.
7. **Rejected alternatives:** Pushing again to "retry" (adds load and changes nothing while blocked).
8. **Modified files:** none.
9. **Added tests:** none.
10. **Test results:** BLOCKED. Build, signing, update check and emulator of `f089976e` have not run.
    The last complete green run is `3ecae669`, from before the owner's review fixes.
11. **Regression risk:** —
12. **Remaining risks:** Phase 1 is not complete until a full green run on the PR #3 head.
13. **Commit / PR:** —
14. **Final status:** **OWNER ACTION**.

### CI-4 · Economical CI without weaker checks (owner's request after Actions was re-enabled)

1. **ID:** CI-4
2. **Severity:** High for the cache point; Medium for the rest
3. **Verification status:** Confirmed in the files.
4. **Evidence:**
   - **Cache poisoning path.** `setup-gradle` writes its cache by default on the default branch. The
     scheduled sync runs there (`plus-pipeline.yml` on `cmp-rewrite`) and builds Nuvio code nobody has
     reviewed yet. Caches of the default branch are readable by every branch, so they were readable
     by the publishing runs too.
   - **Retries could outlast the time limit.** `gradle-retry.sh` could run a timed-out command
     (`timeout 30m`, exit 124) again when its log mentioned a download, three times past the job's
     time limit.
   - **Every change ran everything.** Each change to a draft pull request ran a full build and the
     emulator, and the Nuvio baseline ran every time, although it only labels failures.
5. **Root cause:** Defaults; one validation level.
6. **Selected solution:**
   - **Cache:** Every `setup-gradle` sets `cache-read-only` unless the run is a push to
     `custom-subtitles` or `plus-beta` (reviewed code). Pull requests and the sync only read.
   - **Retries:** Exits 124, 126, 127, 137 and 143 are never retried.
   - **Two levels:**
     - Draft pull request: **fast validation** (policy, security, gate and script tests, review
       records, in seconds). `nuvio-plus/checks` is set to **pending, never success**.
     - Ready for review, every later push, a manual run, publishing, the sync: **full validation**.
   - **Baseline:** The Nuvio baseline runs only when a Nuvio Plus test fails. Every Nuvio Plus test
     must still pass, and without the baseline a failure blocks as "not compared".
   - **One run per change:** Pushes start nothing and a newer commit cancels the older run (pull
     requests only). Publishing runs are never cancelled.
7. **Rejected alternatives:**
   - Path filters on the required check: they would leave it missing on some commits.
   - Running only changed modules' tests: no reliable module map.
   - Overlapping syncs (concurrency): changing the default-branch copy would start Nuvio's heavy
     debug build on PR #4 for little gain.
8. **Modified files:** `plus-build.yml`, `plus-ci.yml`, `gradle-retry.sh`, `test-gate.py`.
9. **Added tests:**
   - `test_gradle_retry.py`: timeout or kill after a download error, missing command, unrecognised
     message. All are returned at once.
   - `test_test_gate.py` (4): passes without the baseline when nothing fails; blocks a failure with
     or without the baseline; blocks when no tests ran.
   - Policy tests:
     - Statuses follow the job results: every heavy job failed or skipped means failure; a skipped
       baseline is fine.
     - Fast validation never reports success.
     - The cache is read-only outside publishing runs.
     - Ready pull requests, publishing and the sync always get full validation.
10. **Test results:** Local 62/62. Against `2c289de2`, 8 of the new tests fail or error. CI: see Phase 1 results.
11. **Regression risk:**
    - A draft pull request can no longer show a green `nuvio-plus/checks` (intended).
    - The first runs after this have no Gradle cache until a publishing run writes one.
12. **Remaining risks:** The cache is written by publishing runs. They run reviewed code, but Gradle
    plugins downloaded during them are trusted as before.
13. **Commit / PR:** see PR #3.
14. **Final status:** Done locally; CI below.

---

## Phase 1 results

### Passed on the latest commit of PR #3: `8a05a545` (pull request run, full validation)

| Check | Result |
|---|---|
| Build, 4 ABIs (throwaway key; Sentry plugin from Maven Central) | PASS, 10 min |
| Sign: no Gradle, no secret; v2 only; certificate `40335e31…f48a` (custom-12) | PASS |
| Update check over custom-12: package, real certificate comparison, 1012 → 1013 | PASS |
| Nuvio Plus unit tests | PASS 2154/2154 |
| Nuvio 1.0.0 baseline | Not run: no Nuvio Plus test failed (runs only on a failure, CI-4) |
| Policy, gate, certificate-reader, retry and test-gate tests | PASS 64/64 |
| Review records | PASS (no Nuvio release since custom-12) |
| Emulator, Android 14: install over custom-12, launch, rotation, Back | PASS |
| `nuvio-plus/checks` / `nuvio-plus/review` (set by `github-actions[bot]`) | success / success |

### Passed earlier only (superseded)

`3ecae669` (before the owner's review fixes) passed every check. Later commits changed the signing
secrets, app settings, CI levels and cache, and are covered by the run on `8a05a545` above.

### Could not be run, or not proven by CI

- **Android 9+ `SigningInfo` path** of the in-app update check: NEEDS PHYSICAL DEVICE (SEC-7).
- **A live publish:** not done on purpose. Nothing is published before the owner's decision (SEC-12).
- **Ruleset and repository variable:** owner settings.
- **Emulator checks:** the Arabic first screen is inconclusive, and data-kept counts only 1 prefs
  file (phase 3 · T2).

### Failing runs on the way, all explained

| Run | Cause | Fixed by |
|---|---|---|
| `de53c765` | checkout with `persist-credentials: false` fails on Nuvio's gitlink | inline token removal |
| `291736fd` | the Gradle plugin needs a key | throwaway build key |
| `c70685b0`, `a18a0c00` | runner memory | in-process Kotlin, 10 GB heap (CI-1) |
| `56cb197c`, `dc9d56aa` | token step under `pipefail` | fixed step |
| `8fd7eba2` | certificate parsing | APK Signing Block reader (SEC-10) |
| `15f80e4f`, `99751263`, `0913035f` (push), `121ecc4e`, `e46d0b8a` | Sentry plugin marker 404 on the plugin portal; one Robolectric download | plugin from Maven Central; download-only retries (CI-2) |
| `0913035f` (pull request), `f089976e`, `2c289de2` | GitHub blocked Actions for the repository | owner re-enabled Actions (CI-3) |
| `5b2ef16a` | emulator skipped below a skipped job (reported as failure, not success) | explicit condition plus policy test |

---

## Phase 2 — Functional correctness

### F1 · Pausing a season episode started the next one

1. **ID:** F1
2. **Severity:** High
3. **Verification status:** Confirmed in code (audit): the old `pump` removed every Downloading entry
   whose download was not running. The old logic was not a separate function, so the new tests encode
   the scenarios but were not run against it.
4. **Evidence:** `SeasonDownloadQueue.kt@28a3e4dd`:
   - `pump()` (lines 286–312) dropped an entry whose download status was not `Downloading` (paused
     included), then found the queue not busy and searched the next episode.
   - `continueInBackground` counted only running downloads as busy.
   - In Nuvio 1.1.0-beta, "Pause all" (`pauseActiveDownloads`) pauses the running download, so the
     queue started the next one at once.
5. **Root cause:** The queue was built before pausing existed, and treated "not running" as "done".
6. **Selected solution:**
   - A new state `SeasonQueueEntryState.Paused`. A paused episode keeps its place and its download id,
     and holds the queue.
   - When resumed it is Downloading again; the queue moves on only when it finishes, fails, is
     deleted, or is removed from the queue (skip).
   - The decisions live in `SeasonQueuePolicy` (`settle`, `busy`, `next`), free of the repositories.
     `pump` and `continueInBackground` both use them.
   - The downloads screen lists a paused queue episode as "Paused: the next episode waits until you
     resume it" (English and Arabic), with its remove button.
7. **Rejected alternatives:**
   - A global "queue paused" flag: it mixes pausing one episode with pausing all, and needs UI that
     stable does not have.
   - A larger refactor of the singleton: the pure policy covers the decisions with a minimal change.
8. **Modified files:** `SeasonDownloadQueue.kt`, `DownloadsScreen.kt`, `values/strings.xml`, `values-ar/strings.xml`.
9. **Added tests:** `SeasonQueuePolicyTest` (9):
   - pause does not start the next;
   - resume continues the same episode, then the next;
   - "Pause all" holds the queue, and "Resume all" keeps the order;
   - deleted or failed paused episode leaves;
   - removing a paused episode skips it;
   - a paused queue survives a restart;
   - a search holds the queue, and an interrupted search starts over;
   - an episode without a source does not hold the queue;
   - a Downloading entry without a download leaves.
10. **Test results:** PR #5 run on `85748af4`: PASS.
    - 2163/2163 unit tests (2154 + 9 new).
    - Build, signing, update check over custom-12, emulator.
    - `nuvio-plus/checks` success.
11. **Regression risk:** A queue whose episode stays paused waits indefinitely (intended). Stored
    queues from build 12 contain no Paused entries and load as before.
12. **Remaining risks:**
    - A "Pause all" pressed while the next episode is still being searched (up to 90 s) lets that
      episode start. Stable has no "Pause all" button; wiring beta's button to the queue belongs to
      the beta merge (B1).
    - 429/503 handling during the search is unchanged (it ends as NeedsChoice).
13. **Commit / PR:** `13b651dd`, PR #5.
14. **Final status:** Done; full validation PASS. A device test is pending: pause a queue episode
    from the notification and from the screen.

### F2 · The season queue mixed profiles

1. **ID:** F2
2. **Severity:** High for multi-profile users (worse than the audit said)
3. **Verification status:** Confirmed in code.
4. **Evidence:**
   - `DownloadsStorage.android.kt`: the download list uses `ProfileScopedKey`, while the queue is in
     the device-wide `extra_season_queue`.
   - `ProfileRepository.selectProfile` calls `DownloadsRepository.onProfileChanged()`, which loads
     profile B's downloads.
   - The queue's next `pump()` did not find profile A's running episode in B's list and dropped it as
     finished.
   - It then started A's next episode and `enqueueFromStream` stored it in **B's** downloads.
5. **Root cause:** A queue without a profile, in front of per-profile downloads.
6. **Selected solution:**
   - `SeasonQueueEntry.profileId`.
   - `SeasonQueuePolicy.settle/busy/next` take the active profile: other profiles' episodes are left
     exactly as they are, and never hold or start this profile's queue.
   - Screens get only the active profile's episodes (`visible`). `clear()` and the duplicate check
     work per profile.
   - On a switch, a running search for another profile's episode stops (it waits as Waiting), and
     `startEntry` and `handOff` never hand an episode to the downloader under another profile.
   - The queue also listens to `ProfileRepository.state`, so a switch shows the right queue even when
     both download lists are equal.
   - Migration: entries saved before have no profile and are given the active profile when they load.
     Nothing is dropped.
   - Download location and Wi-Fi settings stay device-wide (intended).
7. **Rejected alternatives:**
   - One stored queue per profile key: this moves data and needs migration of the existing key; one
     list with a profile field keeps everything in place.
   - Guessing the owner of old entries from the downloads: unreliable.
8. **Modified files:** `SeasonDownloadQueue.kt`, `SeasonQueuePolicyTest.kt`.
9. **Added tests:** four in `SeasonQueuePolicyTest`:
   - a profile switch leaves the other queue untouched, and B's queue moves on alone;
   - A → B → A without loss or duplicates (running and finished cases);
   - another profile's search does not hold this queue;
   - old queues are kept for the active profile.

   The F1 tests now run with a profile.
10. **Test results:** See the F2 pull request.
11. **Regression risk:**
    - A download already running when the profile is switched keeps running as before (Nuvio's
      behaviour); the queue no longer drops it.
    - Old queue entries from another profile (saved before this) are given the active profile at the
      first start.
12. **Remaining risks:** What happens to a running download's own record on a profile switch is
    Nuvio's download code, unchanged. NEEDS PHYSICAL DEVICE: two profiles, a season queued in A,
    switch to B and back.
13. **Commit / PR:** `70ba30d9`, F2 pull request.
14. **Final status:** Code and tests done; full validation pending.

### COMPAT-1 · A way back to Build 12 would have erased the season queue

1. **ID:** COMPAT-1
2. **Severity:** High (silent, permanent data loss on a rollback). Found in the Decision Gate review.
3. **Verification status:** Confirmed in code and reproduced in a test with Build 12's exact model.
4. **Evidence:**
   - `SeasonDownloadQueue.kt@28a3e4dd` lines 105 and 128–129 decode the whole stored queue at once,
     with `ignoreUnknownKeys` and without `coerceInputValues`. Any unknown enum value or one damaged
     entry fails the whole decode; `runCatching … getOrNull() … orEmpty()` turns that into an empty
     queue, and the next `update()` (line 507) overwrites the store.
   - The F1 change (#5, #6 at `399047bc`) stored the literal state `Paused`.
5. **Root cause:** A storage format tied to the in-memory enum, and an all-or-nothing reader.
6. **Selected solution:** `SeasonQueueStorage`.
   - **Write:** only Build 12's four state names. A paused episode is stored as `Downloading` plus
     `"paused": true`; `profileId` and `paused` are fields Build 12 ignores.
   - **Read:** entry by entry.
     - A damaged entry, or one missing required fields, is set aside; the other entries are kept.
     - An unknown future state becomes `Downloading` if the entry has a download (the queue then
       matches it with its real download), otherwise `Waiting`.
     - The literal `Paused` of the earlier test builds is still read.
   - **Unreadable data:** an unreadable payload or entries are kept under `season_queue_unreadable`
     (the newest 20), never overwritten silently.
   - **Limit:** Build 12 itself cannot be changed. After a way back, its old behaviour applies: the
     paused episode leaves the queue, and profiles share the queue. Nothing is erased.
7. **Rejected alternatives:**
   - `coerceInputValues` only: it maps an unknown state to Waiting and loses `downloadId`'s meaning
     (duplicate downloads).
   - A preparatory release that teaches Build 12 the new state: impossible for users already on 12.
8. **Modified files:** new `SeasonQueueStorage.kt`; `SeasonDownloadQueue.kt` (load and save);
   `docs/plus/rollback.md`.
9. **Added tests:** `SeasonQueueStorageTest` (12):
   - every state survives a round trip;
   - paused is stored in Build 12's form;
   - **Build 12 (exact copy of its model and decoder) reads build 13's queue completely**;
   - the literal Paused erases Build 12's queue (the cause, kept as a guard);
   - Build 12's saved queue loads;
   - a damaged entry is set aside;
   - a missing required field is set aside;
   - unreadable payloads are returned whole;
   - empty stores;
   - newer states;
   - the literal Paused is still read;
   - leftovers are kept and capped.
10. **Test results:** CI: see PR #6 (pending at the time of writing).
11. **Regression risk:** none for Build 12 queues (the same fields); the queue now saves the
    paused-flag format.
12. **Remaining risks:** Downloads, settings and other stores were not changed by #3, #5 or #6. A
    later change to them needs the same old-build test.
13. **Commit / PR:** `b450bce2`, PR #6.
### R1 · Release notes: wrong heading, and a stale section could be published again

1. **ID:** R1
2. **Severity:** Medium (wrong information to users)
3. **Verification status:** Confirmed.
4. **Evidence:**
   - Build 12's release notes start with "Next build (pending approval)". `publish-release.sh@28a3e4dd`
     copied the top `CHANGELOG.md` section with its heading.
   - That top section still described build 12, so build 13 would have repeated build 12's changes
     as new.
5. **Root cause:** No link between the changelog section and the build being published.
6. **Selected solution:**
   - The top section must be `## Next build …`; it is published as `### Build <N> …`, without any
     "(pending approval)" label.
   - Every release keeps a SHA-256 fingerprint of its section (`<!-- changelog-section: … -->`).
     Publishing is refused when the section equals the previous release's, when it has no
     "Next build" section, or when the release list cannot be read.
   - The changelog: build 12's section is renamed, and a new "Next build" section describes the
     user-visible changes of #3, #5 and #6.
7. **Rejected alternatives:** Writing the changelog automatically (the token cannot push, and the
   notes need a person).
8. **Modified files:** `publish-release.sh`, `CHANGELOG.md`.
9. **Added tests:** `test_publish_release.py` (8):
   - the section is published as this build;
   - a pending label is dropped;
   - no Next build section means nothing is published;
   - the previous release's notes are not published again;
   - new notes after a release are published;
   - an unreadable release list blocks;
   - an already published build is left alone;
   - beta is a pre-release.
10. **Test results:** Local 72/72; CI: see the R1 pull request.
11. **Regression risk:** A release is refused until the changelog is updated (intended).
12. **Remaining risks:** After each release someone must rename the section and start a new one. The
    refusal makes that visible.
13. **Commit / PR:** R1 pull request.
14. **Final status:** Done locally; CI pending.

## Later phases (planned, not started)

| ID | Phase | Item | Status |
|---|---|---|---|
| F1 | 2 | Pausing a season episode starts the next; Pause all does not stop the queue | Done (see F1) |
| F2 | 2 | Season queue shared across profiles | Done (see F2) |
| F3 | 2 | Library recomposition during downloads (measure first) | Planned |
| F4 | 2 | SAF and `.part` resume robustness | Planned |
| F5 | 2 | Large-download performance (Nuvio #1604; measure first) | Planned |
| P1–P5, L1, L2, T1–T3 | 3 | Outline width, HDR/mpv, ASS dimming, gestures, settings migration, library, UI, tests | Planned |
| B1–B3 | 4 | `plus-beta`, PR #2 retarget, build numbers, channel switching | Planned |
| S4, S5 | 5 | Exposed key, rotation study (API 26/28/34) | Planned; SEC-8 has the first evidence |
| A1–A4 | 6 | End-to-end sync test, rerere, smaller conflict surface, automation levels | Planned |
| R1 | 2 | Build 12 release notes show the heading "Next build (pending approval)" | Done (see R1) |
| R2 | 0/owner | Repository description says "Official Nuvio Mobile Repository" | OWNER DECISION |
| U1 | 4 | Beta → Stable policy (no downgrade) | Planned |
| H1 | 3 | ASS dimming limits with libass | Planned |
| P1-iOS | 3 | iOS sources never built in CI | Planned |
| X1 | 3 | `usesCleartextTraffic`, deep links, damaged local files, SAF permissions, crash-log privacy, per-ABI APK integrity, release-notes correctness, schedule auto-disable after 60 days, rollback and recovery docs | Planned |
| X2 | 6 | `update-store-source.yml` (Nuvio) runs on published releases of the default branch; releases made by `GITHUB_TOKEN` do not trigger it. Re-check if releases are ever published another way | Recorded |

## SEC-KEY-1: the public key never publishes (2026-11-19)

- **Finding:** publishing with the key of builds 8–12 depended on a repository variable that cannot
  be read from review sessions; if set, a merge would publish a build signed with a compromised key.
- **Change:** `release-identity` treats that certificate as compromised: a build signed with it is
  checked but never published (`publish=false`, the publish job is skipped, no failure release). Any
  other certificate must equal `RELEASE_IDENTITY`, empty until the key migration is approved. The
  variable `PLUS_PUBLIC_KEY_RELEASES` is no longer read.
- **Tests:** `test_workflow_policy.py` runs the job's script for the public key, an unknown key, the
  pinned key and a configured private key.
- **Next:** the migration plan in `signing-migration.md`, waiting for the owner's approval.

## SEC-KEY-2: release signing with a protected new key (2026-11-19, approved by the owner)

- **Decision:** new key, v3 lineage from the old key, Android 9 or later (`signing-migration.md`).
- **Evidence:** study run 44521306430 (Android 8, 9, 13, 14): the new-key-only APK with the lineage
  updates build 12 with data kept on 9/13/14, then old-key and attacker-lineage APKs are refused;
  Android 8 cannot install it. `apksigner` refuses a new-key v2 block next to a lineage.
- **Change:** `-Pnuvio.minSdk=28` for release builds; `release-identity` → `release-sign` (environment
  `plus-release`, owner approval, no file of the commit) → `release-verify.py` → `release-upgrade`
  (emulators 26/28/34 from build 12) → `publish`; the one-time `plus-release-key.yml`; test builds
  compare with `custom-12`; release notes explain the one manual install.
- **Tests:** `test_workflow_policy.py` (who sees the key, v3-only flags, ordering), `test_release_verify.py`.
- **Not yet:** the key itself (owner steps), then the pins.
- **Key made (2026-11-19, run 44534502910, approved by the owner):** certificate
  `99c0a98cc1700bd1186018e480eab670daaf55d4d8c5316d7cf03d788d431e35`, pinned in `release-identity`
  and `UpdateArchiveCheck.trustedRotationTargets`. The lineage is made in `release-sign` from the old
  public key and the release key, and must name exactly those two certificates. Publishing waits for a
  second owner approval after release-verify and the emulator move tests.

## CI-ROBO-1: Robolectric's Android jars through Gradle (2026-11-19)

- **Finding:** every publishing run since #7 (runs 44527216109 ×3, 44537630817) failed 73 test
  classes with `Failed to fetch maven artifact org.robolectric:android-all-instrumented:…` (versions
  14-…-10818077-i7, 9-…-4913185-2-i7, 8.0.0_r4-…-r1-i7), while pull request runs of the same code
  passed. Robolectric's own downloader has no retry and its failure cause is not shown.
- **Change:** `composeApp/build.gradle.kts` resolves those jars with Gradle and hands them to
  Robolectric through `robolectric-deps.properties`, one Gradle configuration per version (in one
  configuration Gradle keeps only the highest version). With that file Robolectric uses nothing else,
  so it lists every version the tests use (SDK 26, 28, 34, 36; checked when the file is written).
  No test is skipped or made optional.

## B14-1: Build 14 foundation (2026-11-20)

- **M1:** `CHANGELOG.md` closes "Build 13" and opens a new "Next build". The old top section's
  SHA-256 `05e73912…` equals the `changelog-section` of `custom-13`, so the publish script would have
  refused it; the new one differs. Tests: `test_the_real_changelog_can_be_published_after_build_13`.
- **Release notes:** how to update from build 13 and from build 12, Android 7–8, English and Arabic
  (`publish-release.sh`, test).
- **M3:** `rollback.md`: build 13 is the floor; a rescue build is a forward release with the release
  key, a higher version code, data compatibility and every gate.
- **S2:** `osv-check.py` in the build job: the release runtime classpath against osv.dev; HIGH,
  CRITICAL or unknown severity blocks unless reviewed with a reason and an end date (≤ 180 days).
- **S3:** README (install, update pipeline, signing), feature registry (identity).
- **S6:** the `allow_key_change` switch is removed from both workflows and `check-update-compat.sh`.
- **Settings the session may not change (owner):** repository description, Dependabot alerts,
  branch ruleset. Attempts by the session were refused by its own safety rules.
- **Upstream:** latest stable Nuvio release is 1.0.0 (`511ebd5f`), already the base of Nuvio Plus;
  1.1.0-beta.1/2 are betas and are not merged.

## B14-2: update safety (2026-11-20)

- **M2:** `release-upgrade` (before every release, Android 8/9/14 emulators): from the previous
  release (signed with the release key, checked) the app's own update check
  (`UpdateArchiveDeviceTest`, real PackageManager) must accept the release and refuse a foreign-key,
  an old-key and a damaged APK; Android must refuse the foreign and old-key APKs; the release must
  install as an update with data kept, a higher version code, and open. From build 12 the same check
  must accept the pinned rotation, then the update keeps data and the old key is refused. Android 8
  refuses the release.
- **Pull requests:** the emulator job also installs the published release and runs the app's check
  against the test build (refused: other key), the release itself (not newer) and a damaged download.
- **Code:** `UpdateArchiveCheck.checkAgainst` (the app's `check` uses it), `UpdateArchiveDeviceCheck`
  for the device test; debug-only `<queries>` for `com.nuvio.app.plus`.
- **First emulator run failed (run 44657320619), root cause fixed:** `update-device-check.sh` used
  the host `com.nuvio.app`, but the debug app's id is `com.nuviodebug.com` (`androidApp/build.gradle.kts`),
  so `run-as` and the instrumentation were not found; and `adb shell` handed `Unreadable|Unsigned`
  to the device's shell, where `|` became a pipe. Now `device-test-host.sh` reads the host and runner
  from the APKs (aapt2, `apk-instrumentation.py`), checks them against `pm list instrumentation` and
  `run-as`; every value given to `am instrument` is single-quoted. Tests: `test_device_test_host.py`
  (fails on the old script).
- **S2 first finding (CI run 44656038835):** HIGH `GHSA-xxph-c9ww-hj94` in
  `com.google.guava:guava:33.3.1-android` (declared by Nuvio in `composeApp`), fixed in 33.7.2.
  Fixed by updating to `33.7.2-android`, not by a review entry.

## B14-3: downloads (2026-11-20)

- **E1:** `SeasonDownloadQueue.startSeason` returns `SeasonStartResult` (`Queued(n)`,
  `NothingToAdd`, `ProfileChanged`); the streams screen shows the profile-changed message (EN/AR).
  Harness r1: result is `ProfileChanged`, nothing queued in A or B.
- **r2:** an episode whose resolve was stopped by a profile switch returns to `Waiting`
  (`NeedsChoice` only when its stream really failed). Harness r2: `E1:Waiting` after the switch, then
  downloaded exactly once in A after the return, never in B.
- **F4 review (SAF), no code change:** resume reopens the same `.part` document (`wa`) and checks
  the server range; a revoked folder permission fails with a clear message before any write;
  completeness is checked (`downloaded == total`) before the final name; a provider that cannot
  rename gets a size-checked copy, and on any failure the `.part` is kept, never deleted; Remove
  deletes only unfinished documents; names are unique against both `Name` and `Name.part`.
  Low, deferred: when a `.part` document was deleted outside the app the free-space check still
  counts the old progress (the download then fails on a full disk instead of before it; no data
  loss). Needs a SAF-provider test before changing.
- **NEEDS PHYSICAL DEVICE:** SD-card providers and vendor file managers.
