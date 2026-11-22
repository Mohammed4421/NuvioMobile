# Build 15 — Add-ons / networking journey (P3-05)

Maps the six cases of the journey matrix (`journeys.md`, "Six-case matrix") for the Add-ons /
networking domain to host tests. The coordinator records run ids in the registers; this file only
names the tests.

Tests run against the real `AddonRepository`, `AddonManifestParser`, the app's OkHttp client (60 s
connect / read timeouts) and the real `nuvio_addons` SharedPreferences, with add-on servers on a local
MockWebServer (Robolectric, Android 14). A "restart" clears the repository's memory and reopens the same
preferences file. Every add-on address carries a secret token; each error shown to the user is checked
not to contain it.

File: `composeApp/src/androidHostTest/kotlin/com/nuvio/app/features/addons/AddonJourneyTest.kt`
(`AddonJourneyTest`), plus the existing `AddonLinkConfirmationTest` (commonTest) and
`AddonStoragePersistenceTest` (androidHostTest).

| Case | What is shown | Tests |
|---|---|---|
| **N** normal use | Install from a pasted address, from a confirmed link; disable, reorder | `installedAddonsComeBackAfterARestartWithOrderAndEnabledState`, `aDismissedAddonLinkNeverContactsTheAddonOrInstallsIt` (confirm part), `AddonLinkConfirmationTest.confirmingInstallsTheLinkedAddonOnce` |
| **F** expected failure | 15 malformed manifests (not JSON, HTML, wrong root, truncated, missing / blank / wrong-type required fields, bad catalog / resource / hints, ~6 MB unterminated body), HTTP 500 / 404, empty body, dropped connection, a server that never answers (real 60 s timeout): refused with a short message without the token; installed add-ons and storage unchanged | `malformedManifestsAreRejectedWithoutTouchingInstalledAddons`, `aDroppedConnectionDuringInstallChangesNothingAndARetrySucceeds`, `aServerThatNeverAnswersTimesOutChangesNothingAndARetrySucceeds` |
| **I** cancel / interrupt | Cancelling an install while the server is still answering installs nothing, even when the answer arrives later; a dismissed link never contacts the add-on | `cancellingAnInstallInFlightInstallsNothingEvenIfTheServerAnswersLater`, `aDismissedAddonLinkNeverContactsTheAddonOrInstallsIt`, `AddonLinkConfirmationTest.aLinkAloneInstallsNothing` |
| **R** resume / restore | Retry after a dropped connection or a timeout succeeds; an add-on whose manifest breaks keeps its last good manifest, stays installed across a restart with an error, and recovers on refresh without reinstalling | `aDroppedConnectionDuringInstallChangesNothingAndARetrySucceeds`, `aServerThatNeverAnswersTimesOutChangesNothingAndARetrySucceeds`, `aManifestThatBreaksLaterKeepsTheAddonInstalledAndRecovers` |
| **P** persistence after restart | Order, enabled state and the exact tokened address come back; a disabled add-on is not contacted at start; per profile | `installedAddonsComeBackAfterARestartWithOrderAndEnabledState`, `installsAndRemovalsStayInTheCurrentProfile`, `removingAnAddonRemovesOnlyItAndSurvivesARestart`, `AddonStoragePersistenceTest` (3 tests) |
| **D** no damage to related features | Removing one add-on keeps the others, their manifests and enabled state, without refetching; another profile's add-ons untouched; a broken add-on does not affect the others | `removingAnAddonRemovesOnlyItAndSurvivesARestart`, `installsAndRemovalsStayInTheCurrentProfile`, `aManifestThatBreaksLaterKeepsTheAddonInstalledAndRecovers` |

## Limits

- Per-profile behaviour is shown at the storage and repository level (the add-ons of the current profile;
  another profile's stored add-ons untouched). Switching profiles through `ProfileRepository.selectProfile`
  is not driven here (it resets some 30 other repositories).
- The server push (`sync_push_addons`) is cancelled by each simulated restart and is not checked here;
  `PluginSyncTest` covers add-on pull from the server.
- Manifest responses have no size limit in `httpGetText` (Nuvio's code): the ~6 MB body is read in full
  before it is refused. See the report for this finding.
