# C27 – recenzja planu (agent 2, read-only)

Data: 2026-11-09. Recenzowany plik: `briefs/toyota-companion/C27-plan.md`.
Zakres: eksport CSV tankowań i wydatków (PRD F47, SPEC §48), rozszerzenie formatu F6/§14.4.

Werdykt wstępny: plan jest dobry kierunkowo – dwa arkusze w formacie §14.4, bez zmian domeny i Pythona,
`csvRow` współdzielony. Poniżej ryzyka do domknięcia przed „gotowe”.

## Blokujące / ważne

1. **Escapowanie notatek wydatków.** `csvCell` chroni przed formułą tylko po `text[0]`; notatka
   `" =1+1"` lub `"\t=1+1"` może zostać wykonana przez arkusz po obcięciu białego znaku. Notatki z importu
   nie są trimowane. Guard powinien patrzeć na pierwszy nie-biały znak; rozważyć cytowanie komórek z
   wiodącym/końcowym białym znakiem. Testy: `=1+1`, ` =1+1`, `\t=1+1`, `+1`, `-1` (liczba bez `'`), `@x`,
   `;`, `"`, CRLF.
2. **`Litry [l]` – sprzeczność.** „3 miejsca (pełna precyzja domeny)” daje `14,190`, a lista i
   `RefuelFormatting.liters` pokazują `14,19` (bez zer końcowych). Wybrać jedno i opisać w SPEC §48.
3. **`Koszt [zł]`** tylko z `Refuel.totalCost`; puste przy `null`, nawet gdy cena i litry są znane.
   Nie liczyć `litry × cena`. Test: `totalCost = null`, cena i litry niepuste → pusta komórka.
4. **Liczniki przycisków.** `SyncStatus` ma tylko `tripsStored`; `refuelsStored`/`expensesStored` trzeba
   ładować przy `open()`. Osobny guard od `ensureLoaded`, ten sam `generation`; przy błędzie odczytu nie
   pokazywać fałszywego 0 (użyć `null` = nieznane albo ostatniej znanej wartości). Ustalić relację
   `hasTrips` (parametr) vs nowe liczniki (stan).
5. **Pusty eksport.** Osobne komunikaty „Brak tankowań do eksportu.” / „Brak wydatków do eksportu.”,
   nie `CSV_NO_TRIPS`; zdecydować informacja vs błąd; `writeFile` nie jest wołany.
6. **`confirmedRefuels()` vs `refuelLog()`.** Filtr i sort są zdublowane. Rozważyć, by `refuelLog()`
   korzystał z `confirmedRefuels()` (i odwracał kolejność), żeby nie rozjechały się przy nowym statusie.
   Test remisu `ts` po `id`.
7. **Prywatność / docs.** Plan pomija `docs/SECURITY.md`. Dopisać F47: brak VIN/GPS/id, ale daty i
   godziny tankowań, licznik oraz notatki wydatków to dane osobowe (notatka = dowolny tekst użytkownika);
   eksport verbatim, plik tylko tam, gdzie wskaże użytkownik; nazwy plików bez VIN.
8. **SPEC §14.4** zawiera zdanie „dziś tekstami są tylko stałe aplikacji, wymóg dotyczy przyszłych
   kolumn” – po notatkach wydatków jest nieprawdziwe. Zaktualizować, nie tylko dodać §48.
9. **Etykiety źródła litrów.** Istnieją `Texts.LOG_SOURCE_RECEIPT/ESTIMATE/DERIVED`; nie wprowadzać
   trzeciego zestawu tłumaczeń („paragon/szacunek/wyliczone”) bez uzasadnienia.
10. **PL/EN.** `Texts.expenseCategoryName` zależy od globalnego `Lang.current`; testy muszą ustawiać
    i przywracać PL oraz mieć test EN (lekcja D1 z C26).
