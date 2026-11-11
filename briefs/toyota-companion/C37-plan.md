# C37 – plan: opony – dwa komplety, przebieg i przypomnienie o wymianie (PRD F56, SPEC §56)

Status: **projekt do akceptacji**. Data: 2026-11-11. Pozycja 10 z `docs/ROADMAP.md` NEXT (opony: dwa komplety, przebieg każdego z tras między
wymianami, przypomnienie o wymianie wg temperatury, F15). Numer F56/§56 proponowany. Tylko plan: bez kodu, testów, Gradle i gita.

## 1. Cel

Właściciel ma dwa komplety (letni i zimowy). Wpisuje ręcznie, kiedy komplet został założony. Aplikacja pokazuje, ile km przejechał każdy komplet
(z archiwum tras między datami zmian) i kiedy przypomnieć o wymianie. Wszystko lokalnie, bez sieci, bez budzenia auta.

## 2. Ustalenia z kodu

**Dane pogodowe lokalnie (F15):**
- `core/weather/OpenMeteoClient.kt:22-32` (`WeatherDay`): data, średnia/min/maks temperatura, opad (mm i godziny), wiatr maks., kod pogody.
- `data/db/Entities.kt:246-262` (`WeatherEntity`, tabela `weather`): klucz `(lat_r, lon_r, date)`, współrzędne zaokrąglone do 0,01°, `fetched_at`.
- `ui/WeatherController.kt:125` (`daysOfTrips`) i `:267` (`cachedDays`): pogoda jest pobierana **tylko dla dni, w których są trasy z punktem startu**.
  Wniosek: w cache nie ma ciągłego szeregu dni. Dzień bez jazdy nie ma temperatury, więc „N kolejnych dni poniżej 7 °C” nie da się uczciwie wyznaczyć.
- Brak prognozy i brak danych z dni wolnych od jazdy. Każde uzupełnienie wymagałoby nowego pobrania z sieci, czego ten plan nie dopuszcza.
- Dodatkowo średnia z dni z jazdą to obciążona próba (jeżdżę zwykle w cieplejsze popołudnia). Nie nadaje się do progu.

**Wniosek:** przypomnienie „wg temperatur” w tej wersji **nie jest uczciwe**. Proponuję wariant bez sieci (§3) i osobno opisany wariant temperaturowy
na przyszłość, po zmianie źródła danych (np. jawne pobranie 2 razy w roku, F15 ma już klienta).

**Wydatki (F37) i tryb przechowywania:**
- `ui/Expenses.kt:7-10`: `ExpenseCategory` ma już `TYRES`. Wydatek na opony da się zapisać dzisiaj.
- `data/db/Entities.kt:300-307` (`ExpenseEntity`, tabela `expenses`, indeks po dacie), `app/.../data/db/AppDatabase.kt:25` (wersja 7), `:27` (`AutoMigration` 1→7).
- Ustawienia telefonu: `settings/SettingsStore.kt` (budżet `:102-122`, lista powiadomionych tankowań `:177-196`). Lista małych wpisów w jednym kluczu to wzorzec już użyty.

**Trasy i przebieg:**
- Trasy: `Trip.start` (`core/domain/Models.kt:41`), dystans `distanceKm`, `StoredTrip` z `SyncEngine.trips()`. Przebieg komplet = suma dystansów tras
  z datą startu w przedziale `[data założenia, data następnej zmiany)`.
- Przebieg z licznika: `SyncStatus.odometerKm` (status, telemetria), używany już na statusie. Pozwala ustalić przebieg auta w dniu zmiany, jeśli jest zapis.
- Trasy sprzed pierwszego wpisu: nie należą do żadnego kompletu (nieznany komplet), pokazywane jako „przed pierwszym wpisem: N km”.

**Kopia i eksport:**
- JSON archiwum (F7, SPEC §14.1) zawiera pojazdy, trasy, tankowania, odczyty i wydatki (opcjonalne pole `expenses`). Ustawienia telefonu (budżet, cena, cele) są poza JSON.
- CSV: `ExpensesCsv.kt` eksportuje wydatki, bez zmian formatu.
- Auto Backup Google obejmuje `settings` i bazę (SECURITY, ADR-015).

**Ekran i kafelki:** menu statusu ma 15 kafelków (`ui/StatusScreen.kt`, `MenuGrid`). Właściciel chce kafelki na dole statusu, a siatka jest pełna.
Ekran „Wydatki” (`ui/ExpensesScreen.kt`) już jest tematycznie blisko (kategoria opon).

