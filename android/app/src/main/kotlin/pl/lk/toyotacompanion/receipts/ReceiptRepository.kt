package pl.lk.toyotacompanion.receipts

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.lk.toyotacompanion.data.db.ExpenseDao
import pl.lk.toyotacompanion.data.db.RefuelDao

/** Which entry a receipt belongs to. */
enum class ReceiptOwner(val prefix: String) {
    REFUEL("refuel"),
    EXPENSE("expense"),
}

/** The key of an entry in [ReceiptRepository.attached]: `refuel:<id>` or `expense:<id>`. */
fun receiptKey(owner: ReceiptOwner, id: String): String = "${owner.prefix}:$id"

/**
 * The link between the Room rows and the photos in [store] (PRD F59, docs/SPEC.md §59). Every write runs under one [Mutex], so a
 * sweep never deletes a file that an import has just written and not yet linked. A failed import leaves the entry as it was.
 */
class ReceiptRepository(
    private val store: ReceiptStore,
    private val refuels: RefuelDao,
    private val expenses: ExpenseDao,
    private val io: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private val linked = MutableStateFlow<Set<String>>(emptySet())

    /** The entries that have a photo; the lists show a small icon for them. */
    val attached: StateFlow<Set<String>> = linked.asStateFlow()

    suspend fun refresh() = withContext(io) { lock.withLock { reloadLinks() } }

    /**
     * Stores [source] as the photo of the entry and removes the old one. Returns `false` (and keeps the new file out of the folder)
     * when the entry does not exist. Throws [ReceiptException] when the photo cannot be read or stored.
     */
    suspend fun attach(owner: ReceiptOwner, id: String, source: Uri): Boolean = withContext(io) {
        lock.withLock {
            val name = store.importPhoto(source)
            val old = currentName(owner, id)
            val updated = try {
                setName(owner, id, name)
            } catch (e: Exception) {
                store.delete(name)
                throw e
            }
            if (updated == 0) {
                store.delete(name)
                reloadLinks()
                return@withLock false
            }
            old?.let { store.delete(it) }
            reloadLinks()
            true
        }
    }

    /** Imports a photo that is not linked yet (the add-expense form); it is linked by [addExpense] or dropped by [discard]. */
    suspend fun importPending(source: Uri): String = withContext(io) { lock.withLock { store.importPhoto(source) } }

    /** Deletes a photo that was imported but never linked, when its form is cancelled. */
    suspend fun discard(name: String) = withContext(io) { lock.withLock { store.delete(name) } }

    /** Removes the photo and clears the link; the entry stays. */
    suspend fun remove(owner: ReceiptOwner, id: String) = withContext(io) {
        lock.withLock {
            val old = currentName(owner, id)
            setName(owner, id, null)
            old?.let { store.delete(it) }
            reloadLinks()
        }
    }

    /** Deletes an expense together with its photo, so no file outlives its entry. */
    suspend fun deleteExpense(id: String) = withContext(io) {
        lock.withLock {
            val old = expenses.receiptOf(id)
            expenses.delete(id)
            old?.let { store.delete(it) }
            reloadLinks()
        }
    }

    /**
     * Start-up housekeeping: clears links to photos that are gone (a restore from another phone brings the rows, not the files), deletes
     * photos no entry points to, and removes the temporary files of an interrupted import.
     */
    suspend fun sweep() = withContext(io) {
        lock.withLock {
            val files = store.files()
            val referenced = refuels.receiptNames().toSet() + expenses.receiptNames().toSet()
            for (name in missingReceipts(referenced, files)) {
                refuels.clearReceipt(name)
                expenses.clearReceipt(name)
            }
            for (name in orphanedReceipts(files, referenced)) store.delete(name)
            for (name in files) store.deleteTemporary(name)
            reloadLinks()
        }
    }

    private suspend fun currentName(owner: ReceiptOwner, id: String): String? = when (owner) {
        ReceiptOwner.REFUEL -> refuels.receiptOf(id)
        ReceiptOwner.EXPENSE -> expenses.receiptOf(id)
    }

    private suspend fun setName(owner: ReceiptOwner, id: String, name: String?): Int = when (owner) {
        ReceiptOwner.REFUEL -> refuels.setReceipt(id, name)
        ReceiptOwner.EXPENSE -> expenses.setReceipt(id, name)
    }

    private suspend fun reloadLinks() {
        linked.value = refuels.receiptRefs().map { receiptKey(ReceiptOwner.REFUEL, it.id) }.toSet() +
            expenses.receiptRefs().map { receiptKey(ReceiptOwner.EXPENSE, it.id) }.toSet()
    }

    /** The file name of the entry's photo, or `null` when it has none. */
    suspend fun nameOf(owner: ReceiptOwner, id: String): String? = withContext(io) { lock.withLock { currentName(owner, id) } }

    /** The photo of an entry for the screen; `null` when there is none or it cannot be read. */
    suspend fun decode(name: String, maxSide: Int): Bitmap? = withContext(io) { store.decode(name, maxSide) }
}
