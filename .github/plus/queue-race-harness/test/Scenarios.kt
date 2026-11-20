package harness

import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver as Resolver
import com.nuvio.app.features.details.MetaDetailsRepository as Meta
import com.nuvio.app.features.details.season
import com.nuvio.app.features.downloads.DownloadsRepository as Downloads
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
}
