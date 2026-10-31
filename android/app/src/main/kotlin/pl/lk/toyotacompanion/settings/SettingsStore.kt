package pl.lk.toyotacompanion.settings

import android.content.SharedPreferences
import java.math.BigDecimal
import java.time.Instant

/**
 * The phone's own settings, kept in the SharedPreferences file `settings`
 * (docs/SPEC.md §12.2).
 *
 * Deliberately *not* the `secrets` file of
 * [pl.lk.toyotacompanion.security.EncryptedSecretStore]: a fuel price is not a
 * secret (docs/SECURITY.md), so it is plain text and never reaches the Keystore.
 * It also survives logging out — it belongs to the phone, not to the account, just
 * like the archive (ADR-006).
 *
 * Reads and writes go to disk, so callers keep them off the UI thread
 * (`Dispatchers.IO`, docs/SPEC.md §12.2).
 */
class SettingsStore(private val prefs: SharedPreferences) {

    /**
     * Stored price, or `null` when it was never set, the stored text is garbage, or the
     * value is not positive — a zero or negative price would cost trips 0,00 zl, so it is
     * treated exactly like a broken value (docs/SPEC.md §12.2).
     */
    fun defaultPrice(): BigDecimal? {
        val text = prefs.getString(KEY_DEFAULT_PRICE, null)?.trim()
        if (text.isNullOrEmpty()) return null
        // No exception and the value is never logged.
        val parsed = try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            return null
        }
        return if (parsed.signum() <= 0) null else parsed
    }

    /** Stores [price] as plain text; deciding the rounding is the caller's job (docs/SPEC.md §12.2). */
    fun saveDefaultPrice(price: BigDecimal) {
        prefs.edit().putString(KEY_DEFAULT_PRICE, price.toPlainString()).apply()
    }

    /** Removes the setting; trips with no refuel before them fall back to "brak ceny". */
    fun clearDefaultPrice() {
        prefs.edit().remove(KEY_DEFAULT_PRICE).apply()
    }

    /**
     * Ids of refuels the app has already announced with a notification
     * (docs/SPEC.md §13.1). Plain ids of local rows, not secrets — they live in
     * this same unencrypted file. The set is what keeps a repeated
     * synchronization from notifying twice.
     */
    fun notifiedRefuelIds(): Set<String> {
        val text = prefs.getString(KEY_NOTIFIED_REFUELS, null) ?: return emptySet()
        return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Remembers that [id] was announced; the next synchronization leaves it alone. */
    fun markRefuelNotified(id: String) {
        val ids = notifiedRefuelIds() + id
        prefs.edit().putString(KEY_NOTIFIED_REFUELS, ids.joinToString(",")).apply()
    }

    /**
     * Forgets [id] after its notification was cancelled (docs/SPEC.md §13.1).
     * Without this the set would only grow, and a refuel is announced once and
     * then leaves the waiting list for good.
     */
    fun forgetNotifiedRefuel(id: String) {
        val ids = notifiedRefuelIds() - id
        val editor = prefs.edit()
        if (ids.isEmpty()) editor.remove(KEY_NOTIFIED_REFUELS) else editor.putString(KEY_NOTIFIED_REFUELS, ids.joinToString(","))
        editor.apply()
    }

    /**
     * When the whole archive was last exported (PRD F8, docs/SPEC.md §14.3), or `null`
     * when never or when the stored text is garbage. Survives logging out: the copy
     * belongs to the phone, like the default price.
     */
    fun lastArchiveExport(): Instant? {
        val millis = try {
            prefs.getLong(KEY_LAST_ARCHIVE_EXPORT, -1L)
        } catch (e: ClassCastException) {
            // Something else was stored under the key: read as "never", like a garbage price.
            return null
        }
        return if (millis < 0) null else Instant.ofEpochMilli(millis)
    }

    /** Stores the moment of a successful archive export. */
    fun saveLastArchiveExport(at: Instant) {
        prefs.edit().putLong(KEY_LAST_ARCHIVE_EXPORT, at.toEpochMilli()).apply()
    }

    /** Whether the runtime notification permission was already requested (docs/SPEC.md §13.2). */
    fun notificationsAsked(): Boolean = prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)

    /** A refused permission is never asked for again. */
    fun markNotificationsAsked() {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
    }

    private companion object {
        const val KEY_DEFAULT_PRICE = "default_price"
        const val KEY_NOTIFIED_REFUELS = "refuel_notified_ids"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        const val KEY_LAST_ARCHIVE_EXPORT = "last_archive_export"
    }
}
