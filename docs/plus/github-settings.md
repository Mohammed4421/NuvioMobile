# GitHub settings for Nuvio Plus (owner only)

The workflows cannot change these settings; the owner sets them once in the repository settings.
They are written for a repository with a single owner: no rule asks for an approval the owner
cannot give, and the owner can still merge, revert and manage the repository.

## 1. Ruleset for the publishing branches

Settings → Rules → Rulesets → New ruleset → New branch ruleset

| Field | Value |
|---|---|
| Ruleset name | `Nuvio Plus publishing branches` |
| Enforcement status | Active |
| Bypass list | empty |
| Target branches | Include by pattern: `custom-subtitles`, `plus-beta` |
| Restrict deletions | on |
| Block force pushes | on |
| Require a pull request before merging | on, **Required approvals: 0**, Dismiss stale pull request approvals when new commits are pushed: on |
| Require status checks to pass | on, Require branches to be up to date before merging: on |
| Status checks | `nuvio-plus/checks` and `nuvio-plus/review` (source: any source) |

Why 0 approvals: GitHub does not let anyone approve their own pull request, so requiring one would
lock the owner out of pull requests the owner opens. The approval is instead the owner's merge,
which the publish gate (`review-gate.py publish`) confirms with GitHub before anything is published:
merged by the owner, and the pull request head passed both statuses.

What it stops: direct pushes, force pushes and deletion of the two branches, and merging a pull
request whose head has not passed every check or whose Nuvio review record is not complete.

`plus-beta` does not exist yet; the pattern applies as soon as it is created.

### Checks to require (verified names)

The statuses that every pull request into `custom-subtitles` actually gets (PR #3, #5 and #6 heads,
set by `github-actions[bot]` from `plus-ci.yml`):

- `nuvio-plus/checks`: build, signing, update check, all tests, emulator. A **draft** gets
  `pending` (fast checks only), so a draft can never be merged.
- `nuvio-plus/review`: Nuvio review records.

Do **not** require job names such as "checks / Build APKs": they are check runs of one workflow
event and are missing on review pull requests opened by the sync (those get the two statuses from the
sync itself), so they would leave such pull requests waiting forever. Dependabot pull requests get the
statuses only if their workflows may write statuses; if not they stay waiting (nothing is merged by
mistake).

### The same rules as an API request (prepared, not applied)

`POST /repos/Mohammed4421/NuvioMobile/rulesets` with:

```json
{
  "name": "Nuvio Plus publishing branches",
  "target": "branch",
  "enforcement": "active",
  "bypass_actors": [],
  "conditions": { "ref_name": { "include": ["refs/heads/custom-subtitles", "refs/heads/plus-beta"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 0, "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": false, "require_last_push_approval": false,
        "required_review_thread_resolution": false } },
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": true,
        "required_status_checks": [ { "context": "nuvio-plus/checks" }, { "context": "nuvio-plus/review" } ] } }
  ]
}
```

With an empty bypass list, the publish gate cannot be skipped: there is no "admin may bypass" path.
The owner can still change the ruleset itself in the settings, which is a visible, deliberate act.

## 2. Actions permissions

Settings → Actions → General

| Field | Value |
|---|---|
| Workflow permissions | Read repository contents and packages permissions |
| Allow GitHub Actions to create and approve pull requests | on (the sync opens review pull requests; without it the review is published as a `review-nuvio-<tag>` pre-release instead) |
| Fork pull request workflows | Require approval for all outside collaborators |

Each workflow asks for the write access it needs per job, so the read-only default changes nothing
for them.

Optional, later: "Require actions to be pinned to a full-length commit SHA". The Nuvio Plus
workflows already comply; Nuvio's own workflows in this repository (kept from the upstream merge)
use tags and would stop running, so turn it on only after deciding about them.

## 3. Checks after changing the settings

1. A push straight to `custom-subtitles` is refused.
2. A pull request into `custom-subtitles` shows `nuvio-plus/checks` and `nuvio-plus/review` and
   cannot be merged before both are green.
3. Merging a green pull request publishes the next build only once section 4 is decided (the publish run shows "Reviewed and
   tested pull request" passing).

## 4. The public key never publishes

Every Nuvio Plus build from 8 to 12 is signed with the key in `signing/nuvio-plus.p12`, whose
password is in the repository too. That key is treated as **compromised**: anyone can sign an APK
that Android accepts as an update of those builds.

- The checks still sign test builds with it (so the update over build 12 can be tested), but the
  publishing workflow **never publishes** a build carrying its certificate. The "Release signing
  identity" job says so and the run ends without a release. No setting or variable changes this;
  the old `PLUS_PUBLIC_KEY_RELEASES` variable is no longer read (delete it if it exists).
- Publishing needs the new release key and its certificate pinned in the workflow
  (`RELEASE_IDENTITY`, empty today). How the move to it works, and what the owner does once, is in
  [signing-migration.md](signing-migration.md).
- The workflow also stops if a private key secret (`CUSTOM_KEYSTORE_BASE64`) exists, so the identity
  cannot change outside that plan.

Until then: install updates only from this repository's release page or the app's updater.

## 5. Four separate approvals

Each is a separate act, and none implies another:

| Approval | What it allows | How it is given | What the system checks |
|---|---|---|---|
| Code change | Code enters a branch for review | A pull request with its checks | `nuvio-plus/checks`, `nuvio-plus/review` |
| Merge | The change enters `custom-subtitles` | The owner merges the pull request | Ruleset (above); `review-gate.py publish` asks GitHub who merged it |
| Signing identity | Which certificate users' phones must trust | The new certificate is pinned in the workflow (`RELEASE_IDENTITY`) by a reviewed change, after the owner approves the migration | `release-identity`: the public key's certificate never publishes; any other unpinned one fails |
| Publishing | A merged, checked build is released to users | Only a build signed with the pinned release key | `release-identity`, then the publish job |

Nothing written in a conversation, a commit or a document counts as approval of the key migration;
the owner gives it explicitly, and the key itself can only be created with the owner (see
signing-migration.md).

## Secrets

- `CUSTOM_KEYSTORE_*`: do not add them. No job uses them for signing; their presence alone stops
  publishing until phase 5 is approved.
- `CUSTOM_LOCAL_PROPERTIES` (optional): app settings compiled into the APK, one `NAME=value` per
  line. Every value ends up inside the APK, where anyone can read it, so put nothing private there.
  Only these names are accepted; any other name stops the build without printing its value:
  `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET`, `TRAKT_REDIRECT_URI`, `SIMKL_CLIENT_ID`,
  `SIMKL_REDIRECT_URI`, `SIMKL_APP_NAME`, `MDBLIST_CLIENT_ID`, `PREMIUMIZE_CLIENT_ID`, `TMDB_API_KEY`,
  `SENTRY_DSN`, `INTRODB_API_URL`, `IMDB_RATINGS_API_BASE_URL`, `IMDB_TAPFRAME_API_BASE_URL`,
  `CONTRIBUTIONS_URL`, `SUPPORTERS_WALL_URL`, `DONATIONS_BASE_URL`, `DONATIONS_DONATE_URL`.
  Build 12 contains no Trakt id and no Sentry address, so this secret is most likely not set today.
