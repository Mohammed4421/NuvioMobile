package harness

import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver as Resolver
import com.nuvio.app.features.details.MetaDetailsRepository as Meta
import com.nuvio.app.features.details.season
import com.nuvio.app.features.downloads.DownloadsRepository as Downloads
import com.nuvio.app.features.downloads.DownloadEnqueueResult
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.DownloadsStorage
import com.nuvio.app.features.downloads.SeasonDownloadQueue as Queue
import com.nuvio.app.features.downloads.SeasonDownloadScope
import com.nuvio.app.features.downloads.SeasonQueueEntry
import com.nuvio.app.features.downloads.SeasonQueueStorage
import com.nuvio.app.features.downloads.SeasonStartResult
import com.nuvio.app.features.profiles.ProfileRepository as Profiles
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CompletableDeferred

/** Each scenario runs in a fresh class loader (a fresh process); it returns "PASS ..." or "FAIL ...". */
object Scenarios {
    private const val A = 1
    private const val B = 2
    private val fails = mutableListOf<String>()
    private fun check(ok: Boolean, what: String) { if (!ok) fails += what }

    private fun all(): List<SeasonQueueEntry> = SeasonQueueStorage.decode(DownloadsStorage.loadExtra("season_queue")).entries
    private fun eps(profile: Int, meta: String = "tt1") =
        all().filter { it.profileId == profile && it.parentMetaId == meta }.map { "E${it.episodeNumber}:${it.state}" }
    private fun dl(profile: Int) = Downloads.storedFor(profile).map { "${it.parentMetaId}:E${it.episodeNumber}" }.sorted()

    /** What the last startSeason returned (the toast the user sees comes from it). */
    private var lastResult: SeasonStartResult? = null

    private fun startSeason(meta: String = "tt1", episode: Int = 1) {
        Queue.launch {
            lastResult = Queue.startSeason(
                "series", meta, "series", "Show", null, null, null, "$meta:1:$episode", 1, episode, null, null,
                StreamItem(name = "Show S01E0$episode 1080p WEB", addonId = "addon", playableDirectUrl = "https://x/$meta-e$episode.mkv"),
                SeasonDownloadScope.RestOfSeason,
            )
        }
    }

    /** No queue entry twice, no episode downloaded twice in one profile. */
    private fun invariants() {
        val e = all()
        check(e.map { it.id }.distinct().size == e.size, "duplicate entry id: ${e.map { it.id }}")
        val keys = e.map { "${it.profileId}:${it.parentMetaId}:${it.seasonNumber}:${it.episodeNumber}" }
        check(keys.distinct().size == keys.size, "duplicate queue entry: $keys")
        check(Downloads.log.distinct().size == Downloads.log.size, "same episode handed to the downloader twice: ${Downloads.log}")
    }

    private fun result(name: String, detail: () -> String): String {
        invariants()
        val d = detail()
        return if (fails.isEmpty()) "PASS $name | $d" else "FAIL $name | ${fails.joinToString("; ")} | $d"
    }

    private fun boot(profile: Int = A) {
        Profiles.startOn(profile)
        Downloads.ensureLoaded()
        TestMain.drain()
    }

    @JvmStatic fun r1_switchDuringMetaFetch(): String {
        boot()
        val gate = CompletableDeferred<com.nuvio.app.features.details.MetaDetails>()
        Meta.gate = gate
        startSeason()                       // requested in profile A
        TestMain.drain()                    // waiting for the season's episode list
        Profiles.selectProfile(B)
        TestMain.drain()
        gate.complete(season(3))            // the episode list arrives after the switch
        TestMain.drain()
        check(dl(B).isEmpty(), "profile B got downloads it never asked for: ${dl(B)}")
        check(eps(B).isEmpty(), "profile B got queue entries it never asked for: ${eps(B)}")
        check(eps(A).isEmpty(), "profile A got entries after the switch: ${eps(A)}")
        check(lastResult == SeasonStartResult.ProfileChanged, "result was $lastResult, not ProfileChanged (the user's message)")
        return result("A->B during MetaDetailsRepository.fetch") { "A queue=${eps(A)} A dl=${dl(A)} B queue=${eps(B)} B dl=${dl(B)} log=${Downloads.log}" }
    }

