package pl.lk.toyotacompanion.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import pl.lk.toyotacompanion.core.domain.SummaryPeriod
import pl.lk.toyotacompanion.ui.MonthKey

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

    /** The month the consumption goal counts from for the streak (PRD F58); `null` when never set or garbage: from the archive start. */
    fun ecoGoalConsumptionSince(): MonthKey? = readMonth(KEY_ECO_CONSUMPTION_SINCE)

    /** The month the electric goal counts from for the streak (PRD F58); `null` when never set or garbage. */
    fun ecoGoalEvSince(): MonthKey? = readMonth(KEY_ECO_EV_SINCE)

    /**
     * Stores both goals at once; `null` removes that goal. [consumptionSince] and [evSince] are the months a goal counts from
     * (PRD F58); without a goal, or without a month, the key is removed.
     */
    fun saveEcoGoals(
        consumption: BigDecimal?,
        evPct: Int?,
        consumptionSince: MonthKey? = null,
        evSince: MonthKey? = null,
    ) {
        prefs.edit {
            if (consumption == null) remove(KEY_ECO_CONSUMPTION) else putString(KEY_ECO_CONSUMPTION, consumption.toPlainString())
            if (consumption == null || consumptionSince == null) {
                remove(KEY_ECO_CONSUMPTION_SINCE)
            } else {
                putString(KEY_ECO_CONSUMPTION_SINCE, monthText(consumptionSince))
            }
            if (evPct == null) remove(KEY_ECO_EV) else putInt(KEY_ECO_EV, evPct)
            if (evPct == null || evSince == null) {
                remove(KEY_ECO_EV_SINCE)
            } else {
                putString(KEY_ECO_EV_SINCE, monthText(evSince))
            }
        }
    }

    private fun readMonth(key: String): MonthKey? {
        val text = try {
            prefs.getString(key, null) ?: return null
        } catch (e: ClassCastException) {
            return null
        }
        val match = MONTH_TEXT.matchEntire(text.trim()) ?: return null
        val month = match.groupValues[2].toInt()
        return if (month in 1..12) MonthKey(match.groupValues[1].toInt(), month) else null
    }

    private fun monthText(month: MonthKey): String = "%04d-%02d".format(month.year, month.month)

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
     * The owner's valuation of the car (PRD F55, docs/SPEC.md §55): purchase price and date, value
     * and its date. `null` when any of the four is missing, garbage, of the wrong type, or the
     * numbers/dates contradict each other - read as "no valuation", like a garbage monthly budget.
     * A phone setting: plain text, survives logging out and reaches Auto Backup (ADR-006).
     */
    fun carValuation(): CarValuation? {
        val purchaseText = stringSetting(KEY_CAR_PURCHASE)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val purchaseDateText = stringSetting(KEY_CAR_PURCHASE_DATE)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val valueText = stringSetting(KEY_CAR_VALUE)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val valueDateText = stringSetting(KEY_CAR_VALUE_DATE)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val purchase = parsePositiveAmount(purchaseText) ?: return null
        val value = parseAmount(valueText) ?: return null
        val purchaseDate = parseDate(purchaseDateText) ?: return null
        val valueDate = parseDate(valueDateText) ?: return null
        if (purchase.signum() <= 0 || value.signum() < 0 || valueDate.isBefore(purchaseDate)) return null
        return CarValuation(purchase, purchaseDate, value, valueDate)
    }

    /** Stores the valuation; `null` removes all four keys. Deciding the rounding is the caller's job. */
    fun saveCarValuation(valuation: CarValuation?) {
        prefs.edit {
            if (valuation == null) {
                remove(KEY_CAR_PURCHASE)
                remove(KEY_CAR_PURCHASE_DATE)
                remove(KEY_CAR_VALUE)
                remove(KEY_CAR_VALUE_DATE)
            } else {
                putString(KEY_CAR_PURCHASE, valuation.purchasePln.toPlainString())
                putString(KEY_CAR_PURCHASE_DATE, valuation.purchaseDate.toString())
                putString(KEY_CAR_VALUE, valuation.valuePln.toPlainString())
                putString(KEY_CAR_VALUE_DATE, valuation.valueDate.toString())
            }
        }
    }

    private fun stringSetting(key: String): String? = try {
        prefs.getString(key, null)
    } catch (e: ClassCastException) {
        // Something else was stored under the key: read as "no valuation", like a garbage price.
        null
    }

    private fun parseAmount(text: String): BigDecimal? = try {
        BigDecimal(text).setScale(2, java.math.RoundingMode.HALF_UP)
    } catch (e: NumberFormatException) {
        null
    }

    private fun parsePositiveAmount(text: String): BigDecimal? = parseAmount(text)?.takeIf { it.signum() > 0 }

    private fun parseDate(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (e: java.time.format.DateTimeParseException) {
        null
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

    /**
     * The tyre fittings as text (PRD F56, docs/SPEC.md §56), parsed by the core; a value of the wrong type reads as none. A phone setting, so it is
     * in Auto Backup like the budget and is not part of the archive export.
     */
    fun tyreChangesText(): String? = try {
        prefs.getString(KEY_TYRE_CHANGES, null)
    } catch (e: ClassCastException) {
        null
    }

    fun saveTyreChangesText(text: String) {
        prefs.edit { putString(KEY_TYRE_CHANGES, text) }
    }

    /** The reminder thresholds per set as text (PRD F56); the same rules as [tyreChangesText]. */
    fun tyreThresholdsText(): String? = try {
        prefs.getString(KEY_TYRE_THRESHOLDS, null)
    } catch (e: ClassCastException) {
        null
    }

    fun saveTyreThresholdsText(text: String) {
        prefs.edit { putString(KEY_TYRE_THRESHOLDS, text) }
    }

    /** The tyre reminder keys already sent, as text (PRD F56, one per set, fitting day and kind). */
    fun tyreReminderKeysText(): String? = try {
        prefs.getString(KEY_TYRE_REMINDER_KEYS, null)
    } catch (e: ClassCastException) {
        null
    }

    fun saveTyreReminderKeysText(text: String) {
        prefs.edit { putString(KEY_TYRE_REMINDER_KEYS, text) }
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
        const val KEY_ECO_CONSUMPTION_SINCE = "eco_goal_consumption_since"
        const val KEY_ECO_EV_SINCE = "eco_goal_ev_since"
        private val MONTH_TEXT = Regex("(\\d{4})-(\\d{2})")
        const val KEY_MONTHLY_BUDGET = "monthly_budget"
        const val KEY_CAR_PURCHASE = "car_purchase_pln"
        const val KEY_CAR_PURCHASE_DATE = "car_purchase_date"
        const val KEY_CAR_VALUE = "car_value_pln"
        const val KEY_CAR_VALUE_DATE = "car_value_date"
        const val KEY_BUDGET_ALERT = "budget_alert"
        const val KEY_BUDGET_ALERT_LAST = "budget_alert_last"
        const val KEY_PERIOD_SUMMARY = "period_summary"
        const val KEY_PERIOD_SUMMARY_LAST = "period_summary_last"
        const val KEY_TYRE_CHANGES = "tyre_changes"
        const val KEY_TYRE_THRESHOLDS = "tyre_thresholds"
        const val KEY_TYRE_REMINDER_KEYS = "tyre_reminder_keys"
        const val KEY_ALERT = "consumption_alert"
        const val KEY_NOTIFIED_REFUELS = "refuel_notified_ids"
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        const val KEY_LAST_ARCHIVE_EXPORT = "last_archive_export"
    }
}
