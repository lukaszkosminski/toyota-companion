package pl.lk.toyotacompanion.storage

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Unit tests for [SafFolderStorage] using Robolectric (docs/SPEC.md §60, PRD F60, C44).
 *
 * Verifies SAF tree URI operations:
 * 1. Querying folder display name, falling back to "?" on errors.
 * 2. Listing child files while strictly ignoring directories (MIME_TYPE_DIR).
 * 3. Atomic document writes, overwriting without duplicates, and deleting incomplete files on write failure.
 * 4. Handling duplicate suffix naming and swallowing rename refusal from cloud/external providers.
 * 5. Deleting copies by name and releasing persistable URI grants without SecurityException escapes.
 */
@RunWith(RobolectricTestRunner::class)
class SafFolderStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var storage: SafFolderStorage
    private val treeUri: Uri = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, "root")
    private val folder: String = treeUri.toString()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TestDocumentsProvider.reset(tmp.root)
        val info = ProviderInfo().apply {
            authority = TestDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(info)
        storage = SafFolderStorage(context.contentResolver)
    }

    // ---------------- folderName tests ----------------

    @Test
    fun `folderName returns display name when query succeeds`() {
        TestDocumentsProvider.folderDisplayName = "Google Drive Backups"
        assertEquals("Google Drive Backups", storage.folderName(folder))
    }

    @Test
    fun `folderName returns question mark when folder is unreadable or empty`() {
        TestDocumentsProvider.folderDisplayName = null
        assertEquals("?", storage.folderName(folder))
    }

    @Test
    fun `folderName returns question mark when query throws exception`() {
        TestDocumentsProvider.queryThrows = true
        assertEquals("?", storage.folderName(folder))
    }

    // ---------------- folderChildren and listCopies tests ----------------

    @Test
    fun `folderChildren and listCopies return non-directory files with tree document URIs`() {
        TestDocumentsProvider.addFile("doc-1", "toyota-companion-2026-11-10.json", "application/json")
        TestDocumentsProvider.addFile("doc-2", "toyota-companion-2026-11-11.json", "application/json")

        val children = storage.folderChildren(folder)
        assertEquals(2, children.size)
        assertTrue(children.containsKey("toyota-companion-2026-11-10.json"))
        assertTrue(children.containsKey("toyota-companion-2026-11-11.json"))

        val expectedUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "doc-1")
        assertEquals(expectedUri, children["toyota-companion-2026-11-10.json"])

        val copies = storage.listCopies(folder)
        assertEquals(listOf("toyota-companion-2026-11-10.json", "toyota-companion-2026-11-11.json"), copies)
    }

    @Test
    fun `folderChildren and listCopies ignore subfolders carrying copy names to protect subfolder contents`() {
        // A subfolder named identically to a backup copy must never be treated as a copy
        TestDocumentsProvider.addFolder("dir-1", "toyota-companion-2026-11-10.json")
        TestDocumentsProvider.addFile("doc-1", "toyota-companion-2026-11-11.json", "application/json")

        val children = storage.folderChildren(folder)
        assertEquals(1, children.size)
        assertFalse(children.containsKey("toyota-companion-2026-11-10.json"))
        assertTrue(children.containsKey("toyota-companion-2026-11-11.json"))

        // Attempting to delete the copy name must not touch the subfolder
        storage.deleteCopy(folder, "toyota-companion-2026-11-10.json")
        assertFalse(TestDocumentsProvider.deletedDocIds.contains("dir-1"))
        assertTrue(TestDocumentsProvider.hasDocument("dir-1"))
    }

    // ---------------- writeCopy tests ----------------

    @Test
    fun `writeCopy creates new document and writes bytes when file does not exist`() {
        val payload = """{"version":1,"trips":[]}""".toByteArray()
        storage.writeCopy(folder, "toyota-companion-2026-11-12.json", payload)

        val children = storage.folderChildren(folder)
        assertTrue(children.containsKey("toyota-companion-2026-11-12.json"))

        val written = TestDocumentsProvider.readContent("toyota-companion-2026-11-12.json")
        assertEquals(payload.toList(), written?.toList())
        assertEquals(1, TestDocumentsProvider.createDocumentCount)
    }

    @Test
    fun `writeCopy overwrites existing file without creating duplicate suffix`() {
        val firstPayload = """{"version":1}""".toByteArray()
        storage.writeCopy(folder, "toyota-companion-2026-11-12.json", firstPayload)
        assertEquals(1, TestDocumentsProvider.createDocumentCount)

        val secondPayload = """{"version":2}""".toByteArray()
        storage.writeCopy(folder, "toyota-companion-2026-11-12.json", secondPayload)

        // Must reuse existing document target without calling createDocument again
        assertEquals(1, TestDocumentsProvider.createDocumentCount)
        assertEquals(listOf("toyota-companion-2026-11-12.json"), storage.listCopies(folder))

        val written = TestDocumentsProvider.readContent("toyota-companion-2026-11-12.json")
        assertEquals(secondPayload.toList(), written?.toList())
    }

    @Test
    fun `writeCopy deletes incomplete file on write failure in the middle`() {
        TestDocumentsProvider.failOnWrite = true
        val payload = """{"incomplete":true}""".toByteArray()

        assertFailsWith<IOException> {
            storage.writeCopy(folder, "toyota-companion-2026-11-12.json", payload)
        }

        // Target document created before stream failure must have been deleted via deleteDocument
        assertEquals(1, TestDocumentsProvider.createDocumentCount)
        assertEquals(1, TestDocumentsProvider.deletedDocIds.size)
        assertFalse(storage.folderChildren(folder).containsKey("toyota-companion-2026-11-12.json"))
        assertEquals(emptyList(), storage.listCopies(folder))
    }

    @Test
    fun `writeCopy attempts rename and handles failure gracefully when provider refuses rename`() {
        TestDocumentsProvider.appendDuplicateSuffixOnCreate = true
        TestDocumentsProvider.refuseRename = true

        val payload = """{"data":123}""".toByteArray()
        storage.writeCopy(folder, "toyota-companion-2026-11-12.json", payload)

        // Provider assigned duplicate suffix and refused renameDocument: writeCopy must still succeed
        assertEquals(1, TestDocumentsProvider.attemptedRenameDocIds.size)
        assertTrue(storage.folderChildren(folder).containsKey("toyota-companion-2026-11-12 (1).json"))
    }

    @Test
    fun `writeCopy renames file back when provider appends duplicate suffix and rename succeeds`() {
        TestDocumentsProvider.appendDuplicateSuffixOnCreate = true
        TestDocumentsProvider.refuseRename = false

        val payload = """{"data":123}""".toByteArray()
        storage.writeCopy(folder, "toyota-companion-2026-11-12.json", payload)

        assertEquals(1, TestDocumentsProvider.attemptedRenameDocIds.size)
        assertTrue(storage.folderChildren(folder).containsKey("toyota-companion-2026-11-12.json"))
        assertFalse(storage.folderChildren(folder).containsKey("toyota-companion-2026-11-12 (1).json"))
    }

    // ---------------- deleteCopy tests ----------------

    @Test
    fun `deleteCopy deletes existing document via DocumentsContract`() {
        TestDocumentsProvider.addFile("doc-del", "toyota-companion-2026-11-10.json", "application/json")
        assertTrue(storage.folderChildren(folder).containsKey("toyota-companion-2026-11-10.json"))

        storage.deleteCopy(folder, "toyota-companion-2026-11-10.json")

        assertFalse(storage.folderChildren(folder).containsKey("toyota-companion-2026-11-10.json"))
        assertTrue(TestDocumentsProvider.deletedDocIds.contains("doc-del"))
    }

    @Test
    fun `deleteCopy does nothing when file does not exist`() {
        storage.deleteCopy(folder, "non-existent-copy.json")
        assertEquals(0, TestDocumentsProvider.deletedDocIds.size)
    }

    // ---------------- releaseFolderPermission tests ----------------

    @Test
    fun `releaseFolderPermission completes normally for standard folder`() {
        storage.releaseFolderPermission(folder)
    }

    @Test
    @Config(shadows = [ShadowSecurityExceptionContentResolver::class])
    fun `releaseFolderPermission silently catches SecurityException when grant was already revoked`() {
        // The shadow unconditionally throws SecurityException on releasePersistableUriPermission
        storage.releaseFolderPermission(folder)
    }
}