    @JvmStatic fun r1b_abaDuringMetaFetch(): String {
        boot()
        val gate = CompletableDeferred<com.nuvio.app.features.details.MetaDetails>()
        Meta.gate = gate
        startSeason()
        TestMain.drain()
        Profiles.selectProfile(B); TestMain.drain()
        Profiles.selectProfile(A); TestMain.drain()
        gate.complete(season(3)); TestMain.drain()
        check(dl(B).isEmpty() && eps(B).isEmpty(), "B touched: ${eps(B)} ${dl(B)}")
        check(eps(A) == listOf("E1:Downloading", "E2:Waiting", "E3:Waiting"), "A's request not queued in A: ${eps(A)}")
        return result("A->B->A during MetaDetailsRepository.fetch") { "A queue=${eps(A)} A dl=${dl(A)} log=${Downloads.log}" }
    }

    @JvmStatic fun r2_switchDuringResolveOfChosenEpisode(): String {
        boot()
        Meta.cached = season(3)
        Resolver.hold = true
        startSeason()
        TestMain.drain()                    // E1 (the chosen stream) is being resolved in A
        check(Resolver.calls.size == 1, "expected one resolve, got ${Resolver.calls.map { it.first }}")
        Profiles.selectProfile(B)
        TestMain.drain()
        Resolver.calls[0].second.complete(true)   // the late answer
        TestMain.drain()
        check(dl(B).isEmpty(), "download landed in B: ${dl(B)}")
        check(eps(A).size == 3, "A lost queue entries: ${eps(A)}")
        // The chosen episode was stopped by the switch, not by its stream: it waits, not "needs a choice".
        check(eps(A).firstOrNull() == "E1:Waiting", "the chosen episode is ${eps(A).firstOrNull()}, not E1:Waiting")
        val afterSwitch = "A queue=${eps(A)} A dl=${dl(A)} B dl=${dl(B)}"
        // Back to A: the queue goes on in A, nothing twice.
        Resolver.hold = false
        Profiles.selectProfile(A)
        TestMain.drain()
        check(dl(B).isEmpty(), "download landed in B after return: ${dl(B)}")
        // Back in A it is searched again (fresh links) and downloaded once, in A.
        check(dl(A).contains("tt1:E1"), "E1 was not downloaded in A after the return: ${dl(A)}")
        check(Downloads.log.count { it == "P1:tt1:S1E1" } == 1, "E1 handed to the downloader ${Downloads.log.count { it == "P1:tt1:S1E1" }} times")
        return result("A->B during resolveToPlayableStream (chosen episode)") { "after switch: $afterSwitch | back in A: queue=${eps(A)} dl=${dl(A)} log=${Downloads.log}" }
    }

    /** E1 downloads in A, E2's resolve is held. */
    private fun e2Resolving() {
        boot()
        Meta.cached = season(3)
        startSeason()
        TestMain.drain()
        check(dl(A) == listOf("tt1:E1"), "setup: E1 not downloading in A: ${dl(A)}")
        Resolver.hold = true
        Downloads.finish(Downloads.storedFor(A).first().id)
        TestMain.drain()
        check(Resolver.calls.map { it.first } == listOf("S1E2"), "setup: E2 not resolving: ${Resolver.calls.map { it.first }}")
    }

    @JvmStatic fun r3_switchDuringResolveOfQueuedEpisode(): String {
        e2Resolving()
        Profiles.selectProfile(B)
        TestMain.drain()
        Resolver.calls[0].second.complete(true)    // late answer for A's E2, while B is active
        TestMain.drain()
        check(dl(B).isEmpty(), "download landed in B: ${dl(B)}")
        check(eps(A) == listOf("E2:Waiting", "E3:Waiting"), "A's E2/E3 not waiting: ${eps(A)}")
        return result("A->B during resolveToPlayableStream (queued episode)") { "A queue=${eps(A)} A dl=${dl(A)} B dl=${dl(B)} log=${Downloads.log}" }
    }

