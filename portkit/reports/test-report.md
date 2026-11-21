# PortKit test report (Build 15, P7)

Status words: **PASS** (ran and passed, evidence linked), **FAIL**, **BLOCKED** (cannot run yet),
**NOT TESTED**. Unit tests: `.github/plus/tests/test_portkit*.py`, run by "Tests of the review gate
and release scripts" on every pull request. Transfer run: PortKit transfer tests
[44766832136](https://github.com/Mohammed4421/NuvioMobile/actions/runs/44766832136) on `bdb1e951`.

| Required check (spec 10.7) | Result | Evidence |
|---|---|---|
| Compatibility check of the target | PASS | `test_a_project_that_is_not_nuvio_is_unsupported`; target contract in `compatibility.json` |
| Transfer of one feature | PASS | `test_portable_features_are_ready_on_clean_upstream`, CLI test |
| Transfer of several features with dependencies | PASS | `test_dependencies_are_planned_first`; run 44766832136 transfers both portable features |
| Dry run changes nothing | PASS | `test_a_dry_run_changes_nothing_in_the_target` (byte digest of the target) |
| Re-applying adds nothing (idempotent) | PASS | `test_a_target_that_already_has_the_feature_needs_nothing`, `test_applying_again_changes_nothing` |
| Unsupported target or feature refused | PASS | `test_identity_and_the_updater_are_refused`, non-Nuvio target, unknown feature |
| Conflict with a changed file | PASS | `test_a_fork_change_where_the_feature_changes_is_a_conflict`, new-path test |
| No partial transfer on failure | PASS | `test_a_failure_while_writing_removes_the_branch`, conflict leaves no branch |
| Rollback of PortKit's own changes only | PASS | `test_rollback_undoes_only_what_portkit_wrote`, refused while checked out |
| Uncommitted target changes | PASS | refused unless `--from-head` (`test_uncommitted_changes_in_the_target_are_refused_unless_from_head`) |
| Only portable, verified features in a real transfer | PASS | `test_outside_a_trial_only_portable_verified_features_are_planned`, `test_a_real_transfer_needs_portable_verified_features` |
| Build of the result and the transferred tests (official Nuvio 1.0.0) | PASS | run 44766832136: `DuplicateSafeLazyKeysTest` 4/4, `SubtitleParserWorstCaseTest` 4/4, `androidApp-full-debug.apk` assembled |
| Build and tests on an independent, deliberately different fork | PASS | same run, target fork A (`fixtures/fork-a.patch`): both classes 4/4, APK assembled; the fork's note kept (patch-context) |
| Evidence bound to the run and commit | PASS | `test_portkit_evidence.py` (12); `check-evidence` on every pull request |
| Licences and notices kept | PASS (by design) | transfers copy Plus sources unchanged (GPL-3.0 headers and repository licence stay); no licence file is touched; not separately tested |
| Adapter-required and manual features | NOT TESTED | no adapters yet; they are refused outside a trial |
| Shared files split by feature (hunk level) | BLOCKED | owners must transfer together until hunks are attributed |
| Running the transferred app on a device | NOT TESTED | APK assembled only; not installed or launched |

Verified features: **2 of 14** (`lazy-key-collisions`, `subtitle-parser-worst-case`). The other 12 are
not portable by PortKit today; see `compatibility-matrix.md`.
