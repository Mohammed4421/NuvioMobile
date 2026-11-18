package com.nuvio.app.features.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.nuvioCardDepth
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.core.ui.rememberPosterCardStyleUiState
import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.SeasonQueueEntry
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** One poster in the library's Downloads tab: a movie, or a show with all of its episodes. */
internal data class LibraryDownloadEntry(
    val metaId: String,
    val metaType: String,
    val title: String,
    val poster: String?,
    val isSeries: Boolean,
    val completed: List<DownloadItem>,
    val active: List<DownloadItem>,
    val queuedCount: Int,
    val lastChangedEpochMs: Long,
) {
    /** Overall progress of the running transfers, or null when a size is unknown. */
    val activeProgress: Float?
        get() {
            val running = active.filter { it.status != DownloadStatus.Failed }
            if (running.isEmpty()) return null
            val total = running.sumOf { it.totalBytes?.takeIf { size -> size > 0L } ?: return null }
            return (running.sumOf { it.downloadedBytes }.toFloat() / total).coerceIn(0f, 1f)
        }

    /** Stable identity: the same id can belong to a movie and to a show of another add-on. */
    val key: String
        get() = "$metaType:$metaId"

    val failedCount: Int
        get() = active.count { it.status == DownloadStatus.Failed }

    /** The single finished copy to play, or null when there is none or several to choose from. */
    val playableMovie: DownloadItem?
        get() = completed.filter(DownloadItem::isPlayable).singleOrNull()?.takeIf { !isSeries }

    val activeState: DownloadStatus?
        get() = when {
            active.any { it.status == DownloadStatus.Downloading } -> DownloadStatus.Downloading
            active.any { it.status == DownloadStatus.Paused } -> DownloadStatus.Paused
            active.any { it.status == DownloadStatus.Failed } -> DownloadStatus.Failed
            else -> null
        }
}

/**
 * Groups downloads and season-queue entries by title, newest activity first. Episodes of a show
 * share its poster, so a season shows up once.
 */
internal fun buildLibraryDownloadEntries(
    items: List<DownloadItem>,
    queue: List<SeasonQueueEntry>,
): List<LibraryDownloadEntry> {
    // One pass each (not a filter per title), keyed by type and id.
    val downloadsByKey = items.groupBy { it.parentMetaType to it.parentMetaId }
    val queuedByKey = queue.groupBy { it.parentMetaType to it.parentMetaId }
    val keys = LinkedHashSet<Pair<String, String>>().apply {
        addAll(downloadsByKey.keys)
        addAll(queuedByKey.keys)
    }
    return keys.mapNotNull { key ->
        val (type, id) = key
        val downloads = downloadsByKey[key].orEmpty()
        val queued = queuedByKey[key].orEmpty()
        val newest = downloads.maxByOrNull { it.updatedAtEpochMs }
        val title = newest?.title ?: queued.firstOrNull()?.title ?: return@mapNotNull null
        LibraryDownloadEntry(
            metaId = id,
            metaType = type,
            title = title,
            poster = downloads.firstNotNullOfOrNull { it.poster?.takeIf(String::isNotBlank) }
                ?: queued.firstNotNullOfOrNull { it.poster?.takeIf(String::isNotBlank) },
            isSeries = queued.isNotEmpty() || downloads.any(DownloadItem::isEpisode),
            completed = downloads.filter { it.status == DownloadStatus.Completed },
            active = downloads.filter { it.status != DownloadStatus.Completed },
            queuedCount = queued.size,
            lastChangedEpochMs = maxOf(
                newest?.updatedAtEpochMs ?: 0L,
                downloads.maxOfOrNull { it.createdAtEpochMs } ?: 0L,
            ),
        )
    }.sortedWith(
        compareByDescending<LibraryDownloadEntry> { it.active.isNotEmpty() || it.queuedCount > 0 }
            .thenByDescending { it.lastChangedEpochMs },
    )
}