    @JvmStatic fun r4_abaLateOldAnswerAfterNewSearch(): String {
        e2Resolving()
        Profiles.selectProfile(B); TestMain.drain()   // A's search is cancelled
        Profiles.selectProfile(A); TestMain.drain()   // A searches E2 again
        check(Resolver.calls.size == 2, "expected a second resolve after A->B->A: ${Resolver.calls.map { it.first }}")
        Resolver.calls[1].second.complete(true); TestMain.drain()   // the new answer
        Resolver.calls[0].second.complete(true); TestMain.drain()   // the old operation's answer, late
        val e2 = Downloads.log.count { it.endsWith("E2") }
        check(e2 == 1, "E2 handed to the downloader $e2 times: ${Downloads.log}")
        check(dl(B).isEmpty(), "download landed in B: ${dl(B)}")
        return result("A->B->A, old answer arrives after the new one") { "A queue=${eps(A)} A dl=${dl(A)} log=${Downloads.log}" }
    }

    @JvmStatic fun r5_abaWithoutMainRunningBetween(): String {
        e2Resolving()
        // Both switches before the main thread runs the queue's collector (StateFlow keeps only A).
        Profiles.selectProfile(B)
        Profiles.selectProfile(A)
        TestMain.drain()
        val searches = Resolver.calls.size
        Resolver.calls.forEach { it.second.complete(true) }
        TestMain.drain()
        val e2 = Downloads.log.count { it.endsWith("E2") }
        check(e2 == 1, "E2 handed to the downloader $e2 times: ${Downloads.log}")
        check(dl(B).isEmpty(), "download landed in B: ${dl(B)}")
        return result("A->B->A within one main-thread turn, late answer") { "resolves=$searches A queue=${eps(A)} A dl=${dl(A)} log=${Downloads.log}" }
    }

    @JvmStatic fun r6_abaOldAnswerFirst(): String {
        e2Resolving()
        Profiles.selectProfile(B); TestMain.drain()
        Profiles.selectProfile(A); TestMain.drain()
        Resolver.calls[0].second.complete(true); TestMain.drain()   // old operation answers first
        Resolver.calls.drop(1).forEach { it.second.complete(true) }; TestMain.drain()
        val e2 = Downloads.log.count { it.endsWith("E2") }
        check(e2 == 1, "E2 handed to the downloader $e2 times: ${Downloads.log}")
        return result("A->B->A, old answer arrives first") { "resolves=${Resolver.calls.size} A queue=${eps(A)} A dl=${dl(A)} log=${Downloads.log}" }
    }

    @JvmStatic fun r7_removeThenLateAnswer(): String {
        e2Resolving()
        val e2 = all().first { it.episodeNumber == 2 }
        Queue.remove(e2.id); TestMain.drain()
        Resolver.calls[0].second.complete(true); TestMain.drain()
        check(Downloads.log.none { it.endsWith("E2") }, "removed E2 was downloaded: ${Downloads.log}")
        check(all().none { it.episodeNumber == 2 }, "removed E2 came back: ${eps(A)}")
        // clear() while E3 resolves, then its late answer.
        val e3Calls = Resolver.calls.filter { it.first == "S1E3" }
        check(e3Calls.size == 1, "E3 did not start after removing E2: ${Resolver.calls.map { it.first }}")
        Queue.clear(); TestMain.drain()
        e3Calls.forEach { it.second.complete(true) }; TestMain.drain()
        check(Downloads.log.none { it.endsWith("E3") }, "cleared E3 was downloaded: ${Downloads.log}")
        return result("cancelled job (remove / clear), then late answer") { "A queue=${eps(A)} A dl=${dl(A)} log=${Downloads.log}" }
    }

    @JvmStatic fun r8_restartPhase1(): String {
        boot()
        Meta.cached = season(3)
        startSeason("tt1"); TestMain.drain()          // A: tt1 E1 downloading, E2 E3 waiting
        Profiles.selectProfile(B); TestMain.drain()
        Meta.cached = season(2, prefix = "tt2")
        startSeason("tt2"); TestMain.drain()          // B: tt2 E1 downloading, E2 waiting
        return result("restart phase 1 (two profiles queued)") { "A=${eps(A)} B=${eps(B, "tt2")} A dl=${dl(A)} B dl=${dl(B)}" }
    }

