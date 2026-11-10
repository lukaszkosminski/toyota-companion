package pl.lk.toyotacompanion.ui

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import pl.lk.toyotacompanion.core.domain.TrafficClass
import pl.lk.toyotacompanion.core.data.SyncErrorKind
import pl.lk.toyotacompanion.core.data.SyncStatus
import pl.lk.toyotacompanion.core.sync.ArchiveImportCounts
import pl.lk.toyotacompanion.core.sync.RefuelChange

/**
 * Polish and English UI texts via [Lang] (docs/SPEC.md §9).
 *
 * Plain functions instead of string resources: they are testable without
 * Android and the `when` over an enum forces a message for every error kind.
 * Never a password, token, VIN or GPS position (docs/SECURITY.md).
 */
object Texts {
    val LOGIN_TITLE: String get() = Lang.pick("Zaloguj się do MyToyota", "Log in to MyToyota")
    val LOGIN_FIELD: String get() = Lang.pick("Login", "Login")
    val PASSWORD_FIELD: String get() = Lang.pick("Hasło", "Password")
    val LOGIN_BUTTON: String get() = Lang.pick("Zaloguj", "Log in")
    val LOGIN_BUSY: String get() = Lang.pick("Logowanie…", "Logging in…")
    val LOGOUT_BUTTON: String get() = Lang.pick("Wyloguj", "Log out")
    val SYNC_NOW_BUTTON: String get() = Lang.pick("Synchronizuj teraz", "Sync now")
    val SYNCING: String get() = Lang.pick("Synchronizacja w toku…", "Sync in progress…")
    val STATUS_TITLE: String get() = Lang.pick("Status synchronizacji", "Sync status")
    val NO_DATA: String get() = Lang.pick("Brak danych", "No data")
    val RELOGIN_HINT: String get() = Lang.pick(
        "Zaloguj się ponownie – Toyota odrzuciła dane logowania.",
        "Log in again – Toyota rejected login credentials.",
    )

    /** docs/SPEC.md §9.2 — what each login failure means for the user. */
    fun loginError(error: LoginError): String = when (error) {
        LoginError.EMPTY_FIELDS -> Lang.pick("Podaj login i hasło.", "Enter login and password.")
        LoginError.INVALID_CREDENTIALS -> Lang.pick(
            "Toyota odrzuciła logowanie – sprawdź login i hasło.",
            "Toyota rejected the login – check your login and password.",
        )
        LoginError.NETWORK -> Lang.pick(
            "Brak internetu. Sprawdź połączenie i spróbuj ponownie.",
            "No internet connection. Check connection and try again.",
        )
        LoginError.SERVICE -> Lang.pick("Serwis Toyoty niedostępny. Spróbuj później.", "Toyota service unavailable. Try again later.")
    }

    /** docs/SPEC.md §9.3 — only the kind is shown, never the stored message. */
    fun syncError(kind: SyncErrorKind): String = when (kind) {
        SyncErrorKind.NO_CREDENTIALS -> Lang.pick("Brak danych logowania – zaloguj się.", "No login credentials – log in.")
        SyncErrorKind.LOGIN -> Lang.pick("Toyota odrzuciła logowanie – zaloguj się ponownie.", "Toyota rejected login – log in again.")
        SyncErrorKind.NO_VEHICLE -> Lang.pick(
            "Na koncie Toyoty nie ma pojazdu albo jest ich kilka – aplikacja obsługuje jedno konto z jednym autem.",
            "The Toyota account has no vehicle or has several – the app supports one account with one car.",
        )
        SyncErrorKind.API -> Lang.pick("Serwis Toyoty niedostępny. Spróbuj później.", "Toyota service unavailable. Try again later.")
        SyncErrorKind.NETWORK -> Lang.pick("Brak internetu. Spróbuj później.", "No internet connection. Try again later.")
        SyncErrorKind.DATA -> Lang.pick("Nieoczekiwana odpowiedź Toyoty. Spróbuj później.", "Unexpected response from Toyota. Try again later.")
        SyncErrorKind.STORAGE -> Lang.pick(
            "Nie udało się zapisać danych w telefonie. Sprawdź wolne miejsce i spróbuj ponownie.",
            "Failed to save data on phone. Check free space and try again.",
        )
    }

    /** "aktualne" / "nieaktualne" — local data on screen but not current (docs/SPEC.md §8.4). */
    fun freshness(status: SyncStatus): String =
        if (status.isFresh) Lang.pick("aktualne", "current") else Lang.pick("nieaktualne", "not current")

    /** Last full success as `10.11 19:04` (the year only when it is not [today]'s), or "brak" (C32 U20). */
    fun lastSuccess(status: SyncStatus, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val at = status.state.lastSuccessAt ?: return Lang.pick("Ostatnia synchronizacja: brak", "Last sync: none")
        val formatted = TripsFormatting.shortStamp(at, zone, today)
        return Lang.pick("Ostatnia synchronizacja: $formatted", "Last sync: $formatted")
    }

    /** Dialog confirming logout, so a tap on "Wyloguj" is never final by itself. */
    val LOGOUT_CONFIRM_TITLE: String get() = Lang.pick("Wylogować się?", "Log out?")
    val LOGOUT_CONFIRM_BODY: String get() = Lang.pick(
        "Usuniemy zapisane dane logowania i dostęp Toyoty. Trasy i odczyty zostaną " +
            "w bazie do następnego logowania.",
        "We will delete saved login credentials and Toyota access. Trips and readings will remain " +
            "in the database until the next login.",
    )
    val LOGOUT_CONFIRM_YES: String get() = Lang.pick("Wyloguj", "Log out")
    val LOGOUT_CONFIRM_NO: String get() = Lang.pick("Anuluj", "Cancel")

    /** Title of the status screen; same wording as `app_name` in res/values/strings.xml. */
    val APP_NAME: String get() = Lang.pick("Toyota Companion", "Toyota Companion")

    /** First read of the local archive. */
    val LOADING: String get() = Lang.pick("Ładowanie…", "Loading…")

    val SHOW_PASSWORD: String get() = Lang.pick("Pokaż", "Show")
    val HIDE_PASSWORD: String get() = Lang.pick("Ukryj", "Hide")

    /** Stat cards show the number alone, the caption below says what it counts. */
    val STAT_TRIPS: String get() = Lang.pick("Trasy", "Trips")
    val STAT_FUEL_LEVEL: String get() = Lang.pick("Poziom paliwa", "Fuel level")
    val STAT_ODOMETER: String get() = Lang.pick("Przebieg (km)", "Odometer (km)")
    val STAT_PENDING_REFUELS: String get() = Lang.pick("Oczekujące tankowania", "Pending refuels")

    /** The level of the newest reading, adjusted by the trips since then (docs/SPEC.md §10.4, §20.2). */
    fun fuelLevel(pct: Int): String = Lang.pick("$pct %", "$pct%")

    /** Diagnostic line under the chip: how many telemetry snapshots the archive holds (docs/SPEC.md §10.4). */
    fun telemetryReadings(count: Int): String = Lang.pick(
        "Telemetria: $count ${plPlural(count.toLong(), "odczyt", "odczyty", "odczytów")}",
        "Telemetry: $count ${enPlural(count.toLong(), "reading", "readings")}",
    )

    /** Chip on the status screen when there is nothing fresh or stale to say yet. */
    val STATE_NEVER: String get() = Lang.pick("brak synchronizacji", "never synced")
    val STATE_NEEDS_RELOGIN: String get() = Lang.pick("wymaga logowania", "login required")

    // ---------------- Trips list (docs/SPEC.md §10) ----------------

    /** Title of the trips screen and the button that opens it from the status screen. */
    val TRIPS_TITLE: String get() = Lang.pick("Trasy", "Trips")

    /** Every secondary screen leaves through a text button with this word (no Icons). */
    val BACK: String get() = Lang.pick("Wstecz", "Back")

    /** The archive of the selected vehicle is empty. */
    val TRIPS_EMPTY: String get() = Lang.pick("Brak tras – zsynchronizuj dane", "No trips – sync data")

    /** The local read failed; never the exception text (docs/SECURITY.md). */
    val TRIPS_READ_ERROR: String get() = Lang.pick(
        "Nie udało się odczytać tras. Spróbuj ponownie.",
        "Failed to read trips. Try again.",
    )

    /** Trip without a usable cost, and the month header when no trip in it has one. */
    val NO_PRICE: String get() = Lang.pick("brak ceny", "no price")

    /** The cost came from the configured default price, not from a refuel (docs/SPEC.md §4). */
    val DEFAULT_PRICE_NOTE: String get() = Lang.pick("cena domyślna", "default price")

    /** Separates the values inside one line of the trips list. */
    const val SEPARATOR = " · "

    /** „bez ceny: 3” — the month header shows it only when the count is not zero. */
    fun tripsWithoutCost(count: Int): String = Lang.pick("bez ceny: $count", "without price: $count")

    // ---------------- refuel price form (docs/SPEC.md §11) ----------------

    /** Title of the list of refuels that still wait for a price. */
    val REFUELS_TITLE: String get() = Lang.pick("Tankowania", "Refuels")

    /** The selected vehicle has no refuel waiting for a price. */
    val REFUELS_EMPTY: String get() = Lang.pick("Brak tankowań czekających na cenę", "No refuels waiting for a price")

    /** The local read failed; never the exception text (docs/SECURITY.md). */
    val REFUELS_READ_ERROR: String get() = Lang.pick(
        "Nie udało się odczytać tankowań. Spróbuj ponownie.",
        "Failed to read refuels. Try again.",
    )

    /** Button on the status screen; [count] is how many refuels still wait for a price. */
    fun refuelPrompt(count: Int): String = Lang.pick("Podaj cenę tankowania ($count)", "Enter refuel price ($count)")

    /** Title of the form. */
    val REFUEL_FORM_TITLE: String get() = Lang.pick("Cena tankowania", "Refuel price")

    val REFUEL_FIELD_PRICE: String get() = Lang.pick("Cena za litr", "Price per liter")
    val REFUEL_FIELD_LITERS: String get() = Lang.pick("Litry", "Liters")
    val REFUEL_FIELD_TOTAL: String get() = Lang.pick("Kwota", "Total cost")

    /** Only usable when the liters are known (docs/SPEC.md §11.3). */
    val REFUEL_FILLED_FULL: String get() = Lang.pick("Do pełna", "Filled to full")

    val REFUEL_SAVE: String get() = Lang.pick("Zapisz", "Save")
    val REFUEL_DISMISS: String get() = Lang.pick("Odrzuć", "Dismiss")

    /** Refusing an entry is final for that entry, so it is confirmed first. */
    val REFUEL_DISMISS_TITLE: String get() = Lang.pick("Odrzucić to tankowanie?", "Dismiss this refuel?")
    val REFUEL_DISMISS_BODY: String get() = Lang.pick(
        "Odrzucone tankowanie nie wejdzie do kosztów tras i nie będzie czekać na cenę.",
        "Dismissed refuel will not be included in trip costs and will not wait for a price.",
    )
    val REFUEL_DISMISS_YES: String get() = Lang.pick("Odrzuć", "Dismiss")
    val REFUEL_DISMISS_NO: String get() = Lang.pick("Anuluj", "Cancel")

    /** `6,1,9` or `abc` in an amount field. */
    val REFUEL_NUMBER_ERROR: String get() = Lang.pick("To nie jest liczba", "This is not a number")

    /** `0` or a negative amount; the domain would refuse the same value (§2). */
    val REFUEL_POSITIVE_ERROR: String get() = Lang.pick("Wartość musi być większa od zera", "Value must be greater than zero")

    /** The form has to keep the combinations of docs/SPEC.md §2. */
    val REFUEL_COMBINATION_ERROR: String get() = Lang.pick(
        "Podaj cenę za litr albo litry i kwotę",
        "Enter price per liter, or liters and total cost",
    )

    /** D3 of PRD F2a: an estimate cannot be the base of a price (docs/SPEC.md §11.2). */
    val REFUEL_ESTIMATE_NEEDS_PRICE: String get() = Lang.pick(
        "Podaj cenę za litr – z szacunku litrów nie da się wyliczyć ceny",
        "Enter price per liter – price cannot be calculated from estimated liters",
    )

    /** Sensor estimate under the liters field, e.g. `Szacunek z czujnika: ok. 14,19 l` (§11.3). */
    fun refuelEstimateHint(liters: String): String = Lang.pick("Szacunek z czujnika: ok. $liters", "Sensor estimate: approx. $liters")

    /** Fills the liters field with the estimate. */
    val REFUEL_USE_ESTIMATE: String get() = Lang.pick("Użyj szacunku", "Use estimate")

    /** Shown while the liters field holds the accepted estimate. */
    val REFUEL_LITERS_FROM_ESTIMATE: String get() = Lang.pick("Litry z szacunku czujnika", "Liters from sensor estimate")

    /** Takes the estimate mark off: the same number is from the receipt. */
    val REFUEL_LITERS_FROM_RECEIPT: String get() = Lang.pick("Z paragonu", "From receipt")

    /** The domain refused the values although the form check passed (docs/SPEC.md §11.2). */
    val REFUEL_WRITE_ERROR: String get() = Lang.pick(
        "Nie udało się zapisać ceny. Popraw wartości i spróbuj ponownie.",
        "Failed to save price. Correct values and try again.",
    )

    /** The write itself failed (the database, not the values): the user may try again. */
    val REFUEL_SAVE_FAILED: String get() = Lang.pick("Nie udało się zapisać. Spróbuj ponownie.", "Failed to save. Try again.")

    /** The entry is gone, already answered or belongs to another vehicle. */
    val REFUEL_STALE_ENTRY: String get() = Lang.pick(
        "Ten wpis już nie czeka na cenę. Odśwież listę.",
        "This entry is no longer waiting for a price. Refresh the list.",
    )

    /** Polish message of a refused write; null when the write went through. */
    fun refuelChangeError(change: RefuelChange): String? = when (change) {
        is RefuelChange.Done -> null
        RefuelChange.Invalid -> REFUEL_WRITE_ERROR
        RefuelChange.NotFound, RefuelChange.NotPending, RefuelChange.NoVehicle -> REFUEL_STALE_ENTRY
        RefuelChange.NotConfirmed -> AMEND_NOT_CONFIRMED
    }

    // ---------------- domyślna cena paliwa (docs/SPEC.md §12) ----------------

    /** Title of the default price screen. */
    val DEFAULT_PRICE_TITLE: String get() = Lang.pick("Cena domyślna", "Default price")

