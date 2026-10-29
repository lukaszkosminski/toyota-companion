package pl.lk.toyotacompanion.ui

import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncStatus

/**
 * Polish UI texts (the app is single-language, docs/SPEC.md §9).
 *
 * Plain functions instead of string resources: they are testable without
 * Android and the `when` over an enum forces a message for every error kind.
 * Never a password, token, VIN or GPS position (docs/SECURITY.md).
 */
object Texts {
    const val LOGIN_TITLE = "Zaloguj się do MyToyota"
    const val LOGIN_FIELD = "Login"
    const val PASSWORD_FIELD = "Hasło"
    const val LOGIN_BUTTON = "Zaloguj"
    const val LOGIN_BUSY = "Logowanie…"
    const val LOGOUT_BUTTON = "Wyloguj"
    const val SYNC_NOW_BUTTON = "Synchronizuj teraz"
    const val SYNCING = "Synchronizacja w toku…"
    const val STATUS_TITLE = "Status synchronizacji"
    const val NO_DATA = "Brak danych"
    const val RELOGIN_HINT = "Zaloguj się ponownie – Toyota odrzuciła dane logowania."

    /** docs/SPEC.md §9.2 — what each login failure means for the user. */
    fun loginError(error: LoginError): String = when (error) {
        LoginError.EMPTY_FIELDS -> "Podaj login i hasło."
        LoginError.INVALID_CREDENTIALS -> "Toyota odrzuciła logowanie – sprawdź login i hasło."
        LoginError.NETWORK -> "Brak internetu. Sprawdź połączenie i spróbuj ponownie."
        LoginError.SERVICE -> "Serwis Toyoty niedostępny. Spróbuj później."
    }

    /** docs/SPEC.md §9.3 — only the kind is shown, never the stored message. */
    fun syncError(kind: SyncErrorKind): String = when (kind) {
        SyncErrorKind.NO_CREDENTIALS -> "Brak danych logowania – zaloguj się."
        SyncErrorKind.LOGIN -> "Toyota odrzuciła logowanie – zaloguj się ponownie."
        SyncErrorKind.NO_VEHICLE -> "Brak pojazdu na koncie lub kilka pojazdów do wyboru."
        SyncErrorKind.API -> "Serwis Toyoty niedostępny. Spróbuj później."
        SyncErrorKind.NETWORK -> "Brak internetu. Spróbuj później."
        SyncErrorKind.DATA -> "Nieoczekiwana odpowiedź Toyoty. Spróbuj później."
    }

    /** "aktualne" / "nieaktualne" — local data on screen but not current (docs/SPEC.md §8.4). */
    fun freshness(status: SyncStatus): String = if (status.isFresh) "aktualne" else "nieaktualne"

    fun trips(count: Int): String = "Trasy: $count"

    fun readings(count: Int): String = "Odczyty paliwa: $count"

    fun pendingRefuels(count: Int): String = "Oczekujące tankowania: $count"

    /** Last full success as `YYYY-MM-DD HH:mm` in the local zone, or "brak". */
    fun lastSuccess(status: SyncStatus, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = status.state.lastSuccessAt ?: return "Ostatnia synchronizacja: brak"
        val local = at.atZone(zone)
        return "Ostatnia synchronizacja: " +
            "%04d-%02d-%02d %02d:%02d".format(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute)
    }
}
