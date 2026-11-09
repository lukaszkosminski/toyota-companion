# C28 – plan: budżet miesięczny na paliwo i wydatki (PRD F48, SPEC §49)

Status: **zaakceptowany przez polecenie koordynatora 2026-11-09** (pozycja z ROADMAP, propozycje z przeglądu C26).
Data: 2026-11-09.

## 1. Cel

Karta „Budżet miesiąca” na ekranie statusu: jeden opcjonalny limit miesięczny na **paliwo i wydatki poza paliwem**,
postęp liczony wyłącznie z lokalnego archiwum (koszty tras + tabela `expenses`), bez sieci i bez nowych pól Toyoty.

Powiadomienie przy progu jest **poza zakresem tego kroku** (decyzja niżej): istniejące kanały to „Tankowania”
(`IMPORTANCE_HIGH`, konkretnie tankowanie) i „Spalanie”/„Consumption” (`IMPORTANCE_DEFAULT`, konkretnie rosnące
spalanie) – żaden nie pasuje do budżetu. Osobny krok: nowy kanał „Budżet” (`IMPORTANCE_DEFAULT`, teksty PL/EN),
progi **80%** (ostrzeżenie) i **100%** (przekroczenie), klucz `RRRR-MM:80`/`RRRR-MM:100` w `SettingsStore`
(wzorzec `lastConsumptionAlert`), trigger po udanej synchronizacji i po dodaniu/usunięciu wydatku (nie przy samym
wejściu na status), bez nowego uprawnienia (`POST_NOTIFICATIONS` już proszone raz w §13.2), treść tylko kwota/procent.
Opisane w SPEC §49 jako następny krok, nieimplementowane teraz.

## 2. Decyzje jawne (SPEC §49)

- **Jeden budżet łączny**, nie dwa osobne: limit obejmuje paliwo + wydatki; karta pokazuje rozbicie na paliwo i wydatki.
- Limit jest **ustawieniem telefonu** (`SettingsStore`, jak cena domyślna i cele eco): opcjonalny, przeżywa wylogowanie,
  nie jest sekretem (plain text, SECURITY.md).
- **Miesiąc kalendarzowy w strefie lokalnej**; paliwo = suma `costPln` tras z ceną, których start wypada w tym miesiącu
  (`hasNoCost` wyklucza „brak ceny”); wydatki = suma `amount` wpisów z datą w tym miesiącu.
- `spent = fuel + expenses`, `remaining = limit - spent` (może być ujemne), `percentUsed` = cały procent HALF_UP
  (może przekroczyć 100), `overBudget = spent > limit`; kwoty zaokrąglone do 0,01 zł HALF_UP.
- Brak limitu → karta z podpowiedzią i „Ustaw budżet”; brak odczytu tras/wydatków → sama podpowiedź i „Zmień budżet”,
  bez zmyślonych zer.
- Puste pole w dialogu usuwa budżet; niepoprawna kwota (≤ 0, > 2 miejsca, tekst) zostawia dialog otwarty.
- Karta jest zawsze widoczna na statusie (wejście do ustawienia także bez kosztów); po ustawieniu pokazuje postęp.
- Odświeżanie: po zalogowaniu i przy każdym wejściu na ekran statusu (`LaunchedEffect(screen)`), żeby nowe wydatki
  i trasy były widoczne bez restartu; brak sieci i brak `refresh_status()`.

## 3. Domena i lustro w Pythonie

Konwencja projektu: logika domenowa w `core/domain` z testem w Pythonie o tej samej nazwie.

| Warstwa | Plik |
|---|---|
| Kotlin | `core/domain/BudgetOps.kt`: `BudgetProgress` (`limitPln`, `fuelPln`, `expensesPln`, `spentPln`, `remainingPln`, `percentUsed`, `overBudget`), `budgetProgress(fuelCost, expenses, limitPln)` |
| Python | `backend/app/domain/budget.py`: `BudgetProgress`, `budget_progress`; eksport w `__init__.py` |
| Testy | `core/.../BudgetOpsTest.kt` i `backend/tests/test_domain_budget.py` – te same nazwy testów (fuel+expenses, zero, over budget, rounding HALF_UP, zły limit/ujemne wartości, dokładnie limit) |

`budgetProgress` wymaga limitu > 0; ujemne wydatki/paliwo to błąd programisty (formularz przyjmuje tylko dodatnie).

## 4. App

| Plik | Zmiana |
|---|---|
| `settings/SettingsStore.kt` | `monthlyBudget(): BigDecimal?` (dodatnia), `saveMonthlyBudget(value: BigDecimal?)`, klucz `monthly_budget` |
| `ui/Budget.kt` (nowy) | `MonthlyBudget`, `BudgetState`, `BudgetLimitCheck`/`parseBudgetLimit` (puste = usuń, ≤ 0/za dużo miejsc = błąd), `monthFuelCost(trips, zone, month)`, `monthExpenses(expenses, month)` |
| `ui/BudgetController.kt` (nowy) | odczyt limitu + tras + wydatków, `budgetProgress` dla bieżącego miesiąca, `set(text)`, `refresh()`, `reset()` (generation + loadStarted jak `EcoGoalsController`) |
| `ui/BudgetCard.kt` (nowy) | karta „Budżet miesiąca”: `Headline` (wydane / limit), linie paliwo/wydatki/zostało albo przekroczono, procent, „Ustaw/Zmień budżet”; dialog z jednym polem |
| `ui/StatusScreen.kt` | `budget: BudgetState`, `onSetBudget: (String) -> Boolean`, dialog, karta po `EcoGoalsCard` |
| `ui/AppRoot.kt` | kontroler budżetu, `reset()` przy wylogowaniu, `ensureLoaded()` przy logowaniu, `refresh()` przy wejściu na `Screen.Status` |
| `AppContainer.kt` | `budgetController` (`SettingsStore` + `tripsLoader` + `expenseDao().all()`, `Dispatchers.IO`) |
| `MainActivity.kt` | przekazanie kontrolera |
| `ui/Texts.kt` | teksty karty, dialogu, linii i komunikatów PL/EN |