**Powiadomienia (lekcje C29/C35):** `notify/BudgetAlertNotifier.kt` i `PeriodSummaryNotifier.kt`: Mutex, klucz po udanym `post`, I/O poza UI,
wyciszony kanał nie zużywa przypomnienia, cisza 8–22.

## 3. Dwa warianty przypomnienia – rozdzielone

**A. Przypomnienie kalendarzowe i przebiegowe (proponowane, bez sieci):**
- Właściciel ustawia dla każdego kompletu opcjonalny **próg miesięczny** (np. 6 miesięcy od założenia) i/lub **próg przebiegu** (np. 15 000 km od założenia).
- Przypomnienie, gdy minie próg. Klucz `komplet:RRRR-MM-DD:data-założenia` po udanym `post`, raz na komplet i próg.
- Liczy się z istniejących danych (data zmiany, trasy, licznik), bez zgadywania temperatur.
- Uczciwe: mówi „minęło 6 miesięcy od założenia letniego”, a nie „jest już ciepło”.

**B. Przypomnienie temperaturowe (odłożone):** „średnia dobowa poniżej 7 °C przez 5 kolejnych dni” wymaga ciągłego szeregu temperatur.
Dziś nie istnieje lokalnie (§2). Wymagałoby osobnego pobierania dziennego szeregu z Open-Meteo, osobnej zgody właściciela i decyzji o sieci.
Zalecenie: **nie w tej wersji**; wpisać jako osobną pozycję LATER z warunkiem.

## 4. Model danych (propozycja: SettingsStore, nie Room)

Porównanie:
- **Room (nowa tabela, wersja 8, `AutoMigration(7→8)`, test migracji wzorem v6→v7):** pełna historia, zapytania po dacie, wchodzi do JSON jako nowa sekcja.
  Koszt: nowy schemat, kolejna migracja, nowy test, wpływ na JSON (nowa wersja formatu albo opcjonalne pole, jak `expenses`).
- **SettingsStore (proponowane):** kilka wpisów rocznie. Jeden klucz `tyre_changes` z listą `RRRR-MM-DD:S|W:przebieg_lub_pusty` i prostym parserem
  (wzorzec `refuel_notified_ids`). Brak migracji, objęte Auto Backup, jak budżet i cena domyślna. Nie wchodzą do JSON archiwum (jak inne ustawienia).
  Koszt: nie są w eksporcie archiwum; przy dużej liczbie wpisów trzeba by przenieść do Room.

**Rekomendacja:** SettingsStore. Dane są małe, ręczne, a Room w wersji 8 dla kilku dat rocznie to nadmiar. Jeśli właściciel chce mieć wymiany w eksporcie
JSON, to już wymaga Room i nowej wersji formatu – pytanie 1.

Pola kompletu: `typ` (letni/zimowy), `data założenia`, opcjonalnie `przebieg w dniu założenia` (z licznika), opcjonalne progi (miesiące, km).
Walidacja: data nie w przyszłości, nie wcześniej niż poprzednia zmiana, typ inny niż poprzedni, co najwyżej jeden wpis dziennie.

## 5. Obliczenia

- **Przedziały:** wpisy posortowane po dacie. Komplet wpisu `i` obowiązuje od `data_i` do `data_{i+1}` (bez końca dla ostatniego, do dziś).
- **Przebieg kompletu:** Σ `distanceKm` tras z `start` w przedziale (strefa telefonu, jak w F10), plus przebieg początkowy, jeśli podany.
  Jeśli podano przebieg w dniu założenia, a jest odczyt licznika dziś, pokazuje też różnicę licznika (kontrola zgodności z trasami).
- **Trasy sprzed pierwszego wpisu:** osobna liczba „przed pierwszym wpisem: N km”, nie przypisywana do żadnego kompletu.
- **Trasy bez współrzędnych i z zerowym dystansem:** liczone jak w F10 (dystans > 0), bez wymogu GPS.
- **Progi:** próg miesięczny = pełne miesiące od daty założenia (`ownershipMonths`-podobnie); próg km = przebieg kompletu ≥ próg.

## 6. Ekran i prezentacja

