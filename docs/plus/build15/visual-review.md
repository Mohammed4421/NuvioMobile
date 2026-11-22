# Build 15 — visual review of the Plus screens (P4)

Reviewed on 2026-11-22 from the reference screenshots (`PlusReferenceScreenshotsTest`) of
`custom-subtitles` after #68, in six setups: English and Arabic on a phone (portrait, 411 × 891 dp),
a landscape phone (891 × 411 dp) and a tablet (1280 × 800 dp). The same 72 images are the approved
baselines of the automatic comparison (P4-11, `screenshot-baselines/`); a later change to any of
them is reported as CHANGED in the checks log for review.

Design rule (P4-13): Nuvio's look is kept. Every change below fixes a defect seen in a screenshot or
a report, with its finding; none is a restyle.

## Screens and findings

| Screen (reference name) | Spacing, sizes, alignment (P4-01) | Controls clear (P4-02) | Hierarchy (P4-04) | Loading, error, empty (P4-05) | Phone and tablet (P4-07) | Arabic / RTL (P4-08) |
|---|---|---|---|---|---|---|
| Library › Downloads (`library-downloads`) | Even grid; long titles cut at two lines | Manage card leads to the downloads screen; tiles open the title | Poster, title, state | Paused (still track), downloading (bar), failed (red word) | 3 columns on the phone, 4 on the tablet | Mirrored; percentage first (B15-UI-06) |
| Library › Download states (`library-download-states`) | Badge top-end of the poster | State read out on the poster (B15-UI-08) | Badge plus text, not colour alone | Failed in red with its word | As above | Mirrored badges |
| Library › Empty (`library-downloads-empty`) | Centred message | Says how to download | Title over hint | Empty state shown | Fits | Arabic text |
| Downloads › Season queue (`downloads-queue`) | Title cut on its own, season/episode badge kept | Pause/resume season, remove, retry; "Pause all", "Clear queue" | Season header over episodes | Searching, waiting, paused with season, failed with retry | Fits on the landscape phone | Mixed titles keep S01E01 visible |
| Downloads › Settings (in `downloads-queue`) | Location and Wi-Fi rows aligned | Wi-Fi row toggles as a whole (B15-UI-09) | Title over description | — | Fits | Mirrored |
| Player › Controls (`player-controls`) | Nuvio's layout | Nuvio's controls (not changed by Plus) | Title, episode line, timeline | — | Landscape phone and tablet | Mirrored title; times in digits |
| Player › Subtitles panel (`player-subtitles`) | Three columns on the tablet; style column scrolls on the phone | − / + labelled (B15-UI-09) | Languages, tracks, style | AutoSync loading and error with Retry | Fits | Values read "+0.000s" (B15-UI-05, fixed in #67) |
| Player › Audio panel (`player-audio`) | Two columns | Reset disabled until changed | Tracks, then delay and gain | — | Fits | "0 dB" (B15-UI-05) |
| Settings › Playback pages (`playback-*`) | Nuvio's settings list | Switch rows: Nuvio's component (B15-UI-07, deferred) | Section headers | — | Fits | Decoder wording fixed (B15-UI-04) |
| Settings › Subtitle appearance (`subtitle-appearance`) | Preview, then controls | Colour codes isolated (B15-UI-02) | Preview first | — | Fits | Codes read left to right |

## Not covered by screenshots

- **Dialogs and alerts (P4-03):** Plus's dialogs (add-on link confirmation, folder errors) have no
  reference screenshot yet. Not reviewed here; listed as open.
- **Animations and transitions (P4-06):** a still image cannot show them. The download bar no longer
  moves when nothing downloads (#60); the rest needs the owner's device.
- **Contrast and focus order (P4-09):** not measured; labels are asserted (#68).

## Owner's device

How the screens feel in use (scrolling, gestures, real posters, real titles) stays with the owner's
checks on the final release (`release-readiness.md`).
