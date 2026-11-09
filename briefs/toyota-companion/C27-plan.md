# C27 – plan: eksport CSV tankowań i wydatków (PRD F47, SPEC §48)

Status: **zaakceptowany przez polecenie koordynatora 2026-11-09** (pozycja z ROADMAP, LATER – propozycje
z przeglądu C26). Data: 2026-11-09.

## 1. Cel

Rozszerzyć istniejący eksport arkusza tras (F6, SPEC §14.4) o dwa arkusze w tym samym formacie:

- **tankowania** – potwierdzone tankowania wybranego auta (dziennik, §15.2): data i godzina, licznik,
  poziom przed/po, litry, cena za litr, koszt, źródło litrów, „do pełna”,
- **wydatki** – wszystkie wydatki poza paliwem (F37, §38): data, kategoria, kwota, notatka.

Oba z sekcji „Arkusz kalkulacyjny” na ekranie „Kopia i eksport”, obok „Eksportuj trasy do CSV”.
Bez VIN, GPS i identyfikatorów; bez zmiany daty ostatniej kopii archiwum (F8 dotyczy tylko kopii).

Poza zakresem: nowe kolumny w bazie, nowe endpointy Toyoty, eksport paragonów/CSV z faktur, zmiany w Gradle.

## 2. Kontekst – co już jest

- `app/ui/TripsCsv.kt`: `tripsCsv(trips, zone)`, `tripsCsvFileName(day)`, wspólne `csvCell` (RFC 4180 +
  ochrona przed CSV injection) i `csvDateTime`; BOM dokleja `BackupController` przy zamianie na bajty.
- `BackupController.exportTripsCsv(target)` czyta trasy przez `loadTrips`, puste → `CSV_NO_TRIPS` bez zapisu;
  przycisk nieaktywny bez tras (`hasTrips` ze `SyncStatus.tripsStored`).
- `SyncEngine.refuelLog()` zwraca **potwierdzone** tankowania (najnowsze pierwsze) + interwały spalania.
- `ExpensesController`/`ExpenseEntity` (Room) – wydatki w `app`, poza `core/domain`; `Expense` i
  `ExpenseCategory` są typami UI (F37), `ExpenseEntity.toExpense()` już istnieje.
- Konwencja: formatowanie arkuszy to warstwa `app` (`TripsCsv`), więc **bez zmian w `core/domain`
  i w Pythonie**. Jedyny dodatek w `core` to odczyt `SyncEngine.confirmedRefuels()` (warstwa sync, bez
  lustra w Pythonie – Python modeluje domenę kosztów/spalania, nie eksport).

## 3. Zmiany

### core (`android/core`)

| Plik | Zmiana |
|---|---|
| `core/sync/SyncEngine.kt` | `confirmedRefuels(): List<Refuel>` – potwierdzone tankowania wybranego VIN, od najstarszego (ts, id), bez interwałów spalania (tańsze niż `refuelLog()` przy eksporcie i liczeniu) |
| `core/test/.../SyncEngineRefuelLogTest.kt` | test: potwierdzone, bez pending, bez innego VIN, kolejność rosnąca |

### app (`android/app`)

| Plik | Zmiana |
|---|---|
| `ui/TripsCsv.kt` | `csvRow` staje się `internal` (współdzielone z nowymi arkuszami); bez zmiany formatu |
| `ui/RefuelsCsv.kt` (nowy) | `refuelsCsv(refuels, zone)`, `refuelsCsvFileName(day)` – ten sam format co §14.4 |
| `ui/ExpensesCsv.kt` (nowy) | `expensesCsv(expenses)`, `expensesCsvFileName(day)` – data bez godziny, kategoria z `Texts`, kwota 2 miejsca, notatka escapowana |
| `ui/Texts.kt` | nagłówki obu arkuszy, etykiety „paragon/szacunek/wyliczone”, „tak/nie”, przyciski, komunikaty pustego eksportu i sukcesu; `CSV_EXPLANATION` mówi o trzech arkuszach |
| `ui/BackupController.kt` | `loadRefuels`, `loadExpenses`; `refuelsStored`/`expensesStored` w stanie, odświeżane przy `open()`; `exportRefuelsCsv`, `exportExpensesCsv`; wyniki `RefuelsCsvExported`, `ExpensesCsvExported`; puste listy → komunikat bez zapisu |
| `ui/BackupScreen.kt` | dwa nowe `CreateDocument("text/csv")` i przyciski (aktywne, gdy liczniki > 0 i nie `busy`) |
| `AppContainer.kt` | `loadRefuels = { syncEngine.confirmedRefuels() }`, `loadExpenses = { database.expenseDao().all().map { it.toExpense() } }` |
| `app/test/.../RefuelsCsvTest.kt`, `ExpensesCsvTest.kt` (nowe) | format, kolejność, puste komórki, escapowanie notatki, ochrona przed formułą, nazwy plików, brak BOM w tekście |
| `app/test/.../BackupControllerTest.kt` | eksport obu arkuszy, liczniki przy `open()`, puste listy bez zapisu, nazwy plików |

