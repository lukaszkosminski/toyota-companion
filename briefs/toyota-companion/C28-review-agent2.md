# C28 – recenzja planu (agent 2, read-only)

Data: 2026-11-09. Recenzowany plik: `briefs/toyota-companion/C28-plan.md`.
Zakres: budżet miesięczny paliwo + wydatki (PRD F48, SPEC §49), karta na statusie, ustawienie telefonu.

Werdykt wstępny: plan jest dobry kierunkowo — jeden łączny limit, dane wyłącznie lokalne, brak sieci,
domena Kotlin + lustro Python, `parseBudgetLimit` reużywa `parseExpenseAmount`. Poniżej ryzyka do
domknięcia przed „gotowe”.

## Blokujące / ważne

1. **Karta poza `if (status != null)`.** W `StatusScreen.kt` `BudgetCard` stoi wewnątrz bloku zależnego od
   statusu Toyoty. Budżet liczy się tylko z archiwum i tabeli wydatków, więc karta i wejście do ustawienia
   nie mogą znikać przy `status == null`. Przenieść poza warunek (np. przed `MenuGrid`) i sprawdzić, że jest
   widoczna także bez statusu.
2. **Auto Backup a prywatność.** `settings.xml` jest w `data_extraction_rules.xml` i `backup_rules.xml`,
   więc limit trafia na Dysk Google właściciela (cloud + device transfer). Zdanie „nic nie wychodzi z
   telefonu” jest nieprawdziwe. SECURITY/SPEC: karta nie wykonuje sieci, ale limit jest w `settings.xml`
   objętym Auto Backup (ADR-015), jak cena domyślna i cele eco; nie jest sekretem. Baza z trasami i
   wydatkami też jest w Auto Backup; ręczny eksport archiwum nie zawiera limitu.
3. **Limit < 0,005 → dzielenie przez zero.** `budgetProgress`/`budget_progress` sprawdza `limit > 0` przed
   `roundMoney`, potem limit może wyjść 0,00 i procent dzieli przez zero. `parseBudgetLimit` tego nie
   wyprodukuje, ale `SettingsStore.monthlyBudget()` może zwrócić śmieci 0.004, a domena jest publiczna.
   Zaokrąglić limit, potem wymagać dodatniego; test `a_limit_that_rounds_to_zero_is_rejected` w obu
   językach. Ujednolić sprawdzanie ujemnych `expenses`/`fuel` przed zaokrągleniem.
4. **Powiadomienie — doprecyzować przyszły krok.** `POST_NOTIFICATIONS` już jest w Manifeście i §13.2; nie
   dodawać drugiej prośby ani nowego uprawnienia. Nowy kanał „Budżet” (DEFAULT), próg (np. 80% i 100%),
   klucz „raz na miesiąc” (wzorzec `lastConsumptionAlert`), trigger (po wydatku/synchronizacji, nie tylko
   przy otwarciu statusu), treść bez VIN/GPS. Jeśli powiadomienie zostaje na później, nie przenosić całej
   pozycji ROADMAP do DONE — rozdzielić postęp i powiadomienie.
5. **ROADMAP LATER→DONE.** Pozycja jest w `## LATER`, nie `NOW`; plan §8 mówi „NOW→DONE”.
6. **Zakres budżetu.** Jeden limit na telefon, nie na auto; paliwo tylko z wybranego auta i jako koszt
   przejechanych tras (`costPln`, `hasNoCost`, start w miesiącu), wydatki jako cash basis (data). Nazwać
   „koszt jazdy” vs „wydatki”, żeby nie sugerować zakupów paliwa w miesiącu. Tylko PLN, bez FX; sumy
   zaokrąglane raz na końcu (0,01 HALF_UP), procent całkowity HALF_UP, `overBudget` po zaokrągleniu,
   dokładnie limit = nie przekroczono.
7. **Odświeżanie.** Dodać `budgetController.refresh()` w `syncRunner` po `SyncOutcome.Ok` (obok
   `ecoGoalsController.refresh()`), inaczej pull-to-refresh nowych tras nie zmieni budżetu na otwartym
   statusie. `ensureLoaded()` przy logowaniu, `reset()` przy wylogowaniu, `refresh()` przy wejściu na
   Status. Północ/zmiana miesiąca przy otwartym ekranie i zmiana strefy — opisać lub odświeżać przy resume.
