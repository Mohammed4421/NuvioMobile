# Build 15 — proposed protection of `custom-subtitles` (spec §12, P6-10)

Status: **proposal for the owner**. This session cannot change repository settings; the owner applies
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
