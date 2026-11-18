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
   - Gradle builds unsigned APKs.
   - Job `sign` signs them with `.github/plus/sign-apks.sh` (apksigner, **v2 only**, matching
     build 12). It does a sparse checkout of only the script and the public key, and runs no Gradle.
   - The SHA-256 list of the unsigned APKs is passed as a job output and checked by `sign`. The list
     of the signed APKs is passed as an output and checked by `publish`, along with the certificate
     of every APK.
   - Job outputs cannot be changed by other jobs, so an artifact swapped by another job is refused.
7. **Rejected alternatives:**
   - A throwaway Gradle key followed by re-signing: leaves two signing steps and keeps a key next to Gradle.
   - Signing with v2 and v3: changes the scheme set without need; v3 belongs to the rotation study (phase 5).
8. **Modified files:** `plus-build.yml`, `custom-android-build.yml`, `prepare-build.sh` (now refuses
   signing settings), new `sign-apks.sh`.
9. **Added tests:** Policy tests (key only in `sign`; publish needs checks and gate; emulator needs
   sign, update check, tests). In CI, the existing update check and emulator test cover signing: the
   update check compares the certificate with custom-12, and the emulator installs the new APK as an
   update over custom-12.
10. **Test results:** See "Phase 1 results".
11. **Regression risk:** The certificate or scheme differs from earlier builds → the update check
    fails and nothing is published (fail closed).
12. **Remaining risks:** The signing key itself is public (S4, phase 5); this change protects a
    future private key, not the current one.
13. **Commit / PR:** `0d83908c`.
14. **Final status:** Done, pending CI and owner review.

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
14. **Final status:** Done, pending CI and device test.

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

---

## Phase 1 results

Filled in after the CI run of the pull request (see below).

---

## Later phases (planned, not started)

| ID | Phase | Item | Status |
|---|---|---|---|
| F1 | 2 | Pausing a season episode starts the next; Pause all does not stop the queue | Planned |
| F2 | 2 | Season queue shared across profiles | Planned |
| F3 | 2 | Library recomposition during downloads (measure first) | Planned |
| F4 | 2 | SAF and `.part` resume robustness | Planned |
| F5 | 2 | Large-download performance (Nuvio #1604; measure first) | Planned |
| P1–P5, L1, L2, T1–T3 | 3 | Outline width, HDR/mpv, ASS dimming, gestures, settings migration, library, UI, tests | Planned |
| B1–B3 | 4 | `plus-beta`, PR #2 retarget, build numbers, channel switching | Planned |
| S4, S5 | 5 | Exposed key, rotation study (API 26/28/34) | Planned; SEC-8 has the first evidence |
| A1–A4 | 6 | End-to-end sync test, rerere, smaller conflict surface, automation levels | Planned |
| R1 | 2 | Build 12 release notes show the heading "Next build (pending approval)" | Planned |
| R2 | 0/owner | Repository description says "Official Nuvio Mobile Repository" | OWNER DECISION |
| U1 | 4 | Beta → Stable policy (no downgrade) | Planned |
| H1 | 3 | ASS dimming limits with libass | Planned |
| P1-iOS | 3 | iOS sources never built in CI | Planned |
| X1 | 3 | `usesCleartextTraffic`, deep links, damaged local files, SAF permissions, crash-log privacy, per-ABI APK integrity, release-notes correctness, schedule auto-disable after 60 days, rollback and recovery docs | Planned |
| X2 | 6 | `update-store-source.yml` (Nuvio) runs on published releases of the default branch; releases made by `GITHUB_TOKEN` do not trigger it. Re-check if releases are ever published another way | Recorded |