8. **Błąd vs ładowanie.** `BudgetState` nie ma `failed`/`loading`; `BudgetCard` przy `progress == null`
   pokazuje „Liczby pojawią się po odczytaniu archiwum” także przy trwałym błędzie. Dodać flagę/sealed
   state i osobny komunikat błędu; zachować limit i „Zmień budżet”, bez zmyślonych zer. Rozważyć ostatni
   dobry `progress` + błąd (lekcja C26).
9. **PL/EN.** `BUDGET_FIELD` jest identyczne w PL i EN („Limit [zł]”); sweep `TextsTest` wymaga różnicy.
   Zmienić EN albo świadomie wyłączyć z sweepa; dodać wszystkie nowe teksty i przywracanie `Lang.current`.
10. **Ruff I001.** `backend/app/domain/__init__.py`: `from .budget` po `.cost` — przenieść przed `.cost`.

## Drobne / testy

11. `monthFuelCost`/`monthExpenses` duplikują filtrowanie z `groupTripsByMonth`/`monthTotals`; reużyć albo
    testami pilnować tej samej semantyki (`hasNoCost`, strefa, granica miesiąca).
12. Granica miesiąca: 23:59 ostatniego dnia vs 00:01 pierwszego w `Europe/Warsaw`, DST wiosna/jesień, trip
    z UTC przechodzący na inny miesiąc lokalnie, wydatki pierwszego/ostatniego dnia, `today` wstrzyknięty.
13. `hasNoCost`: `cost == null`, `costPln == null`, `source == NONE` wykluczone; koszt 0,00 wliczony.
14. `parseBudgetLimit`: puste, przecinek/kropka, 2/3 miejsca, zero/ujemne, tekst, NBSP, 7 cyfr,
    `1 234,50`, `1.234,56` odrzucone, `1e3` odrzucone.
15. Domena: procent 0,5→1, 99,5→100, 100,5→101; limit 0,01; dokładnie limit; przekroczenie o 0,01;
    ujemne `remaining`; limit 0,004 odrzucony; ujemne kwoty (także -0,004). Te same nazwy w Pythonie.
16. Kontroler: load, set, delete, invalid, błąd odczytu tras/wydatków (limit zostaje, brak fałszywego 0),
    reset + późny wynik, generation, limit przeżywa reset, `today` injection. SettingsStore: round-trip,
    śmieci, zero/ujemne, usunięcie, zły typ (`ClassCastException` — dodać catch).
17. Docs: SPEC §12.2 (`monthly_budget`), PRD F48, SPEC §49, SECURITY (Auto Backup, brak sieci, brak
    VIN/GPS, przyszłe powiadomienie bez nowego uprawnienia), ROADMAP LATER→DONE + rozdzielenie
    powiadomienia, CHANGELOG Unreleased, ewentualnie README. `C28-plan.md` w commitcie? (C26/C27 tak);
    nie commitować pliku recenzji; bez zmian Gradle.

## Checklista finalnego przeglądu (po „gotowe”)

- Zakres commita: tylko `core/domain`, `app/ui`, `settings`, testy, docs, plan; zero Gradle/`libs.versions`.
- Domena: zaokrąglenia, dokładnie limit, przekroczenie, procent, limit 0,004, ujemne kwoty; lustro Python
  z tymi samymi nazwami testów.
- Miesiąc: strefa ekranu, granica miesiąca/DST, wydatki po dacie, `today` wstrzyknięty.
- Karta: zawsze widoczna (także bez statusu Toyoty), limit/rozbicie/zostało/przekroczono/procent, błąd
  odczytu ≠ 0, dialog (puste usuwa, złe zostawia otwarty), PL/EN.
- Odświeżanie: login, wejście na Status, synchronizacja; reset przy wylogowaniu i późny wynik odrzucony.
- Prywatność: brak VIN/GPS, Auto Backup opisany, brak sieci i `refresh_status()`.
- Powiadomienie: poza zakresem, ale SPEC §49 opisuje kanał, próg, klucz, trigger i reuse §13.2.
- Docs spójne (PRD F48, SPEC §49 + §12.2, SECURITY, ROADMAP, CHANGELOG); ruff/mypy/pytest i Gradle
  autora zielone; recenzent nie uruchamia Gradle.