    @JvmStatic fun r8_restartPhase2(): String {
        boot(B)                                       // cold start, remembered profile B
        check(Queue.entries.value.all { it.profileId == B && it.parentMetaId == "tt2" }, "B sees other entries: ${Queue.entries.value}")
        check(eps(A) == listOf("E1:Downloading", "E2:Waiting", "E3:Waiting"), "A's queue changed while B was active: ${eps(A)}")
        check(eps(B, "tt2") == listOf("E1:Downloading", "E2:Waiting"), "B's queue changed: ${eps(B, "tt2")}")
        Downloads.finish(Downloads.storedFor(B).first().id); TestMain.drain()
        check(dl(B) == listOf("tt2:E1", "tt2:E2"), "B did not move on: ${dl(B)}")
        Profiles.selectProfile(A); TestMain.drain()
        check(eps(A) == listOf("E1:Downloading", "E2:Waiting", "E3:Waiting"), "A's queue wrong after switch: ${eps(A)}")
        Downloads.finish(Downloads.storedFor(A).first().id); TestMain.drain()
        check(dl(A) == listOf("tt1:E1", "tt1:E2"), "A did not move on in A: ${dl(A)}")
        check(dl(B).none { it.startsWith("tt1") }, "A's episodes landed in B: ${dl(B)}")
        return result("restart with queues of two profiles") { "A=${eps(A)} B=${eps(B, "tt2")} A dl=${dl(A)} B dl=${dl(B)}" }
    }

    // ---- Build 15: concurrency cases asked for by the review (prove a lost update or a duplicate
    // enqueue before changing the queue's design).

    @JvmStatic fun r9_twoSeasonsStartedTogether(): String {
        boot()
        Meta.byId["tt1"] = season(3)
        Meta.byId["tt2"] = season(2, prefix = "tt2")
        startSeason("tt1"); startSeason("tt2")       // both before the main thread runs
        TestMain.drain()
        check(eps(A) == listOf("E1:Downloading", "E2:Waiting", "E3:Waiting"), "tt1 queue: ${eps(A)}")
        check(eps(A, "tt2") == listOf("E1:Downloading", "E2:Waiting"), "tt2 queue: ${eps(A, "tt2")}")
        for (round in 1..4) {
            Downloads.storedFor(A).filter { it.status == DownloadStatus.Downloading }.forEach { Downloads.finish(it.id) }
            TestMain.drain()
        }
        check(dl(A) == listOf("tt1:E1", "tt1:E2", "tt1:E3", "tt2:E1", "tt2:E2"), "not every episode downloaded once: ${dl(A)}")
        return result("two seasons started in the same main-thread turn") { "A dl=${dl(A)} log=${Downloads.log}" }
    }

    @JvmStatic fun r10_sameSeasonStartedTwice(): String {
        boot()
        Meta.cached = season(3)
        startSeason("tt1"); startSeason("tt1")       // a double tap
        TestMain.drain()
        check(eps(A) == listOf("E1:Downloading", "E2:Waiting", "E3:Waiting"), "queue after a double start: ${eps(A)}")
        return result("the same season started twice at once") { "A=${eps(A)} log=${Downloads.log}" }
    }

    @JvmStatic fun r11_pauseAndResume(): String {
        // Build 15 contract: a paused episode is skipped; the next one goes on.
        boot()
        Meta.cached = season(3)
        startSeason(); TestMain.drain()
        val e1 = Downloads.storedFor(A).single()
        Downloads.pauseDownload(e1.id); TestMain.drain()
        check(eps(A) == listOf("E1:Paused", "E2:Downloading", "E3:Waiting"), "E2 did not follow the paused E1: ${eps(A)}")
        Downloads.resumeDownload(e1.id); TestMain.drain()      // while E2 downloads
        check(eps(A) == listOf("E1:Resuming", "E2:Downloading", "E3:Waiting"), "E1 not held for its turn: ${eps(A)}")
        check(Downloads.resumed.isEmpty(), "E1 ran next to E2: ${Downloads.resumed}")
        Downloads.resumeDownload(e1.id); TestMain.drain()      // a second tap changes nothing
        check(Downloads.resumed.isEmpty(), "second tap started E1: ${Downloads.resumed}")
        val e2 = Downloads.storedFor(A).first { it.episodeNumber == 2 }
        check(e2.status == DownloadStatus.Downloading, "E2 was stopped by the resume: $e2")
        Downloads.finish(e2.id); TestMain.drain()
        check(Downloads.resumed == listOf(e1.id), "E1 did not continue (same download) after E2: ${Downloads.resumed}")
        check(eps(A) == listOf("E1:Downloading", "E3:Waiting"), "E3 started before E1 finished: ${eps(A)}")
        Downloads.finish(e1.id); TestMain.drain()
        check(dl(A) == listOf("tt1:E1", "tt1:E2", "tt1:E3"), "E3 did not follow once: ${dl(A)}")
        return result("pause E1 -> E2 goes on; resume E1 during E2 waits its turn") { "A=${eps(A)} log=${Downloads.log} resumed=${Downloads.resumed}" }
    }