### Dokumentacja

`docs/PRD.md` (F47), `docs/SPEC.md` (§48 + wzmianka w §14.4, że sekcja ma trzy arkusze), `docs/ROADMAP.md`
(pozycja z LATER do DONE), `CHANGELOG.md` (Unreleased). Bez zmian w Gradle i bez nowych zależności.

## 4. Format arkuszy

Wspólny z §14.4: separator `;`, przecinek dziesiętny, UTF-8 z BOM (doklejany przy zapisie), CRLF,
cudzysłów gdy komórka zawiera `;`, `"`, CR/LF, tekst zaczynający się od `= + - @` dostaje `'`.

**Tankowania** (`Texts.CSV_REFUELS_HEADER`), od najstarszego (ts, id):

| Kolumna | Wartość |
|---|---|
| `Data` | `RRRR-MM-DD GG:MM` w strefie ekranu |
| `Licznik [km]` | 1 miejsce |
| `Poziom przed [%]` | liczba całkowita |
| `Poziom po [%]` | liczba całkowita |
| `Litry [l]` | 3 miejsca (pełna precyzja domeny), puste gdy brak |
| `Cena [zł/l]` | 3 miejsca, puste gdy brak |
| `Koszt [zł]` | 2 miejsca, puste gdy brak |
| `Źródło litrów` | `paragon` / `szacunek` / `wyliczone`, puste gdy brak |
| `Do pełna` | `tak` / `nie`, puste gdy Toyota/app nie wie |

**Wydatki** (`Texts.CSV_EXPENSES_HEADER`), od najstarszego (data, id):

| Kolumna | Wartość |
|---|---|
| `Data` | `RRRR-MM-DD` |
| `Kategoria` | nazwa z `Texts.expenseCategoryName` (PL/EN wg języka UI) |
| `Kwota [zł]` | 2 miejsca |
| `Notatka` | tekst, escapowany, chroniony przed formułą |

Bez `id`, VIN, GPS i wiersza „Razem” – sumy liczy arkusz.

## 5. Przypadki brzegowe

- Brak tankowań/wydatków: przycisk nieaktywny; lista pusta w chwili zapisu → komunikat i brak zapisu ze strony
  aplikacji (systemowy wybór pliku mógł już utworzyć pusty plik, jak w §14.4).
- Tankowanie pending (bez ceny) nie trafia do arkusza – to dziennik potwierdzonych (§15.2); brakujące
  litry/cena/koszt w potwierdzonym wpisie dają pustą komórkę, nie 0.
- Notatka z `;`, cudzysłowem, nową linią albo zaczynająca się od `=` – escapowana przez `csvCell`.
- Wydatek o nieznanej kategorii z nowszej wersji czyta się jako `Inne` (`ExpenseEntity.toExpense()`).
- Eksport nie zmienia daty ostatniej kopii archiwum i nie wymaga sieci.

## 6. Testy

- `core`: `confirmedRefuels` – filtr statusu/VIN i kolejność.
- `app`: oba arkusze (nagłówek, kolejność, liczby, puste komórki, escapowanie, brak BOM), nazwy plików,
  kontroler (zapis BOM + tekst, puste listy bez zapisu, liczniki po `open()`), teksty PL/EN.

## 7. Walidacja

