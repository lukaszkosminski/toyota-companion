package pl.lk.toyotacompanion.ui

import java.math.BigDecimal

/** Which field of the refuel form a problem refers to (docs/SPEC.md §11.2). */
enum class RefuelField {
    Price,
    Liters,
    Total,
}

/** One field problem: the field to mark and the Polish text to show under it. */
data class RefuelFieldError(val field: RefuelField, val message: String)

/**
 * What the refuel form may send (docs/SPEC.md §11.2): either three amounts ready
 * for the domain, or the fields the user has to fix first.
 *
 * The domain (`core/domain`, docs/SPEC.md §2) stays the only authority for what
 * the numbers mean — this check only covers the syntax, the sign and the
 * combination, so the screen can say it in Polish instead of showing the English
 * `IllegalArgumentException` of the domain (docs/SECURITY.md). Knowing the allowed
 * combinations twice is a deliberate trade-off, pinned by `RefuelFormTest`.
 */
sealed class RefuelFormCheck {
    /**
     * @param price `null` when the user gave no price and the domain has to derive it
     */
    data class Valid(val price: BigDecimal?, val liters: BigDecimal?, val total: BigDecimal?) : RefuelFormCheck()

    /**
     * @param message what the error card shows: the first problem the user can act on
     * @param errors every problem, so each field can carry its own note
     */
    data class Invalid(val message: String, val errors: List<RefuelFieldError> = emptyList()) : RefuelFormCheck()
}

/** One amount field after reading the keyboard. */
sealed class RefuelAmount {
    /** The user left the field empty — the domain will derive this magnitude. */
    data object Missing : RefuelAmount()

    data class Given(val value: BigDecimal) : RefuelAmount()

    /** Anything that is not a plain decimal number, e.g. `6,1,9` or `abc`. */
    data object NotANumber : RefuelAmount()
}

/** Optional minus on purpose: a negative amount is a *value* error, not a typing error. */
private val DECIMAL_TEXT = Regex("-?[0-9]+([.,][0-9]+)?")

/** The combinations docs/SPEC.md §2 admits: (price), (liters+total), (price+liters), (price+total). */
private val ALLOWED_GIVEN: Set<Set<String>> = setOf(
    setOf("price"),
    setOf("liters", "total"),
    setOf("price", "liters"),
    setOf("price", "total"),
)

/**
 * Reads one amount field: [String.trim] first, a comma or a dot as the decimal
 * separator (the decimal keyboard differs between devices), an empty field as
 * [RefuelAmount.Missing]. No [java.util.Locale] and no `NumberFormat` — the value
 * is built from digits with a normalized dot (docs/SPEC.md §11.2).
 */
fun parseRefuelAmount(text: String): RefuelAmount {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return RefuelAmount.Missing
    if (!DECIMAL_TEXT.matches(trimmed)) return RefuelAmount.NotANumber
    return RefuelAmount.Given(BigDecimal(trimmed.replace(',', '.')))
}

/**
 * Checks the whole form: syntax of every field, then the sign, then the
 * combination. The first problem found is the one the error card shows.
 */
fun checkRefuelForm(priceText: String, litersText: String, totalText: String): RefuelFormCheck {
    val errors = mutableListOf<RefuelFieldError>()
    val values = mutableMapOf<RefuelField, BigDecimal?>()

    for ((field, text) in listOf(
        RefuelField.Price to priceText,
        RefuelField.Liters to litersText,
        RefuelField.Total to totalText,
    )) {
        when (val amount = parseRefuelAmount(text)) {
            RefuelAmount.NotANumber -> errors += RefuelFieldError(field, Texts.REFUEL_NUMBER_ERROR)
            is RefuelAmount.Given -> {
                values[field] = amount.value
                if (amount.value <= BigDecimal.ZERO) {
                    errors += RefuelFieldError(field, Texts.REFUEL_POSITIVE_ERROR)
                }
            }

            RefuelAmount.Missing -> values[field] = null
        }
    }

    if (errors.isNotEmpty()) return RefuelFormCheck.Invalid(errors.first().message, errors)

    val price = values[RefuelField.Price]
    val liters = values[RefuelField.Liters]
    val total = values[RefuelField.Total]
    val given = setOfNotNull("price" to price, "liters" to liters, "total" to total)
    if (given !in ALLOWED_GIVEN) return RefuelFormCheck.Invalid(Texts.REFUEL_COMBINATION_ERROR)

    return RefuelFormCheck.Valid(price, liters, total)
}

private fun setOfNotNull(vararg pairs: Pair<String, BigDecimal?>): Set<String> =
    pairs.filter { it.second != null }.map { it.first }.toSet()
