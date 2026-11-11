# C35 – plan: podsumowanie okresu w powiadomieniu (PRD F54, SPEC §54)

Status: **projekt do akceptacji**. Data: 2026-11-10. Pozycja 4 z `docs/ROADMAP.md` NEXT, numer F54/§54 proponowany (F53 jest zajęte, §53 też).
Tylko plan: bez kodu, testów, Gradle i gita.

## 1. Cel

Raz na okres (tydzień albo miesiąc, wybór w „Opcjach”) jedno lokalne powiadomienie: km, koszt, l/100 km, udział jazdy na prądzie
i porównanie z poprzednim okresem tej samej długości. Tylko z lokalnego archiwum, bez sieci, bez budzenia auta, bez nowego uprawnienia.

## 2. Ustalenia z kodu

**Istniejące powiadomienia i klucze „raz na okres”:**
- `notify/ConsumptionAlertNotifier.kt:30-46`: po `SyncOutcome.Ok` liczy alert, porównuje `lastAlertKey()` z kluczem (`RisingAlert.key`, `YYYY-MM`,
  `ui/ConsumptionAlert.kt:17-19`), potem `canPost()`, dopiero po `post()` zapisuje klucz. Kanał „consumption”, ID 900.
- `notify/BudgetAlertNotifier.kt` (C29): klucz `RRRR-MM:80|100` w `SettingsStore` (`last_budget_alert`, `settings/SettingsStore.kt:141-149`),
  `Mutex` wokół całego `check()`, klucz czytany wewnątrz blokady, `canPost` i `post` na `Dispatchers.IO`, przełącznik `alertEnabled` osobnym parametrem.
  Kanał „budget”, ID 901.
- `SettingsStore.kt:153-156`: `KEY_ALERT` (`consumption_alert`), jeden tekst na klucz. Nowy klucz `period_summary_last` w tym samym stylu.
- Wszystkie trzy notifiery wołane są z `AppContainer.kt:362-377` (`syncRunner`) po `SyncOutcome.Ok`. Nie ma własnego alarmu ani WorkManagera poza synchronizacją.
- Uprawnienie: `notify/NotificationPermission.kt:27` (`notificationPermissionGranted`), jedna prośba z §13.2 (`notificationsAsked`, `SettingsStore.kt:221-225`).

**Dane okresu:**
- Trasy i koszty: `StoredTrip` z `SyncEngine.trips()` (`tripsLoader`, `AppContainer`), `TripCost.costPln`, `hasNoCost` (`ui/TripsGrouping.kt`).
- Km, litry, l/100 km: `consumptionView` (`ui/ConsumptionTrend.kt`) liczy miesiące; dla tygodnia trzeba osobnej sumy (jak `evByWeek`, `ui/EvWeeks.kt:34`).
- Udział prądu: `aggregateElectricShare` (`core/domain/TripStatsOps.kt`), liczony z `evDistanceKm`.
- Wydatki poza paliwem: `ExpenseDao.all()` i `ui/Expenses.kt:19-25` (`Expense.date`), tak jak budżet (`BudgetAlertNotifier.kt:78`).
- Tygodnie zaczynają się w poniedziałek (`EvWeeks.kt:37`, `TemporalAdjusters`), miesiące w strefie listy tras.

**Przełącznik i wybór w „Opcjach”:** `ui/OptionsScreen.kt:74` (`Switch` przy budżecie, C29) i `FilterChip` w sekcji języka (`OptionsScreen.kt:53`) jako wzorzec wyboru.

## 3. Decyzje proponowane

- **Okres:** tydzień (pon.–nd.) albo miesiąc kalendarzowy, jeden wybór w „Opcjach” (`period_summary_kind`), domyślnie wyłączone.
  Wyłączone = nic nie jest liczone ani zapisywane. (Pytanie: domyślnie wyłączone czy włączone – patrz §9.)
- **Klucz „raz na okres”:** `RRRR-Www` (tydzień ISO, poniedziałek) albo `RRRR-MM` (miesiąc), zapisany w `period_summary_last` **po udanym `post`**
  (lekcja C29). Porównanie z kluczem ostatnio zakończonego okresu. Okres bieżący nigdy nie jest raportowany.
- **Wyzwalacz:** wyłącznie po `SyncOutcome.Ok` w `syncRunner`. Pierwsza synchronizacja po zakończeniu okresu liczy i wysyła raport za **ostatni zakończony** okres.
  Bez własnego alarmu i bez zadania w tle – zgodnie z zasadą aplikacji.
- **Cisza nocna:** powiadomienie tylko między 8:00 a 22:00 czasu lokalnego. Poza tym raport zostaje nieoznaczony i wyjdzie przy następnej synchronizacji
  w porze dziennej (sync co 30 min, więc to nie wymaga alarmu). Pytanie 2: czy tak, czy wystarczy wysyłać od razu.
- **Pierwszy start (baseline):** gdy klucz jest pusty, zapisz ostatnio zakończony okres **bez wysyłania**. Inaczej instalacja albo włączenie
  wysłałoby raport zaległego okresu jako nowość.
- **Zmiana wyboru** (tydzień ↔ miesiąc): ustawia baseline na ostatnio zakończony okres nowego rodzaju, bez wysyłania.
- **Przełącznik wyłączony albo brak `POST_NOTIFICATIONS` albo wyciszony kanał:** nic nie jest oznaczane (jak C29 §2), więc włączenie później wyśle
  raport ostatniego zakończonego okresu, o ile nie został już oznaczony. Zgodnie z dokładnością: najwyżej jeden raport przy włączeniu, nie zaległości.