internal fun LazyListScope.libraryDownloadsContent(
    entries: List<LibraryDownloadEntry>,
    activeCount: Int,
    queueCount: Int,
    columns: Int,
    onManageClick: () -> Unit,
    onEntryClick: (LibraryDownloadEntry) -> Unit,
    onEntryLongClick: (LibraryDownloadEntry) -> Unit,
) {
    item(key = "library-downloads-manage") {
        LibraryDownloadsManageCard(
            activeCount = activeCount,
            queueCount = queueCount,
            onClick = onManageClick,
            modifier = libraryContentTransitionModifier().padding(horizontal = 16.dp),
        )
    }
    if (entries.isEmpty()) {
        item(key = "library-downloads-empty") {
            Column(
                modifier = libraryContentTransitionModifier()
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.downloads_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.library_downloads_empty_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    items(
        items = entries.chunked(columns),
        key = { row -> "library-downloads:${row.first().key}" },
    ) { row ->
        Row(
            modifier = libraryContentTransitionModifier()
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            row.forEach { entry ->
                DownloadPosterTile(
                    entry = entry,
                    modifier = Modifier.weight(1f),
                    onClick = { onEntryClick(entry) },
                    onLongClick = { onEntryLongClick(entry) },
                )
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun LibraryDownloadsManageCard(
    activeCount: Int,
    queueCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = buildList {
        if (activeCount > 0) add(stringResource(Res.string.library_downloads_active_count, activeCount))
        if (queueCount > 0) add(stringResource(Res.string.library_downloads_queue_count, queueCount))
    }.joinToString(" • ").ifEmpty { stringResource(Res.string.library_downloads_manage_description) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = if (activeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.library_downloads_manage),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DownloadPosterTile(
    entry: LibraryDownloadEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = rememberPosterCardStyleUiState()
    val shape = RoundedCornerShape(style.cornerRadiusDp.dp)
    val state = entry.activeState
    val detail = downloadEntryDetail(entry)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            // Read the state out, not only its icon or color.
            contentDescription = "${entry.title}, $detail"
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .nuvioCardDepth(shape = shape, surface = NuvioCardDepthSurface.Posters)
                .posterCardClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    zoomImageUrl = entry.poster,
                    zoomCornerRadius = style.cornerRadiusDp.dp,
                ),
        ) {
            if (entry.poster != null) {
                AsyncImage(
                    model = entry.poster,
                    contentDescription = entry.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            val badge = when {
                state == DownloadStatus.Downloading -> Icons.Rounded.Download
                state == DownloadStatus.Paused -> Icons.Rounded.Pause
                state == DownloadStatus.Failed -> Icons.Rounded.ErrorOutline
                entry.queuedCount > 0 -> Icons.Rounded.Schedule
                else -> null
            }
            if (badge != null) {
                PosterBadge(
                    icon = badge,
                    tint = if (state == DownloadStatus.Failed) MaterialTheme.colorScheme.error else Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
            if (state == DownloadStatus.Downloading || state == DownloadStatus.Paused) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    val progress = entry.activeProgress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.25f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.25f),
                        )
                    }
                }
            }
        }
        if (!style.hideLabelsEnabled) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state == DownloadStatus.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PosterBadge(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(26.dp)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun downloadEntryDetail(entry: LibraryDownloadEntry): String {
    val progress = entry.activeProgress?.let { "${(it * 100).toInt()}%" }
    // A failed episode stays visible while another one is downloading.
    val failed = entry.failedCount.takeIf { it > 0 && entry.activeState != DownloadStatus.Failed }
        ?.let { stringResource(Res.string.library_downloads_failed_count, it) }
    return when (entry.activeState) {
        DownloadStatus.Downloading -> listOfNotNull(stringResource(Res.string.downloads_queue_downloading), progress, failed)
            .joinToString(" • ")
        DownloadStatus.Paused -> listOfNotNull(stringResource(Res.string.library_downloads_paused), progress, failed)
            .joinToString(" • ")
        DownloadStatus.Failed -> stringResource(Res.string.downloads_status_failed)
        else -> when {
            entry.queuedCount > 0 && entry.completed.isEmpty() -> stringResource(Res.string.downloads_queue_waiting)
            entry.isSeries -> stringResource(Res.string.downloads_episode_count, entry.completed.size)
            else -> stringResource(Res.string.library_downloads_movie)
        }
    }
}
