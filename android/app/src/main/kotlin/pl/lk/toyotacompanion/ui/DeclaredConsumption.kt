package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The largest declared consumption the field accepts, l/100 km: above it the typed number is a typo, not a car. */
val DECLARED_MAX: BigDecimal = BigDecimal(20)

/** Result of reading the typed declared consumption. */
sealed interface DeclaredCheck {
    data class Valid(val value: BigDecimal) : DeclaredCheck

    /** An empty field removes the setting. */
    data object Empty : DeclaredCheck

    data object Invalid : DeclaredCheck
}

/** `4,5` or `4.5` → 4.50; positive, at most [DECLARED_MAX], two decimals HALF_UP. Anything else is invalid, never clamped. */
fun parseDeclaredConsumption(text: String): DeclaredCheck {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return DeclaredCheck.Empty
    val value = try {
        BigDecimal(trimmed.replace(',', '.'))
    } catch (e: NumberFormatException) {
        return DeclaredCheck.Invalid
    }
    if (value.signum() <= 0 || value > DECLARED_MAX) return DeclaredCheck.Invalid
    return DeclaredCheck.Valid(value.setScale(2, RoundingMode.HALF_UP))
}

/** `Twoje średnie względem deklarowanego: -8 % (taniej)`; `null` without either number. */
fun declaredComparison(own: BigDecimal?, declared: BigDecimal?): String? {
    val change = percentChange(own, declared) ?: return null
    val word = when {
        change.startsWith("+") -> Texts.DECLARED_MORE
        change.startsWith("-") -> Texts.DECLARED_LESS
        else -> Texts.DECLARED_SAME
    }
    return Texts.declaredVersus(TripsFormatting.plain(requireNotNull(declared), 2), change, word)
}

/** The declared consumption of the owner's car (docs/SPEC.md §30): a value kept in the settings and shown on the card. */
class DeclaredConsumptionController(
    private val read: () -> BigDecimal?,
    private val save: (BigDecimal?) -> Unit,
) {
    private val ui = MutableStateFlow<BigDecimal?>(null)
    val state: StateFlow<BigDecimal?> = ui.asStateFlow()
    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            loaded = true
            ui.value = read()
        }
    }

    /** Saves a typed value; returns false (and changes nothing) for an invalid one. */
    fun set(text: String): Boolean = when (val check = parseDeclaredConsumption(text)) {
        is DeclaredCheck.Valid -> {
            save(check.value)
            ui.value = check.value
            true
        }
        DeclaredCheck.Empty -> {
            save(null)
            ui.value = null
            true
        }
        DeclaredCheck.Invalid -> false
    }
}
