# C33 – plan: Kalkulator kosztu planowanej trasy (PRD F52, SPEC §52)

Status: **szkic do akceptacji** (tylko ten plik; kod, testy i docs po akceptacji). Data: 2026-11-10, agent 3.
Worktree `toyota-companion-a3`, `c32-agent3` = `06c16bf`. Uwaga: `F52`/`§52` **nie ma jeszcze w docs** – grep wskazuje
tylko `docs/ROADMAP.md:84` (pozycja 1 NEXT); pierwszym krokiem po akceptacji jest wpis w PRD/SPEC, potem kod.

## 1. Cel

Użytkownik wpisuje kilometry planowanej trasy i wybiera rodzaj jazdy; aplikacja pokazuje **przedział** litrów i zł
z własnego archiwum tras – bez sieci, map, GPS, budzenia auta (`refresh_status()` zakazany), nowych uprawnień,
zależności i zmian Room. Logika czysta w `core/domain`, z lustrem w Pythonie (kierunek C22 D9).

## 2. Ustalenia z kodu

**(a) Rodzaj jazdy.** F19 ma pasma tempa w `app/ui/TrafficIndex.kt:9-20`: `TRAFFIC_MIN_KM = 2.0`, progi 15/25/40 km/h,
`TrafficClass { SLOW, CITY, FLOWING, HIGHWAY }`; `trafficSummaries` (`:65`) liczy Σ paliwa / Σ km i odrzuca trasę bez
prędkości, < 2 km i z ujemnym paliwem. Prędkość jest w `TripRecord.averageSpeed` (`core/data/Models.kt:41`), bo domena
`Trip` (`core/domain/Models.kt:41`) jej nie ma. Krótkie nazwy: `Texts.drivingType` (`Texts.kt:469`), długie
`Texts.trafficClassName` (`:481`). **Wybór:** cztery pasma tempa jako „rodzaj jazdy”; pasma długości F50
(`ShortTripOps.kt:43`) nie są rodzajem jazdy, bo długość planowanej trasy jest już wejściem (km).

**(b) Cena litra „dziś”.** `TripCost.pricePerLiter` wypełnia `computeTripCost` (`core/domain/CostOps.kt:19-63`) z ceny w baku składanej przez `tankPriceAfterRefuel` (`core/domain/RefuelOps.kt:181`), z ceną domyślną włącznie.
Nie ma publicznej funkcji „cena teraz”, a `computeStats` (`app/ui/StatsController.kt:115`) ma już `StoredTrip.cost`. Kolejność: 1) `pricePerLiter` najnowszej wycenionej trasy (`StoredTrip.cost.pricePerLiter`), 2) `SettingsStore.defaultPrice()` (`app/.../settings/SettingsStore.kt:28`), 3) brak – tylko litry, bez zera.

**(c) Temperatura.** Wersja 1 **bez temperatury**. F24 (`ui/WeatherMatrix.kt:44`, `temperatureBucket` w `core/domain/WeatherOps.kt:27`) potrzebuje danych Open-Meteo, a 4 pasma × 4 koszyki z progiem 10 tras dają zbyt rzadkie komórki;
ręczny sezon to drugie pytanie, a pogoda z przeszłości nie jest pogodą planowanej trasy. To materiał na wersję 2.

**(d) Przedział.** Ludność: trasy klasy jak w F19 (≥ 2 km, prędkość > 0, paliwo ≥ 0). Minimum **10 tras** (`TRAFFIC_MIN_COMPARE_TRIPS`, `TrafficIndex.kt:17`); poniżej „za mało tras: n/10”, bez wyniku.
Zakres: na trasę `fuelL / distanceKm × 100`, sortowanie; dolny kwartyl = indeks `⌊(n−1)/4⌋`, górny = `⌊3(n−1)/4⌋`; litry = km × kwartyl / 100, zł = litry × cena. Litry 0,1 l, zł do 1 zł, wskaźniki 0,01. To przedział zużycia, nie średnia pasma.

**(e) Funkcja czysta.** `TrafficClass` i progi przenosimy do `core/domain/TrafficBands.kt` (są dziś w UI, F19 bez zmian
zachowania); `ui/TrafficIndex.kt` dostaje `typealias TrafficClass`/importy. Nowy `core/domain/PlannedTripOps.kt`:
`data class PlannedCost(band, trips, litresLow, litresHigh, costLow, costHigh)` (pola `BigDecimal?` dla granic) oraz
`plannedTripCost(trips: List<Trip>, speedsKmh: Map<String, Double?>, km: BigDecimal, band: TrafficClass, pricePerLiter: BigDecimal? = null)`.
Mapa prędkości trzyma domenę z dala od `StoredTrip`; brak wpisu = trasa odpada. Lustro: `backend/app/domain/planned.py`
– `planned_trip_cost(...)` i `backend/tests/test_domain_planned.py` z tymi samymi nazwami testów (Python `Trip` nie ma
prędkości, `models.py:100`, więc mapa jest naturalna też tam).

**(f) UI.** **Karta na górze „Statystyk”**, nie 16. kafelek: menu (`StatusScreen.kt:181-198`) ma 15 kafelków i prowadzi
do pełnych ekranów, a kalkulator to formularz + wynik obok już pokazywanego spalania i kosztów. `StatsScreen.kt:65-74`
to `LazyColumn` kart – nowa karta jako pierwszy `item`. Pola: km (`OutlinedTextField`, klawiatura dziesiętna), cztery
wybieraki rodzaju jazdy (krótkie nazwy), linia wyniku. Walidacja: istniejące `parseExpenseAmount` (`ui/Expenses.kt:51`)
– dodatnie, przecinek/kropka, maks. 2 miejsca; puste = brak wyniku, błędne = komunikat. Cenę rozwiązuje
`StatsController` (nowy `defaultPriceProvider`, jak `SyncEngine.kt:73`), bez nowego odczytu bazy.