- Python: `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Android: `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`
- gitleaks na historii (jak CI). Gradle uruchamia tylko autor zmiany.

## 8. Kolejność

1. `core`: `confirmedRefuels` + test.
2. Arkusze w `app/ui` + testy formatu.
3. `BackupController`/`BackupScreen`/`AppContainer` + testy kontrolera.
4. Docs (PRD F47, SPEC §48, ROADMAP, CHANGELOG), walidacja, atomowy commit.

## 9. Uwagi z przeglądu planu (2026-11-09) i decyzje

Recenzent pD zgłosił ryzyka przed implementacją; domknięte w kodzie:

- **Escapowanie notatek** – `csvCell` sprawdza pierwszy **nie-biały** znak i wstawia `'` na samym początku tekstu
  (`" =1+1"` → `"' =1+1"`), `CSV_NUMBER` sprawdza oryginał (więc `-1` zostaje liczbą, a ` -1` jest tekstem i dostaje `'`),
  komórki z wiodącym/końcowym białym znakiem lub tabulatorem są w cudzysłowie; notatki nie są trimowane.
  Trasy mają tylko liczby/daty/stałe, więc ich arkusz się nie zmienił (testy `TripsCsvTest` przechodzą).
- **Litry** – jawne 3 miejsca (`14,190`); dziennik pokazuje `14,19`, różnica to tylko zera końcowe (SPEC §48).
- **Koszt** – tylko zapisany `totalCost`; brak = pusta komórka, nigdy „litry × cena”.
- **Liczniki** – `Int?` (`null` = nieznane → przycisk aktywny), osobny `countsInFlight` tylko na równoległy odczyt,
  odświeżane przy każdym otwarciu (wydatki mogą się zmienić), błąd zostawia ostatnią znaną wartość, `reset()` czyści.
- **Pusty eksport** – nowe arkusze dają informację (`BackupResult.Empty` → `message`), nie czerwony błąd, i nie zapisują
  pliku. Trasy zachowują dotychczasowe `Failed(CSV_NO_TRIPS)` – istniejące zachowanie F6, poza zakresem C27.
- **`confirmedRefuels`** – wspólny filtr `confirmedOnly` z `refuelLog`; sort rosnący dla CSV, malejący dla dziennika
  (remisy po `id`), więc filtr nie może się rozjechać.
- **Prywatność** – SECURITY.md: arkusze bez VIN/GPS/`id`, ale daty/godziny tankowań, licznik i notatki wydatków to dane
  osobowe; plik tylko tam, gdzie wskaże użytkownik, bez ostrzeżenia; nazwy plików bez VIN.
- **Etykiety źródła litrów** – reużyte `Texts.LOG_SOURCE_*` (bez trzeciego zestawu tłumaczeń).
- **Język** – testy arkuszy ustawiają PL w `@Before` i przywracają poprzedni język w `@After`, plus test EN nagłówków
  i etykiet; `TextsTest` obejmuje nowe teksty w sweepie i jawnych asercjach PL/EN.
- **`csvDate`** – KDoc poprawiony (bierze `LocalDate`, nie strefę).
- **SPEC §48** – dopisane, że `Do pełna` jest puste przy `filledToFull == null`, a nie „nie”.

## 10. Domknięcie uwag recenzenta (2026-11-09)

Po finalnym przeglądzie (commit `786693b` wypchnięty) domknięte w jednym commicie `docs/fix`:

1. **Pusty plik** – SPEC §48, CHANGELOG, ROADMAP i plan mówią teraz jak §14.4: aplikacja nic nie zapisuje, ale
   systemowy wybór pliku (`CreateDocument`) mógł już utworzyć pusty plik, zanim lista została sprawdzona.
2. **Defensywny filtr** – `refuelsCsv` sam odrzuca wpisy inne niż `CONFIRMED` (produkcja i tak dostaje listę
   z `SyncEngine.confirmedRefuels()`); test z listą mieszaną.
3. **SECURITY/SPEC „verbatim”** – treść nie jest trimowana, ale escapowanie CSV może dodać `'` przed tekstem
   przypominającym formułę; opis poprawiony w SECURITY i §48.
5. **README** – zamiast samego arkusza tras wymienia trzy arkusze CSV: trasy, tankowania, wydatki (F6, F47).

Punkt 4 (kosmetyka opisu commita `NOW→DONE` vs `LATER→DONE`) pominięty – historia commitów zostaje bez zmian.
