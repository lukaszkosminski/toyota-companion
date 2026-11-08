package pl.lk.toyotacompanion.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The two UI languages (Settings → "Język / Language"). Polish is the default and what the unit tests see. */
enum class AppLanguage(val tag: String, val label: String) {
    PL("pl", "Polski"),
    EN("en", "English"),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag } ?: PL
    }
}

/**
 * The language the texts are read in. [current] is Compose snapshot state, so every composable that reads a text through
 * [Texts] recomposes when the owner switches the language; outside Compose (notifications, the widget) the value is simply read.
 */
object Lang {
    var current: AppLanguage by mutableStateOf(AppLanguage.PL)

    val isEnglish: Boolean get() = current == AppLanguage.EN

    /** [pl] or [en] depending on the current language. */
    fun pick(pl: String, en: String): String = if (isEnglish) en else pl
}

/**
 * The Polish noun form for [n]: [one] for 1 ("1 trasa"), [few] for 2–4 except 12–14 ("22 trasy"),
 * [many] for the rest ("5 tras", "12 tras", "0 tras"). Only the word, the number is the caller's.
 */
fun plPlural(n: Long, one: String, few: String, many: String): String {
    val abs = kotlin.math.abs(n)
    return when {
        abs == 1L -> one
        abs % 10 in 2..4 && abs % 100 !in 12..14 -> few
        else -> many
    }
}

/** The English noun form for [n]: [one] only for exactly 1. */
fun enPlural(n: Long, one: String, many: String): String = if (n == 1L) one else many