    @JvmStatic fun r12_finishWhileAnEpisodeIsRemoved(): String {
        boot()
        Meta.cached = season(3)
        startSeason(); TestMain.drain()
        val e2 = all().first { it.episodeNumber == 2 }
        Queue.remove(e2.id)                            // same main-thread turn as the finish below
        Downloads.finish(Downloads.storedFor(A).single().id)
        TestMain.drain()
        check(Downloads.log.none { it.endsWith("E2") }, "removed E2 was downloaded: ${Downloads.log}")
        check(dl(A) == listOf("tt1:E1", "tt1:E3"), "E3 did not follow E1: ${dl(A)}")
        return result("download finishes while the next episode is removed") { "A=${eps(A)} log=${Downloads.log}" }
    }

    @JvmStatic fun r13_profileSwitchWhileTwoSeasonsRun(): String {
        boot()
        Meta.byId["tt1"] = season(2)
        Meta.byId["tt2"] = season(2, prefix = "tt2")
        startSeason("tt1"); TestMain.drain()
        Profiles.selectProfile(B); TestMain.drain()
        startSeason("tt2"); TestMain.drain()
        Downloads.storedFor(B).forEach { Downloads.finish(it.id) }; TestMain.drain()
        Profiles.selectProfile(A); TestMain.drain()
        Downloads.storedFor(A).forEach { Downloads.finish(it.id) }; TestMain.drain()
        check(dl(A) == listOf("tt1:E1", "tt1:E2"), "A: ${dl(A)}")
        check(dl(B) == listOf("tt2:E1", "tt2:E2"), "B: ${dl(B)}")
        return result("two profiles, each with its own season, switching between") { "A dl=${dl(A)} B dl=${dl(B)}" }
    }

    // ---- Build 15: pause contract (episode, season, all).

    private fun running(profile: Int) = Downloads.storedFor(profile).filter { it.status == DownloadStatus.Downloading }

    @JvmStatic fun r14_pauseOneSeasonOfTwo(): String {
        boot()
        Meta.byId["tt1"] = season(2)
        Meta.byId["tt2"] = season(2, prefix = "tt2")
        startSeason("tt1"); TestMain.drain()
        startSeason("tt2"); TestMain.drain()
        check(eps(A) == listOf("E1:Downloading", "E2:Waiting") && eps(A, "tt2") == listOf("E1:Downloading", "E2:Waiting"), "start (each chosen episode starts at once): ${eps(A)} ${eps(A, "tt2")}")
        Queue.pauseSeason("tt1", 1); TestMain.drain()
        check(eps(A) == listOf("E1:Paused", "E2:Waiting"), "tt1 not paused: ${eps(A)}")
        check(all().first { it.parentMetaId == "tt1" && it.episodeNumber == 1 }.pausedBy.name == "Season", "E1 not paused by its season")
        check(eps(A, "tt2").first() == "E1:Downloading", "the other season did not go on: ${eps(A, "tt2")}")
        running(A).forEach { Downloads.finish(it.id) }; TestMain.drain()
        running(A).forEach { Downloads.finish(it.id) }; TestMain.drain()
        check(dl(A).none { it == "tt1:E2" }, "the paused season started its next episode: ${dl(A)}")
        check(eps(A, "tt2").isEmpty(), "the other season did not finish: ${eps(A, "tt2")}")
        Queue.resumeSeason("tt1", 1); TestMain.drain()
        check(eps(A).first() == "E1:Downloading" && Downloads.resumed.size == 1, "season resume did not continue E1: ${eps(A)} ${Downloads.resumed}")
        running(A).forEach { Downloads.finish(it.id) }; TestMain.drain()
        check(dl(A).contains("tt1:E2"), "tt1 E2 did not follow: ${dl(A)}")
        return result("pause one season of two, then resume it") { "tt1=${eps(A)} tt2=${eps(A, "tt2")} dl=${dl(A)} resumed=${Downloads.resumed}" }
    }

