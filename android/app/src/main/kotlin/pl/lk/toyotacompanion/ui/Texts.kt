package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.sync.RefuelChange

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

    /** Last full success as `YYYY-MM-DD HH:mm` in the local zone, or "brak". */
    fun lastSuccess(status: SyncStatus, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = status.state.lastSuccessAt ?: return "Ostatnia synchronizacja: brak"
        val local = at.atZone(zone)
        return "Ostatnia synchronizacja: " +
            "%04d-%02d-%02d %02d:%02d".format(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute)
    }

    /** Dialog confirming logout, so a tap on "Wyloguj" is never final by itself. */
    const val LOGOUT_CONFIRM_TITLE = "Wylogować się?"
    const val LOGOUT_CONFIRM_BODY =
        "Usuniemy zapisane dane logowania i dostęp Toyoty. Trasy i odczyty zostaną " +
            "w bazie do następnego logowania."
    const val LOGOUT_CONFIRM_YES = "Wyloguj"
    const val LOGOUT_CONFIRM_NO = "Anuluj"

    /** Title of the status screen; same wording as `app_name` in res/values/strings.xml. */
    const val APP_NAME = "Toyota Companion"

    /** First read of the local archive. */
    const val LOADING = "Ładowanie…"

    /** Reads the archive again, without asking Toyota (docs/SPEC.md §9.3). */
    const val REFRESH_BUTTON = "Odśwież"

    const val SHOW_PASSWORD = "Pokaż"
    const val HIDE_PASSWORD = "Ukryj"

    /** Stat cards show the number alone, the caption below says what it counts. */
    const val STAT_TRIPS = "Trasy"
    const val STAT_READINGS = "Odczyty paliwa"
    const val STAT_PENDING_REFUELS = "Oczekujące tankowania"

    /** Chip on the status screen when there is nothing fresh or stale to say yet. */
    const val STATE_NEVER = "brak synchronizacji"
    const val STATE_NEEDS_RELOGIN = "wymaga logowania"

    // ---------------- Trips list (docs/SPEC.md §10) ----------------

    /** Title of the trips screen and the button that opens it from the status screen. */
    const val TRIPS_TITLE = "Trasy"

    /** Every secondary screen leaves through a text button with this word (no Icons). */
    const val BACK = "Wstecz"

    /** The archive of the selected vehicle is empty. */
    const val TRIPS_EMPTY = "Brak tras – zsynchronizuj dane"

    /** The local read failed; never the exception text (docs/SECURITY.md). */
    const val TRIPS_READ_ERROR = "Nie udało się odczytać tras. Spróbuj ponownie."

    /** Trip without a usable cost, and the month header when no trip in it has one. */
    const val NO_PRICE = "brak ceny"

    /** The cost came from the configured default price, not from a refuel (docs/SPEC.md §4). */
    const val DEFAULT_PRICE_NOTE = "cena domyślna"

    /** Separates the values inside one line of the trips list. */
    const val SEPARATOR = " · "

    /** „bez ceny: 3” — the month header shows it only when the count is not zero. */
    fun tripsWithoutCost(count: Int): String = "bez ceny: $count"

    // ---------------- refuel price form (docs/SPEC.md §11) ----------------

    /** Title of the list of refuels that still wait for a price. */
    const val REFUELS_TITLE = "Tankowania"

    /** The selected vehicle has no refuel waiting for a price. */
    const val REFUELS_EMPTY = "Brak tankowań czekających na cenę"

    /** The local read failed; never the exception text (docs/SECURITY.md). */
    const val REFUELS_READ_ERROR = "Nie udało się odczytać tankowań. Spróbuj ponownie."

    /** Button on the status screen; [count] is how many refuels still wait for a price. */
    fun refuelPrompt(count: Int): String = "Podaj cenę tankowania ($count)"

    /** Title of the form. */
    const val REFUEL_FORM_TITLE = "Cena tankowania"

    const val REFUEL_FIELD_PRICE = "Cena za litr"
    const val REFUEL_FIELD_LITERS = "Litry"
    const val REFUEL_FIELD_TOTAL = "Kwota"

    /** Only usable when the liters are known (docs/SPEC.md §11.3). */
    const val REFUEL_FILLED_FULL = "Do pełna"

    const val REFUEL_SAVE = "Zapisz"
    const val REFUEL_DISMISS = "Odrzuć"

    /** Refusing an entry is final for that entry, so it is confirmed first. */
    const val REFUEL_DISMISS_TITLE = "Odrzucić to tankowanie?"
    const val REFUEL_DISMISS_BODY =
        "Odrzucone tankowanie nie wejdzie do kosztów tras i nie będzie czekać na cenę."
    const val REFUEL_DISMISS_YES = "Odrzuć"
    const val REFUEL_DISMISS_NO = "Anuluj"

    /** `6,1,9` or `abc` in an amount field. */
    const val REFUEL_NUMBER_ERROR = "To nie jest liczba"

    /** `0` or a negative amount; the domain would refuse the same value (§2). */
    const val REFUEL_POSITIVE_ERROR = "Wartość musi być większa od zera"

    /** The form has to keep the combinations of docs/SPEC.md §2. */
    const val REFUEL_COMBINATION_ERROR = "Podaj cenę za litr albo litry i kwotę"

    /** The domain refused the values although the form check passed (docs/SPEC.md §11.2). */
    const val REFUEL_WRITE_ERROR = "Nie udało się zapisać ceny. Popraw wartości i spróbuj ponownie."

    /** The write itself failed (the database, not the values): the user may try again. */
    const val REFUEL_SAVE_FAILED = "Nie udało się zapisać. Spróbuj ponownie."

    /** The entry is gone, already answered or belongs to another vehicle. */
    const val REFUEL_STALE_ENTRY = "Ten wpis już nie czeka na cenę. Odśwież listę."

    /** Polish message of a refused write; null when the write went through. */
    fun refuelChangeError(change: RefuelChange): String? = when (change) {
        is RefuelChange.Done -> null
        RefuelChange.Invalid -> REFUEL_WRITE_ERROR
        RefuelChange.NotFound, RefuelChange.NotPending, RefuelChange.NoVehicle -> REFUEL_STALE_ENTRY
    }

    // ---------------- domyślna cena paliwa (docs/SPEC.md §12) ----------------

    /** Title of the default price screen. */
    const val DEFAULT_PRICE_TITLE = "Cena domyślna"

    /** Status-screen button when no default price is stored yet. */
    const val DEFAULT_PRICE_PROMPT_UNSET = "Ustaw cenę domyślną paliwa"

    /**
     * Status-screen button: [price] in the label when one is stored, e.g.
     * `Cena domyślna paliwa: 6,19 zł/l`, the plain request when none is.
     */
    fun defaultPricePrompt(price: BigDecimal?): String =
        if (price == null) DEFAULT_PRICE_PROMPT_UNSET
        else "Cena domyślna paliwa: " + TripsFormatting.pricePerLiter(price)

    /** Under the field: which trips the default price prices (docs/SPEC.md §4). */
    const val DEFAULT_PRICE_HINT =
        "Cena domyślna liczy trasy sprzed pierwszego potwierdzonego tankowania."

    /** Removes the setting; an empty field never does that (docs/SPEC.md §12.3). */
    const val DEFAULT_PRICE_REMOVE = "Usuń cenę domyślną"

    const val DEFAULT_PRICE_REMOVE_TITLE = "Usunąć cenę domyślną?"
    const val DEFAULT_PRICE_REMOVE_BODY =
        "Trasy bez potwierdzonego tankowania znów będą bez ceny."
    const val DEFAULT_PRICE_REMOVE_YES = "Usuń"
    const val DEFAULT_PRICE_REMOVE_NO = "Anuluj"

    /** "Zapisz" with an empty field: removing the price has its own button. */
    const val DEFAULT_PRICE_EMPTY_ERROR =
        "Podaj cenę za litr albo usuń cenę przyciskiem poniżej"

}
