# C36 – plan: utrata wartości auta w koszcie całkowitym (PRD F55, SPEC §55)

Status: **projekt do akceptacji**. Data: 2026-11-10. Pozycja 9 z `docs/ROADMAP.md` NEXT („Utrata wartości auta w koszcie całkowitym (F38): cena zakupu
i szacowana wartość wpisane ręcznie”), numer F55/§55 proponowany. Tylko plan: bez kodu, testów, Gradle i gita.

## 1. Cel

Pokazać, ile auta kosztuje w utracie wartości, obok wydatków gotówkowych z F38, bez ich mieszania. Właściciel wpisuje cenę zakupu, datę zakupu
i szacowaną wartość na dziś z datą wyceny. Wszystko lokalnie; bez sieci i bez zmiany archiwum.

## 2. Ustalenia z kodu

**Koszt całkowity F38 dziś:**
- `core/domain/OwnershipOps.kt:15-43` (`ownershipCost`): suma = paliwo (0, gdy brak ceny) + wydatki; zł/km i zł/100 km dzielą przez wszystkie km roku;
  `perMonthPln` przez liczbę miesięcy; `fuelSharePct` w całych %. Zwraca `null`, gdy nie ma ani paliwa, ani wydatków.
- `OwnershipOps.kt:45-52` (`ownershipMonths`): od pierwszego miesiąca roku z trasą lub wydatkiem; 0 dla przyszłego roku.
- `ui/YearSummary.kt` (`yearOwnership`, `YearOwnership`, `ownershipLines`): rok = `yearSummary` (paliwo z tras z ceną) + wydatki z datą w roku (`ExpenseDao`).
- Lustro: `backend/app/domain/ownership.py:33` (`ownership_cost`), `:72` (`ownership_months`), testy `test_domain_ownership.py`.
- Prezentacja: `ui/YearSummaryScreen.kt:213-218` (linie kosztu), `Texts.kt:1099-1116` (`ownershipTotal`, `ownershipPerMonth`, `ownershipSplit`,
  `ownershipTop`, `ownershipVersus`), udostępnianie przez `yearSummaryShareText` (`YearSummary.kt`).
- Wydatki: `ui/Expenses.kt:7` (`ExpenseCategory`: serwis, przegląd, opony, …), `Expense` (`:19-25`). Wydatki to gotówka, więc utrata wartości
  NIE może trafić do tej listy ani do `ExpenseCategory`.

**Gdzie trzymać dane:** budżet jest już wzorcem ustawienia telefonu: `SettingsStore.monthlyBudget()` (`settings/SettingsStore.kt:102-122`),
bez Room, objęty Auto Backup (SECURITY, ADR-006). Cena i wartość to też ustawienia telefonu, nie archiwum.

**Ograniczenie:** `OwnershipCost` nie zna utraty wartości (`core/domain/Models.kt:129`). Dodanie pola zmienia model, więc trzeba mirror w Pythonie.

## 3. Model proponowany (najprostszy uczciwy)

Dane: `purchasePln` > 0, `purchaseDate`, `valuePln` ≥ 0, `valueDate` ≥ `purchaseDate`. Strata = `max(0, purchasePln − valuePln)` (wzrost nie jest liczony).

- **Liniowo w dniach:** dzienna strata = strata / liczba dni od `purchaseDate` do `valueDate`. Strata roku = dzienna strata × liczba dni roku należących do
  przedziału `[purchaseDate, valueDate)`. Rok przed zakupem: 0. Rok po dacie wyceny: **0** (nie ekstrapolujemy w przyszłość).
- **Dlaczego liniowo:** właściciel podaje dwie liczby, a nie krzywą. Liniowość jest jawna i łatwa do sprawdzenia ręcznie; degresja (np. 15 % rocznie)
  dałaby więcej straty na początku, ale wymagałaby założenia, którego nie mamy.
- **Zaokrąglenia:** strata roku do 0,01 zł, HALF_UP, z niezaokrąglonej dziennej stawki (jak F38 z niezaokrąglonych wartości).
- **Pokazanie:** w karcie „Koszt całkowity” roku dwa osobne wiersze:
  - „Koszt całkowity (gotówka): 4 210 zł · 1,02 zł/km · 3,10 zł/100 km” – dotychczasowy, bez zmian;
  - „Utrata wartości (szacunek): 3 150 zł · 0,76 zł/km” oraz „Z utratą wartości: 7 360 zł · 1,78 zł/km”.
  Podpis „szacunek” w tekście i w udostępnianiu; wydatki i utrata wartości nie są w tej samej liście kategorii.
