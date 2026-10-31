package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.ZoneId
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.sync.ArchiveImportCounts
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
        SyncErrorKind.STORAGE -> "Nie udało się zapisać danych w telefonie. Sprawdź wolne miejsce i spróbuj ponownie."
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

    /** D3 of PRD F2a: an estimate cannot be the base of a price (docs/SPEC.md §11.2). */
    const val REFUEL_ESTIMATE_NEEDS_PRICE = "Podaj cenę za litr \u2013 z szacunku litrów nie da się wyliczyć ceny"

    /** Sensor estimate under the liters field, e.g. `Szacunek z czujnika: ok. 14,19 l` (§11.3). */
    fun refuelEstimateHint(liters: String): String = "Szacunek z czujnika: ok. $liters"

    /** Fills the liters field with the estimate. */
    const val REFUEL_USE_ESTIMATE = "U\u017cyj szacunku"

    /** Shown while the liters field holds the accepted estimate. */
    const val REFUEL_LITERS_FROM_ESTIMATE = "Litry z szacunku czujnika"

    /** Takes the estimate mark off: the same number is from the receipt. */
    const val REFUEL_LITERS_FROM_RECEIPT = "Z paragonu"

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


    /** Name of the notification channel of refuels (docs/SPEC.md §13.1). */
    const val REFUEL_NOTIFICATION_CHANNEL = "Tankowania"

    /**
     * Title of the heads-up notification and of the in-app prompt: the same words,
     * because both say the same thing (docs/SPEC.md §13).
     */
    const val REFUEL_NOTIFICATION_TITLE = "Wykryto tankowanie"

    /**
     * Notification text: when and how much the level changed, then the request, e.g.
     * `30.10 14:05 · 20% → 80%. Podaj cenę za litr.` No VIN, no GPS, no odometer
     * (docs/SECURITY.md, docs/SPEC.md §13.1).
     */
    fun refuelNotificationText(time: String, levelJump: String): String =
        "$time \u00b7 $levelJump. Podaj cen\u0119 za litr."

    /** First line of the in-app prompt: the refuel this question is about. */
    fun refuelPromptBody(time: String, levelJump: String): String = "$time \u00b7 $levelJump"

    /** "Not now": silences this one entry for the rest of the process (docs/SPEC.md §13.3). */
    const val REFUEL_PROMPT_LATER = "P\u00f3\u017aniej"

    /** Opens the full form of C3 with liters and total cost. */
    const val REFUEL_PROMPT_MORE = "Wi\u0119cej"


    // Kopia archiwum (PRD F7, F8, docs/SPEC.md §14).

    /** Status button and the title of the backup screen. */
    const val BACKUP_TITLE = "Kopia i eksport"

    const val BACKUP_EXPORT = "Eksportuj archiwum"
    const val BACKUP_IMPORT = "Importuj archiwum"

    /** Shown before the system file picker, docs/SECURITY.md. */
    const val BACKUP_WARNING_TITLE = "Plik z danymi osobowymi"
    const val BACKUP_WARNING_BODY =
        "Kopia zawiera trasy z położeniem GPS i numer VIN samochodu. Zapisz ją w bezpiecznym miejscu i nie udostępniaj."
    const val BACKUP_WARNING_OK = "Eksportuj"
    const val BACKUP_WARNING_CANCEL = "Anuluj"

    /** Explains the screen: the phone is the only place the old trips live (ADR-006). */
    const val BACKUP_EXPLANATION =
        "Toyota usuwa trasy starsze niż około rok, więc archiwum jest tylko w tym telefonie. " +
            "Kopia to jeden plik – import na nowym telefonie dodaje tylko to, czego jeszcze nie ma."

    fun backupExported(trips: Int): String = "Zapisano kopię archiwum (trasy: $trips)."

    /** Counts without Polish plural forms: "trasy: 3" reads right for every number. */
    fun backupImported(counts: ArchiveImportCounts): String = buildString {
        append("Zaimportowano – trasy: ${counts.tripsAdded}, tankowania: ${counts.refuelsAdded}, ")
        append("odczyty paliwa: ${counts.readingsAdded}.")
        if (counts.refuelsUpdated > 0) append(" Uzupełnione ceny tankowań: ${counts.refuelsUpdated}.")
        if (counts.refuelsSkipped > 0) {
            append(" Pominięte tankowania (odrzucone w tym telefonie): ${counts.refuelsSkipped}.")
        }
        if (counts.otherVehicleTrips > 0) {
            append(" Trasy innego pojazdu: ${counts.otherVehicleTrips} – widać je przy tamtym pojeździe.")
        }
    }

    const val BACKUP_UNSUPPORTED =
        "To nie jest kopia archiwum tej aplikacji albo pochodzi z nowszej wersji. Nic nie zostało zapisane."
    const val BACKUP_CORRUPT = "Plik kopii jest uszkodzony. Nic nie zostało zapisane."
    const val BACKUP_TOO_LARGE = "Plik jest za duży na kopię archiwum. Nic nie zostało zapisane."
    const val BACKUP_FAILED = "Nie udało się zapisać ani odczytać pliku. Spróbuj ponownie."

    /** Status line under the backup button, e.g. `Ostatnia kopia archiwum: 31.10.2026`. */
    fun lastBackup(date: String): String = "Ostatnia kopia archiwum: $date"

    const val BACKUP_NONE_YET = "Archiwum nie ma jeszcze kopii"

    // Arkusz tras (PRD F6, docs/SPEC.md §14.4).

    const val CSV_SECTION = "Arkusz kalkulacyjny"
    const val CSV_EXPLANATION =
        "Trasy wybranego pojazdu z kosztem w pliku CSV dla Excela i Arkuszy Google – bez położenia GPS i numeru VIN."
    const val CSV_EXPORT = "Eksportuj trasy do CSV"
    const val CSV_NO_TRIPS = "Brak tras do eksportu."

    fun csvExported(trips: Int): String = "Zapisano arkusz tras (trasy: $trips)."

    /** Header cells; the unit lives here, so the cells below stay plain numbers. */
    val CSV_HEADER: List<String> = listOf(
        "Start",
        "Koniec",
        "Dystans [km]",
        "Paliwo [l]",
        "Dystans EV [km]",
        "Cena [zł/l]",
        "Koszt [zł]",
        "Źródło ceny",
    )

    /** "Źródło ceny" of a trip priced by a confirmed refuel. */
    const val CSV_SOURCE_REFUEL = "tankowanie"

    /** "Źródło ceny" of a trip priced with the default price of the phone. */
    const val CSV_SOURCE_DEFAULT = "cena domyślna"

    /** F8 reminder card on the status screen. */
    const val BACKUP_REMINDER_NEVER =
        "Zrób kopię archiwum – trasy starsze niż rok są już tylko w tym telefonie."

    fun backupReminderOverdue(days: Long): String =
        "Od ostatniej kopii archiwum minęło $days dni – zrób nową."
}
