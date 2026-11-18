package com.nuvio.app.features.downloads

import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.isNetworkOffline
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.InstalledStreamAddonTarget
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLoadCompletion
import com.nuvio.app.features.streams.StreamSearchSessions
import com.nuvio.app.features.streams.StreamSearchTargets
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class SeasonQueueEntryState {
    /** Waiting for its turn. */
    Waiting,

    /** Looking for a source that matches the one picked for the first episode. */
    Searching,

    /** Handed to the downloader; leaves the queue once that download stops running. */
    Downloading,

    /** No matching source was found; the user downloads it manually or retries. */
    NeedsChoice,
}

/** The source the user picked for the first episode; later episodes look for the same release. */
@Serializable
data class SeasonQueueReference(
    val addonId: String,
    val addonName: String,
    val bingeGroup: String? = null,
    val text: String,
)

@Serializable
data class SeasonQueueEntry(
    val id: String,
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val videoId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val reference: SeasonQueueReference,
    val state: SeasonQueueEntryState = SeasonQueueEntryState.Waiting,
    val downloadId: String? = null,
)

enum class SeasonDownloadScope {
    /** The chosen episode and every later one in the season. */
    RestOfSeason,

    /** Every episode of the season. */
    WholeSeason,
}

@Serializable
private data class StoredSeasonQueue(val entries: List<SeasonQueueEntry> = emptyList())

/**
 * Downloads a season one episode at a time. Each episode's source is looked up right before its
 * turn (so debrid links are fresh), using the add-on, binge group and release details of the
 * source picked for the first episode.
 */
object SeasonDownloadQueue {
    private const val storageKey = "season_queue"
    private const val searchTimeoutMs = 90_000L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _entries = MutableStateFlow<List<SeasonQueueEntry>>(emptyList())
    val entries: StateFlow<List<SeasonQueueEntry>> = _entries.asStateFlow()

    private var started = false
    /** Running source searches, one per queue entry, so removing an entry stops only its own. */
    private val searchJobs = mutableMapOf<String, Job>()
    private var ordinal = 0L
    private var appVisible = true

    /**
     * True on platforms that may start a new download while the app is in the background. On
     * Android 14 and later this is false: the next episode then runs inside the download job that
     * is still active (see [continueInBackground]).
     */
    internal var startsInBackground = false

    /** Loads the saved queue and keeps it moving as downloads finish. Safe to call often. */
    fun ensureStarted() {
        if (started) return
        started = true
        _entries.value = DownloadsStorage.loadExtra(storageKey)
            ?.let { runCatching { json.decodeFromString<StoredSeasonQueue>(it).entries }.getOrNull() }
            .orEmpty()
            // A search cut short by the app closing starts over.
            .map { if (it.state == SeasonQueueEntryState.Searching) it.copy(state = SeasonQueueEntryState.Waiting) else it }
        scope.launch {
            DownloadsRepository.uiState.collect { pump() }
        }
        scope.launch {
            AppForegroundMonitor.events().collect { visibility ->
                appVisible = visibility == AppVisibility.Foreground
                // Coming back to the app picks up episodes that waited while it was hidden.
                if (appVisible) pump()
            }
        }
    }

    /**
     * Called by a download job that just finished a queue episode while the app may be hidden.
     * Settles that episode, then looks up and hands over the next one so the job can keep
     * running it. Returns true when an episode was handed to the downloader.
     */
    internal suspend fun continueInBackground(finishedDownloadId: String?): Boolean = withContext(Dispatchers.Main) {
        continueInBackgroundOnMain(finishedDownloadId)
    }

