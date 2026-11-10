package pl.lk.toyotacompanion.core.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Approximate CO2 emitted by burning one litre of gasoline: 2.3 kg (Natural Resources Canada,
 * "Learn the facts: Fuel consumption and CO2": "Burning 1 L of gasoline produces approximately
 * 2.3 kg of CO2"). Combustion only; the same value lives in `backend/app/domain/co2.py`.
 */
val CO2_KG_PER_LITER_GASOLINE: BigDecimal = BigDecimal("2.3")

/**
 * CO2 from [fuelL] litres of gasoline in kilograms, one decimal, HALF_UP (PRD F51, docs/SPEC.md §51).
 * Mirrors `co2_kg` in `backend/app/domain/co2.py`. Negative litres are a programming error.
 */
fun co2Kg(fuelL: BigDecimal): BigDecimal {
    require(fuelL.signum() >= 0) { "fuel must not be negative" }
    return fuelL.multiply(CO2_KG_PER_LITER_GASOLINE).setScale(1, RoundingMode.HALF_UP)
}
