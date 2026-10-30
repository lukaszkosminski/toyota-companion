package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What the default price form may store (docs/SPEC.md §12.3): a rounded price, or
 * the reason the screen has to show instead.
 */
sealed class DefaultPriceCheck {
    /** Ready to store, rounded to 0.001 zl/l. */
    data class Valid(val price: BigDecimal) : DefaultPriceCheck()

    /**
     * @param message the Polish text of the error card, never an exception message
     * (docs/SECURITY.md)
     */
    data class Invalid(val message: String) : DefaultPriceCheck()
}

/** Price per liter rounded to 0.001 zl/l, ROUND_HALF_UP — the price rule of docs/SPEC.md §2. */
private fun roundStoredPrice(value: BigDecimal): BigDecimal = value.setScale(3, RoundingMode.HALF_UP)

/**
 * Reads the one field of the default price form: the same keyboard syntax as the
 * refuel amounts (comma or dot, docs/SPEC.md §11.2), then the sign, then the
 * rounding of a price (§2).
 *
 * An empty field is a mistake and not "remove the price" — removing has its own
 * button (docs/SPEC.md §12.3). A value that rounds down to zero is refused, so the
 * check on the sign happens after the rounding. `roundPrice` of `core.domain` is `internal` to that
 * module, so the rounding rule is repeated here on purpose, exactly like the refuel
 * form repeats the combinations of §2; `DefaultPriceFormTest` pins both sides.
 */
fun parseDefaultPrice(text: String): DefaultPriceCheck =
    when (val amount = parseRefuelAmount(text)) {
        RefuelAmount.Missing -> DefaultPriceCheck.Invalid(Texts.DEFAULT_PRICE_EMPTY_ERROR)
        RefuelAmount.NotANumber -> DefaultPriceCheck.Invalid(Texts.REFUEL_NUMBER_ERROR)
        is RefuelAmount.Given -> {
            // The sign is checked on the stored value: `0,0004` rounds to 0.000 and a zero
            // price would cost every trip 0,00 zl (docs/SPEC.md §12.3).
            val rounded = roundStoredPrice(amount.value)
            if (rounded <= BigDecimal.ZERO) {
                DefaultPriceCheck.Invalid(Texts.REFUEL_POSITIVE_ERROR)
            } else {
                DefaultPriceCheck.Valid(rounded)
            }
        }
    }