- **Rok bez paliwa i wydatków, ale ze stratą:** karta roku ma sens, bo strata jest kosztem. `ownershipCost` dostaje wtedy osobną ścieżkę (nie `null`).
- **Miesięcznie:** „średnio na miesiąc” tylko dla gotówki (jak dotąd). Strata na miesiąc nie jest pokazywana, żeby nie dublować.

## 4. Pliki (propozycja)

| Warstwa | Plik |
|---|---|
| `core/domain` | `ValueLossOps.kt` (nowy): `valueLossInYear(purchase, valuation, year)`; `OwnershipCost` dostaje `valueLossPln`, `totalWithLossPln` (w `Models.kt:129`) |
| `backend/app/domain` | `ownership.py`: `value_loss_in_year(...)`; testy w `test_domain_ownership.py` z tymi samymi nazwami |
| `settings` | `SettingsStore.kt`: `carValuation()`/`saveCarValuation()` – cztery klucze (cena, data zakupu, wartość, data wyceny), walidacja jak budżet |
| `app/ui` | `YearSummary.kt` (`yearOwnership` dostaje wycenę), `YearSummaryScreen.kt` (dwa wiersze + formularz), `Texts.kt` PL/EN |
| `app/ui` | `CarValuationForm.kt` (nowy): formularz z polami kwoty (`parseExpenseAmount`) i datami (jak formularz wydatku) |
| `app/ui` | `OptionsScreen.kt` lub karta „Koszt całkowity”: wejście do formularza |
| docs | PRD F55, SPEC §55, SECURITY (dane wyceny w `settings`, Auto Backup), CHANGELOG, README, ROADMAP (pozycja 9 do DONE po wdrożeniu) |

Bez Room, bez zmian w `ExpenseCategory`, bez Gradle.

## 5. Testy

- `valueLossInYear` (Kotlin i Python, te same nazwy): rok przed zakupem = 0; rok zakupu – tylko dni od zakupu do końca roku; rok z pełnym przedziałem
  = strata; rok po dacie wyceny = 0; przestępny luty w dziennej stawce; wartość ≥ cena = 0 (bez ujemnej straty); data wyceny = data zakupu (przedział pusty).
- `ownershipCost` z utratą: suma i zł/km z i bez straty; rok z samą stratą (bez paliwa i wydatków) nie jest `null`.
- Walidacja formularza: cena > 0, wartość ≥ 0, wartość ≤ cena nie jest wymagana (wzrost), data wyceny ≥ data zakupu, puste pola = brak wyceny.
- Teksty PL/EN, „szacunek” w obu językach, udostępnianie zawiera oba wiersze, sweep zakazanych ciągów; `Lang` w `@Before`/`@After`.
- Brak wyceny = dokładnie dzisiejsze wyniki (regresja F38).

## 6. Prywatność

Cena i wartość auta są danymi właściciela; nie są sekretem (jak budżet), trafiają do Auto Backup, nie do archiwum ani CSV, nie do logów. Nie ma sieci
i nie ma wyceny z internetu – wartość wpisuje właściciel. Karta nie pokazuje VIN.

## 7. Walidacja (po akceptacji)

`cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
oraz `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

## 8. Kolejność

1. PRD F55 + SPEC §55. 2. `ValueLossOps` + lustro Python i testy. 3. `SettingsStore` i formularz. 4. `yearOwnership` i karta roku.
5. Teksty, udostępnianie, CHANGELOG, README, SECURITY, ROADMAP.

## 9. Pytania otwarte

1. Model liniowy (proponowany) czy degresywny (np. stała roczna stopa)? Liniowy wymaga dwóch liczb, degresywny założenia.
2. Po dacie wyceny: strata przestaje rosnąć (proponowane, bez ekstrapolacji), czy ma biec dalej tą samą stawką do dziś, oznaczona jako szacunek?
3. Wiele aut: ustawienia są na telefon, jak budżet. Jeśli właściciel zmieni auto, czy wycena ma być przypisana do VIN, czy wystarczy ręczne przepisanie?
4. Wzrost wartości (wartość > cena): pokazać 0 zł (proponowane) czy wyjątek z dopiskiem?
5. Czy wydatki kapitałowe (np. zakup wyposażenia) mają wchodzić do ceny zakupu, czy zostają w wydatkach jak dziś?
6. Czy strata ma być w udostępnianym tekście roku (proponowane tak, z podpisem „szacunek”) czy tylko na ekranie?
