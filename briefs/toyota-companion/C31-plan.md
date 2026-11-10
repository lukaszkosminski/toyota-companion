# C31 – plan: ślad CO₂ z przepalonego paliwa (PRD F51, SPEC §51)

Status: **projekt do akceptacji**. Data: 2026-11-10. Podstawa: `docs/ROADMAP.md:84` (NEXT, pozycja 5), po commicie `84bad7f`; tylko plan, bez kodu, testów, sieci, Gradle i gita.

## 1. Cel

Pokazać szacowany ślad CO₂ z przepalonych litrów: w nagłówku miesiąca na liście tras i w „Podsumowaniu roku” (ekran
i tekst do udostępnienia). Wartość pochodzi z lokalnego archiwum; bez sieci, nowych uprawnień i zmiany schematu Room.

## 2. Decyzje jawne

- **Litry trasy**: `Trip.fuelL` (`android/core/src/main/kotlin/pl/lk/toyotacompanion/core/domain/Models.kt:46`) pochodzi z `fuelConsumption` w ml
  (`android/core/src/main/kotlin/pl/lk/toyotacompanion/core/toyota/ToyotaMapper.kt:48-55`); brak tego odczytu odrzuca trasę już w mapperze. Sumy miesiąca i roku dodają
  `fuelL` bez wyjątku (`ui/TripsGrouping.kt:65`, `ui/YearSummary.kt:61`) i nie mają licznika „bez paliwa”, więc trasa
  z `fuelL = 0` nadal daje km i 0 kg CO₂ (tak samo „rozcieńcza” dzisiejsze l/100 km). Nie przeliczać ponownie: użyć
  `MonthGroup.totalLiters` (`ui/TripsGrouping.kt:27-29`) i `YearSummary.fuelL` (`ui/YearSummary.kt:23`).
- **Współczynnik**: jedna nazwana stała `CO2_KG_PER_LITER_GASOLINE` w nowym `core/domain/Co2Ops.kt`, wartość robocza
  **ok. 2,3 kg CO₂/l, do potwierdzenia przez agenta głównego ze źródłem**; KDoc wskaże zatwierdzone źródło, a liczba
  nie może wystąpić w żadnym innym miejscu app/backend.
- **Czysta funkcja**: `co2Kg(fuelL: BigDecimal): BigDecimal` w `core/domain/Co2Ops.kt`; ujemne litry odrzucone
  (`IllegalArgumentException`), wynik w kg z 1 miejscem po przecinku, HALF_UP. Lustro `co2_kg` w
  `backend/app/domain/co2.py` (wzorzec `BudgetOps.kt` ↔ `budget.py`); testy o tych samych nazwach.
- **Jednostki**: domena zawsze kg. UI: poniżej 1000 kg → „123,4 kg”, od 1000 kg → tony „2,05 t” (2 miejsca, HALF_UP).
  Uzasadnienie: miesiąc jest czytelny w kg, rok w tonach; zaokrąglenie tylko w domenie, prezentacja dzieli przez 1000.
- **Zasięg CO₂**: tylko przepalone litry; km elektryczne nie dodają CO₂, PHEV = CO₂ wyłącznie z paliwa. Bez czynnika
  dla prądu, produkcji auta i paliw innych niż benzyna.
- **Bez przechowywania**: wartość pochodna, liczona przy odczycie; bez zmian Room, `SettingsStore`, manifestu i Gradle.

## 3. Pliki

- **Nowe**: `android/core/src/main/kotlin/pl/lk/toyotacompanion/core/domain/Co2Ops.kt`,
  `android/core/src/test/kotlin/pl/lk/toyotacompanion/core/domain/Co2OpsTest.kt`,
  `backend/app/domain/co2.py`, `backend/tests/test_domain_co2.py`; eksport w `backend/app/domain/__init__.py`.