**(g) Teksty PL/EN** w `Texts`, bez `Locale`: `PLANNED_TITLE` „Kalkulator trasy”/„Trip cost calculator”; `PLANNED_HINT`;
`PLANNED_KM_LABEL` „Kilometry”/„Kilometres”; `PLANNED_KIND_LABEL` „Rodzaj jazdy”/„Kind of drive”;
`plannedResult(litres, money)` → „ok. 3,9–4,6 l, 24–29 zł”/„approx. …”; `plannedTooFew(n, min)`;
`PLANNED_NO_PRICE` „brak ceny paliwa – ustaw cenę domyślną w Opcjach”; `PLANNED_BAD_KM`.
Liczby przez `TripsFormatting.number`; pojedyncze kwoty przez `money`/`pricePerLiter`.

## 3. Decyzje jawne

- Cztery pasma tempa F19; próg 2 km i minimum 10 tras jak w F19.
- Wersja 1 bez temperatury i sezonu; F24 tylko jako przyszłe rozszerzenie.
- Zakres P25–P75 z tras, nie średnia; wynik zawsze „od–do”.
- Cena: ostatnia wyceniona trasa → cena domyślna → tylko litry.
- Karta na „Statystykach”; zero nowych kafelków i ekranów.
- Trasy 0 l (całkiem na prądzie) wchodzą do ludności jak w F19; ich wpływ na dolny kwartyl to pytanie otwarte.

## 4. Pliki

- `docs/PRD.md` (F52), `docs/SPEC.md` (§52), `CHANGELOG.md` (Unreleased) – po akceptacji, przed kodem.
- `android/core/src/main/kotlin/.../domain/TrafficBands.kt` – przeniesione `TrafficClass`, progi, `trafficClassOf`.
- `android/app/src/main/kotlin/.../ui/TrafficIndex.kt`, `WeatherMatrix.kt`, `WeatherFormatting.kt`, `WeatherController.kt`,
  `Texts.kt` – importy/alias po przeniesieniu; zachowanie F19 i F24 bez zmian.
- `android/core/src/main/kotlin/.../domain/PlannedTripOps.kt`, `android/core/src/test/.../PlannedTripOpsTest.kt`.
- `android/app/src/main/kotlin/.../ui/StatsController.kt`, `StatsScreen.kt`, `PlannedTripFormatting.kt`, `Texts.kt`;
  `android/app/src/test/.../PlannedTripFormattingTest.kt`.
- `backend/app/domain/planned.py`, `backend/app/domain/__init__.py`, `backend/tests/test_domain_planned.py`.

## 5. Testy

Kotlin (JUnit4, `org.junit.Test`, `Lang.current` przywracany w `finally`): granice pasm (2,0 km; 15/25/40 km/h);
odrzucenia (< 2 km, brak prędkości, prędkość ≤ 0, ujemne paliwo); 9 tras → brak wyniku, 10 → wynik; pusta klasa
→ `trips = 0`; znane kwartyle, litry i zł dla 100 km; zaokrąglenia 0,1 l / 1 zł; brak ceny → `costLow/High = null`,
litry nadal są; km 0/ujemne/tekst → brak wyniku; PL/EN linii wyniku i komunikatów, liczby z przecinkiem.
Python: te same nazwy przypadków w `test_domain_planned.py` (parytet domeny).

## 6. Prywatność

Tylko lokalne archiwum tras, ceny i ustawienia. Bez GPS na ekranie, VIN, sieci, nowych uprawnień, `Room` i budzenia auta.
Cena domyślna nie jest logowana; wynik nie zapisuje planu.

## 7. Walidacja (dopiero po akceptacji, nie teraz)

`cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
oraz `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:assembleDebug`.
**Nie uruchamiano nic w ramach tego planu.**

## 8. Kolejność

1. PRD F52 + SPEC §52 + CHANGELOG. 2. Przeniesienie pasm do `core/domain`, zielone testy F19.
3. `plannedTripCost` + testy Kotlin. 4. Lustro Python + testy. 5. Cena w `StatsController`.
6. Karta i teksty + testy PL/EN. 7. Krótka aktualizacja SPEC (wzór, progi, źródło ceny) i CHANGELOG.

## 9. Poza zakresem

Temperatura/sezon (F24), mapy i planowanie trasy, GPS, koszt energii elektrycznej, zapisywanie planów, historia planów,
Room, powiadomienia, ceny stacji, import/eksport, zmiany Gradle.

## 10. Pytania otwarte

1. Czy trasy 0 l mają wchodzić do kwartyli? Wg F19 wchodzą – dolny kwartyl może wtedy spaść do 0. Alternatywa:
   kwartyle tylko z tras `fuelL > 0` i osobna informacja o udziale EV.
2. Domyślny rodzaj jazdy przed wyborem: „miasto” czy „płynnie”?
3. Kwota w przedziale: całe złote („24–29 zł”) czy grosze („24,00–29,00 zł”)?
4. Cena z ostatniej trasy bywa stara przy braku nowych tras – czy dopuścić cenę z ostatniego tankowania, nawet gdy
   po nim nie było trasy (wymaga odczytu tankowań w `StatsController`)?
5. Potwierdzenie numerów F52/§52 – w docs ich jeszcze nie ma; czy rezerwacja numerów jest właściwa?
