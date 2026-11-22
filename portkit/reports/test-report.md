# PortKit test report (Build 15, P7)

Status words: **PASS** (ran and passed, evidence linked), **FAIL**, **BLOCKED** (cannot run yet),
**NOT TESTED**. Unit tests: `.github/plus/tests/test_portkit*.py`, run by "Tests of the review gate
and release scripts" on every pull request. Transfer runs: PortKit transfer tests
[44766832136](https://github.com/Mohammed4421/NuvioMobile/actions/runs/44766832136) on `bdb1e951` (first two
features) and [45009048362](https://github.com/Mohammed4421/NuvioMobile/actions/runs/45009048362) on `c8af5323`
(the three portable features, both targets, 21 transferred tests). The # column is the spec's list of ten
mandatory tests (P7-08).

| # | Required check (spec 10.7) | Result | Evidence |
|---|---|---|---|
| 1 | Compatibility check of the target | PASS | `test_a_project_that_is_not_nuvio_is_unsupported`; target contract in `compatibility.json` |
| 2 | Transfer of one feature | PASS | `test_portable_features_are_ready_on_clean_upstream`, CLI test |
| 3 | Transfer of several features with dependencies | PASS | `test_dependencies_are_planned_first`; run 45009048362 transfers the three portable features together |
| 4 | Dry run changes nothing | PASS | `test_a_dry_run_changes_nothing_in_the_target` (byte digest of the target) |
| 5 | Re-applying adds nothing (idempotent) | PASS | `test_a_target_that_already_has_the_feature_needs_nothing`, `test_applying_again_changes_nothing` |
| 6 | Unsupported target or feature refused | PASS | `test_identity_and_the_updater_are_refused`, non-Nuvio target, unknown feature |
| 7 | Conflict with a changed file | PASS | `test_a_fork_change_where_the_feature_changes_is_a_conflict`, new-path test |
| 8 | No partial transfer on failure | PASS | `test_a_failure_while_writing_removes_the_branch`, conflict leaves no branch |
| — | Rollback of PortKit's own changes only | PASS | `test_rollback_undoes_only_what_portkit_wrote`, refused while checked out |
| — | Uncommitted target changes | PASS | refused unless `--from-head` (`test_uncommitted_changes_in_the_target_are_refused_unless_from_head`) |
| — | Only portable, verified features in a real transfer | PASS | `test_outside_a_trial_only_portable_verified_features_are_planned`, `test_a_real_transfer_needs_portable_verified_features` |
| 9 | Build of the result and the transferred tests (official Nuvio 1.0.0) | PASS | run 44766832136: `DuplicateSafeLazyKeysTest` 4/4, `SubtitleParserWorstCaseTest` 4/4, `androidApp-full-debug.apk` assembled |
| 9 | Build and tests on an independent, deliberately different fork | PASS | same run, target fork A (`fixtures/fork-a.patch`): both classes 4/4, APK assembled; the fork's note kept (patch-context) |
| — | Evidence bound to the run and commit | PASS | `test_portkit_evidence.py` (12); `check-evidence` on every pull request |
| 10 | Licences and notices kept | PASS | `test_portkit_licence.py`: a target without GPL-3.0 (none, MIT, GPL-2.0, LGPL-3.0) is refused before planning and nothing is written; a transfer keeps the target's `LICENSE` byte for byte and the fork's own copyright line in a patched file; no feature lists a licence file |
| — | Adapter-required and manual features | NOT TESTED | no adapters yet; they are refused outside a trial |
| — | Shared files split by feature (hunk level) | BLOCKED | owners must transfer together until hunks are attributed |
| — | Running the transferred app on a device | NOT TESTED | APK assembled only; not installed or launched |

Verified features: **3 of 15** (`lazy-key-collisions`, `subtitle-parser-worst-case`, `playback-credential-tls`). The other 12 are
not portable by PortKit today; see `compatibility-matrix.md`.
