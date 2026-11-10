package pl.lk.toyotacompanion.ui

import pl.lk.toyotacompanion.core.domain.BudgetProgress

/**
 * The monthly budget notification (PRD F49, docs/SPEC.md §49): one warning at 80% of the limit and one when the
 * limit is really exceeded (spent > limit), once per month and threshold. Pure, so the rule is tested without Android; the notifier posts it.
 */

const val BUDGET_ALERT_WARN_PCT: Int = 80
const val BUDGET_ALERT_OVER_PCT: Int = 100

/** One alert to post: [key] is `RRRR-MM:80` or `RRRR-MM:100`, the value kept in the settings. */
data class BudgetAlert(
    val key: String,
    val thresholdPct: Int,
    val progress: BudgetProgress,
    val month: MonthKey,
)

/**
 * The alert for [progress] in [month], or `null` below 80% (whole percent, HALF_UP, so 79.5% already counts) or when this month already told the same or a higher
 * threshold ([lastKey]). 80 after 100 is silence, 100 after 80 is an alert; a new month starts again. An
 * unreadable [lastKey] is treated as "nothing told yet".
 */
fun budgetAlert(progress: BudgetProgress, month: MonthKey, lastKey: String?): BudgetAlert? {
    val threshold = when {
        // Over budget means really over: spent > limit. 99.95% or exactly the limit still reads as the 80% warning.
        progress.overBudget -> BUDGET_ALERT_OVER_PCT
        progress.percentUsed >= BUDGET_ALERT_WARN_PCT -> BUDGET_ALERT_WARN_PCT
        else -> return null
    }
    val monthKey = budgetMonthKey(month)
    val last = parseBudgetAlertKey(lastKey)
    if (last != null && last.monthKey == monthKey && last.thresholdPct >= threshold) return null
    return BudgetAlert("$monthKey:$threshold", threshold, progress, month)
}

/** `YYYY-MM`, the month part of the key. */
fun budgetMonthKey(month: MonthKey): String =
    month.year.toString() + "-" + month.month.toString().padStart(2, '0')

/** A stored key split into its month and threshold; `null` for anything else. */
internal data class StoredBudgetAlert(val monthKey: String, val thresholdPct: Int)

internal fun parseBudgetAlertKey(key: String?): StoredBudgetAlert? {
    if (key == null) return null
    val parts = key.split(':')
    if (parts.size != 2) return null
    val month = parts[0]
    if (!Regex("\\d{4}-\\d{2}").matches(month)) return null
    val pct = parts[1].toIntOrNull() ?: return null
    if (pct != BUDGET_ALERT_WARN_PCT && pct != BUDGET_ALERT_OVER_PCT) return null
    return StoredBudgetAlert(month, pct)
}

/** Title of the notification: the 80% warning or the exceeded budget. */
fun budgetAlertTitle(alert: BudgetAlert): String =
    if (alert.thresholdPct >= BUDGET_ALERT_OVER_PCT) Texts.BUDGET_ALERT_OVER_TITLE else Texts.BUDGET_ALERT_WARN_TITLE

/**
 * The body: month, spent, limit and percent; the exceeded one adds how much over. Only amounts, never a VIN, a place
 * or a coordinate (docs/SECURITY.md).
 */
fun budgetAlertText(alert: BudgetAlert): String {
    val progress = alert.progress
    val month = TripsFormatting.monthLabel(alert.month)
    val spent = TripsFormatting.plain(progress.spentPln, 2)
    val limit = TripsFormatting.plain(progress.limitPln, 2)
    return if (alert.thresholdPct >= BUDGET_ALERT_OVER_PCT) {
        Texts.budgetAlertOver(month, spent, limit, progress.percentUsed, TripsFormatting.plain(progress.remainingPln.abs(), 2))
    } else {
        Texts.budgetAlertWarn(month, spent, limit, progress.percentUsed, TripsFormatting.plain(progress.remainingPln, 2))
    }
}