    /** Under the title of the card in "Opcje": what the price is for (docs/SPEC.md §12). */
    val DEFAULT_PRICE_EXPLAINED: String get() = Lang.pick(
        "Cena za litr paliwa, które jest w baku przed pierwszym potwierdzonym tankowaniem – liczy trasy sprzed niego, a pierwsze tankowanie miesza się z nią średnią ważoną. Potem koszt tras liczy się z ceny z baku.",
        "Price per liter of the fuel in the tank before the first confirmed refuel – it prices the trips before it, and the first refuel mixes with it as a weighted average. After that trip costs use the price of the fuel in the tank.",
    )

    /** Status-screen button when no default price is stored yet. */
    val DEFAULT_PRICE_PROMPT_UNSET: String get() = Lang.pick("Ustaw cenę domyślną paliwa", "Set default fuel price")

    /**
     * Status-screen button: [price] in the label when one is stored, e.g.
     * `Cena domyślna paliwa: 6,19 zł/l`, the plain request when none is.
     */
    fun defaultPricePrompt(price: BigDecimal?): String =
        if (price == null) DEFAULT_PRICE_PROMPT_UNSET
        else {
            val formatted = TripsFormatting.pricePerLiter(price)
            Lang.pick("Cena domyślna paliwa: $formatted", "Default fuel price: $formatted")
        }

    /** Under the field: what the default price prices (docs/SPEC.md §4, ADR-005 amendment 2026-11-08). */
    val DEFAULT_PRICE_HINT: String get() = Lang.pick(
        "Cena paliwa w baku przed pierwszym potwierdzonym tankowaniem: liczy trasy sprzed niego i miesza się z ceną pierwszego tankowania.",
        "The price of the fuel in the tank before the first confirmed refuel: it prices the trips before it and mixes with the first refuel's price.",
    )

    /** Removes the setting; an empty field never does that (docs/SPEC.md §12.3). */
    val DEFAULT_PRICE_REMOVE: String get() = Lang.pick("Usuń cenę domyślną", "Remove default price")

    val DEFAULT_PRICE_REMOVE_TITLE: String get() = Lang.pick("Usunąć cenę domyślną?", "Remove default price?")
    val DEFAULT_PRICE_REMOVE_BODY: String get() = Lang.pick(
        "Trasy sprzed pierwszego potwierdzonego tankowania znów będą bez ceny, a pierwsze tankowanie będzie liczone tylko własną ceną.",
        "Trips before the first confirmed refuel will again have no price, and the first refuel will count at its own price only.",
    )
    val DEFAULT_PRICE_REMOVE_YES: String get() = Lang.pick("Usuń", "Remove")
    val DEFAULT_PRICE_REMOVE_NO: String get() = Lang.pick("Anuluj", "Cancel")

    /** "Zapisz" with an empty field: removing the price has its own button. */
    val DEFAULT_PRICE_EMPTY_ERROR: String get() = Lang.pick(
        "Podaj cenę za litr albo usuń cenę przyciskiem poniżej",
        "Enter price per liter or remove price using the button below",
    )


    /** Name of the notification channel of refuels (docs/SPEC.md §13.1). */
    val REFUEL_NOTIFICATION_CHANNEL: String get() = Lang.pick("Tankowania", "Refuels")

    /**
     * Title of the heads-up notification and of the in-app prompt: the same words,
     * because both say the same thing (docs/SPEC.md §13).
     */
    val REFUEL_NOTIFICATION_TITLE: String get() = Lang.pick("Wykryto tankowanie", "Refuel detected")

    /**
     * Notification text: when and how much the level changed, then the request, e.g.
     * `30.10 14:05 · 20% → 80%. Podaj cenę za litr.` No VIN, no GPS, no odometer
     * (docs/SECURITY.md, docs/SPEC.md §13.1).
     */
    fun refuelNotificationText(time: String, levelJump: String): String =
        Lang.pick(
            "$time · $levelJump. Podaj cenę za litr.",
            "$time · $levelJump. Enter price per liter.",
        )

    /** First line of the in-app prompt: the refuel this question is about. */
    fun refuelPromptBody(time: String, levelJump: String): String = "$time · $levelJump"

    /** "Not now": silences this one entry for the rest of the process (docs/SPEC.md §13.3). */
    val REFUEL_PROMPT_LATER: String get() = Lang.pick("Później", "Later")

    /** Opens the full form of C3 with liters and total cost. */
    val REFUEL_PROMPT_MORE: String get() = Lang.pick("Więcej", "More")


    // Kopia archiwum (PRD F7, F8, docs/SPEC.md §14).

    /** Status button and the title of the backup screen. */
    val BACKUP_TITLE: String get() = Lang.pick("Kopia i eksport", "Backup and export")

    /** Caption under the date of the last archive copy on the backup screen. */
    val BACKUP_LAST: String get() = Lang.pick("Ostatnia kopia", "Last backup")

    val BACKUP_EXPORT: String get() = Lang.pick("Eksportuj archiwum", "Export archive")
    val BACKUP_IMPORT: String get() = Lang.pick("Importuj archiwum", "Import archive")

    /** Shown before the system file picker, docs/SECURITY.md. */
    val BACKUP_WARNING_TITLE: String get() = Lang.pick("Plik z danymi osobowymi", "File with personal data")
    val BACKUP_WARNING_BODY: String get() = Lang.pick(
        "Kopia zawiera trasy z położeniem GPS i numer VIN samochodu. Zapisz ją w bezpiecznym miejscu i nie udostępniaj.",
        "The backup contains trips with GPS locations and car VIN. Save it in a safe place and do not share it.",
    )
    val BACKUP_WARNING_OK: String get() = Lang.pick("Eksportuj", "Export")
    val BACKUP_WARNING_CANCEL: String get() = Lang.pick("Anuluj", "Cancel")

    /** Explains the screen: the phone is the only place the old trips live (ADR-006). */
    val BACKUP_EXPLANATION: String get() = Lang.pick(
        "Toyota usuwa trasy starsze niż około rok, więc archiwum jest tylko w tym telefonie. " +
            "Kopia to jeden plik – import na nowym telefonie dodaje tylko to, czego jeszcze nie ma.",
        "Toyota deletes trips older than about a year, so the archive only lives on this phone. " +
            "The backup is a single file – importing on a new phone only adds what is not already there.",
    )

    fun backupExported(trips: Int): String = Lang.pick(
        "Zapisano kopię archiwum (trasy: $trips).",
        "Archive backup saved (trips: $trips).",
    )