11. **`BackupController`/`BackupResult`.** Nowe wyniki wymagają gałęzi w `when`; zaktualizować
    `BackupControllerTest.controller()` i `AppContainer`. Testy: liczniki po `open()`, `reset()`,
    spóźniony wynik po `reset()`, błąd liczników ≠ 0, trzy ścieżki eksportu.
12. **`AppContainer` + `ExpenseEntity.toExpense()`.** `toExpense()` jest `internal` w `ui` – potrzebny
    import w root package. Odczyt DAO poza main (kontroler opakowuje w `withContext(io)`). Test nieznanej
    kategorii → `Inne`/`Other`.

## Drobne / testy

13. `csvRow` na `internal` wystarczy; `csvCell`, `CSV_BOM`, `csvDateTime` są publiczne; nie duplikować
    escapowania.
14. BOM doklejany dokładnie raz przez kontroler; tekst funkcji bez BOM. CRLF na każdej linii, także
    ostatniej. Test bajtów `EF BB BF`.
15. Pusta lista w funkcji: ustalić zachowanie (najlepiej sam nagłówek + CRLF); kontroler nie zapisuje.
16. Null-e: `liters`, `pricePerLiter`, `totalCost`, `litersSource`, `filledToFull` → pusta komórka,
    nie `0`/`nie`/`null`. `Poziom przed/po` zawsze liczby.
17. Kolejność i strefa: tankowania rosnąco `(ts, id)`, wydatki `(data, id)`; czas w `zone()`, test
    północy/DST. Data wydatku bez strefy.
18. Nazwy plików: `toyota-companion-tankowania-YYYY-MM-DD.csv`,
    `toyota-companion-wydatki-YYYY-MM-DD.csv`; bez VIN; launcher z `LocalDate.now(zone)`. Treść bez VIN
    i bez `refuel.id`/`expense.id`.
19. Liczby: `TripsFormatting.plain` HALF_UP, przecinek, bez grupowania i `Locale`; cena 3, koszt 2,
    kwota 2. Test zaokrągleń.
20. `BackupScreen`: trzy osobne `CreateDocument("text/csv")` i nazwy; przyciski nieaktywne przy `busy`
    i znanym zerze; `CSV_EXPLANATION` o trzech arkuszach.
21. Docs: PRD F47 (wolne), SPEC §48 (wolne, ostatni §47) + wzmianka w §14.4, ROADMAP LATER→DONE
    (opis na F47/§48), CHANGELOG Unreleased, SECURITY.md. Walidacja: Python bez zmian, ale przejść
    ruff/mypy/pytest; Gradle uruchamia tylko autor zmiany.

## Checklista finalnego przeglądu (po „gotowe”)

- Zakres commita: tylko `core/sync`, `app/ui`, testy, docs; zero Gradle/`libs.versions`, zero plików
  niepowiązanych.
- Format: separator `;`, przecinek dziesiętny, CRLF, BOM raz, cudzysłowy RFC 4180, formuły z `'`.
- Prywatność: brak VIN/GPS/id w treści i nazwach plików; SECURITY.md zaktualizowane.
- Puste komórki przy `null`; `totalCost` autorytatywny; kolejność i strefa zgodne z planem.
- Kontroler: puste listy bez zapisu, osobne komunikaty, liczniki/`generation`, data kopii bez zmian.
- Testy PL/EN z przywróceniem `Lang.current`; BOM/CRLF/escapowanie/null-e/nieznana kategoria.
- Docs spójne (PRD F47, SPEC §48 + §14.4, ROADMAP DONE, CHANGELOG, SECURITY).
- Walidacja autora zielona; recenzent nie uruchamia Gradle.

## Finalny przegląd commita (2026-11-09, read-only)

Commit: `786693b` — `feat(app): CSV export of refuels and expenses (PRD F47, SPEC §48)`,
parent `0903847` (changelog 0.2.5). 20 plików, +1123/−14; brak zmian Gradle/`libs.versions`,
brak plików niepowiązanych; `git diff --check` czysty. `C27-plan.md` w commicie, plik recenzji
pozostawiony untracked.

