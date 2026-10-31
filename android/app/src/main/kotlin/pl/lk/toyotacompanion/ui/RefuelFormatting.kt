package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.toBigDecimal

/**
 * Polish formatting of one refuel row (docs/SPEC.md §11.2). Like
 * [TripsFormatting] this is pure string work, independent of
 * `Locale.getDefault()`: the zone arrives as a parameter and the thousand
 * separator is inserted by hand.
 */
object RefuelFormatting {

    /** When the refuel happened, e.g. `29.10 14:05` — the same shape as the trip list. */
    fun time(ts: Instant, zone: ZoneId): String = TripsFormatting.startDateTime(ts, zone)

    /** Fuel level before and after, e.g. `20% → 80%`. */
    fun levelJump(levelBefore: Int, levelAfter: Int): String = "$levelBefore% \u2192 $levelAfter%"

    /**
     * Odometer as whole kilometres with a plain space as the thousand separator,
     * e.g. `12 345 km`. Rounded `HALF_UP` to whole kilometres; no `Locale` and no
     * `NumberFormat`, so the separator cannot turn into a dot or a comma.
     */
    fun odometer(odometerKm: Double): String =
        groupThousands(toBigDecimal(odometerKm).setScale(0, RoundingMode.HALF_UP).toPlainString()) + " km"

    /**
     * Liters for the form field, e.g. `14,19`: the full precision of the domain
     * (0.001 l) without trailing zeros, so an accepted estimate stores exactly the
     * number §6 would use and the tank price does not move (docs/SPEC.md §2a).
     */
    fun litersInput(liters: BigDecimal): String =
        liters.stripTrailingZeros().toPlainString().replace('.', ',')

    /** Liters for reading, e.g. `14,19 l` — the same precision as [litersInput]. */
    fun liters(liters: BigDecimal): String = litersInput(liters) + " l"

    private fun groupThousands(plain: String): String {
        val sign = if (plain.startsWith('-')) "-" else ""
        val digits = plain.removePrefix("-")
        val first = (digits.length - 1) % 3 + 1
        val out = StringBuilder(digits.length + digits.length / 3)
        for (index in digits.indices) {
            if (index > 0 && (index - first) % 3 == 0) out.append(' ')
            out.append(digits[index])
        }
        return sign + out
    }
}
