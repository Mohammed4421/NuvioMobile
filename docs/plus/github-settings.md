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
3. Merging a green pull request publishes the next build (the publish run shows "Reviewed and
   tested pull request" passing).

## Secrets

None are needed today: builds are signed with the public key in `signing/` (the identity of every
build since build 8). `CUSTOM_KEYSTORE_*` are read only by the sign job of the publishing workflow
and must not be added before the signing-key plan (phase 5) is approved, because a new key means
users reinstall.