    /** The queue state is only touched on the main thread. */
    private suspend fun continueInBackgroundOnMain(finishedDownloadId: String?): Boolean {
        // Only a warm process has the add-ons, profile and downloads loaded; otherwise the queue
        // continues when the app is opened.
        if (!started || !DownloadsRepository.isLoaded) return false
        if (AddonRepository.uiState.value.addons.isEmpty()) return false
        if (finishedDownloadId != null) {
            update { entries ->
                entries.filterNot {
                    it.state == SeasonQueueEntryState.Downloading && it.downloadId == finishedDownloadId
                }
            }
        }
        while (true) {
            val downloads = DownloadsRepository.uiState.value.items.associateBy { it.id }
            val current = _entries.value
            val busy = searchJobs.isNotEmpty() || current.any {
                it.state == SeasonQueueEntryState.Downloading &&
                    it.downloadId?.let(downloads::get)?.status == DownloadStatus.Downloading
            }
            if (busy || isNetworkOffline()) return false
            val next = current.firstOrNull { it.state == SeasonQueueEntryState.Waiting } ?: return false
            if (trackSearch(next.id) { startEntry(next) }) return true
        }
    }

    /**
     * Downloads [referenceStream] for the chosen episode right away and queues the other episodes
     * of the season. Returns how many episodes were added (the chosen one included).
     */
    suspend fun startSeason(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        currentVideoId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        currentEpisodeTitle: String?,
        currentEpisodeThumbnail: String?,
        referenceStream: StreamItem,
        downloadScope: SeasonDownloadScope,
    ): Int {
        ensureStarted()
        DownloadsRepository.ensureLoaded()
        val reference = SeasonQueueReference(
            addonId = referenceStream.addonId,
            addonName = referenceStream.addonName,
            bingeGroup = referenceStream.behaviorHints.bingeGroup?.trim()?.takeIf { it.isNotEmpty() },
            text = SeasonStreamMatcher.describe(referenceStream),
        )
        val meta = MetaDetailsRepository.peek(parentMetaType, parentMetaId)
            ?: runCatching { MetaDetailsRepository.fetch(parentMetaType, parentMetaId) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        val seasonEpisodes = meta?.videos.orEmpty()
            .filter { it.season == seasonNumber && it.episode != null && it.available }
            .distinctBy { it.episode }
            .sortedBy { it.episode }
        val currentEntry = baseEntry(
            contentType, parentMetaId, parentMetaType, title, logo, poster, background, reference,
            videoId = currentVideoId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = currentEpisodeTitle,
            episodeThumbnail = currentEpisodeThumbnail,
        )
        val others = seasonEpisodes
            .filter { video ->
                val episode = video.episode ?: return@filter false
                episode != episodeNumber &&
                    (downloadScope == SeasonDownloadScope.WholeSeason || episode > episodeNumber)
            }
            .map { video ->
                baseEntry(
                    contentType, parentMetaId, parentMetaType, title, logo, poster, background, reference,
                    videoId = video.id,
                    seasonNumber = seasonNumber,
                    episodeNumber = video.episode ?: 0,
                    episodeTitle = video.title.takeIf { it.isNotBlank() },
                    episodeThumbnail = video.thumbnail,
                )
            }
        val wanted = (listOf(currentEntry) + others)
            .filter { entry -> needsDownload(entry) && !isQueued(entry) }
            .sortedBy { it.episodeNumber }
        if (wanted.isEmpty()) return 0

        val currentWanted = wanted.firstOrNull { it.episodeNumber == episodeNumber }
        val queued = wanted.filterNot { it === currentWanted }
        update { it + queued }
        if (currentWanted != null) {
            // The chosen episode uses exactly the stream the user picked.
            update { listOf(currentWanted) + it }
            val handedOff = trackSearch(currentWanted.id) { safeHandOff(currentWanted, referenceStream) }
            if (!handedOff) setState(currentWanted.id, SeasonQueueEntryState.NeedsChoice)
        }
        pump()
        return wanted.size
    }

    /**
     * Runs [block] in the queue's own scope, so a season request survives leaving the screen
     * it was made from.
     */
    fun launch(block: suspend () -> Unit): Job = scope.launch { block() }

    fun remove(entryId: String) {
        // Stop the search running for this episode only, so it is not downloaded afterwards.
        searchJobs.remove(entryId)?.cancel()
        update { entries -> entries.filterNot { it.id == entryId } }
        pump()
    }

    /** Looks again for a source for an episode that had none. */
    fun retry(entryId: String) {
        update { entries ->
            entries.map {
                if (it.id == entryId && it.state == SeasonQueueEntryState.NeedsChoice) {
                    it.copy(state = SeasonQueueEntryState.Waiting)
                } else it
            }
        }
        pump()
    }

    /** Drops every episode that has not started; running downloads keep going. */
    fun clear() {
        searchJobs.values.toList().forEach(Job::cancel)
        searchJobs.clear()
        update { entries -> entries.filter { it.state == SeasonQueueEntryState.Downloading } }
        pump()
    }

    private fun pump() {
        if (!started) return
        val downloads = DownloadsRepository.uiState.value.items.associateBy { it.id }
        // Episodes whose download stopped running (finished, failed, paused or deleted) leave the queue.
        val settled = _entries.value
            .filterNot { entry ->
                entry.state == SeasonQueueEntryState.Downloading &&
                    entry.downloadId?.let(downloads::get)?.status != DownloadStatus.Downloading
            }
            // A search whose job is gone (cancelled or interrupted) goes back to waiting.
            .map { entry ->
                if (entry.state == SeasonQueueEntryState.Searching && entry.id !in searchJobs) {
                    entry.copy(state = SeasonQueueEntryState.Waiting)
                } else entry
            }
        if (settled != _entries.value) update { settled }

        val busy = searchJobs.isNotEmpty() || settled.any { it.state == SeasonQueueEntryState.Downloading }
        if (busy) return
        if (isNetworkOffline()) return
        // Android cannot start a download job from the background; the running job continues instead.
        if (!appVisible && !startsInBackground) return
        val next = settled.firstOrNull { it.state == SeasonQueueEntryState.Waiting } ?: return
        scope.launch {
            trackSearch(next.id) { startEntry(next) }
            pump()
        }
    }

    /**
     * Finds the closest sources for [entry] and starts the first one that resolves (a debrid
     * source that is not cached, for example, falls through to the next). Leaves the entry as
     * Downloading or NeedsChoice and returns true when a download started.
     */
    private suspend fun startEntry(entry: SeasonQueueEntry): Boolean {
        val candidates = try {
            withContext(Dispatchers.Default) { findMatchingStreams(entry) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        for (candidate in candidates) {
            if (safeHandOff(entry, candidate)) return true
        }
        setState(entry.id, SeasonQueueEntryState.NeedsChoice)
        return false
    }

    /**
     * Runs the source search of one entry as its own job (see [remove]). Returns false when the
     * search found nothing or was cancelled because the entry was removed.
     */
    private suspend fun trackSearch(entryId: String, block: suspend () -> Boolean): Boolean = coroutineScope {
        val job = async(start = CoroutineStart.LAZY) { block() }
        searchJobs[entryId] = job
        setState(entryId, SeasonQueueEntryState.Searching)
        try {
            job.await()
        } catch (cancelled: CancellationException) {
            // Only this entry's search was cancelled; the caller keeps running.
            if (!job.isCancelled || !isActive) throw cancelled
            false
        } finally {
            if (searchJobs[entryId] === job) searchJobs.remove(entryId)
        }
    }

    private suspend fun safeHandOff(entry: SeasonQueueEntry, stream: StreamItem): Boolean =
        try {
            handOff(entry, stream)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    /** Resolves [stream] to a direct link when needed and starts its download; true on success. */
    private suspend fun handOff(entry: SeasonQueueEntry, stream: StreamItem): Boolean {
        val playable = when (
            val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
                stream = stream,
                season = entry.seasonNumber,
                episode = entry.episodeNumber,
            )
        ) {
            is DirectDebridPlayableResult.Success -> resolved.stream
            else -> null
        }
        if (playable == null) return false
        // The episode may have been removed from the queue while its source was being resolved.
        if (_entries.value.none { it.id == entry.id }) return false
        val result = DownloadsRepository.enqueueFromStream(
            contentType = entry.contentType,
            videoId = entry.videoId,
            parentMetaId = entry.parentMetaId,
            parentMetaType = entry.parentMetaType,
            title = entry.title,
            logo = entry.logo,
            poster = entry.poster,
            background = entry.background,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            episodeTitle = entry.episodeTitle,
            episodeThumbnail = entry.episodeThumbnail,
            stream = playable,
        )
        val download = DownloadsRepository.findDownload(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
        if (
            (result == DownloadEnqueueResult.Started || result == DownloadEnqueueResult.Replaced) &&
            download != null
        ) {
            update { entries ->
                entries.map {
                    if (it.id == entry.id) {
                        it.copy(state = SeasonQueueEntryState.Downloading, downloadId = download.id)
                    } else it
                }
            }
            return true
        }
        return false
    }

    private suspend fun findMatchingStreams(entry: SeasonQueueEntry): List<StreamItem> {
        val type = entry.contentType
        val targets = AddonRepository.uiState.value.addons.enabledAddons().mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            if (!manifest.supportsStream(type, entry.videoId)) return@mapNotNull null
            InstalledStreamAddonTarget(
                addonName = addon.displayTitle.ifBlank { manifest.name },
                addonId = addon.streamAddonInstanceId(manifest.id),
                manifest = manifest,
            )
        }
        // Ask the add-on that supplied the first episode; fall back to all add-ons if it is gone.
        val preferred = targets.filter { it.addonId == entry.reference.addonId }
            .ifEmpty { targets.filter { it.addonName == entry.reference.addonName } }
        val searchTargets = preferred.ifEmpty { targets }
        if (searchTargets.isEmpty()) return emptyList()

        val debridSettings = DebridSettingsRepository.snapshot()
        val streams = mutableListOf<StreamItem>()
        withTimeoutOrNull(searchTimeoutMs) {
            StreamSearchSessions.search(
                profileId = ProfileRepository.activeProfileId,
                targets = StreamSearchTargets(
                    type = type,
                    videoId = entry.videoId,
                    season = entry.seasonNumber,
                    episode = entry.episodeNumber,
                    streamAddons = searchTargets,
                    serverSources = emptyList(),
                    pluginProviderGroups = emptyList(),
                ),
                forceRefresh = false,
            ).collect { completion ->
                if (completion is StreamLoadCompletion.Addon) {
                    DebridStreamPresentation.apply(listOf(completion.group), debridSettings)
                        .forEach { streams += it.streams }
                }
            }
        }
        return SeasonStreamMatcher.rank(streams, entry.reference).take(3)
    }

    private fun needsDownload(entry: SeasonQueueEntry): Boolean {
        val existing = DownloadsRepository.findDownload(entry.parentMetaId, entry.seasonNumber, entry.episodeNumber)
            ?: return true
        // Finished, running and paused downloads are kept; only failed ones are tried again.
        return existing.status == DownloadStatus.Failed
    }

    private fun isQueued(entry: SeasonQueueEntry): Boolean =
        _entries.value.any {
            it.parentMetaId == entry.parentMetaId &&
                it.seasonNumber == entry.seasonNumber &&
                it.episodeNumber == entry.episodeNumber
        }

    private fun baseEntry(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        reference: SeasonQueueReference,
        videoId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String?,
        episodeThumbnail: String?,
    ): SeasonQueueEntry {
        ordinal += 1
        return SeasonQueueEntry(
            id = "${DownloadsClock.nowEpochMs().toString(36)}_${ordinal.toString(36)}",
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            videoId = videoId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            reference = reference,
        )
    }

    private fun setState(entryId: String, state: SeasonQueueEntryState) {
        update { entries -> entries.map { if (it.id == entryId) it.copy(state = state) else it } }
    }

    private fun update(transform: (List<SeasonQueueEntry>) -> List<SeasonQueueEntry>) {
        val updated = transform(_entries.value)
        _entries.value = updated
        DownloadsStorage.saveExtra(storageKey, json.encodeToString(StoredSeasonQueue(updated)))
    }
}

/**
 * Picks the stream for a later episode that is most like the one chosen for the first episode:
 * the same binge group when the add-on provides one, otherwise the same resolution and release
 * type with the closest release name.
 */
internal object SeasonStreamMatcher {
    private const val minimumScore = 0.3
    private val ignoredTokens = Regex("^(s\\d+e\\d+|s\\d+|e\\d+|ep\\d*|episode|season|\\d+(\\.\\d+)?|\\d+(\\.\\d+)?(gb|mb|gib|mib))$")
    private val flagPatterns = mapOf(
        "remux" to Regex("\\bremux\\b"),
        "hdr" to Regex("\\bhdr(10\\+?|10plus)?\\b"),
        "dv" to Regex("\\b(dv|dovi|dolby[ ._-]?vision)\\b"),
        "hevc" to Regex("\\b(hevc|x265|h\\.?265)\\b"),
        "av1" to Regex("\\bav1\\b"),
    )

    fun describe(stream: StreamItem): String =
        listOfNotNull(stream.name, stream.title, stream.description, stream.behaviorHints.filename)
            .joinToString(" ")
            .lowercase()

    /**
     * Acceptable matches, best first. Only streams with the same resolution and release type count;
     * among them, streams of the same binge group lead, then the rest by name similarity. Sources a debrid service
     * reported as not cached are left out, and cached ones are preferred.
     */
    fun rank(streams: List<StreamItem>, reference: SeasonQueueReference): List<StreamItem> {
        val downloadable = streams
            .filter(::isDownloadable)
            .filterNot { it.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED }
        if (downloadable.isEmpty()) return emptyList()

        val referenceText = reference.text
        val referenceResolution = resolution(referenceText)
        val referenceFlags = flags(referenceText)
        val referenceTokens = tokens(referenceText)
        // Resolution and release type (HDR, Dolby Vision, HEVC...) must match, even within a binge group.
        fun sameQuality(stream: StreamItem): Boolean {
            val text = describe(stream)
            if (referenceResolution != null && resolution(text) != referenceResolution) return false
            return flags(text) == referenceFlags
        }
        val sameGroup = reference.bingeGroup?.let { group ->
            downloadable.filter { it.behaviorHints.bingeGroup?.trim() == group && sameQuality(it) }
        }.orEmpty()
        val similar = downloadable
            .filterNot { it in sameGroup }
            .filter(::sameQuality)
            .mapNotNull { stream ->
                val text = describe(stream)
                val score = similarity(referenceTokens, tokens(text)) +
                    (if (stream.addonId == reference.addonId) 0.1 else 0.0) +
                    (if (stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) 0.15 else 0.0)
                (stream to score).takeIf { score >= minimumScore }
            }
            .sortedByDescending { (_, score) -> score }
            .map { (stream, _) -> stream }
        val cachedFirst = compareBy<StreamItem> {
            if (it.debridCacheStatus?.state == StreamDebridCacheState.CACHED) 0 else 1
        }
        return sameGroup.sortedWith(cachedFirst) + similar
    }

    private fun isDownloadable(stream: StreamItem): Boolean {
        val url = stream.playableDirectUrl?.lowercase()
        if (url != null && (url.startsWith("http://") || url.startsWith("https://")) &&
            !url.contains(".m3u8") && !url.contains(".mpd")
        ) {
            return true
        }
        return DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)
    }

    private fun resolution(text: String): Int? = when {
        Regex("\\b(2160p|4k|uhd)\\b").containsMatchIn(text) -> 2160
        Regex("\\b1440p\\b").containsMatchIn(text) -> 1440
        Regex("\\b1080p\\b").containsMatchIn(text) -> 1080
        Regex("\\b720p\\b").containsMatchIn(text) -> 720
        Regex("\\b(480p|576p|sd)\\b").containsMatchIn(text) -> 480
        else -> null
    }

    private fun flags(text: String): Set<String> =
        flagPatterns.filterValues { it.containsMatchIn(text) }.keys

    private fun tokens(text: String): Set<String> =
        text.split(Regex("[^a-z0-9+]+"))
            .filter { it.length >= 2 && !ignoredTokens.matches(it) }
            .toSet()

    private fun similarity(first: Set<String>, second: Set<String>): Double {
        if (first.isEmpty() || second.isEmpty()) return 0.0
        val shared = first.intersect(second).size.toDouble()
        return shared / first.union(second).size.toDouble()
    }
}
