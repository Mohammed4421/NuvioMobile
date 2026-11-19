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

## 4. Publishing with the public key: your decision

Every Nuvio Plus build since build 8 is signed with the key in `signing/nuvio-plus.p12`, which is
public. Users of builds 8–12 can only update to APKs signed with that key, so changing it now would
cut them off (phase 5 studies a safe move). Until then, the publishing workflow stops unless you
decide, knowingly, to keep publishing with the public key:

Settings → Secrets and variables → Actions → Variables → New repository variable

| Name | Value |
|---|---|
| `PLUS_PUBLIC_KEY_RELEASES` | `allowed` |

Without it, merging into `custom-subtitles` builds and checks everything but publishes nothing (the
"Release signing identity" job explains why). Deleting the variable stops publishing again.

What the public key means: anyone can build an APK that Android accepts as an update of Nuvio
Plus. Install updates only through the app's own updater or this repository's release page.

The workflow also refuses to publish if the APKs carry any other certificate, or if a private key
secret (`CUSTOM_KEYSTORE_BASE64`) exists before the key migration is approved, so the identity
cannot change by accident.

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
