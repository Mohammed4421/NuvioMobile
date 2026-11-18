package com.nuvio.app.features.downloads

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.FileNotFoundException

/**
 * Storage Access Framework helpers for downloads saved in a folder the user picked. Only
 * framework APIs are used, so no extra library is needed.
 */
internal object DownloadDocuments {

    fun isDocumentUri(value: String?): Boolean =
        value?.startsWith("content:", ignoreCase = true) == true

    /** True while the app still holds read and write access to [treeUri]. */
    fun hasPermission(context: Context, treeUri: String): Boolean {
        val uri = Uri.parse(treeUri)
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    /** Folder for a download: the picked folder itself, or a sub-folder named after the show. */
    fun ensureFolder(context: Context, treeUri: String, subFolderName: String?): Uri {
        val tree = Uri.parse(treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = subFolderName?.let(::sanitizeDocumentName)?.takeIf { it.isNotBlank() } ?: return root
        findChild(context.contentResolver, tree, root, name)?.let { return it }
        return DocumentsContract.createDocument(context.contentResolver, root, Document.MIME_TYPE_DIR, name)
            ?: throw FileNotFoundException("Cannot create folder $name")
    }

    fun findChild(resolver: ContentResolver, tree: Uri, parent: Uri, displayName: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == displayName) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    }
                }
            }
        return null
    }

    fun createFile(context: Context, folder: Uri, displayName: String, mimeType: String): Uri =
        DocumentsContract.createDocument(context.contentResolver, folder, mimeType, sanitizeDocumentName(displayName))
            ?: throw FileNotFoundException("Cannot create $displayName")

    /** Returns the renamed document (its URI can change), or [document] when renaming is not supported. */
    fun rename(context: Context, document: Uri, displayName: String): Uri =
        runCatching {
            DocumentsContract.renameDocument(context.contentResolver, document, sanitizeDocumentName(displayName))
        }.getOrNull() ?: document

    fun delete(context: Context, document: String?): Boolean {
        if (!isDocumentUri(document)) return false
        return runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(document))
        }.getOrDefault(false)
    }

    /** Size of the document, or null when it does not exist (deleted or moved outside the app). */
    fun size(context: Context, document: String): Long? =
        runCatching {
            context.contentResolver.query(Uri.parse(document), arrayOf(Document.COLUMN_SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }.getOrNull()

    fun exists(context: Context, document: String): Boolean = size(context, document) != null

    fun displayName(context: Context, document: String): String? =
        runCatching {
            context.contentResolver.query(Uri.parse(document), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    /** "primary:Movies/Nuvio" -> "Movies/Nuvio"; other providers fall back to the folder name. */
    fun readableLabel(context: Context, treeUri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        val path = documentId.substringAfter(':', missingDelimiterValue = "")
        val volume = documentId.substringBefore(':', missingDelimiterValue = "")
        if (path.isNotBlank()) {
            return if (volume.equals("primary", ignoreCase = true)) path else "$volume/$path"
        }
        val root = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        }.getOrNull()
        return root?.let {
            context.contentResolver.query(it, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } ?: documentId.ifBlank { treeUri.toString() }
    }

    fun sanitizeDocumentName(value: String): String =
        value.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(150)
            .trim('.', ' ')
}

@Composable
internal actual fun rememberDownloadFolderPicker(
    onResult: (Result<PickedDownloadFolder>) -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val latestResult = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            // Make sure the folder is writable before saving it as the target.
            val probe = DownloadDocuments.createFile(
                context,
                DownloadDocuments.ensureFolder(context, uri.toString(), null),
                ".nuvio-write-test",
                "application/octet-stream",
            )
            DocumentsContract.deleteDocument(context.contentResolver, probe)
            PickedDownloadFolder(uri = uri.toString(), label = DownloadDocuments.readableLabel(context, uri))
        }
        latestResult.value(result)
    }
    return { launcher.launch(null) }
}
