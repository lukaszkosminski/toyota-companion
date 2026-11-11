package pl.lk.toyotacompanion.settings

import java.math.BigDecimal
import java.time.LocalDate

/**
 * The owner's own valuation of the car (PRD F55, docs/SPEC.md §55): the purchase price and date,
 * the estimated value today and the date of that valuation. A phone setting, not account data;
 * not a secret, so it lives in the plain `settings` file and reaches Auto Backup (ADR-006).
 */
data class CarValuation(
    val purchasePln: BigDecimal,
    val purchaseDate: LocalDate,
    val valuePln: BigDecimal,
    val valueDate: LocalDate,
)