Bez zmian w Gradle i bez nowych zależności.

## 5. Prywatność (SECURITY.md)

Karta pokazuje tylko kwoty i procent z lokalnego archiwum; bez VIN, GPS i danych Toyoty. Limit jest ustawieniem telefonu
w pliku `settings` (jawnym, nie w Keystore), bo nie jest sekretem. Karta nie wykonuje żadnej sieci. **Uwaga:** ten plik
jest objęty Auto Backup / transferem urządzenia (ADR-015), tak jak cena domyślna i cele eco, więc limit trafia na Dysk
Google właściciela; ręczny eksport archiwum i CSV go nie zawierają (settings są poza archiwum).

## 6. Testy

- `core` + Python: `budgetProgress` – paliwo + wydatki, zero, przekroczenie, zaokrąglenia HALF_UP, dokładnie limit,
  odrzucenie limitu ≤ 0 i ujemnych wartości; te same nazwy w obu językach.
- `app`: `parseBudgetLimit` (puste, przecinek/kropka, 2 miejsca, ≤ 0, tekst), `monthFuelCost`/`monthExpenses`
  (miesiąc w strefie, `hasNoCost`, inne miesiące), `BudgetController` (ładowanie, zapis, usunięcie, błąd odczytu,
  `reset`, późny wynik), `SettingsStore` (round-trip, śmieci), teksty PL/EN z `Lang.current` ustawianym i przywracanym
  w `@Before`/`@After` (lekcja C26/C27).

## 7. Walidacja

- Python: `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Android: `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`
- gitleaks na historii (jak CI). Gradle uruchamia tylko autor zmiany.

## 8. Kolejność

1. Domena Kotlin + Python-lustro + testy.
2. `SettingsStore`, `Budget.kt`, `BudgetController` + testy.
3. `BudgetCard`, `StatusScreen`, `AppRoot`, `AppContainer`, `MainActivity`, teksty.
4. Docs: PRD F48, SPEC §49 (+ `monthly_budget` w §12.2), ROADMAP (LATER→DONE dla postępu, powiadomienie zostaje
   w LATER jako osobny krok), SECURITY (Auto Backup), CHANGELOG (Unreleased).
5. Walidacja i atomowy commit.

## 9. Poza zakresem

- Powiadomienie przy progu (osobny krok: nowy kanał, próg, klucz miesiąca; opis w SPEC §49).
- Dwa osobne budżety (paliwo / wydatki) – świadomie jeden łączny.
- Prognoza budżetu, historia limitów, synchronizacja ustawień między urządzeniami.

## 10. Uwagi z przeglądu planu (2026-11-09) i decyzje

- **Karta poza `if (status != null)`** – budżet nie zależy od Toyoty, więc karta i wejście do ustawienia są widoczne
  także przy braku `SyncStatus`; przeniesiona przed `MenuGrid`, po bloku statusu.
- **Auto Backup** – limit w `settings.xml` jest objęty kopią Google/transferem (ADR-015), jak cena domyślna i cele eco;
  SECURITY/SPEC mówią to wprost. Ręczny eksport JSON i CSV limitu nie zawierają.
- **Limit zaokrąglany do zera** – domena zaokrągla limit, potem wymaga > 0 (Kotlin i Python), więc 0,004 jest odrzucane
  przed dzieleniem; testy `a_limit_that_rounds_to_zero_is_rejected` w obu językach. Ujemne kwoty sprawdzane przed
  zaokrągleniem (spójnie w obu), testy z -0,004.
- **Powiadomienie** – zostaje osobnym krokiem: nowy kanał „Budżet” (`IMPORTANCE_DEFAULT`), progi 80%/100%, klucz
  `RRRR-MM:80`/`:100`, trigger po synchronizacji i po zmianie wydatków, bez nowego uprawnienia, treść bez VIN/GPS;
  ROADMAP rozdziela postęp (DONE) i powiadomienie (LATER).
- **Zakres** – jeden limit na telefon (nie na auto); paliwo tylko z wybranego auta (koszt jazdy wg startu w miesiącu),
  wydatki wszystkie (data poniesienia); tylko PLN; sumy raz na końcu, procent HALF_UP, `overBudget` po zaokrągleniu,
  dokładnie limit = nie przekroczono.
- **Odświeżanie** – login, każde wejście na status, udana synchronizacja (`syncRunner`); północ/zmiana miesiąca przy
  otwartym ekranie czeka na następne wejście/synchronizację; zmiana strefy może przenieść trasę między miesiącami.
- **Stan błędu** – `BudgetState` ma `loading`/`failed`; ostatnie dobre liczby tego samego miesiąca zostają z komunikatem,
  pierwszy błąd bez liczb pokazuje komunikat, nie zmyślone zera.
- **Teksty** – `BUDGET_FIELD` EN to „Budget limit [zł]” (nie identyczne z PL), wszystkie nowe teksty w sweepie
  `TextsTest` i teście PL/EN z przywróceniem `Lang.current`.
- **Ruff I001** – import `.budget` przed `.cost` w `backend/app/domain/__init__.py`.
