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
        RefuelChange.NotConfirmed -> AMEND_NOT_CONFIRMED
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

    // Statystyki (PRD F10, F11, docs/SPEC.md §16-§17).

    const val STATS_TITLE = "Statystyki"
    const val STATS_EMPTY = "Brak tras do policzenia statystyk."
    const val STATS_READ_ERROR = "Nie udało się policzyć statystyk. Spróbuj ponownie."
    const val STATS_EV_SECTION = "Jazda na prądzie"
    const val STATS_NO_EV_DATA = "brak danych"
    const val STATS_CLIPPED_MARK = "(dane Toyoty niespójne)"
    const val STATS_RANKING = "Najwięcej jazdy na prądzie (trasy od 1 km)"
    const val STATS_YEAR_OVER_YEAR = "Rok do roku"
    const val STATS_YEARS = "Lata"
    const val STATS_SEASONS = "Pory roku"
    const val SEASON_WINTER = "zima"
    const val SEASON_SPRING = "wiosna"
    const val SEASON_SUMMER = "lato"
    const val SEASON_AUTUMN = "jesień"

    fun statsEvTotal(share: String): String = "$share kilometrów na prądzie"
    fun statsShareOfTrip(share: String): String = "$share na prądzie"
    fun statsTrips(count: Int): String = "trasy: $count"
    fun statsLeftOut(withoutData: Int, tooShort: Int): String =
        "Pominięte – bez danych o prądzie: $withoutData, krótsze niż 1 km: $tooShort."
    fun statsWindow(window: String, current: Int, previous: Int): String = "$window $current vs $previous"

    // Typy tras (PRD F16, docs/SPEC.md §21).

    const val DRIVING_SHORT = "📏 krótki odcinek"
    fun drivingType(cls: TrafficClass, kmh: String): String = when (cls) {
        TrafficClass.SLOW -> "🚦 wolna jazda"
        TrafficClass.CITY -> "🏙️ miasto"
        TrafficClass.FLOWING -> "🚗 płynnie"
        TrafficClass.HIGHWAY -> "🛣️ trasa"
    } + SEPARATOR + "$kmh km/h"

    const val TRAFFIC_SECTION = "Tempo jazdy a spalanie"
    const val TRAFFIC_HINT =
        "Średnia prędkość trasy (od 2 km) jako miara ruchu – wolna jazda to też sygnalizacja i krótkie odcinki, nie tylko korki. Litry liczone do wszystkich km, także tych na prądzie – patrz udział prądu."
    fun trafficClassName(cls: TrafficClass): String = when (cls) {
        TrafficClass.SLOW -> "Wolno (poniżej 15 km/h)"
        TrafficClass.CITY -> "Miasto (15–25 km/h)"
        TrafficClass.FLOWING -> "Płynnie (25–40 km/h)"
        TrafficClass.HIGHWAY -> "Trasa (od 40 km/h)"
    }
    fun trafficConsumption(litres: String): String = "$litres l/100 km"
    fun trafficCost(money: String): String = "$money/100 km"
    fun statsLeftOutTrips(left: Int, total: Int): String = "Poza zestawieniem: $left z $total tras (poniżej 2 km albo bez prędkości)."
    fun trafficSurcharge(litres: String, money: String?): String =
        "Wolna jazda wobec płynnej: $litres l/100 km" + (money?.let { " ($it/100 km)" } ?: "")
    const val TRIP_TYPES_SECTION = "Typy tras"
    const val TRIP_TYPES_HINT = "Dom i praca rozpoznane z postojów; dojazdy to trasy między nimi."
    const val TRIP_TYPES_NO_PLACES = "Za mało postojów, żeby rozpoznać dom i pracę – dojazdów jeszcze nie ma."
    fun tripTypeName(type: TripType): String = when (type) {
        TripType.COMMUTE -> "Dojazdy dom – praca"
        TripType.ERRAND -> "Sprawy i zakupy"
        TripType.WEEKEND -> "Weekend"
        TripType.OTHER -> "Inne"
    }
    const val PLACE_HOME = "Dom"
    const val PLACE_WORK = "Praca"

    // Prognoza tankowania (PRD F14, docs/SPEC.md §20).

    fun forecastWhen(days: Long, date: String): String = when (days) {
        0L -> "Tankowanie dziś"
        1L -> "Tankowanie jutro ($date)"
        else -> "Tankowanie za ok. $days dni (ok. $date)"
    }
    fun forecastBeyond(days: Long): String = "Tankowanie za ponad $days dni"
    fun forecastLeft(liters: String, level: String): String = "w baku ok. $liters ($level)"
    fun forecastRange(km: String): String = "ok. $km do rezerwy"
    const val FORECAST_RESERVE = "Paliwo na rezerwie – zatankuj"

    // Styl jazdy (PRD F12, F13, docs/SPEC.md §19).

    const val STYLE_TITLE = "Styl jazdy"
    const val STYLE_EMPTY = "Brak tras do oceny stylu jazdy."
    const val STYLE_READ_ERROR = "Nie udało się odczytać stylu jazdy. Spróbuj ponownie."
    const val STYLE_ECO_SECTION = "Wynik eco Toyoty po miesiącach"
    const val STYLE_ECO_NONE = "Toyota nie przysłała wyników eco."
    const val STYLE_ROUTES_SECTION = "Ten sam odcinek"
    fun styleRoutesNone(minTrips: Int): String = "Żaden odcinek nie powtórzył się jeszcze $minTrips razy."
    const val STYLE_ANOMALIES_SECTION = "Spalanie wyższe niż zwykle"
    const val STYLE_ANOMALIES_NONE = "Brak przejazdów ze spalaniem wyższym niż zwykle."
    const val STYLE_ANOMALIES_HINT =
        "Aplikacja nie zna przyczyny. Częste powody: zimny silnik i mróz, korki, bagaż lub bagażnik dachowy, za niskie ciśnienie w oponach."

    fun styleEco(score: String, trips: Int): String = "$score pkt · ocenione trasy: $trips"
    fun styleRoute(number: Int): String = "Odcinek $number"
    fun styleRouteLine(km: String, trips: Int, hour: String?): String =
        "ok. $km · przejazdy: $trips" + (hour?.let { " · zwykle ok. $it" } ?: "")
    fun styleRouteFacts(eco: String?, usual: String?): String =
        listOfNotNull(eco?.let { "eco $it pkt" }, usual?.let { "zwykle $it" }).joinToString(" · ")
    fun styleAnomaly(route: Int, actual: String, usual: String, excess: String): String =
        "Odcinek $route: $actual zamiast zwykle $usual ($excess)"

    // Spalanie na ekranie statusu (docs/SPEC.md §24).

    const val CONSUMPTION_TITLE = "Spalanie"
    fun consumptionHint(axisStart: String): String =
        "Słupki: średnie spalanie miesiąca (l/100 km), miesiące od 50 km; oś zaczyna się od $axisStart l/100 km, nie od zera. " +
            "Trend to linia przez słupki; zimą spalanie zwykle rośnie."
    const val CONSUMPTION_COSTS_TITLE = "Koszty miesięczne"
    const val CONSUMPTION_COSTS_HINT =
        "Słupki: koszt paliwa miesiąca w zł (tylko trasy z ceną), od zera. Pod słupkami: miesiące od najstarszego."
    fun consumptionTotalCost(money: String): String = "Razem z całego okresu: $money"
    fun consumptionOverall(litres: String, km: String): String = "Średnio z całego okresu: $litres l/100 km ($km km)"
    fun consumptionRising(change: String): String = "Trend: ↗ rosnące ($change %)"
    fun consumptionFalling(change: String): String = "Trend: ↘ malejące ($change %)"
    fun consumptionSteady(change: String): String = "Trend: → stabilne ($change %)"

    // Jazda na prądzie w tygodniach (docs/SPEC.md §36).

    const val EV_WEEKS_SECTION = "Jazda na prądzie w tygodniach"
    const val EV_WEEKS_HINT =
        "Udział km na prądzie w każdym tygodniu (od poniedziałku), 12 ostatnich. Toyota nie podaje poziomu naładowania baterii hybrydowej ani napięcia akumulatora 12 V – to jedyny obraz baterii z danych tras."
    fun evOnBattery(percent: String): String = "$percent % na prądzie"
    fun evMinutes(minutes: Long): String = "$minutes min na prądzie"

    // Android Auto (docs/SPEC.md §35).

    const val CAR_TITLE = "Toyota Companion"
    const val CAR_CONSUMPTION = "Średnie spalanie"
    const val CAR_TREND = "Trend i cena"
    const val CAR_FORECAST = "Tankowanie"
    const val CAR_EMPTY_TITLE = "Brak danych"
    const val CAR_EMPTY_TEXT = "Otwórz aplikację na telefonie i zsynchronizuj."

    // Mapa ciepła ulubionych tras (docs/SPEC.md §34).

    const val HEAT_BUTTON = "Pokaż przebiegi na mapie"
    const val HEAT_TITLE = "Mapa ciepła trasy"
    fun heatTooltip(litres: String): String = "$litres l/100 km"
    fun heatNote(heat: HeatState): String = when (heat) {
        HeatState.None -> ""
        HeatState.Loading -> "Wczytuję zapisane przebiegi…"
        HeatState.Failed -> "Nie udało się odczytać przebiegów. Spróbuj ponownie."
        is HeatState.Empty -> "Żaden z ${heat.total} przejazdów tej trasy nie ma zapisanego przebiegu. Zapisz je przyciskiem na ekranie „Trasy”."
        is HeatState.Ready ->
            "Zielony – najmniejsze spalanie na tej trasie (${TripsFormatting.plain(heat.minLPer100, 1)} l/100 km), czerwony – największe " +
                "(${TripsFormatting.plain(heat.maxLPer100, 1)}). Przejazdy z przebiegiem: ${heat.lines.size} z ${heat.total}."
    }

    // Zapis przebiegów (docs/SPEC.md §33).

    const val BACKFILL_BUTTON = "Zapisz przebiegi starszych tras"
    const val BACKFILL_STOP = "Zatrzymaj"
    const val BACKFILL_HINT =
        "Toyota usuwa trasy po ok. 12 miesiącach. Przebieg zapisany na telefonie zostaje. Jedno dotknięcie pobiera do 100 przebiegów, od najstarszych."
    fun backfillCounts(stored: Int, total: Int): String = "Przebiegi zapisane na telefonie: $stored z $total tras"
    fun backfillRunning(fetched: Int): String = "Pobieram przebiegi z Toyoty… zapisano $fetched"
    fun backfillResult(result: BackfillResult, fetched: Int): String = when (result) {
        BackfillResult.DONE -> "Zapisano przebiegi ($fetched). Wszystkie trasy mają już przebieg."
        BackfillResult.PARTIAL -> "Zapisano $fetched przebiegów. Dotknij ponownie, żeby pobrać resztę."
        BackfillResult.NOTHING -> "Wszystkie trasy mają już zapisany przebieg."
        BackfillResult.NETWORK -> "Brak połączenia z Toyotą. Zapisano $fetched. Spróbuj później."
        BackfillResult.LOGIN -> "Toyota prosi o ponowne logowanie. Zapisano $fetched."
        BackfillResult.API -> "Toyota nie odpowiedziała poprawnie. Zapisano $fetched. Spróbuj później."
        BackfillResult.STOPPED -> "Zatrzymano. Zapisano $fetched."
    }

    // Widżet (docs/SPEC.md §32).

    const val WIDGET_TITLE = "Toyota Companion"
    const val WIDGET_EMPTY = "Otwórz aplikację"
    fun widgetMain(litres: String): String = "$litres l/100 km"
    fun widgetPrice(price: String): String = "cena $price zł/l"

    // Alert o rosnącym spalaniu (docs/SPEC.md §31).

    const val ALERT_CHANNEL = "Spalanie"
    const val ALERT_TITLE = "Spalanie rośnie trzeci miesiąc z rzędu"
    fun alertRising(values: String, change: String): String =
        "l/100 km: $values ($change %). Poza zimą warto sprawdzić np. ciśnienie w oponach. To tylko podpowiedź, aplikacja nie diagnozuje."

    // Spalanie deklarowane (docs/SPEC.md §30).

    const val DECLARED_SET = "Ustaw spalanie deklarowane"
    const val DECLARED_EDIT = "Zmień deklarowane"
    const val DECLARED_TITLE = "Spalanie deklarowane"
    const val DECLARED_FIELD = "l/100 km (puste usuwa)"
    const val DECLARED_BODY = "Wpisz spalanie mieszane z danych producenta albo z dowodu rejestracyjnego Twojego modelu. Aplikacja go nie zna."
    const val DECLARED_INVALID = "Wpisz liczbę większą od 0 i nie większą niż 20, np. 4,5."
    const val DECLARED_SAVE = "Zapisz"
    const val DECLARED_CANCEL = "Anuluj"
    const val DECLARED_LESS = "taniej"
    const val DECLARED_MORE = "drożej"
    const val DECLARED_SAME = "tak samo"
    fun declaredVersus(declared: String, change: String, word: String): String =
        "Deklarowane: $declared l/100 km · Twoje średnie: $change ($word niż deklarowane)"

    // Podsumowanie roku (docs/SPEC.md §29).

    const val YEAR_TITLE = "Podsumowanie roku"
    const val YEAR_EMPTY = "Brak tras do podsumowania."
    const val YEAR_READ_ERROR = "Nie udało się odczytać tras. Spróbuj ponownie."
    const val YEAR_SHARE = "Udostępnij podsumowanie"
    const val YEAR_NOTE = "Udostępniany jest tylko tekst z tej strony: bez położenia, VIN i danych konta."
    val yearNoCost = "Koszt: brak ceny"
    fun yearHeading(year: Int): String = "Rok $year"
    fun yearShareTitle(year: Int): String = "Toyota Companion – podsumowanie roku $year"
    fun yearTotals(trips: Int, km: String, litres: String): String = "Trasy: $trips · $km km · $litres l paliwa"
    fun yearAverage(litres: String): String = "Średnie spalanie: $litres l/100 km"
    fun yearCost(total: String, per100: String?): String = "Koszt paliwa: $total" + (per100?.let { " ($it/100 km)" } ?: "")
    fun yearEv(percent: String): String = "Jazda na prądzie: $percent % km"
    fun yearDays(days: Int): String = "Dni z jazdą: $days"
    fun yearLongest(km: String, start: String): String = "Najdłuższa trasa: $km km ($start)"
    fun yearBest(month: String, litres: String): String = "Najlepsze spalanie: $month, $litres l/100 km"
    fun yearWorst(month: String, litres: String): String = "Najwyższe spalanie: $month, $litres l/100 km"
    fun yearDearest(month: String, money: String): String = "Najdroższy miesiąc: $month, $money"
    fun yearVersus(year: Int, km: String?, cost: String?, litres: String?): String =
        "Wobec $year: " + listOfNotNull(km?.let { "km $it" }, cost?.let { "koszt $it" }, litres?.let { "spalanie $it l/100 km" })
            .ifEmpty { listOf("brak porównania") }.joinToString(SEPARATOR)

    // Prędkość × temperatura (docs/SPEC.md §28).

    const val MATRIX_TITLE = "Prędkość i temperatura"
    const val MATRIX_HINT =
        "Czy wolna jazda kosztuje więcej zimą? Trasy od 2 km wg tempa (z „Statystyk”) i temperatury dnia: l/100 km i w nawiasie liczba tras. " +
            "Poniżej 5 tras w komórce nie ma wniosku."
    const val MATRIX_EMPTY = "Brak tras z pogodą i prędkością. Pobierz pogodę."
    fun matrixCell(litres: String, trips: Int): String = "$litres l ($trips)"
    fun matrixTooFew(trips: Int): String = "$trips tras – za mało"
    fun matrixBucketShort(bucket: pl.lk.toyotacompanion.core.domain.TemperatureBucket): String = when (bucket) {
        pl.lk.toyotacompanion.core.domain.TemperatureBucket.FROST -> "mróz"
        pl.lk.toyotacompanion.core.domain.TemperatureBucket.COLD -> "chłodno"
        pl.lk.toyotacompanion.core.domain.TemperatureBucket.MILD -> "łagodnie"
        pl.lk.toyotacompanion.core.domain.TemperatureBucket.WARM -> "ciepło"
    }
    fun matrixFrost(signed: String): String = "Zimą przy tej prędkości: $signed l/100 km (mróz wobec łagodnie)"

    // Cena ze stacji (docs/SPEC.md §25).

    const val PRICE_SOURCE = "Źródło ceny"
    const val PRICE_MANUAL = "Ręcznie"
    const val PRICE_FETCHING = "Sprawdzam cenę na trojmiasto.pl…"
    const val PRICE_AGE_TODAY = "wpisana dziś"
    const val PRICE_AGE_YESTERDAY = "wpisana wczoraj"
    const val PRICE_AGE_OLDER = "wpisana dawniej niż wczoraj"
    fun priceFound(station: String, price: String, age: String, stale: Boolean): String =
        "$station: $price zł/l, $age (ceny wpisują użytkownicy portalu i stacje)." +
            (if (stale) " Cena może być nieaktualna – sprawdź." else "")
    fun priceStationMissing(station: String): String = "$station: nie ma jej teraz na liście cen. Wpisz cenę ręcznie."
    fun priceNone(station: String): String = "$station: nikt nie wpisał ceny 95. Wpisz cenę ręcznie."
    fun priceFetchError(error: PriceFetchResult): String = when (error) {
        PriceFetchResult.NETWORK -> "Brak połączenia z trojmiasto.pl. Wpisz cenę ręcznie."
        PriceFetchResult.API -> "trojmiasto.pl nie odpowiada poprawnie. Wpisz cenę ręcznie."
        PriceFetchResult.DATA -> "Nie udało się odczytać cen z trojmiasto.pl (strona mogła się zmienić). Wpisz cenę ręcznie."
    }

    // Mapa w aplikacji (docs/SPEC.md §26).

    const val MAP_TITLE_TRIP = "Mapa trasy"
    const val MAP_TITLE_PLACE = "Mapa miejsca"
    const val MAP_START = "Start"
    const val MAP_END = "Koniec"
    const val MAP_PLACE = "Miejsce"
    const val MAP_NOTE =
        "Mapa OpenStreetMap. Serwer map widzi okolicę, którą oglądasz (kafelki), nie dokładne punkty."
    fun mapRouteNote(route: RouteState): String = when (route) {
        RouteState.None -> "Miejsce zaokrąglone do ok. 100 m."
        RouteState.Loading -> "Pobieram przebieg trasy z Toyoty…"
        is RouteState.Ready -> "Przebieg trasy z GPS Toyoty: zielony – jazda na prądzie, niebieski – silnik spalinowy."
        RouteState.Missing -> "Toyota nie ma przebiegu tej trasy (przechowuje ok. 12 miesięcy). Pokazuję początek i koniec, prosta linia."
        is RouteState.Failed -> when (route.reason) {
            RouteFailure.NETWORK -> "Brak połączenia z Toyotą – pokazuję początek i koniec (prosta linia). Otwórz mapę ponownie później."
            RouteFailure.LOGIN -> "Toyota prosi o ponowne logowanie – pokazuję początek i koniec (prosta linia)."
            RouteFailure.API -> "Toyota nie podała przebiegu tej trasy – pokazuję początek i koniec (prosta linia)."
        }
    }
    const val MAP_OPEN_EXTERNAL = "Otwórz w innej aplikacji z mapą"

    // Pogoda i spalanie (PRD F15, docs/SPEC.md §23).

    const val WEATHER_TITLE = "Pogoda i spalanie"
    const val WEATHER_READ_ERROR = "Nie udało się odczytać pogody. Spróbuj ponownie."
    const val WEATHER_HINT =
        "Trasy podzielone wg pogody dnia i miejsca startu (dane z archiwum reanaliz Open-Meteo, to model, nie termometr). " +
            "Pobranie wysyła do usługi tylko punkt startu zaokrąglony do ok. 1 km i daty – nigdy VIN ani dokładne położenie. Gdy automatyczne pobieranie jest włączone, dzieje się to po każdej synchronizacji i przy otwarciu tego ekranu."
    const val WEATHER_FETCH = "Pobierz pogodę"
    const val WEATHER_AUTO = "Pobieraj pogodę automatycznie (po synchronizacji i przy otwarciu ekranu)"
    const val WEATHER_FETCHING = "Pobieranie…"
    const val WEATHER_BY_TEMPERATURE = "Wg temperatury"
    const val WEATHER_BY_RAIN = "Wg opadów"
    const val WEATHER_NO_DATA = "Brak tras z pogodą. Pobierz pogodę."
    const val WEATHER_TOO_FEW = "za mało tras na wniosek"
    const val WEATHER_CAVEAT =
        "To korelacja na Twoich trasach, nie pomiar przyczyny: zimą trasy są inne, a mróz skraca też jazdę na prądzie " +
            "(patrz udział jazdy elektrycznej)."
    const val WEATHER_ATTRIBUTION = "Weather data by Open-Meteo.com (CC BY 4.0), https://open-meteo.com/"
    fun weatherBucketName(bucket: String): String = when (bucket) {
        "frost" -> "Mróz (poniżej 0 °C)"
        "cold" -> "Chłodno (0–10 °C)"
        "mild" -> "Łagodnie (10–20 °C)"
        "warm" -> "Ciepło (od 20 °C)"
        "dry" -> "Sucho (poniżej 0,5 mm)"
        "wet" -> "Mokro (od 0,5 mm)"
        else -> bucket
    }
    fun weatherDelta(signed: String): String = "$signed l/100 km wobec odniesienia"
    fun weatherEv(share: String): String = "na prądzie $share"
    fun weatherWind(kmh: String): String = "wiatr do $kmh km/h"
    fun weatherWithout(count: Int, total: Int): String = "Bez pogody w tym podziale: $count z $total tras (brak położenia lub danych dla dnia)."
    fun weatherPending(requests: Int, left: Int, cachedDays: Int): String = when {
        requests == 0 -> "Nic do pobrania. Dni w pamięci: $cachedDays."
        left > 0 -> "Do pobrania: zapytania $requests (jedno dotknięcie robi ${requests - left}). Dni w pamięci: $cachedDays."
        else -> "Do pobrania: zapytania $requests. Dni w pamięci: $cachedDays."
    }
    fun weatherFetchResult(result: WeatherFetchResult): String = when (result) {
        WeatherFetchResult.DONE -> "Pobrano pogodę."
        WeatherFetchResult.PARTIAL -> "Pobrano część. Dotknij ponownie, żeby pobrać resztę."
        WeatherFetchResult.NOTHING_TO_FETCH -> "Nie ma czego pobierać."
        WeatherFetchResult.NETWORK -> "Brak połączenia z usługą pogody. Spróbuj później."
        WeatherFetchResult.RATE_LIMIT -> "Usługa pogody prosi o chwilę przerwy. Spróbuj za minutę."
        WeatherFetchResult.API -> "Usługa pogody odrzuciła zapytanie. Spróbuj później."
        WeatherFetchResult.DATA -> "Usługa pogody zwróciła nieczytelną odpowiedź."
        WeatherFetchResult.STORAGE -> "Nie udało się zapisać pogody w telefonie."
    }

    // Postoje (PRD F17, docs/SPEC.md §18).

    const val PARKING_TITLE = "Postoje"
    const val PARKING_EMPTY = "Brak postojów do pokazania."
    const val PARKING_READ_ERROR = "Nie udało się odczytać postojów. Spróbuj ponownie."
    const val PARKING_PRIVACY =
        "Położenie zaokrąglone do ok. 100 m – liczone z tras w archiwum, bez pytania auta. Poza aplikację trafia tylko po dotknięciu „Otwórz w mapie” albo wiersza trasy na liście tras."
    const val PARKING_PLACES = "Miejsca"
    const val PARKING_RECENT = "Ostatnie postoje"
    const val TRIP_MAP_HINT = "Trasa zapisuje tylko początek i koniec. Otworzy się aplikacja z mapą, położenie zaokrąglone do ok. 100 m."
    const val TRIP_MAP_CANCEL = "Anuluj"
    const val TRIP_MAP_START = "Start w mapie"
    const val TRIP_MAP_END = "Koniec w mapie"
    const val PARKING_OPEN_MAP = "Otwórz w mapie"
    const val PARKING_NO_MAP_APP = "Brak aplikacji z mapą."
    const val PARKING_NO_POSITION = "bez położenia"

    fun parkingStops(count: Int): String = "postoje: $count"
    fun parkingTotal(duration: String): String = "razem $duration"
    fun parkingLast(time: String): String = "ostatnio $time"
    fun parkingSkipped(unmatched: Int, longGaps: Int): String =
        "Pominięte – koniec i start w różnych miejscach (brak trasy w archiwum): $unmatched, przerwy dłuższe niż 21 dni: $longGaps."

    // Dziennik tankowań (PRD F9, docs/SPEC.md §15).

    /** Status button and the title of the screen. */
    const val LOG_TITLE = "Dziennik tankowań"

    const val LOG_EMPTY = "Brak potwierdzonych tankowań."
    const val LOG_HINT =
        "Żeby porównać spalanie z komputerem auta, przy tankowaniu zaznacz „Do pełna” i podaj litry z paragonu."
    const val LOG_READ_ERROR = "Nie udało się odczytać dziennika. Spróbuj ponownie."

    /** Where the liters of a refuel come from (docs/SPEC.md §2). */
    const val LOG_SOURCE_RECEIPT = "z paragonu"
    const val LOG_SOURCE_DERIVED = "wyliczone z paragonu"
    const val LOG_SOURCE_ESTIMATE = "szacunek z czujnika"

    /** Header of the open interval at the top of the log. */
    const val LOG_CURRENT = "Bieżący odcinek"

    fun economyCompared(actual: String, computer: String, difference: String): String =
        "Spalanie: faktyczne $actual · komputer $computer ($difference)"

    fun economyTrips(distance: String): String = "$distance tras"
    fun economyOdometer(distance: String): String = "licznik $distance"
    fun economyEv(distance: String): String = "w tym EV $distance"
    fun economyComputerSoFar(consumption: String): String = "komputer $consumption"

    // Poprawka potwierdzonego tankowania (docs/SPEC.md §15.3).
    const val AMEND_BUTTON = "Popraw litry"
    const val AMEND_TITLE = "Litry z paragonu"
    const val AMEND_BODY = "Wpisz litry z paragonu. Kwota jest opcjonalna – z nią cena za litr policzy się z paragonu."
    const val AMEND_FIELD_LITERS = "Litry z paragonu"
    const val AMEND_FIELD_TOTAL = "Kwota z paragonu (opcjonalnie)"
    const val AMEND_SAVE = "Zapisz"
    const val AMEND_CANCEL = "Anuluj"
    const val AMEND_LITERS_REQUIRED = "Podaj litry z paragonu."
    const val AMEND_NOT_CONFIRMED = "Najpierw podaj cenę tego tankowania."

    const val ECONOMY_OPEN = "Czeka na kolejne tankowanie do pełna."
    const val ECONOMY_PENDING = "Brak porównania: tankowanie w tym odcinku czeka na cenę."
    const val ECONOMY_DISMISSED = "Brak porównania: w tym odcinku jest odrzucone tankowanie."
    const val ECONOMY_MISSING_RECEIPT = "Brak porównania: tankowanie w tym odcinku nie ma litrów z paragonu."
    const val ECONOMY_NO_TRIPS = "Brak porównania: brak tras w tym odcinku."
    const val ECONOMY_TOO_SHORT = "Brak porównania: odcinek krótszy niż 100 km."

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
