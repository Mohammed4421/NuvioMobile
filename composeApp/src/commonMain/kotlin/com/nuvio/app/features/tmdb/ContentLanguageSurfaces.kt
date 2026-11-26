package com.nuvio.app.features.tmdb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryDownloadEntry
import com.nuvio.app.features.watchprogress.ContinueWatchingItem

/**
 * Nuvio Plus: continue watching and the downloads tab keep the title and artwork saved when the
 * item was played or downloaded. When content languages are chosen, their cards show the title
 * and artwork in those languages through the same per-card lookup as catalog cards
 * ([rememberContentLanguagePreview]); the saved data itself is not rewritten. Episode titles and
 * episode thumbnails are left as they are.
 */
@Composable
internal fun rememberContentLanguageItem(item: ContinueWatchingItem): ContinueWatchingItem {
    val preview = remember(item) {
        MetaPreview(
            id = item.parentMetaId,
            type = item.parentMetaType,
            name = item.title,
            poster = item.poster,
            banner = item.background,
            logo = item.logo,
        )
    }
    val shown = rememberContentLanguagePreview(preview)
    if (shown == preview) return item
    return remember(item, shown) { item.withShown(shown) }
}

internal fun ContinueWatchingItem.withShown(shown: MetaPreview): ContinueWatchingItem = copy(
    title = shown.name,
    poster = shown.poster,
    logo = shown.logo,
    background = shown.banner,
    imageUrl = when {
        imageUrl == null -> null
        imageUrl == background -> shown.banner
        imageUrl == poster -> shown.poster
        else -> imageUrl
    },
)

@Composable
internal fun rememberContentLanguageEntry(entry: LibraryDownloadEntry): LibraryDownloadEntry {
    val preview = remember(entry) {
        MetaPreview(id = entry.metaId, type = entry.metaType, name = entry.title, poster = entry.poster)
    }
    val shown = rememberContentLanguagePreview(preview)
    if (shown == preview) return entry
    return remember(entry, shown) { entry.copy(title = shown.name, poster = shown.poster) }
}