Sprawdzone:
- **Format:** `;`, przecinek dziesiętny, CRLF na każdej linii, BOM doklejany raz przez kontroler,
  cudzysłowy RFC 4180, liczby bez grupowania i `Locale`; puste komórki przy `null`, nie 0.
- **Escapowanie:** `csvCell` guard po pierwszym nie-białym znaku z `'` na początku, `CSV_NUMBER`
  na oryginale (`-1` liczba, ` -1` tekst), cytowanie edge whitespace/taba, notatki nietrimowane;
  testy `TripsCsvTest` i `ExpensesCsvTest` pokrywają `=`, spację, tab, `+`, `-`, `@`, `;`, `"`, CRLF.
- **Tankowania:** tylko `CONFIRMED` przez `SyncEngine.confirmedRefuels()` (wspólny `confirmedOnly`
  z `refuelLog()`), kolejność `(ts, id)`, strefa ekranu, koszt wyłącznie z `totalCost`, źródło
  z `Texts.LOG_SOURCE_*`, `Do pełna` puste przy `null`.
- **Wydatki:** wszystkie, kolejność `(date, id)`, kategoria PL/EN, kwota 2 miejsca, notatka verbatim
  z escapowaniem; nieznana kategoria → `Inne` (test w `ExpensesControllerTest`).
- **Prywatność:** brak VIN/GPS/`id` w treści i nazwach plików; testy tego pilnują; `SECURITY.md`
  dopisane o notatkach wydatków i datach/liczniku tankowań.
- **Kontroler:** `loadRefuels`/`loadExpenses`, liczniki `Int?` (`null` = nieznane → przycisk aktywny),
  osobny `countsInFlight`, `generation` przy `reset()`, błąd odczytu nie daje fałszywego 0, puste
  arkusze → `BackupResult.Empty` (informacja, bez zapisu), data kopii archiwum bez zmian.
- **UI:** trzy `CreateDocument("text/csv")`, osobne nazwy plików z `LocalDate.now(zone)`, przyciski
  nieaktywne przy znanym zerze/`busy`, `CSV_EXPLANATION` o trzech arkuszach.
- **Docs:** PRD F47, SPEC §48 + §14.4, SECURITY, ROADMAP (LATER→DONE), CHANGELOG pod `## Unreleased`.
- **Testy/PL-EN:** `RefuelsCsvTest` i `ExpensesCsvTest` wymuszają PL w `@Before` i przywracają
  poprzedni język; testy EN nagłówków/etykiet; `TextsTest` obejmuje nowe teksty w sweepie.
- **Walidacja autora (nie powtarzana):** core 439, app 745, Python 179, ruff/mypy OK, `lintDebug`
  bez uwag, `assembleDebug` + `assembleRelease` OK, gitleaks bez wycieków.

## Werdykt

**Można pushować.** Brak blokad. Uwagi nieblokujące:

1. SPEC §48 / CHANGELOG / plan mówią „nie zapisuje pliku (żadnego pustego pliku)”. `CreateDocument`
   może utworzyć pusty plik, zanim aplikacja sprawdzi listę – §14.4 już to opisuje. Warto doprecyzować
   tak samo w §48 (albo uznać za świadome uproszczenie).
2. `refuelsCsv` zakłada listę potwierdzonych tankowań; produkcja przekazuje `confirmedRefuels()`.
   Opcjonalnie filtr `status == CONFIRMED` w funkcji jako defensywa (wtedy test z pending).
3. `SECURITY.md` mówi „verbatim”, a `csvCell` może dodać `'` przy formule; precyzyjniej: „treść
   nietrimowana, escapowanie/guard może dodać `'`”.
4. Commit message: „ROADMAP (the item moves from NOW to DONE)” – faktycznie LATER→DONE; kosmetyka.
5. README nadal wymienia tylko arkusz tras (F6); opcjonalnie dopisać F47.

Push wykonuje koordynator; recenzent nic nie commitował ani nie pushował i nie uruchamiał Gradle.
