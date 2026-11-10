# C29 – plan: powiadomienie o budżecie miesięcznym (PRD F49, SPEC §49)

Status: **zaakceptowany przez polecenie koordynatora 2026-11-09** (domknięcie „kroku następnego” z SPEC §49).
Data: 2026-11-09.

## 1. Cel

Powiadomienie lokalne o budżecie miesięcznym (F48/§49): jedno przy **80%** limitu i jedno przy **przekroczeniu
100%**, raz na miesiąc dla każdego progu. Nowy kanał „Budżet”, bez nowej prośby o uprawnienie, bez sieci i bez
budzenia auta; treść tylko kwota i procent, bez VIN/GPS. Przełącznik w „Opcjach” (domyślnie włączony), zgodnie
z konwencją ustawień telefonu (jak automatyczna pogoda).

## 2. Decyzje jawne

- **Kanał** `budget`, nazwa „Budżet”/„Budget”, `IMPORTANCE_DEFAULT`; tworzony przy pierwszym powiadomieniu,
  wyciszony kanał (`IMPORTANCE_NONE`) znaczy „nie publikuj” i nie zużywa progu.
- **Progi**: 80% (ostrzeżenie) i przekroczenie; próg 80 z `percentUsed` (cały procent HALF_UP z §49, od 79,5%), próg
  100 tylko gdy `overBudget` (wydano > limit). 99,95% i dokładnie limit to nadal ostrzeżenie 80 z treścią „Zostało X zł.”;
  zmiana limitu nie zeruje klucza miesiąca.
- **Klucz**: `RRRR-MM:80` / `RRRR-MM:100` w `SettingsStore` (wzorzec `lastConsumptionAlert`). Powiadamiamy tylko,
  gdy dla tego miesiąca nie powiadomiono jeszcze tego lub wyższego progu: 80 po 100 jest ciszą, 100 po 80 jest
  powiadomieniem; nowy miesiąc zaczyna od nowa. Klucz jest zapisywany **po** udanym `post`; bez uprawnienia,
  wyłączonych powiadomień albo wyłączonego przełącznika nic nie jest oznaczane, więc włączenie później dostarczy
  powiadomienie tego miesiąca.
- **Trigger**: po udanej synchronizacji (`SyncOutcome.Ok`) i po udanej zmianie wydatków (dodanie/usunięcie);
  **nie** przy samym wejściu na status (użytkownik widzi kartę) i nie przy zmianie limitu (świadomie: limit to
  decyzja, nie zdarzenie kosztowe).
- **Uprawnienie**: reuse istniejącego `POST_NOTIFICATIONS` i jednorazowej prośby z §13.2; żadnej nowej prośby,
  żadnego nowego uprawnienia. Odmowa nie psuje karty ani synchronizacji.
- **Treść**: miesiąc, wydane, limit, procent; przy 100% dodatkowo kwota przekroczenia. Bez VIN, GPS, nazw miejsc.
- **Przełącznik**: `budget_alert` w `SettingsStore` (domyślnie `true`), `Switch` w „Opcjach”; przeżywa wylogowanie
  (ustawienie telefonu, jak cena domyślna i cele eco).
- **Zakres**: jedna karta/limit na telefon, jak F48; powiadomienie dotyczy wybranego auta (paliwo z tras) i wszystkich
  wydatków, tak jak karta.

## 3. Pliki

| Warstwa | Plik |
|---|---|
| `app/ui` | `BudgetAlert.kt` (nowy): `budgetAlert(progress, month, lastKey)` → `BudgetAlert?`; stałe 80/100; teksty powiadomień przez `Texts` |
| `app/notify` | `BudgetAlertNotifier.kt` (nowy): odczyt limitu/tras/wydatków, `onSyncOutcome`, `onExpensesChanged`, kanał, `post`, oznaczanie klucza |
| `app/ui` | `Budget.kt`: `BudgetState.alertEnabled`; `BudgetController`: `readAlert`/`saveAlert`, `setAlert`, odczyt w `refresh` |
| `app/ui` | `OptionsScreen.kt`: karta „Powiadomienia o budżecie” z `Switch`; `AppRoot.kt`: przekazanie stanu i settera |
| `app` | `ExpensesController.kt`: `onChanged` po udanym zapisie; `AppContainer.kt`: notifier, trigger w `syncRunner`, `onChanged`, przełącznik |
| `settings` | `SettingsStore.kt`: `budgetAlert()`/`saveBudgetAlert()` + `lastBudgetAlert()`/`saveLastBudgetAlert()` |
| `ui/Texts.kt` | kanał, tytuły, treści 80/100, tekst przełącznika PL/EN |

