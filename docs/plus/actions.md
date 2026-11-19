# GitHub Actions used by Nuvio Plus

The Nuvio Plus workflows (`plus-*.yml`, `custom-android-build.yml`) pin every external action to a
full commit SHA, with the release it came from in a comment. A moved or compromised tag cannot
change what runs.

| Action | Release | Commit |
|---|---|---|
| actions/checkout | v6.1.0 | `d23441a48e516b6c34aea4fa41551a30e30af803` |
| actions/setup-java | v5.7.0 | `b6effb05e454b25005698d916606bdc6ffcbf961` |
| gradle/actions/setup-gradle | v6.4.1 | `8da46b539cd8fe6ab27ca75128919a18d757604c` |
| actions/upload-artifact | v7.0.2 | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |
| actions/download-artifact | v8.0.2 | `9000827ccba6bdab643e8b6fd33ac0654aef8333` |
| reactivecircus/android-emulator-runner | v2.38.0 | `a421e43855164a8197daf9d8d40fe71c6996bb0d` |

These are the commits the major tags (`@v6`, `@v5`, …) pointed to when they were pinned, so the
code is the same that built and tested build 12.

Runners are pinned to `ubuntu-24.04` (not `ubuntu-latest`, which GitHub is moving to a newer Ubuntu)
because the emulator test depends on KVM and the image's Android SDK.

## Updating

1. Dependabot (`.github/dependabot.yml`) opens a weekly pull request into `custom-subtitles` with
   new SHAs. It also proposes updates for Nuvio's own workflows in this repository; those can be
   closed.
2. Read the action's release notes for the new version.
3. The pull request runs every check (`nuvio-plus/checks`). Merge only when it passes; for the
   emulator runner and Gradle actions, compare the run time and the emulator result with the
   previous build.
4. Update the table above in the same pull request.

`.github/plus/tests/test_workflow_policy.py` fails if an action is not pinned, a runner says
`latest`, a job that runs Gradle can write, or secrets are inherited.