/**
 * In-memory test [ContentProvider] handling SAF contract calls (createDocument, deleteDocument,
 * renameDocument, openOutputStream, and cursor queries) for [SafFolderStorageTest].
 */
class TestDocumentsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "pl.lk.toyotacompanion.test.documents"
        private const val EXTRA_URI = "uri"

        data class DocEntry(
            val id: String,
            var name: String,
            val mimeType: String,
        )

        private val documents = LinkedHashMap<String, DocEntry>()
        val deletedDocIds = mutableListOf<String>()
        val attemptedRenameDocIds = mutableListOf<String>()

        var folderDisplayName: String? = "Test Folder"
        var queryThrows = false
        var failOnWrite = false
        var refuseRename = false
        var appendDuplicateSuffixOnCreate = false
        var createDocumentCount = 0

        private lateinit var storageDir: File

        fun reset(tmpRoot: File) {
            documents.clear()
            deletedDocIds.clear()
            attemptedRenameDocIds.clear()
            folderDisplayName = "Test Folder"
            queryThrows = false
            failOnWrite = false
            refuseRename = false
            appendDuplicateSuffixOnCreate = false
            createDocumentCount = 0
            storageDir = File(tmpRoot, "documents_storage").apply { mkdirs() }
        }

        fun addFile(id: String, name: String, mimeType: String) {
            documents[id] = DocEntry(id, name, mimeType)
            File(storageDir, id).createNewFile()
        }

        fun addFolder(id: String, name: String) {
            documents[id] = DocEntry(id, name, DocumentsContract.Document.MIME_TYPE_DIR)
        }

        fun hasDocument(id: String): Boolean = documents.containsKey(id)

        fun readContent(name: String): ByteArray? {
            val entry = documents.values.firstOrNull { it.name == name } ?: return null
            val file = File(storageDir, entry.id)
            return if (file.exists()) file.readBytes() else null
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (queryThrows) throw RuntimeException("Simulated folder query failure")
        val cols = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val uriStr = uri.toString()
        if (uriStr.endsWith("/children")) {
            val cursor = MatrixCursor(cols)
            for (entry in documents.values) {
                cursor.addRow(cols.map { col ->
                    when (col) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> entry.id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> entry.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> entry.mimeType
                        else -> null
                    }
                }.toTypedArray())
            }
            return cursor
        } else {
            val cursor = MatrixCursor(cols)
            val name = folderDisplayName
            if (name != null) {
                cursor.addRow(cols.map { col ->
                    when (col) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "root"
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> DocumentsContract.Document.MIME_TYPE_DIR
                        else -> null
                    }
                }.toTypedArray())
            }
            return cursor
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (failOnWrite) {
            throw IOException("Simulated disk write failure")
        }
        val docId = DocumentsContract.getDocumentId(uri)
        val file = File(storageDir, docId)
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
        )
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            "android:createDocument" -> {
                val parentUri = extras?.getParcelable<Uri>(EXTRA_URI)
                val mimeType = extras?.getString(DocumentsContract.Document.COLUMN_MIME_TYPE) ?: "application/json"
                val displayName = extras?.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                createDocumentCount++
                val docId = "doc-$createDocumentCount"
                val assignedName = if (appendDuplicateSuffixOnCreate) {
                    val base = displayName?.removeSuffix(".json") ?: "copy"
                    "$base (1).json"
                } else {
                    displayName ?: "unnamed.json"
                }
                documents[docId] = DocEntry(docId, assignedName, mimeType)
                File(storageDir, docId).createNewFile()

                val baseTreeUri = parentUri ?: DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
                val newDocUri = DocumentsContract.buildDocumentUriUsingTree(baseTreeUri, docId)
                return Bundle().apply { putParcelable(EXTRA_URI, newDocUri) }
            }
            "android:deleteDocument" -> {
                val docUri = extras?.getParcelable<Uri>(EXTRA_URI)
                val docId = docUri?.let { DocumentsContract.getDocumentId(it) }
                if (docId != null) {
                    deletedDocIds += docId
                    documents.remove(docId)
                    File(storageDir, docId).delete()
                }
                return Bundle()
            }
            "android:renameDocument" -> {
                val docUri = extras?.getParcelable<Uri>(EXTRA_URI)
                val docId = docUri?.let { DocumentsContract.getDocumentId(it) }
                if (docId != null) attemptedRenameDocIds += docId
                if (refuseRename) throw UnsupportedOperationException("Provider refuses rename")
                val newName = extras?.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (docId != null && newName != null) {
                    documents[docId]?.name = newName
                }
                val baseTreeUri = docUri ?: DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
                val renamedUri = DocumentsContract.buildDocumentUriUsingTree(baseTreeUri, docId ?: "doc")
                return Bundle().apply { putParcelable(EXTRA_URI, renamedUri) }
            }
            else -> return super.call(method, arg, extras)
        }
    }
}

/**
 * Robolectric shadow used to verify that [SafFolderStorage.releaseFolderPermission]
 * safely suppresses [SecurityException].
 */
@Implements(ContentResolver::class)
class ShadowSecurityExceptionContentResolver {
    @Implementation
    fun releasePersistableUriPermission(uri: Uri, flags: Int) {
        throw SecurityException("Persistable grant revoked or expired")
    }
}