- `ui/TripsGrouping.kt:27-29,73`: pole `co2Kg` w `MonthGroup`, wyliczone z `liters` przez `co2Kg(...)`.
- `ui/TripsScreen.kt:186-190`: w nagłówku miesiąca dopisać po litrach, przed kosztem:
  `Texts.SEPARATOR + Texts.co2(TripsFormatting.co2(group.co2Kg))`.
- `ui/YearSummary.kt:19-37,79,102-104`: pole `co2Kg` w `YearSummary`, wyliczone z `fuel`; linia w
  `yearSummaryLines` (przed `yearEv`), więc trafia też do `yearSummaryShareText` (`:196-204`).
- `ui/YearSummaryScreen.kt:118-125`: `IconText(AppIcons.Eco, Texts.co2(...))` po koszcie/„brak ceny”, przed dniami.
- `ui/TripsFormatting.kt:76`: `fun co2(kg: BigDecimal): String` (próg 1000 kg → t, HALF_UP).
- `ui/Texts.kt:893`: `fun co2(amount: String)` = „Ślad CO₂: $amount” / „CO₂ footprint: $amount”.
- **Docs**: `docs/PRD.md:120` nowy wiersz F51; `docs/SPEC.md` nowy `## §51` po §49 (`:1184`) z formułą, stałą
  i źródłem, zaokrągleniem, jednostkami, miejscem prezentacji i brakiem CO₂ z prądu; `docs/ROADMAP.md` pozycja 5
  z NEXT (`:84`) do DONE (obok F48/F49); `CHANGELOG.md:3-5` wpis Unreleased; `README.md:5` zakres F1–F51
  (skoordynować z ewentualnym F50). Bez zmian w `core/domain/Models.kt`, Room, ustawieniach i Gradle.

## 4. Testy

- `Co2OpsTest.kt` = `test_domain_co2.py`, te same nazwy: `co2_of_one_litre_is_the_constant`,
  `co2_of_ten_litres_is_ten_times_the_constant`, `co2_rounds_to_one_tenth_of_a_kilogram_half_up`,
  `co2_of_zero_litres_is_zero`, `negative_litres_are_rejected`.
- `TripsGroupingTest`: `co2Kg` miesiąca = suma litrów × stała, z zaokrągleniem; miesiąc bez tras nie istnieje.
- `YearSummaryTest`: `co2Kg` roku z `fuelL`; rok bez tras → brak podsumowania (bez zmian).
- `TripsFormattingTest`: 999,9 kg → „999,9 kg”; 1000,0 kg → „1,00 t”; 1234,5 kg → „1,23 t”; PL/EN bez różnicy jednostek.
- `TextsTest`: nowy tekst w sweepie i para PL/EN („Ślad CO₂” / „CO₂ footprint”) z `Lang.current` przywracanym.

## 5. Prywatność

CO₂ jest wyliczane lokalnie z już zapisanych litrów; bez VIN, GPS, nazw miejsc i danych Toyoty, bez sieci, logów
i nowego przechowywania. Tekst do udostępnienia ma tylko zagregowaną wartość. Brak nowych uprawnień.

## 6. Walidacja

- Python: `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Android: `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`;
  Gradle uruchamia wyłącznie autor zmiany, nie ja (plan).

## 7. Kolejność

1. Potwierdzenie współczynnika i źródła; stała + `co2Kg` + test lustrzany Kotlin/Python.
2. `MonthGroup`/`YearSummary` i formatowanie, potem nagłówek listy i ekran/tekst roku.
3. Teksty PL/EN oraz testy UI.
4. Docs: PRD F51, SPEC §51, ROADMAP NEXT→DONE, CHANGELOG, README.
5. Walidacja i atomowy commit po akceptacji.

## 8. Poza zakresem

- CO₂ z prądu (kWh, mix), z produkcji/recyklingu auta, z ogrzewania i z paliw innych niż benzyna.
- CO₂ na pojedynczej trasie, wykresy trendu, cele/odznaki, powiadomienia i eksport CSV.
- Zmiana sposobu liczenia litrów, schematu Room, ustawień, uprawnień, zależności i Gradle.
- Poprawki F48/F49, C22 i innych pozycji NEXT.
