package pl.lk.toyotacompanion.settings

import android.content.SharedPreferences
import java.math.BigDecimal

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

    private companion object {
        const val KEY_DEFAULT_PRICE = "default_price"
    }
}
