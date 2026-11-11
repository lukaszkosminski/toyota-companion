package pl.lk.toyotacompanion.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import java.math.BigDecimal
import java.time.Instant
import pl.lk.toyotacompanion.core.domain.SummaryPeriod

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

    /** UI language tag (`pl` / `en`); `null` when the owner never chose, which means Polish. */
    fun language(): String? = prefs.getString(KEY_LANGUAGE, null)

    fun saveLanguage(tag: String) {
        prefs.edit { putString(KEY_LANGUAGE, tag) }
    }

    /** Stores [price] as plain text; deciding the rounding is the caller's job (docs/SPEC.md §12.2). */
    fun saveDefaultPrice(price: BigDecimal) {
        prefs.edit { putString(KEY_DEFAULT_PRICE, price.toPlainString()) }
    }

    /** The consumption the maker declares, l/100 km, typed by the owner (docs/SPEC.md §30); `null` when never set or garbage. */
    fun declaredConsumption(): BigDecimal? {
        val text = prefs.getString(KEY_DECLARED, null)?.trim()
        if (text.isNullOrEmpty()) return null
        val parsed = try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            return null
        }
        return if (parsed.signum() <= 0) null else parsed
    }

    fun saveDeclaredConsumption(value: BigDecimal?) {
        prefs.edit { if (value == null) remove(KEY_DECLARED) else putString(KEY_DECLARED, value.toPlainString()) }
    }

    /** The eco goal for the month's consumption, l/100 km (docs/SPEC.md §40); `null` when never set, garbage, or outside (0, 20]. */
    fun ecoGoalConsumption(): BigDecimal? {
        val text = prefs.getString(KEY_ECO_CONSUMPTION, null)?.trim()
        if (text.isNullOrEmpty()) return null
        val parsed = try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            return null
        }
        return if (parsed.signum() <= 0 || parsed > BigDecimal(20)) null else parsed
    }

    /** The eco goal for the month's electric share, whole percent 1-100 (docs/SPEC.md §40); `null` when never set or outside. */
    fun ecoGoalEvPct(): Int? {
        val value = try {
            prefs.getInt(KEY_ECO_EV, 0)
        } catch (e: ClassCastException) {
            return null
        }
        return if (value in 1..100) value else null
    }

    /** Stores both goals at once; `null` removes that goal. */
    fun saveEcoGoals(consumption: BigDecimal?, evPct: Int?) {
        prefs.edit {
            if (consumption == null) remove(KEY_ECO_CONSUMPTION) else putString(KEY_ECO_CONSUMPTION, consumption.toPlainString())
            if (evPct == null) remove(KEY_ECO_EV) else putInt(KEY_ECO_EV, evPct)
        }
    }

    /**
     * The monthly budget for fuel and non-fuel expenses (PRD F48, docs/SPEC.md §49), in zł; `null` when never set,
     * garbage or not positive. A phone setting like the default price: plain text, survives logging out (ADR-006).
     */
    fun monthlyBudget(): BigDecimal? {
        val text = try {
            prefs.getString(KEY_MONTHLY_BUDGET, null)?.trim()
        } catch (e: ClassCastException) {
            // Something else was stored under the key: read as "no budget", like a garbage price.
            return null
        }
        if (text.isNullOrEmpty()) return null
        val parsed = try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            return null
        }
        // A stored garbage value like 0.004 must not reach the domain: it would round to 0.00 and break the percent.
        val rounded = parsed.setScale(2, java.math.RoundingMode.HALF_UP)
        return if (rounded.signum() <= 0) null else rounded
    }

    /** Stores the budget in zł; `null` removes it. Deciding the rounding is the caller's job. */
    fun saveMonthlyBudget(value: BigDecimal?) {
        prefs.edit { if (value == null) remove(KEY_MONTHLY_BUDGET) else putString(KEY_MONTHLY_BUDGET, value.toPlainString()) }
    }

    /**
     * Whether the monthly budget notification is on (PRD F49, docs/SPEC.md §49); on by default, like the automatic
     * weather. A phone setting, not account data: it survives logging out.
     */
    fun budgetAlert(): Boolean = try {
        prefs.getBoolean(KEY_BUDGET_ALERT, true)
    } catch (e: ClassCastException) {
        // Something else was stored under the key: read as the default, so the budget card never fails over it.
        true
    }

    fun saveBudgetAlert(on: Boolean) {
        prefs.edit { putBoolean(KEY_BUDGET_ALERT, on) }
    }

    /** `YYYY-MM:80` or `YYYY-MM:100` of the last budget notification, `null` when nothing was told (docs/SPEC.md §49). */
    fun lastBudgetAlert(): String? = try {
        prefs.getString(KEY_BUDGET_ALERT_LAST, null)
    } catch (e: ClassCastException) {
        // Something else was stored under the key: read as "nothing told yet".
        null
    }

    fun saveLastBudgetAlert(key: String) {
        prefs.edit { putString(KEY_BUDGET_ALERT_LAST, key) }
    }

    /**
     * The period summary (PRD F54, docs/SPEC.md §54): `WEEK`, `MONTH`, or `null` when it is off (the default). A phone setting, like the budget.
     * A change of the period drops the remembered key, so the new period starts from a baseline and sends nothing at once.
     */
    fun periodSummaryKind(): SummaryPeriod? = try {
        prefs.getString(KEY_PERIOD_SUMMARY, null)?.let { SummaryPeriod.valueOf(it) }
    } catch (e: IllegalArgumentException) {
        null
    }

    fun savePeriodSummaryKind(kind: SummaryPeriod?) {
        if (kind == periodSummaryKind()) return
        prefs.edit {
            if (kind == null) remove(KEY_PERIOD_SUMMARY) else putString(KEY_PERIOD_SUMMARY, kind.name)
            remove(KEY_PERIOD_SUMMARY_LAST)
        }
    }

    /** The key (`week:2026-11-02` or `month:2026-10`) of the last summary handled, sent or passed over (docs/SPEC.md §54). */
    fun lastPeriodSummary(): String? = prefs.getString(KEY_PERIOD_SUMMARY_LAST, null)

    fun saveLastPeriodSummary(key: String) {
        prefs.edit { putString(KEY_PERIOD_SUMMARY_LAST, key) }
    }

    /** Month (`YYYY-MM`) of the last "consumption keeps rising" notification (docs/SPEC.md §31). */
    fun lastConsumptionAlert(): String? = prefs.getString(KEY_ALERT, null)

    fun saveLastConsumptionAlert(key: String) {
        prefs.edit { putString(KEY_ALERT, key) }
    }

    /** Whether the weather of new trips is fetched on its own (docs/SPEC.md §23); on by default, the owner asked for it. */
    fun weatherAuto(): Boolean = prefs.getBoolean(KEY_WEATHER_AUTO, true)

    fun saveWeatherAuto(on: Boolean) {
        prefs.edit { putBoolean(KEY_WEATHER_AUTO, on) }
    }

    /** Removes the setting; trips with no refuel before them fall back to "brak ceny". */
    fun clearDefaultPrice() {
        prefs.edit { remove(KEY_DEFAULT_PRICE) }
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
        prefs.edit { putString(KEY_NOTIFIED_REFUELS, ids.joinToString(",")) }
    }

    /**
     * Forgets [id] after its notification was cancelled (docs/SPEC.md §13.1).
     * Without this the set would only grow, and a refuel is announced once and
     * then leaves the waiting list for good.
     */
    fun forgetNotifiedRefuel(id: String) {
        val ids = notifiedRefuelIds() - id
        prefs.edit {
            if (ids.isEmpty()) remove(KEY_NOTIFIED_REFUELS) else putString(KEY_NOTIFIED_REFUELS, ids.joinToString(","))
        }
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
        prefs.edit { putLong(KEY_LAST_ARCHIVE_EXPORT, at.toEpochMilli()) }
    }

    /** Whether the runtime notification permission was already requested (docs/SPEC.md §13.2). */
    fun notificationsAsked(): Boolean = prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)

    /** A refused permission is never asked for again. */
    fun markNotificationsAsked() {
        prefs.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, true) }
    }

    private companion object {
        const val KEY_DEFAULT_PRICE = "default_price"
        const val KEY_WEATHER_AUTO = "weather_auto"
        const val KEY_DECLARED = "declared_consumption"
        const val KEY_LANGUAGE = "language"
        const val KEY_ECO_CONSUMPTION = "eco_goal_consumption"
        const val KEY_ECO_EV = "eco_goal_ev_pct"
        const val KEY_MONTHLY_BUDGET = "monthly_budget"
        const val KEY_BUDGET_ALERT = "budget_alert"
        const val KEY_BUDGET_ALERT_LAST = "budget_alert_last"
        const val KEY_PERIOD_SUMMARY = "period_summary"
        const val KEY_PERIOD_SUMMARY_LAST = "period_summary_last"
        const val KEY_ALERT = "consumption_alert"
        const val KEY_NOTIFIED_REFUELS = "refuel_notified_ids"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        const val KEY_LAST_ARCHIVE_EXPORT = "last_archive_export"
    }
}
