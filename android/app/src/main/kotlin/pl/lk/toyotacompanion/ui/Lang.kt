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
