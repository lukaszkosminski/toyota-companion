package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Depreciation of one year, straight-line in days (PRD F55, docs/SPEC.md §55). Mirrors
 * `backend/app/domain/ownership.py` `value_loss_in_year`.
 *
 * The whole loss is `max(0, purchase - value)`; the daily rate divides it by the calendar days
 * from the purchase to the valuation. A year gets only the days of `[purchaseDate, valueDate)`
 * that fall inside it, so the year before the purchase and every year after the valuation are 0 -
 * the loss never grows past the valuation. Rounded to 0.01 zl, HALF_UP, from the unrounded daily rate.
 */
fun valueLossInYear(
    purchasePln: BigDecimal,
    purchaseDate: LocalDate,
    valuePln: BigDecimal,
    valueDate: LocalDate,
    year: Int,
): BigDecimal {
    require(purchasePln.signum() > 0) { "purchase price must be positive" }
    require(valuePln.signum() >= 0) { "value must not be negative" }
    require(!valueDate.isBefore(purchaseDate)) { "valuation date is before the purchase date" }
    val loss = (purchasePln.subtract(valuePln)).max(BigDecimal.ZERO)
    val totalDays = ChronoUnit.DAYS.between(purchaseDate, valueDate)
    if (loss.signum() == 0 || totalDays <= 0) return BigDecimal.ZERO.setScale(2)
    val start = maxOf(LocalDate.of(year, 1, 1), purchaseDate)
    val end = minOf(LocalDate.of(year + 1, 1, 1), valueDate)
    val days = ChronoUnit.DAYS.between(start, end)
    if (days <= 0) return BigDecimal.ZERO.setScale(2)
    return loss.divideLikePythonDecimal(BigDecimal(totalDays))
        .multiply(BigDecimal(days))
        .setScale(2, RoundingMode.HALF_UP)
}