Bez zmian w Gradle i bez nowych zależności; bez zmian w `core/domain` i Pythonie (logika powiadomienia jest warstwą
`app`, jak `risingConsumptionAlert` z §31).

## 4. Testy

- `ui/BudgetAlertTest.kt`: brak progu < 80; dokładnie 80 → 80; 99 → 80; 100 → 100; > 100 → 100; ten sam miesiąc
  po 80 → cisza dla 80, powiadomienie dla 100; po 100 → cisza dla 80 i 100; nowy miesiąc → znowu; brak limitu → brak;
  klucz ma format `RRRR-MM:80|100`.
- `notify/BudgetAlertTest.kt` (Robolectric, wzorzec `ConsumptionAlertTest`): post z kanałem `budget`, tytuł i treść
  PL, zapis klucza po poście; brak postu bez `canPost` i brak zapisu klucza; wyciszony kanał; ponowna synchronizacja
  ciszą; `onExpensesChanged`; zmiana miesiąca (wstrzyknięte `today` i strefa).
- `BudgetControllerTest`: `alertEnabled` czytany, `setAlert` zapisuje i zmienia stan; błąd odczytu zachowuje stan.
- `SettingsStoreTest`: `budget_alert` round-trip/default i `last_budget_alert` round-trip/śmieci.
- `TextsTest`: nowe teksty w sweepie, PL/EN z `Lang.current` ustawianym i przywracanym (lekcja C26/C27).
- `ExpensesControllerTest`: `onChanged` wołane po udanym dodaniu/usunięciu, nie po błędzie.

## 5. Prywatność

Treść powiadomienia ma tylko kwoty i procent; bez VIN, GPS i danych Toyoty. Kanał i przełącznik są w `settings`
(objęte Auto Backup jak inne ustawienia). Powiadomienie nie wykonuje sieci i nie budzi auta.

## 6. Walidacja

- Python: `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Android: `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`
- gitleaks na historii. Gradle uruchamia tylko autor zmiany.

## 7. Kolejność

1. `BudgetAlert.kt` + testy czystej logiki.
2. `BudgetAlertNotifier` + testy Robolectric.
3. `SettingsStore`, `BudgetController`, `ExpensesController`, `OptionsScreen`, `AppRoot`, `AppContainer`, `Texts`.
4. Docs: SPEC §49 (zamiana „kroku następnego” na stan zrobiony), PRD F49, ROADMAP (LATER→DONE), SECURITY, CHANGELOG.
5. Walidacja, samokontrola diffu pod kątem C26–C28, atomowy commit.

## 8. Poza zakresem

- Nowe uprawnienie albo druga prośba o `POST_NOTIFICATIONS`.
- Powiadomienia o innych progach (np. 50%), dzienne przypomnienia, dźwięk/kanał HIGH.
- Zmiana limitu jako trigger (świadomie nie).

## 9. Samokontrola diffu pod kątem C26–C28

- **C26 B1** (goły VIN): treść powiadomienia to tylko miesiąc, kwoty i procent; test `the_alert_texts_carry_no_vin_or_coordinates`
  sprawdza brak `VIN`, współrzędnych i `GPS`.
- **C26 B2** (HTTP -1): bez zmian, nie dotyczy.
- **C26 W1** (czas telemetrii): bez zmian.
- **C26 W2** („poniżej”): bez zmian; nowe teksty nie opisują układu ekranu.
- **C26 W3** (wyścig `load()`): zastosowany do `BudgetController.refresh()` – token na wywołanie, test
  `a_slower_first_refresh_never_overwrites_a_newer_one` z dwoma `CompletableDeferred`.
- **C26 W4** (KDoc): `BudgetController` opisuje `ensureLoaded`/`refresh` i token.
- **C27**: escapowanie/CSV, liczniki, puste eksporty, SECURITY – bez zmian; nowy kod nie dotyka CSV.
- **C28**: karta poza `if (status != null)` – bez zmian; Auto Backup opisany w SECURITY; limit zaokrąglany przed
  sprawdzeniem > 0 (bez zmian); powiadomienie zaimplementowane wg §49; ROADMAP LATER→DONE; `BudgetState.loading/failed`
  zachowane; `BUDGET_FIELD` EN nadal różne; ruff I001 bez zmian; testy granic miesiąca/DST w `BudgetTest`.
- **Nowe ryzyka sprawdzone**: Lang w testach (`@Before`/`@After` w `BudgetAlertTest`), strefa/miesiąc wstrzykiwane,
  klucz `RRRR-MM:80|100` i semantyka „wyższy próg wygrywa”, brak nowej prośby o uprawnienie, przełącznik w `settings`
  (Auto Backup), brak zapisu klucza bez `post`, wyciszony kanał nie zużywa progu, `onChanged` tylko po udanym zapisie.