- **Propozycja:** karta „Opony” na początku ekranu „Wydatki” (oraz wpis w kategorii `TYRES` bez zmian). Bez nowego kafelka: siatka zostaje na 15 pozycjach.
  Karta: dwa wiersze (letni, zimowy) z datą założenia, przebiegiem kompletu, „od X miesięcy” i ewentualnym progiem; pod spodem lista zmian.
- **Przycisk „Dodaj zmianę”:** data (domyślnie dziś) i typ; przebieg z licznika opcjonalny.
- Wiersze w stylu C32: liczby przez `TripsFormatting.number`, bez ściany tekstu, jedna linia pomocnicza na komplet, odmiana „miesiąc/miesiące/miesięcy”.
- Pusta lista: jedno zdanie „Dodaj datę założenia letniego i zimowego”.

## 7. Powiadomienie

- Kanał „Opony” (`IMPORTANCE_LOW`), ID 903, po udanej synchronizacji; `Mutex`, klucz po `post`, I/O poza UI, cisza 8–22, wyciszony kanał nie zużywa klucza.
- Treść: „Letnie opony: 6 miesięcy od założenia (12 340 km)”. Bez VIN, GPS i nazw miejsc.
- Bez nowego uprawnienia (`POST_NOTIFICATIONS`, §13.2).
- Klucz `komplet:<data założenia>:<miesiące|km>`, raz na komplet i próg; po zmianie kompletu nowy klucz.

## 8. Prywatność

Daty zmian i przebiegi to dane właściciela w `settings` (Auto Backup), bez VIN i bez sieci. Trasy są czytane lokalnie. Brak nowego pobrania pogody.

## 9. Testy (po akceptacji)

- Domena (Kotlin i Python, te same nazwy): przedziały kompletów, trasa na granicy daty (start 23:50 i 00:10), trasy przed pierwszym wpisem, przebieg z progiem km i miesięczny,
  walidacja kolejności i typu, zmiana czasu (strefa), pusta lista.
- Ustawienia: round-trip listy, zachowanie przy uszkodzonym wpisie (pominięty, nie crash).
- Powiadomienie (Robolectric wzorem `PeriodSummaryNotifierTest`): raz na komplet i próg, cisza nocna, wyciszony kanał, brak uprawnienia.
- Teksty PL/EN, odmiana miesięcy, sweep zakazanych ciągów, `Lang` w `@Before`/`@After`.
- Migracja Room: **tylko jeśli wybrany wariant Room** (pytanie 1).

## 10. Walidacja (po akceptacji)

`cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
oraz `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
Pamiętać o przewijaniu: karta dodana do ekranu wymaga sprawdzenia, że kolumna jest przewijana (lekcja v0.2.9).

## 11. Kolejność

1. PRD F56 + SPEC §56 + ROADMAP (pozycja 10 do W toku). 2. Domena `TyreOps` (Kotlin + Python) z testami. 3. Ustawienia (lista kompletów).
4. Karta w „Wydatkach” z przewijaniem i formularzem. 5. Powiadomienie wg A i jego testy. 6. Teksty, CHANGELOG, README, SECURITY, kopia (opcjonalnie JSON).

## 12. Poza zakresem

- Przypomnienie wg temperatur (wariant B) – osobna pozycja LATER, wymaga zgody na dzienne pobieranie pogody.
- Ślad CO₂ opon, cena opon jako osobny model kosztu (koszt idzie przez wydatki kategorii `TYRES`).
- Głębokość bieżnika, numery seryjne, historia dostawców.
- Nowy kafelek na statusie (siatka pełna).

## 13. Pytania otwarte

1. Wymiany w eksporcie JSON archiwum: tak (wymaga Room v8 i nowej wersji formatu) czy nie (ustawienia, Auto Backup)?
2. Wariant B (temperatury): odłożyć (proponowane) czy zgoda na dzienne pobieranie szeregu z Open-Meteo?
3. Gdzie karta: „Wydatki” (proponowane) czy osobny ekran z kafelkiem (16. pozycja siatki)?
4. Progi domyślne: 6 miesięcy i 15 000 km – czy puste (bez przypomnienia, dopóki właściciel nie ustawi)? Proponowane: puste.
5. Przebieg początkowy kompletu: wymagany czy opcjonalny? Proponowane: opcjonalny; bez niego liczą się tylko trasy od daty założenia.
6. Komplet zimowy bez jazdy zimą: czy przypomnienie ma czekać na pierwszą trasę w tym komplecie? Proponowane: nie, liczy się od daty założenia.
