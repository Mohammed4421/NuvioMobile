# Build 15/16 — branch protection verified against GitHub

## Live state on 2026-11-23 (supersedes the 2026-11-21 proposal snapshot)

Verified from the repository's [active ruleset 27721857](https://github.com/Mohammed4421/NuvioMobile/rules/27721857), applied to `custom-subtitles` and `cmp-rewrite`:

- **Deletion and non-fast-forward updates:** blocked.
- **Pull request required:** active, with zero mandatory approvals. This does not replace review of the evidence or the owner's decisions.
- **Required checks:** `nuvio-plus/checks`, `nuvio-plus/review`, `Build 15 requirements gate`, `Lint findings against Build 14`, `Transfer, build and test (upstream and a fork)`, and `Notification pause/resume (Android 28)`.
- **Strict up-to-date rule:** `strict_required_status_checks_policy=false`. The workflow reviews should still test the exact proposed head; GitHub does not currently demand the PR branch be current with the base.
- **Remaining bypass risk:** integration actor ID `1236702` has `bypass_mode=always`, so the ruleset does not guarantee that every integration path is held by the same checks. **B15-SEC-03 is reduced but not fully eliminated**. Restrict its bypass to pull requests only, or remove it, after the owner reviews the integration's operational need.
- **Administrative change:** the GitHub connection in this session can read the active ruleset but received HTTP 403 when reading branch-protection administration. This file reports the observed configuration; it does not claim to have changed any remaining GitHub administration setting.

The original proposal is retained below for the audit trail. Its 2026-11-21 assertions that pull-request and required-check rules were absent are historical, **not today's configuration**. Release readiness still separately requires completing all pre-release obligations; branch protection by itself never certifies Build 16 for publication.

---

## Original proposal and historical snapshot (2026-11-21)

Historical status: **proposal for the owner on 2026-11-21**. This session cannot change repository settings; the owner applies
it (Settings → Rules → Rulesets) or declines it.

## What protects the branch today (read 2026-11-21, `GET /repos/…/rulesets/27721857`)

| Rule | Present |
|---|---|
| Block deletion of `custom-subtitles` and `cmp-rewrite` | yes |
| Block force push (non-fast-forward) | yes |
| Require a pull request | **no** |
| Require status checks to pass | **no** |
| Bypass | one GitHub App integration (id 1236702), always |

Finding **B15-SEC-03**: nothing on GitHub stops a merge (or a direct push) with failing or pending
checks. "No merge with a failing or pending mandatory test" (owner rule 5) is kept only by the
discipline of whoever merges. `plus-ci.yml` says branch protection requires `nuvio-plus/checks` and
`nuvio-plus/review`; the current ruleset does not.

## Proposed additions (same ruleset)

1. **Require a pull request before merging** (0 approvals needed, so the owner is not blocked
   when working alone; dismiss stale approvals off).
2. **Require status checks to pass**, branch must be up to date with `custom-subtitles`:
   - `nuvio-plus/checks` (commit status; build, signing, update check, all unit tests, emulator)
   - `nuvio-plus/review` (commit status)
   - `Build 15 requirements gate`
   - `Transfer, build and test (upstream and a fork)` (PortKit)
   - `Lint findings against Build 14` (after the static-analysis pull request is merged)
   - `Notification pause/resume (Android 34)` and `(Android 28)` (after #28 is merged)
   Not required: path-filtered or still unstable workflows (performance comparison, Nuvio's own
   device tests until B15-PLY-01 is closed), because a required check that does not run blocks
   every pull request.
3. **Keep** deletion and force-push protection.
4. **Bypass**: limit the integration's bypass to "pull requests only" (or remove it), so a merge
   through it still needs the checks.

Effect: a pull request cannot be merged while any listed check fails or is pending, and nothing
reaches the branch without a pull request. Releases are unchanged (the `plus-release` environment
approval stays the owner's).

Risk of applying: a listed check that is renamed later blocks merges until the ruleset is updated;
the names above are the ones in the workflows on 2026-11-21.