    /** Counts without Polish plural forms: "trasy: 3" reads right for every number. */
    fun backupImported(counts: ArchiveImportCounts): String = if (Lang.isEnglish) {
        buildString {
            append("Imported – trips: ${counts.tripsAdded}, refuels: ${counts.refuelsAdded}, ")
            append("fuel readings: ${counts.readingsAdded}.")
            if (counts.refuelsUpdated > 0) append(" Refuel prices updated: ${counts.refuelsUpdated}.")
            if (counts.refuelsSkipped > 0) {
                append(" Skipped refuels (dismissed on this phone): ${counts.refuelsSkipped}.")
            }
            if (counts.otherVehicleTrips > 0) {
                append(" Other vehicle trips: ${counts.otherVehicleTrips} – shown under that vehicle.")
            }
        }
    } else {
        buildString {
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
    }

    val BACKUP_UNSUPPORTED: String get() = Lang.pick(
        "To nie jest kopia archiwum tej aplikacji albo pochodzi z nowszej wersji. Nic nie zostało zapisane.",
        "This is not an archive backup from this app or it comes from a newer version. Nothing was saved.",
    )
    val BACKUP_CORRUPT: String get() = Lang.pick(
        "Plik kopii jest uszkodzony. Nic nie zostało zapisane.",
        "The backup file is corrupt. Nothing was saved.",
    )
    val BACKUP_TOO_LARGE: String get() = Lang.pick(
        "Plik jest za duży na kopię archiwum. Nic nie zostało zapisane.",
        "The file is too large for an archive backup. Nothing was saved.",
    )
    val BACKUP_FAILED: String get() = Lang.pick(
        "Nie udało się zapisać ani odczytać pliku. Spróbuj ponownie.",
        "Failed to save or read file. Try again.",
    )

    /** Status line under the backup button, e.g. `Ostatnia kopia archiwum: 31.10.2026`. */
    fun lastBackup(date: String): String = Lang.pick("Ostatnia kopia archiwum: $date", "Last archive backup: $date")

    val BACKUP_NONE_YET: String get() = Lang.pick("Archiwum nie ma jeszcze kopii", "Archive has no backup yet")

    // Kopia w chmurze Google (ADR-015, docs/SPEC.md §14.2).

    val BACKUP_CLOUD_SECTION: String get() = Lang.pick("Kopia w chmurze Google", "Google cloud backup")

    /** What Auto Backup copies and what it does not (login and tokens stay on the phone). */
    val BACKUP_CLOUD_EXPLANATION: String get() = Lang.pick(
        "Android kopiuje na Dysk Google właściciela bazę archiwum i ustawienia aplikacji. " +
            "Nie kopiuje loginu ani tokenów MyToyota – po odtworzeniu kopii na nowym telefonie zaloguj się ponownie.",
        "Android backs up the archive database and the app settings to the owner's Google Drive. " +
            "Login and MyToyota tokens are not backed up – after restoring on a new phone, log in again.",
    )

    val BACKUP_CLOUD_NOW: String get() = Lang.pick("Zrób kopię w chmurze teraz", "Back up to the cloud now")

    /** `dataChanged()` only requests a backup; Android decides when it runs. */
    val BACKUP_CLOUD_REQUESTED: String get() = Lang.pick(
        "Poproszono Androida o kopię w chmurze. System sam zdecyduje, kiedy ją wykona.",
        "Android was asked to back up to the cloud. The system decides when the backup actually runs.",
    )

    val BACKUP_CLOUD_FAILED: String get() = Lang.pick(
        "Android odmówił żądania kopii w chmurze. Spróbuj ponownie.",
        "Android refused the cloud backup request. Try again.",
    )

    val BACKUP_CLOUD_HINT: String get() = Lang.pick(
        "Ustawienia Androida → Google → Kopia zapasowa",
        "Android Settings → Google → Backup",
    )

    // Statystyki (PRD F10, F11, docs/SPEC.md §16-§17).

    val STATS_TITLE: String get() = Lang.pick("Statystyki", "Statistics")
    val STATS_EMPTY: String get() = Lang.pick("Brak tras do policzenia statystyk.", "No trips to calculate statistics.")
    val STATS_READ_ERROR: String get() = Lang.pick(
        "Nie udało się policzyć statystyk. Spróbuj ponownie.",
        "Failed to calculate statistics. Try again.",
    )
    val STATS_EV_SECTION: String get() = Lang.pick("Jazda na prądzie", "EV driving")
    val STATS_NO_EV_DATA: String get() = Lang.pick("brak danych", "no data")
    val STATS_CLIPPED_MARK: String get() = Lang.pick("(dane Toyoty niespójne)", "(Toyota data inconsistent)")
    val STATS_RANKING: String get() = Lang.pick("Najwięcej jazdy na prądzie (trasy od 1 km)", "Most EV driving (trips from 1 km)")
    val STATS_YEAR_OVER_YEAR: String get() = Lang.pick("Rok do roku", "Year over year")
    val STATS_YEARS: String get() = Lang.pick("Lata", "Years")
    val STATS_SEASONS: String get() = Lang.pick("Pory roku", "Seasons")
    val SEASON_WINTER: String get() = Lang.pick("zima", "winter")
    val SEASON_SPRING: String get() = Lang.pick("wiosna", "spring")
    val SEASON_SUMMER: String get() = Lang.pick("lato", "summer")
    val SEASON_AUTUMN: String get() = Lang.pick("jesień", "autumn")

    fun statsEvTotal(share: String): String = Lang.pick("$share kilometrów na prądzie", "$share kilometers on battery")
    fun statsShareOfTrip(share: String): String = Lang.pick("$share na prądzie", "$share on battery")

    /** A short count of trips with its noun: `24 trasy`, `1 trasa`, `5 tras` / `24 trips`. */
    fun tripsShort(count: Int): String = Lang.pick(
        "${TripsFormatting.count(count)} ${plPlural(count.toLong(), "trasa", "trasy", "tras")}",
        "${TripsFormatting.count(count)} ${enPlural(count.toLong(), "trip", "trips")}",
    )

    fun statsTrips(count: Int): String = Lang.pick("trasy: ${TripsFormatting.count(count)}", "trips: ${TripsFormatting.count(count)}")
    fun statsLeftOut(withoutData: Int, tooShort: Int): String = Lang.pick(
        "Pominięte – bez danych o prądzie: $withoutData, krótsze niż 1 km: $tooShort.",
        "Skipped – without EV data: $withoutData, shorter than 1 km: $tooShort.",
    )
    fun statsWindow(window: String, current: Int, previous: Int): String = "$window $current vs $previous"

    // Typy tras (PRD F16, docs/SPEC.md §21).

    val DRIVING_SHORT: String get() = Lang.pick("krótki odcinek", "short trip")
    fun drivingType(cls: TrafficClass, kmh: String): String = (when (cls) {
        TrafficClass.SLOW -> Lang.pick("wolna jazda", "slow traffic")
        TrafficClass.CITY -> Lang.pick("miasto", "city")
        TrafficClass.FLOWING -> Lang.pick("płynnie", "flowing")
        TrafficClass.HIGHWAY -> Lang.pick("trasa", "highway")
    }) + SEPARATOR + "$kmh km/h"

    val TRAFFIC_SECTION: String get() = Lang.pick("Tempo jazdy a spalanie", "Driving pace and consumption")
    val TRAFFIC_HINT: String get() = Lang.pick(
        "Średnia prędkość trasy (od 2 km) jako miara ruchu – wolna jazda to też sygnalizacja i krótkie odcinki, nie tylko korki. Litry liczone do wszystkich km, także tych na prądzie – patrz udział prądu.",
        "Average trip speed (from 2 km) as a measure of traffic – slow driving also includes traffic lights and short trips, not just traffic jams. Liters counted for all km, including those on battery – see EV share.",
    )
    fun trafficClassName(cls: TrafficClass): String = when (cls) {
        TrafficClass.SLOW -> Lang.pick("Wolno (poniżej 15 km/h)", "Slow (under 15 km/h)")
        TrafficClass.CITY -> Lang.pick("Miasto (15–25 km/h)", "City (15–25 km/h)")
        TrafficClass.FLOWING -> Lang.pick("Płynnie (25–40 km/h)", "Flowing (25–40 km/h)")
        TrafficClass.HIGHWAY -> Lang.pick("Trasa (od 40 km/h)", "Highway (over 40 km/h)")
    }
    fun trafficConsumption(litres: String): String = "$litres l/100 km"
    fun trafficCost(money: String): String = "$money/100 km"
    fun statsLeftOutTrips(left: Int, total: Int): String = Lang.pick(
        "Poza zestawieniem: $left z $total ${plPlural(total.toLong(), "trasy", "tras", "tras")} (poniżej 2 km albo bez prędkości).",
        "Excluded: $left of $total ${enPlural(total.toLong(), "trip", "trips")} (under 2 km or without speed).",
    )
    fun trafficSurcharge(litres: String, money: String?): String {
        val pl = "Wolna jazda wobec płynnej: $litres l/100 km" + (money?.let { " ($it/100 km)" } ?: "")
        val en = "Slow traffic vs flowing: $litres l/100 km" + (money?.let { " ($it/100 km)" } ?: "")
        return Lang.pick(pl, en)
    }
    val TRIP_TYPES_SECTION: String get() = Lang.pick("Typy tras", "Trip types")
    val TRIP_TYPES_HINT: String get() = Lang.pick(
        "Dom i praca rozpoznane z postojów; dojazdy to trasy między nimi.",
        "Home and work recognized from parking stops; commutes are trips between them.",
    )
    val TRIP_TYPES_NO_PLACES: String get() = Lang.pick(
        "Za mało postojów, żeby rozpoznać dom i pracę – dojazdów jeszcze nie ma.",
        "Not enough parking stops to recognize home and work – commutes are not available yet.",
    )
    fun tripTypeName(type: TripType): String = when (type) {
        TripType.COMMUTE -> Lang.pick("Dojazdy dom – praca", "Commute home – work")
        TripType.ERRAND -> Lang.pick("Sprawy i zakupy", "Errands and shopping")
        TripType.WEEKEND -> Lang.pick("Weekend", "Weekend")
        TripType.OTHER -> Lang.pick("Inne", "Other")
    }
    val PLACE_HOME: String get() = Lang.pick("Dom", "Home")
    val PLACE_WORK: String get() = Lang.pick("Praca", "Work")

    // Prognoza tankowania (PRD F14, docs/SPEC.md §20).

    fun forecastWhen(days: Long, date: String): String = when (days) {
        0L -> Lang.pick("Tankowanie dziś", "Refuel today")
        1L -> Lang.pick("Tankowanie jutro ($date)", "Refuel tomorrow ($date)")
        else -> Lang.pick("Tankowanie za ok. $days dni (ok. $date)", "Refuel in approx. $days days (approx. $date)")
    }
    fun forecastBeyond(days: Long): String = Lang.pick(
        "Tankowanie za ponad $days ${plPlural(days, "dzień", "dni", "dni")}",
        "Refuel in over $days ${enPlural(days, "day", "days")}",
    )
    fun forecastLeft(liters: String, level: String): String = Lang.pick("w baku ok. $liters ($level)", "approx. $liters in tank ($level)")
    fun forecastRange(km: String): String = Lang.pick("ok. $km do rezerwy", "approx. $km to reserve")
    val FORECAST_RESERVE: String get() = Lang.pick("Paliwo na rezerwie – zatankuj", "Fuel on reserve – refuel")

    // Styl jazdy (PRD F12, F13, docs/SPEC.md §19).

    val STYLE_TITLE: String get() = Lang.pick("Styl jazdy", "Driving style")
    val STYLE_EMPTY: String get() = Lang.pick("Brak tras do oceny stylu jazdy.", "No trips to assess driving style.")
    val STYLE_READ_ERROR: String get() = Lang.pick(
        "Nie udało się odczytać stylu jazdy. Spróbuj ponownie.",
        "Failed to read driving style. Try again.",
    )
    val STYLE_ECO_SECTION: String get() = Lang.pick("Wynik eco Toyoty po miesiącach", "Toyota eco score by month")
    val STYLE_ECO_NONE: String get() = Lang.pick("Toyota nie przysłała wyników eco.", "Toyota did not send eco scores.")
    val STYLE_ROUTES_SECTION: String get() = Lang.pick("Ten sam odcinek", "Same route")
    fun styleRoutesNone(minTrips: Int): String = Lang.pick(
        "Żaden odcinek nie powtórzył się jeszcze $minTrips razy.",
        "No route has repeated $minTrips times yet.",
    )
    val STYLE_ANOMALIES_SECTION: String get() = Lang.pick("Spalanie wyższe niż zwykle", "Consumption higher than usual")
    val STYLE_ANOMALIES_NONE: String get() = Lang.pick(
        "Brak przejazdów ze spalaniem wyższym niż zwykle.",
        "No trips with consumption higher than usual.",
    )
    val STYLE_ANOMALIES_HINT: String get() = Lang.pick(
        "Aplikacja nie zna przyczyny. Częste powody: zimny silnik i mróz, korki, bagaż lub bagażnik dachowy, za niskie ciśnienie w oponach.",
        "The app does not know the cause. Common reasons: cold engine and frost, traffic jams, luggage or roof rack, low tire pressure.",
    )

    fun styleEco(score: String, trips: Int): String = Lang.pick(
        "$score pkt · ocenione trasy: ${TripsFormatting.count(trips)}",
        "$score pts · rated trips: ${TripsFormatting.count(trips)}",
    )
    fun styleRoute(number: Int): String = Lang.pick("Odcinek $number", "Route $number")
    fun styleRouteLine(km: String, trips: Int, hour: String?): String {
        val pl = "ok. $km · przejazdy: ${TripsFormatting.count(trips)}" + (hour?.let { " · zwykle ok. $it" } ?: "")
        val en = "approx. $km · trips: ${TripsFormatting.count(trips)}" + (hour?.let { " · usually approx. $it" } ?: "")
        return Lang.pick(pl, en)
    }
    fun styleRouteFacts(eco: String?, usual: String?): String = if (Lang.isEnglish) {
        listOfNotNull(eco?.let { "eco $it pts" }, usual?.let { "usually $it" }).joinToString(" · ")
    } else {
        listOfNotNull(eco?.let { "eco $it pkt" }, usual?.let { "zwykle $it" }).joinToString(" · ")
    }
    fun styleAnomaly(route: Int, actual: String, usual: String, excess: String): String = Lang.pick(
        "Odcinek $route: $actual zamiast zwykle $usual ($excess)",
        "Route $route: $actual instead of usual $usual ($excess)",
    )

    // Spalanie na ekranie statusu (docs/SPEC.md §24).

    val CONSUMPTION_TITLE: String get() = Lang.pick("Spalanie", "Consumption")
    fun consumptionHint(axisStart: String): String = Lang.pick(
        "Słupki: średnie spalanie miesiąca (l/100 km), miesiące od 50 km; oś zaczyna się od $axisStart l/100 km, nie od zera. " +
            "Trend to linia przez słupki; zimą spalanie zwykle rośnie.",
        "Bars: monthly average consumption (l/100 km), months from 50 km; axis starts at $axisStart l/100 km, not zero. " +
            "Trend is a line through the bars; consumption usually rises in winter.",
    )
    val CONSUMPTION_COSTS_TITLE: String get() = Lang.pick("Koszty miesięczne", "Monthly costs")
    val CONSUMPTION_COSTS_HINT: String get() = Lang.pick(
        "Słupki: koszt paliwa miesiąca w zł (tylko trasy z ceną), od zera. Pod słupkami: miesiące od najstarszego.",
        "Bars: monthly fuel cost in zł (trips with price only), from zero. Below bars: months from oldest.",
    )
    fun consumptionTotalCost(money: String, period: String?): String {
        val pl = "Razem" + (period?.let { " ($it)" } ?: " z całego okresu") + ": $money"
        val en = "Total" + (period?.let { " ($it)" } ?: " all-time") + ": $money"
        return Lang.pick(pl, en)
    }
    fun costsAverageCaption(months: Int): String = Lang.pick(
        "średnio z pełnych miesięcy ($months), bez bieżącego",
        "average of full months ($months), excluding current",
    )
    fun consumptionCaption(km: String): String = Lang.pick(
        "średnio z całego okresu, $km",
        "all-time average, $km",
    )
    val COSTS_FOLD: String get() = Lang.pick("Zwiń", "Collapse")
    fun costsUnfold(count: Int): String = Lang.pick("Pokaż wszystkie miesiące ($count)", "Show all months ($count)")
    fun consumptionOverall(litres: String, km: String): String = Lang.pick(
        "Średnio z całego okresu: $litres l/100 km ($km km)",
        "All-time average: $litres l/100 km ($km km)",
    )
    fun consumptionRising(change: String): String = Lang.pick("Trend: ↗ rosnące ($change %)", "Trend: ↗ rising ($change %)")
    fun consumptionFalling(change: String): String = Lang.pick("Trend: ↘ malejące ($change %)", "Trend: ↘ falling ($change %)")
    fun consumptionSteady(change: String): String = Lang.pick("Trend: → stabilne ($change %)", "Trend: → steady ($change %)")

    // Kalkulator kosztu planowanej trasy (PRD F52, docs/SPEC.md §52).

    val PLANNED_TITLE: String get() = Lang.pick("Kalkulator trasy", "Trip cost calculator")
    val PLANNED_HINT: String get() = Lang.pick(
        "Kilometry i rodzaj jazdy: przedział z twoich tras tego rodzaju, nie prognoza.",
        "Kilometres and the kind of drive: a range from your own trips of that kind, not a forecast.",
    )
    val PLANNED_KM_LABEL: String get() = Lang.pick("Kilometry", "Kilometres")
    val PLANNED_KIND_LABEL: String get() = Lang.pick("Rodzaj jazdy", "Kind of drive")
    val PLANNED_BAD_KM: String get() = Lang.pick("Wpisz kilometry, np. 12,5 (od 0 do 5000).", "Enter kilometres, e.g. 12.5 (from 0 to 5000).")
    val PLANNED_NO_PRICE: String get() = Lang.pick(
        "Brak ceny paliwa – ustaw cenę domyślną w Opcjach. Pokazane są same litry.",
        "No fuel price – set the default price in Options. Only litres are shown.",
    )

    /** The kind of drive on a button of the calculator: the short names of [drivingType]. */
    fun plannedKind(cls: TrafficClass): String = when (cls) {
        TrafficClass.SLOW -> Lang.pick("wolno", "slow")
        TrafficClass.CITY -> Lang.pick("miasto", "city")
        TrafficClass.FLOWING -> Lang.pick("płynnie", "flowing")
        TrafficClass.HIGHWAY -> Lang.pick("trasa", "highway")
    }

    /** `ok. 0,5–0,7 l · 3–4 zł`, or without the money part when there is no price. */
    fun plannedResult(litres: String, money: String?): String {
        val text = Lang.pick("ok. $litres", "approx. $litres")
        return if (money == null) text else text + SEPARATOR + money
    }

    /** `z 37 podobnych tras`: the trips of a similar length the range came from (the count is at least ten). */
    fun plannedSimilar(count: Int): String = Lang.pick(
        "z ${TripsFormatting.count(count)} podobnych tras",
        "from ${TripsFormatting.count(count)} similar trips",
    )

    /** `z 212 tras tego rodzaju (za mało podobnej długości)`: the whole kind, because too few trips have a similar length. */
    fun plannedWholeKind(count: Int): String = Lang.pick(
        "z ${TripsFormatting.count(count)} tras tego rodzaju (za mało podobnej długości)",
        "from ${TripsFormatting.count(count)} trips of this kind (too few of a similar length)",
    )

    fun plannedTooFew(count: Int, minimum: Int): String = Lang.pick(
        "za mało tras tego rodzaju: $count z $minimum",
        "too few trips of this kind: $count of $minimum",
    )

    // Krótkie trasy i zimny silnik (PRD F50, docs/SPEC.md §50).

    val SHORT_TITLE: String get() = Lang.pick("Krótkie trasy i zimny silnik", "Short trips and cold engine")

    /** One short line: what a cold start is (an approximation) and what the litres are counted over. */
    fun shortHint(gapHours: Long): String = Lang.pick(
        "Zimny start: od poprzedniej trasy minęło co najmniej $gapHours ${plPlural(gapHours, "godzina", "godziny", "godzin")} (przybliżenie). Litry liczone do wszystkich km, także na prądzie.",
        "Cold start: at least $gapHours ${enPlural(gapHours, "hour", "hours")} since the previous trip (an approximation). Litres counted over all km, including those on battery.",
    )
    val SHORT_TOO_FEW: String get() = Lang.pick("za mało", "too few")
    val SHORT_NO_EXCESS: String get() = Lang.pick("Nie zużywają więcej paliwa niż trasy od 10 km", "They do not burn more than trips from 10 km")
    val SHORT_REFERENCE: String get() = Lang.pick("Odniesienie – trasy od 10 km", "Reference – trips from 10 km")
    val SHORT_COLD_TITLE: String get() = Lang.pick("Zimny a ciepły start", "Cold against warm start")
    val SHORT_COLD_NONE: String get() = Lang.pick(
        "Za mało tras, żeby porównać zimny start z ciepłym (potrzeba po 10 w paśmie).",
        "Too few trips to compare cold with warm starts (10 of each in a band are needed).",
    )

    fun shortBandName(band: pl.lk.toyotacompanion.core.domain.LengthBand): String = when (band) {
        pl.lk.toyotacompanion.core.domain.LengthBand.UNDER_2 -> Lang.pick("poniżej 2 km", "under 2 km")
        pl.lk.toyotacompanion.core.domain.LengthBand.FROM_2 -> "2–5 km"
        pl.lk.toyotacompanion.core.domain.LengthBand.FROM_5 -> "5–10 km"
        pl.lk.toyotacompanion.core.domain.LengthBand.FROM_10 -> "10–25 km"
        pl.lk.toyotacompanion.core.domain.LengthBand.FROM_25 -> Lang.pick("od 25 km", "25 km and more")
    }

    /** `41 tras (23 %)` / `41 trips (23%)`: the trips of a band and their share of all the trips. */
    fun shortTripsOfShare(trips: Int, percent: String): String = Lang.pick(
        "${TripsFormatting.count(trips)} ${plPlural(trips.toLong(), "trasa", "trasy", "tras")} ($percent %)",
        "${TripsFormatting.count(trips)} ${enPlural(trips.toLong(), "trip", "trips")} ($percent %)",
    )

    fun shortKmShare(percent: String): String = "$percent % km"

    /** `+1,62 l/100 km`, the excess of a short band over the reference row (trips from 10 km) under the bands. */
    fun shortExcessRate(signed: String): String = "$signed l/100 km"

    fun shortHeadline(tripsPercent: String, kmPercent: String): String = Lang.pick(
        "Krótkie trasy (poniżej 10 km): $tripsPercent % tras, $kmPercent % km",
        "Short trips (under 10 km): $tripsPercent % of trips, $kmPercent % of km",
    )

    /** `Nadwyżka w archiwum: 14,2 l · 91,40 zł`; the money part only with a price. */
    fun shortExcess(litres: String, money: String?): String {
        val tail = money?.let { SEPARATOR + it } ?: ""
        return Lang.pick("Nadwyżka w archiwum: $litres$tail", "Extra in the archive: $litres$tail")
    }

    fun shortColdRow(band: String, cold: String, warm: String, difference: String): String = Lang.pick(
        "$band: zimny $cold · ciepły $warm l/100 km ($difference)",
        "$band: cold $cold · warm $warm l/100 km ($difference)",
    )

    // Jazda na prądzie w tygodniach (docs/SPEC.md §36).

    val EV_WEEKS_SECTION: String get() = Lang.pick("Jazda na prądzie w tygodniach", "Weekly EV driving")
    val EV_WEEKS_HINT: String get() = Lang.pick(
        "Udział km na prądzie w każdym tygodniu (od poniedziałku), 12 ostatnich. Toyota nie podaje poziomu naładowania baterii hybrydowej ani napięcia akumulatora 12 V – to jedyny obraz baterii z danych tras.",
        "Share of km on battery in each week (from Monday), last 12 weeks. Toyota does not provide hybrid battery charge level or 12 V battery voltage – this is the only picture of the battery from trip data.",
    )

    /** The left side of a week row: `od 09.11 · 36,2 z 62,5 km · 7 tras`; the share stands on the right and the bar below (docs/SPEC.md §36). */
    fun evWeekLine(date: String, evKm: String, km: String, trips: Int): String = Lang.pick(
        "od $date · $evKm z $km km · ${TripsFormatting.count(trips)} ${plPlural(trips.toLong(), "trasa", "trasy", "tras")}",
        "from $date · $evKm of $km km · ${TripsFormatting.count(trips)} ${enPlural(trips.toLong(), "trip", "trips")}",
    )

    // Android Auto (docs/SPEC.md §35).

    val CAR_TITLE: String get() = Lang.pick("Toyota Companion", "Toyota Companion")
    val CAR_CONSUMPTION: String get() = Lang.pick("Średnie spalanie", "Average consumption")
    val CAR_TREND: String get() = Lang.pick("Trend i cena", "Trend and price")
    val CAR_FORECAST: String get() = Lang.pick("Tankowanie", "Refuel")
    val CAR_EMPTY_TITLE: String get() = Lang.pick("Brak danych", "No data")
    val CAR_EMPTY_TEXT: String get() = Lang.pick(
        "Otwórz aplikację na telefonie i zsynchronizuj.",
        "Open the app on your phone and sync.",
    )

    // Mapa ciepła ulubionych tras (docs/SPEC.md §34).

    val HEAT_BUTTON: String get() = Lang.pick("Pokaż przebiegi na mapie", "Show routes on map")
    val HEAT_TITLE: String get() = Lang.pick("Mapa ciepła trasy", "Route heat map")
    fun heatTooltip(litres: String): String = "$litres l/100 km"
    fun heatNote(heat: HeatState): String = when (heat) {
        HeatState.None -> ""
        HeatState.Loading -> Lang.pick("Wczytuję zapisane przebiegi…", "Loading saved routes…")
        HeatState.Failed -> Lang.pick(
            "Nie udało się odczytać przebiegów. Spróbuj ponownie.",
            "Failed to read routes. Try again.",
        )
        is HeatState.Empty -> Lang.pick(
            "Żaden z ${heat.total} przejazdów tej trasy nie ma zapisanego przebiegu. Zapisz je przyciskiem na ekranie „Trasy”.",
            "None of the ${heat.total} trips of this route has a saved route. Save them using the button on the \"Trips\" screen.",
        )
        is HeatState.Ready -> {
            val min = TripsFormatting.number(heat.minLPer100, 1)
            val max = TripsFormatting.number(heat.maxLPer100, 1)
            val linesCount = heat.lines.size
            val total = heat.total
            Lang.pick(
                "Zielony – najmniejsze spalanie na tej trasie ($min l/100 km), czerwony – największe " +
                    "($max). Przejazdy z przebiegiem: $linesCount z $total.",
                "Green – lowest consumption on this route ($min l/100 km), red – highest " +
                    "($max). Trips with route: $linesCount of $total.",
            )
        }
    }

    // Zapis przebiegów (docs/SPEC.md §33).

    val BACKFILL_BUTTON: String get() = Lang.pick("Zapisz przebiegi starszych tras", "Save routes of older trips")
    val BACKFILL_STOP: String get() = Lang.pick("Zatrzymaj", "Stop")
    val BACKFILL_HINT: String get() = Lang.pick(
        "Toyota usuwa trasy po ok. 12 miesiącach. Przebieg zapisany na telefonie zostaje. Jedno dotknięcie pobiera do 100 przebiegów, od najstarszych.",
        "Toyota deletes trips after approx. 12 months. Routes saved on phone remain. One tap fetches up to 100 routes, from oldest.",
    )
    fun backfillCounts(stored: Int, total: Int): String = Lang.pick(
        "Przebiegi zapisane na telefonie: $stored z $total ${plPlural(total.toLong(), "trasy", "tras", "tras")}",
        "Routes saved on phone: $stored of $total ${enPlural(total.toLong(), "trip", "trips")}",
    )
    fun backfillRunning(fetched: Int): String = Lang.pick(
        "Pobieram przebiegi z Toyoty… zapisano $fetched",
        "Fetching routes from Toyota… saved $fetched",
    )
    fun backfillResult(result: BackfillResult, fetched: Int): String = when (result) {
        BackfillResult.DONE -> Lang.pick(
            "Zapisano przebiegi ($fetched). Wszystkie trasy mają już przebieg.",
            "Saved routes ($fetched). All trips now have a route.",
        )
        BackfillResult.PARTIAL -> Lang.pick(
            "Zapisano $fetched ${plPlural(fetched.toLong(), "przebieg", "przebiegi", "przebiegów")}. Dotknij ponownie, żeby pobrać resztę.",
            "Saved $fetched ${enPlural(fetched.toLong(), "route", "routes")}. Tap again to fetch the rest.",
        )
        BackfillResult.NOTHING -> Lang.pick(
            "Wszystkie trasy mają już zapisany przebieg.",
            "All trips already have a saved route.",
        )
        BackfillResult.NETWORK -> Lang.pick(
            "Brak połączenia z Toyotą. Zapisano $fetched. Spróbuj później.",
            "No connection to Toyota. Saved $fetched. Try again later.",
        )
        BackfillResult.LOGIN -> Lang.pick(
            "Toyota prosi o ponowne logowanie. Zapisano $fetched.",
            "Toyota asks to log in again. Saved $fetched.",
        )
        BackfillResult.API -> Lang.pick(
            "Toyota nie odpowiedziała poprawnie. Zapisano $fetched. Spróbuj później.",
            "Toyota did not respond properly. Saved $fetched. Try again later.",
        )
        BackfillResult.STOPPED -> Lang.pick("Zatrzymano. Zapisano $fetched.", "Stopped. Saved $fetched.")
    }

    // Widżet (docs/SPEC.md §32).

    val WIDGET_TITLE: String get() = Lang.pick("Toyota Companion", "Toyota Companion")
    val WIDGET_EMPTY: String get() = Lang.pick("Otwórz aplikację", "Open app")
    fun widgetMain(litres: String): String = "$litres l/100 km"
    fun widgetPrice(price: String): String = Lang.pick("cena $price zł/l", "price $price zł/l")

    // Alert o rosnącym spalaniu (docs/SPEC.md §31).

    val ALERT_CHANNEL: String get() = Lang.pick("Spalanie", "Consumption")
    val ALERT_TITLE: String get() = Lang.pick(
        "Spalanie rośnie trzeci miesiąc z rzędu",
        "Consumption is rising for the third consecutive month",
    )
    fun alertRising(values: String, change: String): String = Lang.pick(
        "l/100 km: $values ($change %). Poza zimą warto sprawdzić np. ciśnienie w oponach. To tylko podpowiedź, aplikacja nie diagnozuje.",
        "l/100 km: $values ($change %). Outside winter, it is worth checking tire pressure. This is only a hint, the app does not diagnose.",
    )

    // Autostrada i przekroczenia prędkości (PRD F40, docs/SPEC.md §41).

    fun driveHighway(km: String): String = Lang.pick("autostrada $km km", "motorway $km km")
    fun driveOverspeed(km: String, sharePct: String): String =
        Lang.pick("przekroczenia $km km ($sharePct %)", "speeding $km km ($sharePct %)")

    // Cele eco (PRD F39, docs/SPEC.md §40).

    val ECO_TITLE: String get() = Lang.pick("Cel miesiąca", "Monthly goal")
    val ECO_SET: String get() = Lang.pick("Ustaw cel", "Set a goal")
    val ECO_EDIT: String get() = Lang.pick("Zmień cele", "Edit goals")
    val ECO_DIALOG_TITLE: String get() = Lang.pick("Cele eco", "Eco goals")
    val ECO_BODY: String get() = Lang.pick(
        "Dwa niezależne cele na każdy miesiąc kalendarzowy. Zostaw pole puste, żeby nie mieć danego celu.",
        "Two independent goals for each calendar month. Leave a field empty to have no such goal.",
    )
    val ECO_CONSUMPTION_FIELD: String get() = Lang.pick("Spalanie najwyżej, l/100 km", "Consumption at most, l/100 km")
    val ECO_EV_FIELD: String get() = Lang.pick("Jazda na prądzie co najmniej, %", "Electric driving at least, %")
    val ECO_INVALID: String get() = Lang.pick(
        "Spalanie: liczba większa od 0 i nie większa niż 20, np. 4,5. Prąd: liczba całkowita od 1 do 100.",
        "Consumption: a number greater than 0 and no more than 20, e.g. 4.5. Electric: a whole number from 1 to 100.",
    )
    val ECO_SAVE: String get() = Lang.pick("Zapisz", "Save")
    val ECO_CANCEL: String get() = Lang.pick("Anuluj", "Cancel")
    val ECO_NO_EV_DATA: String get() = Lang.pick(
        "Jazda na prądzie: brak danych w tym miesiącu",
        "Electric driving: no data this month",
    )
    fun ecoConsumptionLine(actual: String, goal: String, mark: String?): String =
        Lang.pick("Spalanie: $actual / $goal l/100 km", "Consumption: $actual / $goal l/100 km") + (mark?.let { " $it" } ?: "")
    fun ecoEvLine(actual: String, goal: String, mark: String?): String =
        Lang.pick("Jazda na prądzie: $actual / $goal %", "Electric driving: $actual / $goal %") + (mark?.let { " $it" } ?: "")
    fun ecoTooLittle(km: String): String = Lang.pick(
        "Za mało jazdy, by ocenić ($km km)",
        "Too little driving to judge ($km km)",
    )
    fun ecoPrevious(parts: String): String = Lang.pick("Poprzedni miesiąc: $parts", "Previous month: $parts")
    fun ecoPreviousTooLittle(km: String): String = Lang.pick(
        "Poprzedni miesiąc: za mało jazdy, by ocenić ($km km)",
        "Previous month: too little driving to judge ($km km)",
    )
    fun ecoPreviousConsumption(met: Boolean): String = Lang.pick(
        if (met) "cel spalania – osiągnięty" else "cel spalania – nieosiągnięty",
        if (met) "consumption goal – met" else "consumption goal – missed",
    )
    fun ecoPreviousEv(met: Boolean): String = Lang.pick(
        if (met) "cel jazdy na prądzie – osiągnięty" else "cel jazdy na prądzie – nieosiągnięty",
        if (met) "electric driving goal – met" else "electric driving goal – missed",
    )
    fun ecoWidget(parts: String): String = Lang.pick("Cel: $parts", "Goal: $parts")
    fun ecoWidgetTooLittle(km: String): String = Lang.pick("za mało jazdy ($km km)", "too little driving ($km km)")
    fun ecoWidgetConsumption(actual: String, goal: String, mark: String?): String =
        "$actual / $goal l/100 km" + (mark?.let { " $it" } ?: "")
    fun ecoWidgetEv(actual: String, goal: String, mark: String?): String =
        Lang.pick("prąd $actual / $goal %", "electric $actual / $goal %") + (mark?.let { " $it" } ?: "")

    // Budżet miesięczny (PRD F48, docs/SPEC.md §49).

    val BUDGET_TITLE: String get() = Lang.pick("Budżet miesiąca", "Monthly budget")
    val BUDGET_HINT: String get() = Lang.pick(
        "Paliwo i wydatki poza paliwem w tym miesiącu, liczone z archiwum na telefonie. Bez sieci, bez budzenia auta.",
        "Fuel and non-fuel expenses of this month, counted from the archive on the phone. No network, no waking the car.",
    )
    val BUDGET_SET: String get() = Lang.pick("Ustaw budżet", "Set budget")
    val BUDGET_EDIT: String get() = Lang.pick("Zmień budżet", "Change budget")
    val BUDGET_DIALOG_TITLE: String get() = Lang.pick("Budżet miesięczny", "Monthly budget")
    val BUDGET_BODY: String get() = Lang.pick(
        "Jeden limit na paliwo i wydatki poza paliwem w każdym miesiącu kalendarzowym. Puste pole usuwa budżet.",
        "One limit for fuel and non-fuel expenses in each calendar month. Leave the field empty to remove the budget.",
    )
    val BUDGET_FIELD: String get() = Lang.pick("Limit [zł]", "Budget limit [zł]")
    val BUDGET_INVALID: String get() = Lang.pick(
        "Podaj kwotę większą od zera, do dwóch miejsc po przecinku, np. 800,50.",
        "Enter an amount greater than zero with at most two decimals, e.g. 800.50.",
    )
    val BUDGET_READ_ERROR: String get() = Lang.pick(
        "Nie udało się odczytać budżetu z archiwum.",
        "Could not read the budget from the archive.",
    )
    val BUDGET_NO_FIGURES: String get() = Lang.pick(
        "Liczby pojawią się po odczytaniu archiwum.",
        "The figures appear once the archive is read.",
    )

    /** `listopad 2026: 74% z 800,00 zł` — the month, the whole percent used and the limit. */
    fun budgetCaption(month: String, limit: String, percent: Int): String =
        Lang.pick("$month: $percent% z $limit zł", "$month: $percent% of $limit zł")

    fun budgetFuel(value: String): String = Lang.pick("Paliwo: $value zł", "Fuel: $value zł")
    fun budgetExpenses(value: String): String = Lang.pick("Wydatki: $value zł", "Expenses: $value zł")
    fun budgetRemaining(value: String): String = Lang.pick("Zostało: $value zł", "Left: $value zł")
    fun budgetOver(value: String): String = Lang.pick("Przekroczono o $value zł", "Over by $value zł")

    // Powiadomienie o budżecie (PRD F49, docs/SPEC.md §49).

    val BUDGET_ALERT_CHANNEL: String get() = Lang.pick("Budżet", "Budget")
    val BUDGET_ALERT_WARN_TITLE: String get() = Lang.pick("Budżet miesiąca: blisko limitu", "Monthly budget: close to the limit")
    val BUDGET_ALERT_OVER_TITLE: String get() = Lang.pick("Budżet miesiąca przekroczony", "Monthly budget exceeded")

    /** The 80% warning: month, spent, limit, the whole percent and what is left (may be 0,00); amounts only, no VIN or place. */
    fun budgetAlertWarn(month: String, spent: String, limit: String, percent: Int, left: String): String = Lang.pick(
        "$month: wydano $spent z $limit zł ($percent%). Zostało $left zł.",
        "$month: spent $spent of $limit zł ($percent%). Left: $left zł.",
    )

    /** The exceeded budget: the same facts plus how much over. */
    fun budgetAlertOver(month: String, spent: String, limit: String, percent: Int, over: String): String = Lang.pick(
        "$month: wydano $spent z $limit zł ($percent%), przekroczono o $over zł.",
        "$month: spent $spent of $limit zł ($percent%), over by $over zł.",
    )

    val BUDGET_ALERT_SETTING_TITLE: String get() = Lang.pick("Powiadomienia o budżecie", "Budget notifications")
    val BUDGET_ALERT_SETTING_SWITCH: String get() = Lang.pick("Powiadamiaj o budżecie", "Notify about the budget")
    val BUDGET_ALERT_SETTING_HINT: String get() = Lang.pick(
        "Jedno powiadomienie przy 80% i jedno przy przekroczeniu limitu, raz na miesiąc. Wymaga włączonych powiadomień aplikacji.",
        "One notification at 80% and one when the limit is exceeded, once a month. Requires the app's notifications to be on.",
    )

    // Spalanie deklarowane (docs/SPEC.md §30).

    val DECLARED_SET: String get() = Lang.pick("Ustaw spalanie deklarowane", "Set declared consumption")
    val DECLARED_EDIT: String get() = Lang.pick("Zmień deklarowane", "Edit declared")
    val REFUEL_EDIT_LABEL: String get() = Lang.pick("Zmień tankowanie", "Edit refuel")
    val DECLARED_TITLE: String get() = Lang.pick("Spalanie deklarowane", "Declared consumption")
    val DECLARED_FIELD: String get() = Lang.pick("l/100 km (puste usuwa)", "l/100 km (empty removes)")
    val DECLARED_BODY: String get() = Lang.pick(
        "Wpisz spalanie mieszane z danych producenta albo z dowodu rejestracyjnego Twojego modelu. Aplikacja go nie zna.",
        "Enter combined consumption from manufacturer data or your vehicle registration certificate. The app does not know it.",
    )
    val DECLARED_INVALID: String get() = Lang.pick(
        "Wpisz liczbę większą od 0 i nie większą niż 20, np. 4,5.",
        "Enter a number greater than 0 and no more than 20, e.g. 4.5.",
    )
    val DECLARED_SAVE: String get() = Lang.pick("Zapisz", "Save")
    val DECLARED_CANCEL: String get() = Lang.pick("Anuluj", "Cancel")
    val DECLARED_LESS: String get() = Lang.pick("taniej", "lower")
    val DECLARED_MORE: String get() = Lang.pick("drożej", "higher")
    val DECLARED_SAME: String get() = Lang.pick("tak samo", "the same")
    fun declaredVersus(declared: String, change: String, word: String): String = Lang.pick(
        "Deklarowane: $declared l/100 km · Twoje średnie: $change ($word niż deklarowane)",
        "Declared: $declared l/100 km · Your average: $change ($word than declared)",
    )

    // Podsumowanie roku (docs/SPEC.md §29).

    val YEAR_TITLE: String get() = Lang.pick("Podsumowanie roku", "Year summary")
    val YEAR_EMPTY: String get() = Lang.pick("Brak tras do podsumowania.", "No trips to summarize.")
    val YEAR_READ_ERROR: String get() = Lang.pick(
        "Nie udało się odczytać tras. Spróbuj ponownie.",
        "Failed to read trips. Try again.",
    )
    val YEAR_SHARE: String get() = Lang.pick("Udostępnij podsumowanie", "Share summary")
    val YEAR_NOTE: String get() = Lang.pick(
        "Udostępniany jest tylko tekst z tej strony: bez położenia, VIN i danych konta.",
        "Only text from this page is shared: without location, VIN or account data.",
    )
    val yearNoCost: String get() = Lang.pick("Koszt: brak ceny", "Cost: no price")
    fun yearHeading(year: Int): String = Lang.pick("Rok $year", "Year $year")
    fun yearShareTitle(year: Int): String = Lang.pick(
        "Toyota Companion – podsumowanie roku $year",
        "Toyota Companion – year $year summary",
    )
    fun yearTotals(trips: Int, km: String, litres: String): String = Lang.pick(
        "Trasy: ${TripsFormatting.count(trips)} · $km km · $litres l paliwa",
        "Trips: ${TripsFormatting.count(trips)} · $km km · $litres l fuel",
    )
    fun yearAverage(litres: String): String = Lang.pick(
        "Średnie spalanie: $litres l/100 km",
        "Average consumption: $litres l/100 km",
    )
    fun yearCost(total: String, per100: String?): String {
        val pl = "Koszt paliwa: $total" + (per100?.let { " ($it/100 km)" } ?: "")
        val en = "Fuel cost: $total" + (per100?.let { " ($it/100 km)" } ?: "")
        return Lang.pick(pl, en)
    }
    fun co2(amount: String): String = Lang.pick("Ślad CO₂: $amount", "CO₂ footprint: $amount")
    fun co2Hint(perLiter: String): String = Lang.pick(
        "Szacunek ze spalonych litrów (ok. $perLiter kg CO₂ na litr benzyny); bez prądu, produkcji i utylizacji auta.",
        "An estimate from the litres burned (about $perLiter kg of CO₂ per litre of gasoline); without electricity or making and scrapping the car.",
    )
    fun yearEv(percent: String): String = Lang.pick("Jazda na prądzie: $percent % km", "EV driving: $percent % km")
    fun yearDays(days: Int): String = Lang.pick("Dni z jazdą: $days", "Days driven: $days")
    fun yearLongest(km: String, start: String): String = Lang.pick(
        "Najdłuższa trasa: $km km ($start)",
        "Longest trip: $km km ($start)",
    )
    fun yearBest(month: String, litres: String): String = Lang.pick(
        "Najlepsze spalanie: $month, $litres l/100 km",
        "Best consumption: $month, $litres l/100 km",
    )
    fun yearWorst(month: String, litres: String): String = Lang.pick(
        "Najwyższe spalanie: $month, $litres l/100 km",
        "Highest consumption: $month, $litres l/100 km",
    )
    fun yearDearest(month: String, money: String): String = Lang.pick(
        "Najdroższy miesiąc: $month, $money",
        "Most expensive month: $month, $money",
    )
    fun yearVersus(year: Int, km: String?, cost: String?, litres: String?): String = if (Lang.isEnglish) {
        "Vs $year: " + listOfNotNull(km?.let { "km $it" }, cost?.let { "cost $it" }, litres?.let { "consumption $it l/100 km" })
            .ifEmpty { listOf("no comparison") }.joinToString(SEPARATOR)
    } else {
        "Wobec $year: " + listOfNotNull(km?.let { "km $it" }, cost?.let { "koszt $it" }, litres?.let { "spalanie $it l/100 km" })
            .ifEmpty { listOf("brak porównania") }.joinToString(SEPARATOR)
    }

    // Koszt całkowity auta (PRD F38, docs/SPEC.md §39).

    val OWNERSHIP_TITLE: String get() = Lang.pick("Koszt całkowity", "Total cost")
    val OWNERSHIP_FUEL_SHARE: String get() = Lang.pick("Udział paliwa", "Fuel share")
    val OWNERSHIP_NOTE: String get() = Lang.pick(
        "Paliwo z tras z ceną i wydatki z ekranu „Wydatki” z tego roku.",
        "Fuel of the priced trips and this year's expenses from \"Expenses\".",
    )
    fun ownershipTotal(total: String, perKm: String?, per100: String?): String {
        val rates = listOfNotNull(perKm?.let { "$it/km" }, per100?.let { "$it/100 km" })
        val tail = if (rates.isEmpty()) "" else " (" + rates.joinToString(SEPARATOR) + ")"
        return Lang.pick("Koszt całkowity: $total$tail", "Total cost: $total$tail")
    }
    fun ownershipPerMonth(money: String, months: Int): String = Lang.pick(
        "Średnio $money na miesiąc (z $months ${plPlural(months.toLong(), "miesiąca", "miesięcy", "miesięcy")})",
        "On average $money a month (over $months ${enPlural(months.toLong(), "month", "months")})",
    )
    fun ownershipSplit(fuel: String, expenses: String, fuelShare: String?): String = Lang.pick(
        "Paliwo $fuel" + (fuelShare?.let { " ($it %)" } ?: "") + SEPARATOR + "wydatki $expenses",
        "Fuel $fuel" + (fuelShare?.let { " ($it %)" } ?: "") + SEPARATOR + "expenses $expenses",
    )
    fun ownershipTop(categories: String): String = Lang.pick("Najwięcej poza paliwem: $categories", "Most besides fuel: $categories")
    fun ownershipVersus(change: String): String = Lang.pick(
        "Koszt całkowity wobec poprzedniego roku: $change",
        "Total cost vs the year before: $change",
    )

    // Prędkość × temperatura (docs/SPEC.md §28).

    val MATRIX_TITLE: String get() = Lang.pick("Prędkość i temperatura", "Speed and temperature")
    val MATRIX_HINT: String get() = Lang.pick(
        "Czy wolna jazda kosztuje więcej zimą? Trasy od 2 km wg tempa (z „Statystyk”) i temperatury dnia: l/100 km i w nawiasie liczba tras. " +
            "Poniżej 5 tras w komórce nie ma wniosku.",
        "Does slow driving cost more in winter? Trips from 2 km by pace (from \"Statistics\") and daily temperature: l/100 km and trip count in parentheses. " +
            "Fewer than 5 trips in a cell yields no conclusion.",
    )
    val MATRIX_EMPTY: String get() = Lang.pick(
        "Brak tras z pogodą i prędkością. Pobierz pogodę.",
        "No trips with weather and speed. Fetch weather.",
    )
    fun matrixCell(litres: String, trips: Int): String = "$litres l (${TripsFormatting.count(trips)})"
    fun matrixTooFew(trips: Int): String = Lang.pick(
        "${TripsFormatting.count(trips)} ${plPlural(trips.toLong(), "trasa", "trasy", "tras")} – za mało",
        "${TripsFormatting.count(trips)} ${enPlural(trips.toLong(), "trip", "trips")} – too few",
    )
    fun matrixBucketShort(bucket: pl.lk.toyotacompanion.core.domain.TemperatureBucket): String = when (bucket) {
        pl.lk.toyotacompanion.core.domain.TemperatureBucket.DEEP_FROST -> Lang.pick("silny mróz", "deep frost")
        pl.lk.toyotacompanion.core.domain.TemperatureBucket.FROST -> Lang.pick("mróz", "frost")
        pl.lk.toyotacompanion.core.domain.TemperatureBucket.COLD -> Lang.pick("chłodno", "cold")
        pl.lk.toyotacompanion.core.domain.TemperatureBucket.MILD -> Lang.pick("łagodnie", "mild")
        pl.lk.toyotacompanion.core.domain.TemperatureBucket.WARM -> Lang.pick("ciepło", "warm")
    }
    fun matrixFrost(signed: String): String = Lang.pick(
        "Zimą przy tej prędkości: $signed l/100 km (mróz wobec łagodnie)",
        "In winter at this speed: $signed l/100 km (frost vs mild)",
    )

    val FORECAST_CARD_TITLE: String get() = Lang.pick("Prognoza", "Forecast")

    // Cena ze stacji (docs/SPEC.md §25).

    val PRICE_SOURCE: String get() = Lang.pick("Źródło ceny", "Price source")
    val PRICE_MANUAL: String get() = Lang.pick("Ręcznie", "Manual")
    val PRICE_FETCHING: String get() = Lang.pick("Sprawdzam cenę na trojmiasto.pl…", "Checking price on trojmiasto.pl…")
    val PRICE_AGE_TODAY: String get() = Lang.pick("wpisana dziś", "entered today")
    val PRICE_AGE_YESTERDAY: String get() = Lang.pick("wpisana wczoraj", "entered yesterday")
    val PRICE_AGE_OLDER: String get() = Lang.pick("wpisana dawniej niż wczoraj", "entered earlier than yesterday")
    fun priceFound(station: String, price: String, age: String, stale: Boolean): String {
        val pl = "$station: $price zł/l, $age (ceny wpisują użytkownicy portalu i stacje)." +
            (if (stale) " Cena może być nieaktualna – sprawdź." else "")
        val en = "$station: $price zł/l, $age (prices are entered by portal users and stations)." +
            (if (stale) " Price may be outdated – check." else "")
        return Lang.pick(pl, en)
    }
    fun priceStationMissing(station: String): String = Lang.pick(
        "$station: nie ma jej teraz na liście cen. Wpisz cenę ręcznie.",
        "$station: not currently on the price list. Enter price manually.",
    )
    fun priceNone(station: String): String = Lang.pick(
        "$station: nikt nie wpisał ceny 95. Wpisz cenę ręcznie.",
        "$station: no one entered a price for 95. Enter price manually.",
    )
    fun priceFetchError(error: PriceFetchResult): String = when (error) {
        PriceFetchResult.NETWORK -> Lang.pick(
            "Brak połączenia z trojmiasto.pl. Wpisz cenę ręcznie.",
            "No connection to trojmiasto.pl. Enter price manually.",
        )
        PriceFetchResult.API -> Lang.pick(
            "trojmiasto.pl nie odpowiada poprawnie. Wpisz cenę ręcznie.",
            "trojmiasto.pl did not respond properly. Enter price manually.",
        )
        PriceFetchResult.DATA -> Lang.pick(
            "Nie udało się odczytać cen z trojmiasto.pl (strona mogła się zmienić). Wpisz cenę ręcznie.",
            "Failed to read prices from trojmiasto.pl (page may have changed). Enter price manually.",
        )
    }

    // Mapa w aplikacji (docs/SPEC.md §26).

    val MAP_TITLE_TRIP: String get() = Lang.pick("Mapa trasy", "Trip map")
    val MAP_TITLE_PLACE: String get() = Lang.pick("Mapa miejsca", "Place map")
    val MAP_START: String get() = Lang.pick("Start", "Start")
    val MAP_END: String get() = Lang.pick("Koniec", "End")
    val MAP_PLACE: String get() = Lang.pick("Miejsce", "Place")
    val MAP_NOTE: String get() = Lang.pick(
        "Mapa OpenStreetMap. Serwer map widzi okolicę, którą oglądasz (kafelki), nie dokładne punkty.",
        "OpenStreetMap map. The map server sees the area you view (tiles), not exact points.",
    )
    fun mapRouteNote(route: RouteState): String = when (route) {
        RouteState.None -> Lang.pick("Miejsce zaokrąglone do ok. 100 m.", "Location rounded to approx. 100 m.")
        RouteState.Loading -> Lang.pick("Pobieram przebieg trasy z Toyoty…", "Fetching route from Toyota…")
        is RouteState.Ready -> Lang.pick(
            "Przebieg trasy z GPS Toyoty: zielony – jazda na prądzie, niebieski – silnik spalinowy.",
            "Route from Toyota GPS: green – EV driving, blue – combustion engine.",
        )
        RouteState.Missing -> Lang.pick(
            "Toyota nie ma przebiegu tej trasy (przechowuje ok. 12 miesięcy). Pokazuję początek i koniec, prosta linia.",
            "Toyota has no route for this trip (stored for approx. 12 months). Showing start and end, straight line.",
        )
        is RouteState.Failed -> when (route.reason) {
            RouteFailure.NETWORK -> Lang.pick(
                "Brak połączenia z Toyotą – pokazuję początek i koniec (prosta linia). Otwórz mapę ponownie później.",
                "No connection to Toyota – showing start and end (straight line). Open map again later.",
            )
            RouteFailure.LOGIN -> Lang.pick(
                "Toyota prosi o ponowne logowanie – pokazuję początek i koniec (prosta linia).",
                "Toyota asks to log in again – showing start and end (straight line).",
            )
            RouteFailure.API -> Lang.pick(
                "Toyota nie podała przebiegu tej trasy – pokazuję początek i koniec (prosta linia).",
                "Toyota did not provide route for this trip – showing start and end (straight line).",
            )
        }
    }
    val MAP_OPEN_EXTERNAL: String get() = Lang.pick("Otwórz w innej aplikacji z mapą", "Open in another map app")

    // Pogoda i spalanie (PRD F15, docs/SPEC.md §23).

    val WEATHER_TITLE: String get() = Lang.pick("Pogoda i spalanie", "Weather and consumption")
    val WEATHER_READ_ERROR: String get() = Lang.pick(
        "Nie udało się odczytać pogody. Spróbuj ponownie.",
        "Failed to read weather. Try again.",
    )
    val WEATHER_HINT: String get() = Lang.pick(
        "Trasy podzielone wg pogody dnia i miejsca startu (dane z archiwum reanaliz Open-Meteo, to model, nie termometr). " +
            "Pobranie wysyła do usługi tylko punkt startu zaokrąglony do ok. 1 km i daty – nigdy VIN ani dokładne położenie. Gdy automatyczne pobieranie jest włączone, dzieje się to po każdej synchronizacji i przy otwarciu tego ekranu.",
        "Trips grouped by daily weather and start place (data from Open-Meteo reanalysis archive, this is a model, not a thermometer). " +
            "Fetching sends to the service only the start point rounded to approx. 1 km and dates – never VIN or exact location. When auto-fetch is enabled, this happens after every sync and upon opening this screen.",
    )
    val WEATHER_FETCH: String get() = Lang.pick("Pobierz pogodę", "Fetch weather")
    val WEATHER_AUTO: String get() = Lang.pick(
        "Pobieraj pogodę automatycznie (po synchronizacji i przy otwarciu ekranu)",
        "Fetch weather automatically (after sync and when opening screen)",
    )
    val WEATHER_FETCHING: String get() = Lang.pick("Pobieranie…", "Fetching…")
    val WEATHER_BY_TEMPERATURE: String get() = Lang.pick("Wg temperatury", "By temperature")
    val WEATHER_BY_RAIN: String get() = Lang.pick("Wg opadów", "By precipitation")
    val WEATHER_NO_DATA: String get() = Lang.pick("Brak tras z pogodą. Pobierz pogodę.", "No trips with weather. Fetch weather.")
    val WEATHER_TOO_FEW: String get() = Lang.pick("za mało tras na wniosek", "too few trips for conclusion")
    val WEATHER_CAVEAT: String get() = Lang.pick(
        "To korelacja na Twoich trasach, nie pomiar przyczyny: zimą trasy są inne, a mróz skraca też jazdę na prądzie " +
            "(patrz udział jazdy elektrycznej).",
        "This is a correlation across your trips, not a cause measurement: in winter trips are different, and frost also shortens EV driving " +
            "(see EV share).",
    )
    val WEATHER_ATTRIBUTION: String get() = Lang.pick(
        "Weather data by Open-Meteo.com (CC BY 4.0), https://open-meteo.com/",
        "Weather data by Open-Meteo.com (CC BY 4.0), https://open-meteo.com/",
    )
    fun weatherBucketName(bucket: String): String = when (bucket) {
        "deep_frost" -> Lang.pick("Silny mróz (poniżej -10 °C)", "Deep frost (below -10 °C)")
        "frost" -> Lang.pick("Mróz (-10 do 0 °C)", "Frost (-10 to 0 °C)")
        "cold" -> Lang.pick("Chłodno (0–10 °C)", "Cold (0–10 °C)")
        "mild" -> Lang.pick("Łagodnie (10–20 °C)", "Mild (10–20 °C)")
        "warm" -> Lang.pick("Ciepło (od 20 °C)", "Warm (from 20 °C)")
        "dry" -> Lang.pick("Sucho (poniżej 0,5 mm)", "Dry (below 0.5 mm)")
        "wet" -> Lang.pick("Mokro (od 0,5 mm)", "Wet (from 0.5 mm)")
        else -> bucket
    }
    fun weatherDelta(signed: String): String = Lang.pick("$signed l/100 km wobec odniesienia", "$signed l/100 km vs baseline")
    fun weatherEv(share: String): String = Lang.pick("na prądzie $share", "on battery $share")
    fun weatherWind(kmh: String): String = Lang.pick("wiatr do $kmh km/h", "wind up to $kmh km/h")
    fun weatherWithout(count: Int, total: Int): String = Lang.pick(
        "Bez pogody w tym podziale: $count z $total ${plPlural(total.toLong(), "trasy", "tras", "tras")} (brak położenia lub danych dla dnia).",
        "Without weather in this split: $count of $total ${enPlural(total.toLong(), "trip", "trips")} (missing location or daily data).",
    )
    fun weatherPending(requests: Int, left: Int, cachedDays: Int): String = when {
        requests == 0 -> Lang.pick(
            "Nic do pobrania. Dni w pamięci: $cachedDays.",
            "Nothing to fetch. Days cached: $cachedDays.",
        )
        left > 0 -> Lang.pick(
            "Do pobrania: zapytania $requests (jedno dotknięcie robi ${requests - left}). Dni w pamięci: $cachedDays.",
            "To fetch: $requests requests (one tap does ${requests - left}). Days cached: $cachedDays.",
        )
        else -> Lang.pick(
            "Do pobrania: zapytania $requests. Dni w pamięci: $cachedDays.",
            "To fetch: $requests requests. Days cached: $cachedDays.",
        )
    }
    fun weatherFetchResult(result: WeatherFetchResult): String = when (result) {
        WeatherFetchResult.DONE -> Lang.pick("Pobrano pogodę.", "Weather fetched.")
        WeatherFetchResult.PARTIAL -> Lang.pick(
            "Pobrano część. Dotknij ponownie, żeby pobrać resztę.",
            "Partially fetched. Tap again to fetch the rest.",
        )
        WeatherFetchResult.NOTHING_TO_FETCH -> Lang.pick("Nie ma czego pobierać.", "Nothing to fetch.")
        WeatherFetchResult.NETWORK -> Lang.pick(
            "Brak połączenia z usługą pogody. Spróbuj później.",
            "No connection to weather service. Try again later.",
        )
        WeatherFetchResult.RATE_LIMIT -> Lang.pick(
            "Usługa pogody prosi o chwilę przerwy. Spróbuj za minutę.",
            "Weather service asks for a short pause. Try in a minute.",
        )
        WeatherFetchResult.API -> Lang.pick(
            "Usługa pogody odrzuciła zapytanie. Spróbuj później.",
            "Weather service rejected request. Try again later.",
        )
        WeatherFetchResult.DATA -> Lang.pick(
            "Usługa pogody zwróciła nieczytelną odpowiedź.",
            "Weather service returned an unreadable response.",
        )
        WeatherFetchResult.STORAGE -> Lang.pick(
            "Nie udało się zapisać pogody w telefonie.",
            "Failed to save weather on the phone.",
        )
    }

    // Postoje (PRD F17, docs/SPEC.md §18).

    val PARKING_TITLE: String get() = Lang.pick("Postoje", "Parking stops")
    val PARKING_EMPTY: String get() = Lang.pick("Brak postojów do pokazania.", "No parking stops to show.")
    val PARKING_READ_ERROR: String get() = Lang.pick(
        "Nie udało się odczytać postojów. Spróbuj ponownie.",
        "Failed to read parking stops. Try again.",
    )
    val PARKING_PRIVACY: String get() = Lang.pick(
        "Położenie zaokrąglone do ok. 100 m – liczone z tras w archiwum, bez pytania auta. Poza aplikację trafia tylko po dotknięciu „Otwórz w mapie” albo wiersza trasy na liście tras.",
        "Location rounded to approx. 100 m – calculated from archived trips without querying the car. Left the app only after tapping \"Open in map\" or a trip row in the trips list.",
    )
    val PARKING_PLACES: String get() = Lang.pick("Miejsca", "Places")
    val PARKING_RECENT: String get() = Lang.pick("Ostatnie postoje", "Recent parking stops")
    val TRIP_MAP_HINT: String get() = Lang.pick(
        "Trasa zapisuje tylko początek i koniec. Otworzy się aplikacja z mapą, położenie zaokrąglone do ok. 100 m.",
        "Trip records only start and end. A map app will open, location rounded to approx. 100 m.",
    )
    val TRIP_MAP_CANCEL: String get() = Lang.pick("Anuluj", "Cancel")
    val TRIP_MAP_START: String get() = Lang.pick("Start w mapie", "Start on map")
    val TRIP_MAP_END: String get() = Lang.pick("Koniec w mapie", "End on map")
    val PARKING_OPEN_MAP: String get() = Lang.pick("Otwórz w mapie", "Open in map")
    val PARKING_NO_MAP_APP: String get() = Lang.pick("Brak aplikacji z mapą.", "No map app available.")
    val PARKING_NO_POSITION: String get() = Lang.pick("bez położenia", "no location")

    fun parkingStops(count: Int): String = Lang.pick("postoje: ${TripsFormatting.count(count)}", "parking stops: ${TripsFormatting.count(count)}")
    fun parkingTotal(duration: String): String = Lang.pick("razem $duration", "total $duration")
    fun parkingLast(time: String): String = Lang.pick("ostatnio $time", "last $time")
    fun parkingSkipped(unmatched: Int, longGaps: Int): String = Lang.pick(
        "Pominięte – koniec i start w różnych miejscach (brak trasy w archiwum): $unmatched, przerwy dłuższe niż 21 dni: $longGaps.",
        "Skipped – start and end in different places (missing trip in archive): $unmatched, gaps longer than 21 days: $longGaps.",
    )

    // Dziennik tankowań (PRD F9, docs/SPEC.md §15).

    /** Status button and the title of the screen. */
    val LOG_TITLE: String get() = Lang.pick("Dziennik tankowań", "Refuel log")

    val LOG_EMPTY: String get() = Lang.pick("Brak potwierdzonych tankowań.", "No confirmed refuels.")
    val LOG_HINT: String get() = Lang.pick(
        "Żeby porównać spalanie z komputerem auta, przy tankowaniu zaznacz „Do pełna” i podaj litry z paragonu.",
        "To compare consumption with the car computer, mark \"Filled to full\" when refueling and enter liters from receipt.",
    )
    val LOG_READ_ERROR: String get() = Lang.pick("Nie udało się odczytać dziennika. Spróbuj ponownie.", "Failed to read log. Try again.")

    /** Where the liters of a refuel come from (docs/SPEC.md §2). */
    val LOG_SOURCE_RECEIPT: String get() = Lang.pick("z paragonu", "from receipt")
    val LOG_SOURCE_DERIVED: String get() = Lang.pick("wyliczone z paragonu", "calculated from receipt")
    val LOG_SOURCE_ESTIMATE: String get() = Lang.pick("szacunek z czujnika", "sensor estimate")

    /** Header of the open interval at the top of the log. */
    val LOG_CURRENT: String get() = Lang.pick("Bieżący odcinek", "Current interval")

    fun economyCompared(actual: String, computer: String, difference: String): String = Lang.pick(
        "Spalanie: faktyczne $actual · komputer $computer ($difference)",
        "Consumption: actual $actual · computer $computer ($difference)",
    )

    fun economyTrips(distance: String): String = Lang.pick("$distance tras", "$distance trips")
    fun economyOdometer(distance: String): String = Lang.pick("licznik $distance", "odometer $distance")
    fun economyEv(distance: String): String = Lang.pick("w tym EV $distance", "including EV $distance")
    fun economyComputerSoFar(consumption: String): String = Lang.pick("komputer $consumption", "computer $consumption")

    // Poprawka potwierdzonego tankowania (docs/SPEC.md §15.3).
    val AMEND_BUTTON: String get() = Lang.pick("Popraw litry", "Amend liters")
    val AMEND_TITLE: String get() = Lang.pick("Litry z paragonu", "Liters from receipt")
    val AMEND_BODY: String get() = Lang.pick(
        "Wpisz litry z paragonu. Kwota jest opcjonalna – z nią cena za litr policzy się z paragonu.",
        "Enter liters from receipt. Total cost is optional – with it, price per liter is calculated from receipt.",
    )
    val AMEND_FIELD_LITERS: String get() = Lang.pick("Litry z paragonu", "Liters from receipt")
    val AMEND_FIELD_TOTAL: String get() = Lang.pick("Kwota z paragonu (opcjonalnie)", "Total cost from receipt (optional)")
    val AMEND_SAVE: String get() = Lang.pick("Zapisz", "Save")
    val AMEND_CANCEL: String get() = Lang.pick("Anuluj", "Cancel")
    val AMEND_LITERS_REQUIRED: String get() = Lang.pick("Podaj litry z paragonu.", "Enter liters from receipt.")
    val AMEND_NOT_CONFIRMED: String get() = Lang.pick("Najpierw podaj cenę tego tankowania.", "First enter the price for this refuel.")

    val ECONOMY_OPEN: String get() = Lang.pick("Czeka na kolejne tankowanie do pełna.", "Waiting for next fill to full.")
    val ECONOMY_PENDING: String get() = Lang.pick(
        "Brak porównania: tankowanie w tym odcinku czeka na cenę.",
        "No comparison: refuel in this interval is waiting for a price.",
    )
    val ECONOMY_DISMISSED: String get() = Lang.pick(
        "Brak porównania: w tym odcinku jest odrzucone tankowanie.",
        "No comparison: this interval contains a dismissed refuel.",
    )
    val ECONOMY_MISSING_RECEIPT: String get() = Lang.pick(
        "Brak porównania: tankowanie w tym odcinku nie ma litrów z paragonu.",
        "No comparison: refuel in this interval has no liters from receipt.",
    )
    val ECONOMY_NO_TRIPS: String get() = Lang.pick("Brak porównania: brak tras w tym odcinku.", "No comparison: no trips in this interval.")
    val ECONOMY_TOO_SHORT: String get() = Lang.pick("Brak porównania: odcinek krótszy niż 100 km.", "No comparison: interval shorter than 100 km.")

    // Arkusz tras (PRD F6, docs/SPEC.md §14.4).

    val CSV_SECTION: String get() = Lang.pick("Arkusz kalkulacyjny", "Spreadsheet")
    val CSV_EXPLANATION: String get() = Lang.pick(
        "Trasy, tankowania i wydatki w plikach CSV dla Excela i Arkuszy Google – bez położenia GPS i numeru VIN.",
        "Trips, refuels and expenses in CSV files for Excel and Google Sheets – without GPS location and VIN.",
    )
    val CSV_EXPORT: String get() = Lang.pick("Eksportuj trasy do CSV", "Export trips to CSV")
    val CSV_NO_TRIPS: String get() = Lang.pick("Brak tras do eksportu.", "No trips to export.")
    val CSV_EXPORT_REFUELS: String get() = Lang.pick("Eksportuj tankowania do CSV", "Export refuels to CSV")
    val CSV_NO_REFUELS: String get() = Lang.pick("Brak tankowań do eksportu.", "No refuels to export.")
    val CSV_EXPORT_EXPENSES: String get() = Lang.pick("Eksportuj wydatki do CSV", "Export expenses to CSV")
    val CSV_NO_EXPENSES: String get() = Lang.pick("Brak wydatków do eksportu.", "No expenses to export.")

    fun csvExported(trips: Int): String = Lang.pick(
        "Zapisano arkusz tras (trasy: $trips).",
        "Saved trip spreadsheet (trips: $trips).",
    )

    fun csvRefuelsExported(refuels: Int): String = Lang.pick(
        "Zapisano arkusz tankowań (tankowania: $refuels).",
        "Saved refuel spreadsheet (refuels: $refuels).",
    )

    fun csvExpensesExported(expenses: Int): String = Lang.pick(
        "Zapisano arkusz wydatków (wydatki: $expenses).",
        "Saved expense spreadsheet (expenses: $expenses).",
    )

    /** Header cells; the unit lives here, so the cells below stay plain numbers. */
    val CSV_HEADER: List<String> get() = listOf(
        Lang.pick("Start", "Start"),
        Lang.pick("Koniec", "End"),
        Lang.pick("Dystans [km]", "Distance [km]"),
        Lang.pick("Paliwo [l]", "Fuel [l]"),
        Lang.pick("Dystans EV [km]", "EV distance [km]"),
        Lang.pick("Cena [zł/l]", "Price [zł/l]"),
        Lang.pick("Koszt [zł]", "Cost [zł]"),
        Lang.pick("Źródło ceny", "Price source"),
    )

    /** "Źródło ceny" of a trip priced by a confirmed refuel. */
    val CSV_SOURCE_REFUEL: String get() = Lang.pick("tankowanie", "refuel")

    /** "Źródło ceny" of a trip priced with the default price of the phone. */
    val CSV_SOURCE_DEFAULT: String get() = Lang.pick("cena domyślna", "default price")

    /** Header of the refuel sheet (PRD F47, docs/SPEC.md §48); the unit lives here, so cells stay plain numbers. */
    val CSV_REFUELS_HEADER: List<String> get() = listOf(
        Lang.pick("Data", "Date"),
        Lang.pick("Licznik [km]", "Odometer [km]"),
        Lang.pick("Poziom przed [%]", "Level before [%]"),
        Lang.pick("Poziom po [%]", "Level after [%]"),
        Lang.pick("Litry [l]", "Liters [l]"),
        Lang.pick("Cena [zł/l]", "Price [zł/l]"),
        Lang.pick("Koszt [zł]", "Cost [zł]"),
        Lang.pick("Źródło litrów", "Liters source"),
        Lang.pick("Do pełna", "Filled to full"),
    )

    /** Header of the expense sheet (PRD F47, docs/SPEC.md §48). */
    val CSV_EXPENSES_HEADER: List<String> get() = listOf(
        Lang.pick("Data", "Date"),
        Lang.pick("Kategoria", "Category"),
        Lang.pick("Kwota [zł]", "Amount [zł]"),
        Lang.pick("Notatka", "Note"),
    )

    val CSV_YES: String get() = Lang.pick("tak", "yes")
    val CSV_NO: String get() = Lang.pick("nie", "no")

    /** F8 reminder card on the status screen. */
    val BACKUP_REMINDER_NEVER: String get() = Lang.pick(
        "Zrób kopię archiwum – trasy starsze niż rok są już tylko w tym telefonie.",
        "Make an archive backup – trips older than a year are now only on this phone.",
    )

    fun backupReminderOverdue(days: Long): String = Lang.pick(
        "Od ostatniej kopii archiwum ${plPlural(days, "minął", "minęły", "minęło")} $days ${plPlural(days, "dzień", "dni", "dni")} – zrób nową.",
        "It has been $days ${enPlural(days, "day", "days")} since the last archive backup – make a new one.",
    )

    val LEVEL_CHART_TITLE: String get() = Lang.pick("Poziom paliwa w czasie", "Fuel level over time")
    /** Legend under the level chart: a dot, a tick on the time axis and how many refuels the chart marks (docs/SPEC.md §15.2). */
    fun levelChartLegend(refuels: Int): String = Lang.pick(
        "Pomarańczowe kropki i kreski na osi to tankowania w tym zakresie: $refuels.",
        "Orange dots and ticks on the axis are refuels in this range: $refuels.",
    )

    /** What a screen reader says for the tank level chart, which has no text of its own. */
    fun levelChartDescription(lowest: Int, highest: Int, last: Int, refuels: Int): String = Lang.pick(
        "Wykres poziomu paliwa: od $lowest do $highest %, ostatni odczyt $last %, tankowania: $refuels.",
        "Tank level chart: from $lowest to $highest %, last reading $last %, refuels: $refuels.",
    )

    // Other expenses (service, inspection, tyres...), separate from fuel.
    val EXPENSES_TITLE: String get() = Lang.pick("Wydatki", "Expenses")
    val EXPENSES_ADD: String get() = Lang.pick("Dodaj wydatek", "Add expense")
    val EXPENSES_EMPTY: String get() = Lang.pick("Brak wydatków. Dodaj przegląd, opony, serwis – także z datą wstecz.", "No expenses yet. Add an inspection, tyres, service – dates in the past are fine.")
    val EXPENSES_READ_ERROR: String get() = Lang.pick("Nie udało się odczytać wydatków.", "Could not read the expenses.")
    val EXPENSES_FUEL_VS_OTHER: String get() = Lang.pick("Paliwo i reszta", "Fuel and the rest")
    val EXPENSES_FUEL: String get() = Lang.pick("Paliwo (z tras)", "Fuel (from trips)")
    val EXPENSES_OTHER: String get() = Lang.pick("Pozostałe wydatki", "Other expenses")
    val EXPENSES_BY_CATEGORY: String get() = Lang.pick("Według rodzaju", "By category")
    val EXPENSES_BY_MONTH: String get() = Lang.pick("Według miesięcy (z paliwem)", "By month (with fuel)")
    val EXPENSES_HISTORY: String get() = Lang.pick("Historia", "History")
    val EXPENSE_AMOUNT_FIELD: String get() = Lang.pick("Kwota (zł)", "Amount (PLN)")
    val EXPENSE_NOTE_FIELD: String get() = Lang.pick("Opis (opcjonalnie)", "Note (optional)")
    val EXPENSE_DATE: String get() = Lang.pick("Data", "Date")
    val EXPENSE_AMOUNT_INVALID: String get() = Lang.pick("Podaj kwotę, np. 350 lub 1234,50.", "Enter an amount, e.g. 350 or 1234.50.")
    val EXPENSE_SAVE: String get() = Lang.pick("Zapisz", "Save")
    val EXPENSE_CANCEL: String get() = Lang.pick("Anuluj", "Cancel")
    val EXPENSE_DELETE: String get() = Lang.pick("Usuń", "Delete")
    val OPTIONS_TITLE: String get() = Lang.pick("Opcje", "Options")
    val LANGUAGE_TITLE: String get() = Lang.pick("Język / Language", "Language / Język")
    val UPDATE_TITLE: String get() = Lang.pick("Aktualizacja aplikacji", "App update")
    val UPDATE_CHECK: String get() = Lang.pick("Sprawdź aktualizacje", "Check for updates")
    val UPDATE_CHECKING: String get() = Lang.pick("Sprawdzanie…", "Checking…")
    val UPDATE_UP_TO_DATE: String get() = Lang.pick("Masz najnowszą wersję.", "You have the latest version.")
    val UPDATE_INSTALL: String get() = Lang.pick("Pobierz i zainstaluj", "Download and install")
    val UPDATE_INSTALLING: String get() = Lang.pick(
        "Instalacja – potwierdź w oknie systemu. Jeśli okno się nie pokazało, dotknij „Pobierz i zainstaluj” jeszcze raz.",
        "Installing – confirm in the system window. If it did not show up, tap “Download and install” again.",
    )
    val UPDATE_ALLOW: String get() = Lang.pick("Zezwól na instalację", "Allow installing")
    val UPDATE_NEEDS_PERMISSION: String get() = Lang.pick(
        "Android wymaga zgody na instalowanie aplikacji z tej aplikacji. Włącz ją, wróć i dotknij „Pobierz i zainstaluj”.",
        "Android needs permission for this app to install apps. Turn it on, come back and tap “Download and install”.",
    )
    val UPDATE_SOURCE_NOTE: String get() = Lang.pick(
        "Źródło: wydania na GitHubie. Nic nie jest wysyłane poza samym zapytaniem.",
        "Source: releases on GitHub. Nothing is sent beyond the request itself.",
    )

    fun updateCurrent(version: String): String = Lang.pick("Zainstalowana wersja: $version", "Installed version: $version")

    fun updateAvailable(version: String): String = Lang.pick("Dostępna wersja $version.", "Version $version is available.")

    fun updateDownloading(done: Long, total: Long): String {
        val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
        return Lang.pick("Pobieranie… $percent %", "Downloading… $percent%")
    }

    fun updateFailure(failure: UpdateFailure): String = when (failure) {
        UpdateFailure.NETWORK -> Lang.pick("Brak internetu. Spróbuj później.", "No internet connection. Try again later.")
        UpdateFailure.API -> Lang.pick("GitHub nie odpowiada albo ogranicza zapytania. Spróbuj później.", "GitHub is not responding or limits requests. Try again later.")
        UpdateFailure.DATA -> Lang.pick("Nieczytelne wydanie na GitHubie albo uszkodzony plik.", "Unreadable release on GitHub or a damaged file.")
        UpdateFailure.STORAGE -> Lang.pick("Nie udało się zapisać pliku – zwolnij miejsce w telefonie.", "Could not save the file – free up space on the phone.")
        UpdateFailure.NO_REPOSITORY -> Lang.pick(
            "Nie znaleziono repozytorium wydań na GitHubie – aktualizacje z aplikacji nie działają, zajrzyj na stronę projektu.",
            "The release repository was not found on GitHub – in-app updates do not work, check the project page.",
        )
        UpdateFailure.BAD_APK -> Lang.pick(
            "Pobrany plik nie jest tą aplikacją albo jest podpisany innym kluczem – nie został zainstalowany.",
            "The downloaded file is not this app or is signed with another key – it was not installed.",
        )
        UpdateFailure.INSTALL -> Lang.pick("Instalacja się nie udała.", "The installation failed.")
    }

    fun expenseCategoryName(category: ExpenseCategory): String = when (category) {
        ExpenseCategory.SERVICE -> Lang.pick("Serwis", "Service")
        ExpenseCategory.INSPECTION -> Lang.pick("Przegląd", "Inspection")
        ExpenseCategory.TYRES -> Lang.pick("Opony", "Tyres")
        ExpenseCategory.INSURANCE -> Lang.pick("Ubezpieczenie", "Insurance")
        ExpenseCategory.PARTS -> Lang.pick("Części", "Parts")
        ExpenseCategory.WASH -> Lang.pick("Myjnia", "Car wash")
        ExpenseCategory.FEES -> Lang.pick("Parkingi i opłaty", "Parking and fees")
        ExpenseCategory.OTHER -> Lang.pick("Inne", "Other")
    }

    // ---------------- Stan pojazdu, lokalizacja i stan techniczny (PRD F41-F43, docs/SPEC.md §42-§44) ----------------

    /** Toyota refused the endpoint (403/404): retired or not supported for this car — not a retryable error. */
    val REMOTE_UNAVAILABLE: String get() = Lang.pick(
        "Toyota nie udostępnia tych danych dla tego auta.",
        "Toyota does not share this data for this car.",
    )

    /** The call broke; the screen offers a retry. */
    val RETRY: String get() = Lang.pick("Spróbuj ponownie", "Try again")

    /** Toyota answered but without a payload. */
    val REMOTE_EMPTY: String get() = Lang.pick("Toyota nie przysłała tych danych.", "Toyota sent no such data.")

    /** A broken retry kept the last good answer on screen (docs/SPEC.md §47). */
    val REMOTE_STALE: String get() = Lang.pick(
        "Dane poniżej pochodzą z ostatniego udanego odczytu – najnowszego nie udało się pobrać.",
        "The data below is from the last successful read – the newest one could not be fetched.",
    )

    val SHOW_ON_MAP: String get() = Lang.pick("Pokaż na mapie", "Show on map")

    val VEHICLE_STATUS_TITLE: String get() = Lang.pick("Stan pojazdu", "Vehicle status")

    /** What the screen shows and why it can be old: the cloud cache, never a wake-up call. */
    val VEHICLE_STATUS_HINT: String get() = Lang.pick(
        "Ostatni stan zapisany w chmurze Toyoty: drzwi, okna i liczba ostrzeżeń. Aplikacja nie budzi auta (bez refresh_status).",
        "The last state Toyota's cloud has: doors, windows and the warning count. The app does not wake the car (no refresh_status).",
    )
    val VEHICLE_STATUS_EMPTY: String get() = Lang.pick("Toyota nie udostępniła stanu pojazdu.", "Toyota did not share the vehicle status.")
    val VEHICLE_STATUS_READ_ERROR: String get() = Lang.pick("Nie udało się odczytać stanu pojazdu.", "Could not read the vehicle status.")
    val VEHICLE_DOORS_TITLE: String get() = Lang.pick("Drzwi i maska", "Doors and hood")
    val VEHICLE_WINDOWS_TITLE: String get() = Lang.pick("Okna", "Windows")

    fun vehicleWarnings(count: Int): String = Lang.pick(
        "Aktywne ostrzeżenia: $count",
        "Active warnings: $count",
    )

    /** Top of the vehicle status card when every reported door, window and the hood is shut and locked. */
    val VEHICLE_ALL_CLOSED: String get() = Lang.pick("Wszystko zamknięte i zablokowane", "Everything is closed and locked")

    /** Top of the card when [count] parts are open or unlocked; the parts themselves are in bold below. */
    fun vehicleDeviations(count: Int): String = Lang.pick(
        "Do sprawdzenia: $count – otwarte lub odblokowane",
        "To check: $count – open or unlocked",
    )

    fun vehicleUpdated(at: String): String = Lang.pick("Stan z $at", "State from $at")

    /** One component as Toyota reports it: open/closed and/or locked/unlocked; nothing known otherwise. */
    val COMPONENT_UNKNOWN: String get() = Lang.pick("brak danych", "no data")

    fun componentOpen(open: Boolean): String = if (open) Lang.pick("otwarte", "open") else Lang.pick("zamknięte", "closed")

    fun componentLocked(locked: Boolean): String = if (locked) Lang.pick("zablokowane", "locked") else Lang.pick("odblokowane", "unlocked")

    fun doorName(position: pl.lk.toyotacompanion.core.domain.DoorPosition): String = when (position) {
        pl.lk.toyotacompanion.core.domain.DoorPosition.DRIVER -> Lang.pick("Kierowca", "Driver")
        pl.lk.toyotacompanion.core.domain.DoorPosition.PASSENGER -> Lang.pick("Pasażer", "Passenger")
        pl.lk.toyotacompanion.core.domain.DoorPosition.REAR_LEFT -> Lang.pick("Tył lewy", "Rear left")
        pl.lk.toyotacompanion.core.domain.DoorPosition.REAR_RIGHT -> Lang.pick("Tył prawy", "Rear right")
        pl.lk.toyotacompanion.core.domain.DoorPosition.REAR_BACK -> Lang.pick("Bagażnik", "Trunk")
        pl.lk.toyotacompanion.core.domain.DoorPosition.HOOD -> Lang.pick("Maska", "Hood")
    }

    fun windowName(position: pl.lk.toyotacompanion.core.domain.WindowPosition): String = when (position) {
        pl.lk.toyotacompanion.core.domain.WindowPosition.DRIVER -> Lang.pick("Kierowca", "Driver")
        pl.lk.toyotacompanion.core.domain.WindowPosition.PASSENGER -> Lang.pick("Pasażer", "Passenger")
        pl.lk.toyotacompanion.core.domain.WindowPosition.REAR_LEFT -> Lang.pick("Tył lewy", "Rear left")
        pl.lk.toyotacompanion.core.domain.WindowPosition.REAR_RIGHT -> Lang.pick("Tył prawy", "Rear right")
    }

    val LOCATION_TITLE: String get() = Lang.pick("Lokalizacja", "Location")

    /** Memory-only: the position is shown, never stored in the database, the archive or a log (docs/SECURITY.md). */
    val LOCATION_HINT: String get() = Lang.pick(
        "Ostatnia znana pozycja auta z chmury Toyoty, aktualizowana po zaparkowaniu. Trzymana tylko w pamięci – nie trafia do bazy, kopii ani logów.",
        "The car's last known position from Toyota's cloud, updated when it parks. Kept in memory only – never in the database, a backup or a log.",
    )
    val LOCATION_EMPTY: String get() = Lang.pick("Toyota nie udostępniła pozycji auta.", "Toyota did not share the car's position.")
    val LOCATION_READ_ERROR: String get() = Lang.pick("Nie udało się odczytać pozycji auta.", "Could not read the car's position.")
    val LOCATION_COORDS_TITLE: String get() = Lang.pick("Współrzędne", "Coordinates")
    val LOCATION_PLACE_TITLE: String get() = Lang.pick("Miejsce", "Place")

    /** Toyota's `Last Parked` name of the position. */
    val LOCATION_LAST_PARKED: String get() = Lang.pick("Ostatnie parkowanie", "Last parked")

    fun locationCoords(lat: String, lon: String): String = "$lat, $lon"

    fun locationAcquired(at: String): String = Lang.pick("Pozycja z $at", "Position from $at")

    fun locationLastUpdate(at: String): String = Lang.pick("Ostatnia aktualizacja $at", "Last update $at")

    val HEALTH_TITLE: String get() = Lang.pick("Stan techniczny", "Vehicle health")

    /** The payload is untyped in the reference, so the screen shows the count and says what it does not know. */
    val HEALTH_HINT: String get() = Lang.pick(
        "Ostrzeżenia i dane o oleju z chmury Toyoty, bez budzenia auta. Aplikacja pokazuje tylko liczbę ostrzeżeń i to, czy Toyota przysłała dane o oleju – nie zna ich treści.",
        "Warnings and engine oil data from Toyota's cloud, without waking the car. The app shows only the number of warnings and whether Toyota sent oil data – it does not know their content.",
    )
    val HEALTH_EMPTY: String get() = Lang.pick("Toyota nie udostępniła stanu technicznego.", "Toyota did not share the vehicle health.")
    val HEALTH_READ_ERROR: String get() = Lang.pick("Nie udało się odczytać stanu technicznego.", "Could not read the vehicle health.")
    val HEALTH_OIL_YES: String get() = Lang.pick("Toyota przysłała dane o oleju silnika.", "Toyota sent engine oil data.")
    val HEALTH_OIL_NO: String get() = Lang.pick("Toyota nie przysłała danych o oleju silnika.", "Toyota sent no engine oil data.")

    fun healthUpdated(at: String): String = Lang.pick("Ostrzeżenia z $at", "Warnings from $at")

    // ---------------- Historia serwisowa i powiadomienia (PRD F44-F45, docs/SPEC.md §45-§46) ----------------

    val SERVICE_TITLE: String get() = Lang.pick("Historia serwisowa", "Service history")

    /** Read-only cloud cache: the screen says what it shows and that the car is never woken. */
    val SERVICE_HINT: String get() = Lang.pick(
        "Serwisy zapisane przez Toyotę: data, przebieg, kategoria, czynności i dealer. Tylko odczyt z chmury, bez budzenia auta.",
        "Services Toyota recorded: date, mileage, category, operations and dealer. Read-only from the cloud, without waking the car.",
    )
    val SERVICE_EMPTY: String get() = Lang.pick("Toyota nie ma wpisów serwisowych dla tego auta.", "Toyota has no service entries for this car.")
    val SERVICE_READ_ERROR: String get() = Lang.pick("Nie udało się odczytać historii serwisowej.", "Could not read the service history.")

    /** No date in the record: the row says so instead of inventing one. */
    val SERVICE_NO_DATE: String get() = Lang.pick("brak daty", "no date")

    /** Toyota sent no readable operations list (the field is untyped in the reference). */
    val SERVICE_NO_OPERATIONS: String get() = Lang.pick("Toyota nie przysłała opisu czynności.", "Toyota sent no description of the operations.")

    val SERVICE_MILEAGE_LABEL: String get() = Lang.pick("Przebieg", "Mileage")

    /** Toyota's category code keeps its code in the value; the label no longer suggests a code-only row. */
    val SERVICE_CATEGORY_LABEL: String get() = Lang.pick("Kategoria", "Category")

    /** Owner-confirmed meaning of `MNT` (2026-11-10), shown with the code in parentheses; other codes stay as sent. */
    val SERVICE_CATEGORY_MAINTENANCE: String get() = Lang.pick("Przegląd okresowy", "Scheduled maintenance")

    /** Owner-confirmed meaning of `VDE` (2026-11-10), shown with the code in parentheses; other codes stay as sent. */
    val SERVICE_CATEGORY_DELIVERY: String get() = Lang.pick("Wydanie auta", "Vehicle delivery")
    val SERVICE_OPERATIONS_LABEL: String get() = Lang.pick("Czynności", "Operations")
    val SERVICE_NO_OPERATIONS_SHORT: String get() = Lang.pick("brak opisu", "no description")

    /** One label for the dealer: `servicingDealer`, or `serviceProvider` when the dealer is missing (decision 2026-11-09). */
    val SERVICE_DEALER: String get() = "Dealer"

    val NOTIFICATIONS_TITLE: String get() = Lang.pick("Powiadomienia", "Notifications")

    /** The endpoint has no pagination and cannot mark a notification as read, so the screen says that. */
    val NOTIFICATIONS_HINT: String get() = Lang.pick(
        "Powiadomienia Toyoty z chmury, najnowsze pierwsze. Aplikacja ich nie oznacza jako przeczytane – API na to nie pozwala.",
        "Toyota notifications from the cloud, newest first. The app does not mark them as read – the API does not allow it.",
    )
    val NOTIFICATIONS_EMPTY: String get() = Lang.pick("Toyota nie ma powiadomień dla tego auta.", "Toyota has no notifications for this car.")
    val NOTIFICATIONS_READ_ERROR: String get() = Lang.pick("Nie udało się odczytać powiadomień.", "Could not read the notifications.")
    val NOTIFICATIONS_READ: String get() = Lang.pick("przeczytane", "read")
    val NOTIFICATIONS_UNREAD: String get() = Lang.pick("nieprzeczytane", "unread")
    val NOTIFICATIONS_NO_DATE: String get() = Lang.pick("brak daty", "no date")

    /** Mileage of a service record with Toyota's own unit; without a unit just the grouped number. */
    fun serviceMileage(mileage: Int, unit: String?): String {
        val number = TripsFormatting.odometer(mileage.toDouble())
        return if (unit.isNullOrBlank()) number else "$number $unit"
    }

    // ---------------- Diagnostyka synchronizacji (PRD F46, docs/SPEC.md §47) ----------------

    val DIAGNOSTICS_TITLE: String get() = Lang.pick("Diagnostyka synchronizacji", "Sync diagnostics")

    /** Local read only: nothing is fetched from Toyota, so the car is never woken. */
    val DIAGNOSTICS_HINT: String get() = Lang.pick(
        "Odczyt lokalny: czasy synchronizacji, stan archiwum i ostatni błąd. Nic nie jest pobierane z Toyoty, więc auto nie jest budzone. " +
            "Raport nie zawiera VIN, współrzędnych GPS ani tokenów.",
        "Local read: sync times, the archive and the last error. Nothing is fetched from Toyota, so the car is never woken. " +
            "The report carries no VIN, GPS coordinates or tokens.",
    )
    val DIAGNOSTICS_READ_ERROR: String get() = Lang.pick("Nie udało się odczytać diagnostyki.", "Could not read the diagnostics.")
    val DIAGNOSTICS_ATTEMPT: String get() = Lang.pick("Ostatnia próba", "Last attempt")
    val DIAGNOSTICS_SUCCESS: String get() = Lang.pick("Ostatni pełny sukces", "Last full success")
    val DIAGNOSTICS_VEHICLES: String get() = Lang.pick("Pojazdy i konto", "Vehicles and account")
    val DIAGNOSTICS_TELEMETRY: String get() = Lang.pick("Telemetria", "Telemetry")
    val DIAGNOSTICS_TRIPS: String get() = Lang.pick("Trasy", "Trips")
    val DIAGNOSTICS_CAR_STATE: String get() = Lang.pick("Stan auta z Toyoty", "Car state from Toyota")
    val DIAGNOSTICS_ERROR: String get() = Lang.pick("Ostatni błąd", "Last error")
    val DIAGNOSTICS_MESSAGE: String get() = Lang.pick("Komunikat", "Message")
    val DIAGNOSTICS_NONE: String get() = Lang.pick("brak", "none")

    fun diagnosticsArchive(trips: Int, readings: Int, pending: Int): String = Lang.pick(
        "Archiwum: trasy $trips, odczyty $readings, tankowania bez ceny $pending",
        "Archive: trips $trips, readings $readings, refuels without a price $pending",
    )

    val DIAGNOSTICS_VERSION: String get() = Lang.pick("Wersja aplikacji", "App version")
    val DIAGNOSTICS_ARCHIVE_TRIPS: String get() = Lang.pick("Trasy w archiwum", "Trips in the archive")
    val DIAGNOSTICS_ARCHIVE_READINGS: String get() = Lang.pick("Odczyty telemetrii", "Telemetry readings")
    val DIAGNOSTICS_ARCHIVE_PENDING: String get() = Lang.pick("Tankowania bez ceny", "Refuels without a price")

    val DIAGNOSTICS_COPY: String get() = Lang.pick("Kopiuj diagnostykę", "Copy diagnostics")
    val DIAGNOSTICS_COPIED: String get() = Lang.pick("Skopiowano do schowka.", "Copied to the clipboard.")
    val DIAGNOSTICS_SHARE: String get() = Lang.pick("Udostępnij", "Share")
}
