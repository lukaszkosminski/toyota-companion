package pl.lk.toyotacompanion.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.IOException

/**
 * Storage Access Framework (SAF) folder operations for automatic archive copies (PRD F60, docs/SPEC.md §60).
 *
 * Interacts with system tree [Uri] grants: listing documents, creating/writing backup files,
 * cleanup on partial failure, duplicate name resolution, and releasing persistable URI grants.
 */
class SafFolderStorage(
    private val contentResolver: ContentResolver,
) {
    /** The files (never the sub-folders) of a picked folder as name to document `Uri`; the provider is asked, nothing is cached. */
    fun folderChildren(folder: String): Map<String, Uri> {
        val tree = folder.toUri()
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val result = LinkedHashMap<String, Uri>()
        contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                // A folder that happens to carry a copy's name is not a copy: deleting it would take its contents along.
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                result[name] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        }
        return result
    }

    /** The names of all backup copies currently present in [folder]. */
    fun listCopies(folder: String): List<String> = folderChildren(folder).keys.toList()

    /** Removes the file [name] from [folder]. */
    fun deleteCopy(folder: String, name: String) {
        folderChildren(folder)[name]?.let { DocumentsContract.deleteDocument(contentResolver, it) }
    }

    /** Display name of the picked folder for the UI card; returns "?" when the folder is set but unreadable. */
    fun folderName(folder: String): String {
        return try {
            val tree = folder.toUri()
            val self = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            contentResolver.query(self, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            // A folder that is gone or lost its grant has no name; the card then shows the failure of the copy.
            null
        } ?: "?"
    }

    /** Writes [bytes] as [name] in [folder]. Replaces existing file or creates new, cleaning up on failure. */
    fun writeCopy(folder: String, name: String, bytes: ByteArray) {
        val tree = folder.toUri()
        // The same day written again (a retry after a failure) replaces the file instead of adding "(1)".
        val target = folderChildren(folder)[name]
            ?: DocumentsContract.createDocument(
                contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)),
                "application/json",
                name,
            )
            ?: throw IOException("no document")
        // A write that fails half way would leave an empty or cut file that the next day's rotation counts as a copy.
        try {
            val out = contentResolver.openOutputStream(target, "wt") ?: throw IOException("no output stream")
            out.use { it.write(bytes) }
        } catch (e: Exception) {
            try {
                DocumentsContract.deleteDocument(contentResolver, target)
            } catch (ignored: Exception) {
                // The folder is not writable at all; the original failure is the one to report.
            }
            throw e
        }
        // A provider may store the file under another name ("… (1).json", a doubled extension); the rotation knows only the
        // dated name, so the file is given it back. A provider that refuses keeps its own name - the copy itself is written.
        if (name !in folderChildren(folder)) {
            try {
                DocumentsContract.renameDocument(contentResolver, target, name)
            } catch (e: Exception) {
                // Not every provider can rename.
            }
        }
    }

    /** The grant of a folder that is no longer used is given back: Android keeps only a limited number of them. */
    fun releaseFolderPermission(folder: String) {
        try {
            contentResolver.releasePersistableUriPermission(
                folder.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // It was already gone.
        }
    }
}