    @JvmStatic fun r15_pauseAllHoldsEverythingAndResumeAllSkipsSinglePauses(): String {
        boot()
        Meta.cached = season(3)
        startSeason(); TestMain.drain()
        val e1 = Downloads.storedFor(A).single()
        Downloads.pauseDownload(e1.id); TestMain.drain()              // E1 paused by the user -> E2 runs
        Downloads.addManual("movie", "tt9"); TestMain.drain()          // a download outside the queue
        Queue.pauseAll(); TestMain.drain()
        check(running(A).isEmpty(), "pause all left a download running: ${running(A)}")
        check(eps(A) == listOf("E1:Paused", "E2:Paused", "E3:Waiting"), "queue after pause all: ${eps(A)}")
        Queue.remove(all().first { it.episodeNumber == 2 }.id); TestMain.drain()   // even a freed slot starts nothing
        check(dl(A).none { it == "tt1:E3" }, "pause all let E3 start: ${dl(A)}")
        check(Queue.holds.value.allPausedProfiles == setOf(A), "pause all not shown: ${Queue.holds.value}")
        // Restore E2 for the resume check.
        return result("pause all holds every start") { "A=${eps(A)} dl=${dl(A)} holds=${Queue.holds.value}" }
    }

    @JvmStatic fun r16_resumeAll(): String {
        boot()
        Meta.cached = season(3)
        startSeason(); TestMain.drain()
        val e1 = Downloads.storedFor(A).single()
        Downloads.pauseDownload(e1.id); TestMain.drain()              // E1: paused by the user
        val e2 = Downloads.storedFor(A).first { it.episodeNumber == 2 }
        Downloads.addManual("movie", "tt9"); TestMain.drain()
        Queue.pauseAll(); TestMain.drain()
        Queue.resumeAll(); TestMain.drain()
        check(Downloads.resumed.toSet() == setOf(e2.id, "movie"), "resume all resumed the wrong downloads: ${Downloads.resumed}")
        check(Downloads.storedFor(A).first { it.id == e1.id }.status == DownloadStatus.Paused, "resume all resumed E1 paused on its own")
        check(eps(A) == listOf("E1:Paused", "E2:Downloading", "E3:Waiting"), "queue after resume all: ${eps(A)}")
        Downloads.finish(e2.id); TestMain.drain()
        check(eps(A) == listOf("E1:Paused", "E3:Downloading"), "E3 did not follow E2: ${eps(A)}")
        return result("resume all continues only what pause all paused") { "A=${eps(A)} resumed=${Downloads.resumed}" }
    }

    @JvmStatic fun r17_restartPhase1(): String {
        boot()
        Meta.cached = season(3)
        startSeason(); TestMain.drain()
        val e1 = Downloads.storedFor(A).single()
        Downloads.pauseDownload(e1.id); TestMain.drain()              // E1 paused, E2 runs
        Downloads.resumeDownload(e1.id); TestMain.drain()             // E1 waits for its turn
        Queue.pauseAll(); TestMain.drain()                             // E2 and the waiting E1 paused by "all"
        return result("restart phase 1 (paused, waiting to resume, pause all)") { "A=${eps(A)} holds=${Queue.holds.value}" }
    }

    @JvmStatic fun r17_restartPhase2(): String {
        boot()                                                          // cold start
        check(eps(A) == listOf("E1:Paused", "E2:Paused", "E3:Waiting"), "states lost on restart: ${eps(A)}")
        check(Queue.holds.value.allPausedProfiles == setOf(A), "pause all lost on restart")
        check(running(A).isEmpty() && Downloads.log.isEmpty() && Downloads.resumed.isEmpty(), "something started after the restart")
        Queue.resumeAll(); TestMain.drain()
        // Both were paused by "all": E1 first in queue order, then E2, never both at once.
        check(running(A).size == 1 && eps(A) == listOf("E1:Downloading", "E2:Resuming", "E3:Waiting"), "after resume all: ${eps(A)}")
        running(A).forEach { Downloads.finish(it.id) }; TestMain.drain()
        check(eps(A) == listOf("E2:Downloading", "E3:Waiting") && running(A).size == 1, "E2 did not continue next: ${eps(A)}")
        return result("restart keeps pauses and pause all") { "A=${eps(A)} resumed=${Downloads.resumed}" }
    }

