package com.nuvio.app.features.downloads

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Writes a transfer straight into a document of the picked folder, so no second copy is needed. */
internal class DocumentDownloadPartialSink(
    private val context: Context,
    private val document: Uri,
) : DownloadPartialSink {
    override fun length(): Long =
        runCatching {
            context.contentResolver.openFileDescriptor(document, "r")?.use { it.statSize }
        }.getOrNull()?.takeIf { it >= 0L }
            ?: DownloadDocuments.size(context, document.toString())
            ?: 0L

    override fun open(append: Boolean): DownloadPartialOutput {
        val descriptor = context.contentResolver.openFileDescriptor(document, if (append) "wa" else "rwt")
            ?: throw IOException("Cannot open the download file in the chosen folder")
        val output = FileOutputStream(descriptor.fileDescriptor)
        try {
            val channel = output.channel
            if (append) channel.position(channel.size()) else channel.truncate(0L)
        } catch (error: Exception) {
            output.close()
            descriptor.close()
            throw IOException("Cannot prepare the download file in the chosen folder", error)
        }
        return DownloadPartialOutput(
            stream = output,
            syncAction = { output.fd.sync() },
            closeAction = { descriptor.close() },
        )
    }
}

internal object DocumentDownloads {
    private const val SUBTITLES_ROOT = "downloads/.document-subtitles"
    private const val EXPORTED_LIST = "exported.list"

    /** Readable name for the saved file, e.g. "Show S01E02 - Title.mkv". */
    fun displayName(item: DownloadItem): String {
        val extension = item.fileName.substringAfterLast('.', missingDelimiterValue = "mp4")
        val base = if (item.isEpisode) {
            buildString {
                append(item.title.ifBlank { "Episode" })
                append(" S")
                append(item.seasonNumber.toString().padStart(2, '0'))
                append('E')
                append(item.episodeNumber.toString().padStart(2, '0'))
                item.episodeTitle?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
            }
        } else {
            item.title.ifBlank { item.streamTitle }.ifBlank { "Movie" }
        }
        return "${DownloadDocuments.sanitizeDocumentName(base).ifBlank { "download" }}.$extension"
    }

    /** Folder for the file: the show's own sub-folder for episodes, the picked folder for movies. */
    fun folderFor(context: Context, item: DownloadItem): Uri =
        DownloadDocuments.ensureFolder(
            context = context,
            treeUri = checkNotNull(item.targetFolderUri),
            subFolderName = if (item.isEpisode) item.title.ifBlank { null } else null,
        )

    /** A name not taken in [folder]: "Name.mkv", then "Name (2).mkv" and so on. */
    fun uniqueName(context: Context, item: DownloadItem, folder: Uri, desired: String): String {
        val tree = Uri.parse(checkNotNull(item.targetFolderUri))
        val stem = desired.substringBeforeLast('.')
        val extension = desired.substringAfterLast('.', missingDelimiterValue = "")
        var candidate = desired
        var index = 2
        while (
            DownloadDocuments.findChild(context.contentResolver, tree, folder, candidate) != null ||
            DownloadDocuments.findChild(context.contentResolver, tree, folder, "$candidate.part") != null
        ) {
            candidate = if (extension.isBlank()) "$stem ($index)" else "$stem ($index).$extension"
            index++
        }
        return candidate
    }

    /** Subtitles of a download saved in a picked folder live in app storage, keyed by the document URI. */
    fun subtitleDirectory(context: Context, documentUri: String): File =
        File(context.filesDir, "$SUBTITLES_ROOT/${sha256(documentUri)}")

    /**
     * Moves the subtitles prepared next to the internal anchor to the document's own folder in app
     * storage, then writes readable copies ("Name.ar.srt") beside the video for other players.
     */
    fun finishSubtitles(context: Context, item: DownloadItem, internalAnchor: File, documentUri: String, videoName: String) {
        val prepared = File(internalAnchor.path + ".subtitles")
        val target = subtitleDirectory(context, documentUri)
        if (prepared.isDirectory) {
            target.parentFile?.mkdirs()
            if (target.exists()) target.deleteRecursively()
            if (!prepared.renameTo(target)) {
                prepared.copyRecursively(target, overwrite = true)
                prepared.deleteRecursively()
            }
        }
        // The video is saved either way; copies beside it are a convenience for other players.
        runCatching { exportSubtitleCopies(context, item, documentUri, videoName) }
            .onFailure { downloadLog("subtitle copies beside the video not saved: ${it.javaClass.simpleName}") }
    }

    private fun exportSubtitleCopies(context: Context, item: DownloadItem, documentUri: String, videoName: String) {
        val tracks = DownloadSubtitles.localSubtitles(documentUri)
        if (tracks.isEmpty()) return
        val folder = folderFor(context, item)
        val videoStem = videoName.substringBeforeLast('.')
        val usedNames = mutableSetOf<String>()
        val exported = mutableListOf<String>()
        tracks.forEach { track ->
            val source = runCatching { File(java.net.URI(track.url)) }.getOrNull()?.takeIf { it.isFile } ?: return@forEach
            val extension = source.extension.ifBlank { "srt" }
            val language = track.language.trim().lowercase().replace(Regex("[^a-z0-9-]"), "").ifBlank { "und" }
            var name = "$videoStem.$language.$extension"
            var index = 2
            while (!usedNames.add(name)) {
                name = "$videoStem.$language.$index.$extension"
                index++
            }
            val document = DownloadDocuments.createFile(context, folder, name, "application/octet-stream")
            // Streamed: an ASS file with embedded fonts can be large.
            context.contentResolver.openOutputStream(document, "wt")?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            exported += document.toString()
        }
        if (exported.isNotEmpty()) {
            File(subtitleDirectory(context, documentUri), EXPORTED_LIST).writeText(exported.joinToString("\n"))
        }
    }

    /** Deletes the video document, the subtitle copies beside it and the subtitles kept in app storage. */
    fun deleteDownload(context: Context, documentUri: String): Boolean {
        val subtitles = subtitleDirectory(context, documentUri)
        File(subtitles, EXPORTED_LIST).takeIf { it.isFile }?.readLines()?.forEach { DownloadDocuments.delete(context, it) }
        subtitles.deleteRecursively()
        return DownloadDocuments.delete(context, documentUri)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