- **Współbieżność (lekcja C29):** `Mutex` wokół całego `check()`, klucz czytany wewnątrz blokady, `canPost()` i `post()` przez `withContext(IO)`,
  `onChanged`-style wyzwalacza nie ma (tylko sync). Kanał „summary”, `IMPORTANCE_LOW`, ID 902.
- **Treść:** „Tydzień 2–8.11: 412 km · 58,30 zł (paliwo 41,20 zł, wydatki 17,10 zł) · 4,6 l/100 km · EV 38 % · km +12 % wobec poprzedniego tygodnia”.
  Tylko liczby i daty okresu. Bez VIN, GPS, nazw miejsc i danych Toyoty. Liczby przez `TripsFormatting.number`, odmiana „trasa/trasy/tras”.
- **Brak danych w okresie:** okres bez tras i bez wydatków – brak powiadomienia (nic do podsumowania); okres z samymi wydatkami – tylko koszt.
- **Porównanie:** poprzedni okres tej samej długości; bez poprzedniego okresu z danymi – zdanie pominięte, nie „0 %”.

## 4. Pliki (propozycja)

| Warstwa | Plik |
|---|---|
| `core/domain` | `PeriodSummaryOps.kt` (nowy): `periodSummary(trips, costs, from, to, zone)` – km, litry, koszt, l/100 km, EV, porównanie; zero przechowywania |
| `backend/app/domain` | `period_summary.py` (lustro) + `tests/test_domain_period_summary.py` (te same nazwy) |
| `app/ui` | `PeriodSummaryFormatting.kt` (treść), `Texts.kt` (PL/EN) |
| `app/notify` | `PeriodSummaryNotifier.kt` (nowy, wzorzec `BudgetAlertNotifier`) |
| `settings` | `SettingsStore.kt`: `periodSummaryKind()`, `lastPeriodSummary()`/`save…` |
| `app` | `AppContainer.kt`: notifier w `syncRunner` po `budgetAlertNotifier` |
| `app/ui` | `OptionsScreen.kt`, `AppRoot.kt`: przełącznik i wybór okresu |
| docs | PRD F54, SPEC §54, SECURITY (jedno zdanie: treść bez VIN/GPS, klucz w `settings`), CHANGELOG, README, ROADMAP (pozycja 4) |

Bez zmian w Room, Gradle i manifeście (kanał tworzony w kodzie, uprawnienie już jest).

## 5. Testy

- Domena (Kotlin i Python, te same nazwy): tydzień od poniedziałku do niedzieli (granica północy w strefie, 31.12 i 1.01), miesiąc z różną długością,
  km i koszt sumowane, l/100 km jako Σ litrów / Σ km, udział EV z przycięciem do dystansu, poprzedni okres tej samej długości, brak poprzedniego okresu = brak zdania.
- Notifier (Robolectric, wzorzec `BudgetAlertTest`): post raz na okres; ponowna synchronizacja cicho; baseline przy pierwszym starcie bez posta;
  cisza nocna = brak posta i brak klucza; przełącznik wyłączony = brak posta i brak klucza; włączenie później wysyła ostatni zakończony okres;
  wyciszony kanał nie zużywa okresu; wyjątek odczytu nie psuje synchronizacji; dwa równoległe wyzwalacze = jeden post (Mutex).
- Teksty PL/EN, odmiana, sweep zakazanych ciągów (VIN, token), `Lang` przywracany w `@Before`/`@After`.
- Zmiana wyboru okresu ustawia baseline (test).

## 6. Prywatność

Liczby z lokalnego archiwum i wydatków. Brak sieci, brak zapisu poza kluczem i wyborem okresu (w `settings`, objęte Auto Backup jak inne ustawienia).
Treść bez VIN, GPS, nazw miejsc. Brak nowych uprawnień; prośba o powiadomienia jest jedna (§13.2).

## 7. Walidacja (po akceptacji)

`cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
oraz `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

## 8. Kolejność

1. PRD F54 + SPEC §54 + ROADMAP (pozycja 4 do NOW/W toku). 2. `PeriodSummaryOps` + lustro Python z testami.
3. `SettingsStore` i przełącznik w „Opcjach”. 4. `PeriodSummaryNotifier` + testy Robolectric. 5. Podpięcie w `AppContainer` i wybór w `AppRoot`.
6. Teksty PL/EN, CHANGELOG, README, SECURITY.

## 9. Pytania otwarte

1. Domyślnie wyłączone (proponowane) czy włączone po aktualizacji? Włączone zaskoczy raportem za pierwszy okres po aktualizacji – baseline to łagodzi, ale nie wszyscy chcą powiadomień.
2. Cisza nocna 8–22 (proponowane) czy wysyłać od razu po synchronizacji?
3. Kanał `IMPORTANCE_LOW` (bez dźwięku, proponowane) czy `DEFAULT` jak budżet?
4. Okres bez synchronizacji przez kilka tygodni: raportować tylko ostatni zakończony (proponowane) czy każdy zaległy?
5. Koszt w treści: sam paliwo, czy paliwo plus wydatki (proponowane, spójnie z budżetem)?
6. Zmiana wyboru okresu w połowie: baseline bez wysyłania (proponowane) – akceptujesz?