    @JvmStatic fun r18_pauseAllIsPerProfile(): String {
        boot()
        Meta.byId["tt1"] = season(2)
        Meta.byId["tt2"] = season(2, prefix = "tt2")
        startSeason("tt1"); TestMain.drain()
        Queue.pauseAll(); TestMain.drain()
        Profiles.selectProfile(B); TestMain.drain()
        check(Queue.holds.value.allPausedProfiles.isEmpty(), "B shows A's pause all")
        startSeason("tt2"); TestMain.drain()
        Downloads.storedFor(B).forEach { Downloads.finish(it.id) }; TestMain.drain()
        check(dl(B) == listOf("tt2:E1", "tt2:E2"), "B's queue was held by A's pause all: ${dl(B)}")
        Profiles.selectProfile(A); TestMain.drain()
        check(running(A).isEmpty() && dl(A) == listOf("tt1:E1"), "A started while paused: ${dl(A)} ${running(A)}")
        Queue.resumeAll(); TestMain.drain()
        check(running(A).size == 1, "A did not resume: ${eps(A)}")
        return result("pause all in A, B goes on, A waits until resume all") { "A=${eps(A)} B dl=${dl(B)}" }
    }

    // ---- Build 15: the owner's rule for "pause all" (2026-11-20): it holds every download of the
    // active profile (season episodes, films, single episodes), starts nothing new until "resume all",
    // keeps partial files, and never resumes a download the user paused on its own.

    /** A download asked for from a stream screen, outside any season queue (a film). */
    private fun newFilm(meta: String) = Downloads.enqueueFromStream(
        "movie", meta, meta, "movie", "Film", null, null, null, null, null, null, null,
        StreamItem(name = "Film 1080p WEB", addonId = "addon", playableDirectUrl = "https://x/$meta.mkv"),
    )
    private fun statusOf(id: String) = Downloads.storedFor(A).first { it.id == id }.status

    @JvmStatic fun r19_newDownloadsWaitForResumeAll(): String {
        boot()
        Meta.cached = season(2)
        Downloads.addManual("movie", "tt9"); TestMain.drain()
        Queue.pauseAll(); TestMain.drain()
        val film = newFilm("tt8"); TestMain.drain()
        val filmId = Downloads.storedFor(A).first { it.parentMetaId == "tt8" }.id
        check(film == DownloadEnqueueResult.Held, "a new film during pause all returned $film, not Held")
        check(statusOf(filmId) == DownloadStatus.Paused, "the new film started during pause all")
        startSeason(); TestMain.drain()
        check(running(A).isEmpty(), "a new season started during pause all: ${running(A)}")
        check(eps(A) == listOf("E1:Paused", "E2:Waiting"), "new season during pause all: ${eps(A)}")
        check(all().first { it.episodeNumber == 1 }.pausedBy.name == "All", "the chosen episode is not held by pause all")
        check(lastResult is SeasonStartResult.Queued, "the season was not queued: $lastResult")
        Queue.resumeAll(); TestMain.drain()
        val e1 = all().first { it.episodeNumber == 1 }.downloadId!!
        check(Downloads.resumed.toSet() == setOf("movie", filmId, e1), "resume all resumed ${Downloads.resumed}")
        check(eps(A) == listOf("E1:Downloading", "E2:Waiting"), "queue after resume all: ${eps(A)}")
        Downloads.finish(e1); TestMain.drain()
        check(eps(A) == listOf("E2:Downloading"), "E2 did not follow: ${eps(A)}")
        return result("new film and new season during pause all wait for resume all") { "A=${eps(A)} resumed=${Downloads.resumed}" }
    }

    @JvmStatic fun r20_singlePauseDuringPauseAllIsKept(): String {
        boot()
        Downloads.addManual("app", "tt8")
        Downloads.addManual("notif", "tt9"); TestMain.drain()
        Queue.pauseAll(); TestMain.drain()
        // The user resumes each one on its own (in the app, from the notification), then pauses it again.
        Downloads.resumeDownload("app"); TestMain.drain()
        Downloads.notificationResume("notif"); TestMain.drain()
        check(running(A).size == 2, "a resume of one download was refused: ${running(A)}")
        Downloads.pauseDownload("app"); Downloads.pauseDownload("notif"); TestMain.drain()
        Queue.resumeAll(); TestMain.drain()
        check(statusOf("app") == DownloadStatus.Paused && statusOf("notif") == DownloadStatus.Paused,
            "resume all resumed a download paused on its own: ${running(A)}")
        check(Downloads.resumed == listOf("app", "notif"), "resumes: ${Downloads.resumed}")
        return result("a download paused on its own after pause all stays paused") { "resumed=${Downloads.resumed}" }
    }

    @JvmStatic fun r21_backgroundJobStartsNothingDuringPauseAll(): String {
        boot()
        Meta.cached = season(3)
        startSeason(); TestMain.drain()
        val e1 = Downloads.storedFor(A).single()
        Queue.pauseAll(); TestMain.drain()
        // The download job that ran E1 asks the queue for the next episode after the pause.
        var next: Boolean? = null
        Queue.launch { next = Queue.continueInBackground(finishedDownloadId = null, pausedDownloadId = e1.id) }
        TestMain.drain()
        check(next == false, "the background job was handed another episode: $next")
        check(running(A).isEmpty() && Downloads.log.size == 1, "something started in the background: ${Downloads.log}")
        check(statusOf(e1.id) == DownloadStatus.Paused, "E1 lost its pause")
        return result("a download job continues nothing during pause all") { "A=${eps(A)} log=${Downloads.log}" }
    }

    // Owner's choice (2026-11-21): an episode the first add-on lacks comes from another add-on, but
    // only in the same resolution and release type; otherwise it asks the user (NeedsChoice).
    private fun twoAddons(otherName: String) {
        com.nuvio.app.features.addons.AddonRepository.uiState.value = com.nuvio.app.features.addons.AddonsUiState(listOf(
            com.nuvio.app.features.streams.Addon(com.nuvio.app.features.streams.Manifest("addon", "Addon"), "Addon"),
            com.nuvio.app.features.streams.Addon(com.nuvio.app.features.streams.Manifest("other", "Other"), "Other"),
        ))
        com.nuvio.app.features.streams.StreamSearchSessions.answer = { addon, videoId ->
            when {
                addon == "addon" && videoId.endsWith(":2") -> emptyList()
                addon == "addon" -> listOf(StreamItem(name = "Show 1080p WEB", addonId = "addon", playableDirectUrl = "https://x/$videoId.mkv"))
                else -> listOf(StreamItem(name = "Show $otherName", addonId = "other", playableDirectUrl = "https://y/$videoId.mkv"))
            }
        }
    }

    @JvmStatic fun r22_missingEpisodeComesFromAnotherAddonInTheSameQuality(): String {
        boot()
        twoAddons("S01E02 1080p WEB")
        Meta.cached = season(3)
        startSeason(); TestMain.drain()
        Downloads.finish(Downloads.storedFor(A).first().id); TestMain.drain()
        check(dl(A).contains("tt1:E2"), "E2 not downloaded from the other add-on: ${dl(A)} queue=${eps(A)}")
        val asked = com.nuvio.app.features.streams.StreamSearchSessions.askedAddons
        check(asked.any { it == listOf("addon") } && asked.any { it == listOf("other") }, "add-ons asked: $asked")
        return result("missing episode, other add-on, same quality") { "A=${eps(A)} dl=${dl(A)} asked=$asked" }
    }

    @JvmStatic fun r23_anotherAddonInAnotherQualityAsksTheUser(): String {
        boot()
        twoAddons("S01E02 720p WEB")
        Meta.cached = season(3)
        startSeason(); TestMain.drain()
        Downloads.finish(Downloads.storedFor(A).first().id); TestMain.drain()
        check(!dl(A).contains("tt1:E2"), "E2 downloaded in another quality: ${dl(A)}")
        check(eps(A).contains("E2:NeedsChoice"), "E2 does not ask the user: ${eps(A)}")
        return result("missing episode, other add-on only in another quality") { "A=${eps(A)} dl=${dl(A)}" }
    }
}
