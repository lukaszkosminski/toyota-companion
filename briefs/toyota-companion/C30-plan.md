# C30 – plan: krótkie trasy i zimny silnik (PRD F50, SPEC §50)

Status: **zaakceptowany i zaimplementowany 2026-11-10** (odstępstwa od planu: sekcja 12 na końcu). Poprzednio: szkic do akceptacji koordynatora (zadanie z ROADMAP/NEXT, pozycja 2, zaakceptowane przez właściciela 2026-11-10).
Data: 2026-11-10. Autor: agent 2. Etap 1: tylko ten plik; kod, docs i testy dopiero po akceptacji.

## 1. Cel

Pokazać na ekranie „Statystyki”, ile jazdy to krótkie trasy i **o ile więcej paliwa na 100 km kosztują niż dłuższe** –
w pasmach długości trasy, z nadwyżką w litrach i złotówkach, oraz (osobno) czy start po dłuższym postoju („zimny silnik”,
przybliżony przerwą od końca poprzedniej trasy) zużywa więcej niż start „na ciepło” w tym samym paśmie.
Wyłącznie lokalne archiwum tras: bez sieci, bez budzenia auta (`refresh_status()` zakazany), bez nowych uprawnień,
zależności i bez zmiany schematu Room. Logika czysta w `core/domain` z lustrem w Pythonie (kierunek C22 D9).

## 2. Ustalenia z kodu (odpowiedzi na (a) i (c))

**(a) Co jest w archiwum** (`data/db/Entities.kt` `TripEntity`, `core/domain/Models.kt` `Trip`, `ToyotaMapper.kt`):

| Pole | Typ / źródło | Uwagi |
|---|---|---|
| `start`, `end` | `Instant` (`start_ts`, `end_ts`) | wystarczają do przerwy między trasami – bez GPS |
| `distanceKm` | `Double`, dokładność metrów (Toyota podaje `length`) | |
| `fuelL` | `BigDecimal`, litry z `summary.fuelConsumption` w **ml** (`ml / 1000`, rozdzielczość 0,001 l) | 0 przy jeździe wyłącznie na prądzie – to prawdziwy odczyt, nie brak danych |
| `evDistanceKm` | `Double?` z `hdc` | do udziału EV (jak F19) |
| `averageSpeed` | `Double?` | nie potrzebna; `durationIdleS` jest puste we wszystkich trasach (§22) |
| koszt | `TripCost` (`costPln`, `source`) | „bez ceny” = `hasNoCost`: `costPln == null` albo `source == NONE` |

Jak istniejące statystyki odfiltrowują trasy (żeby nowa sekcja nie liczyła inaczej niż reszta ekranu):

| Miejsce | Warunek wejścia |
|---|---|
| `consumptionView` (§24, F20) | `distanceKm` skończony i `> 0`, `fuelL >= 0`; **trasy z 0 l wchodzą** |
| `periodStats` (§17) | wszystkie trasy; l/100 km = Σ paliwo / Σ km |
| `trafficSummaries` (§22, F19) | ≥ 2 km, prędkość `> 0`, `fuelL >= 0`; 0 l wchodzi; litry liczone do wszystkich km, także na prądzie |
| anomalie (§19) | ≥ 2 km i `fuelL > 0` (tylko do mediany) |
| ranking EV (§16) | ≥ 1 km i dane `hdc` |

Wniosek: **trasa z 0 l zostaje** (inaczej krótkie trasy na prądzie zniknęłyby i zawyżyły spalanie pasma), odrzucamy
tylko dystans nieskończony/≤ progu szumu i ujemne paliwo. Dolny próg dystansu: **0,1 km** (`SHORT_MIN_KM`) – poniżej to
przestawienie auta, nie trasa; F19 odcina 2 km ze względu na prędkość, której tu nie używamy, więc przedział „< 2 km”
musi wejść (to właśnie tam zimny start kosztuje najwięcej).

**(c) Przerwa od poprzedniej trasy da się policzyć z istniejących danych.** `ParkingStops.kt` (F17, §18) liczy postój
jako `następna.start − poprzednia.end` po sortowaniu `(start, id)`, ale opiera się na `TripRecord` i GPS (150 m,
`unmatched`). Nowa funkcja potrzebuje tylko `Trip.start/end`, więc może być w `core/domain` bez współrzędnych. Różnice
względem F17, wynikające z innego celu:
- poprzednia trasa to **każda** trasa archiwum (także taka z odrzuconym paliwem lub < 0,1 km) – silnik rozgrzewa się
  niezależnie od jakości odczytu;
- brak poprzedniej trasy (pierwsza w archiwum) = **nieznany start**;
- przerwa **> 21 dni** (`MAX_CLAIM` z F17) = **nieznany start**, żeby dziura w archiwum (brak synchronizacji, usunięte
  przez Toyotę trasy) nie zrobiła z ciepłego startu „zimnego”;
- przerwa ujemna lub zerowa (nakładające się zapisy) = ciepły start;
- brakująca trasa w środku archiwum może tylko **skrócić** prawdziwą przerwę, więc to ona powoduje pomyłkę „zimny
  zamiast ciepłego”; stąd ostrożność przy 21 dniach i próg liczności (pkt 4), a w hincie ekranu zdanie, że to
  przybliżenie, nie pomiar temperatury silnika (w archiwum nie ma temperatury płynu).

## 3. Decyzje jawne

- **(b) Pasma długości** (dolna granica włącznie, jak w F19): `< 2`, `2–5`, `5–10`, `10–25`, `≥ 25 km`. Uzasadnienie:
  nadwyżka za rozgrzewanie jest mniej więcej stała na start (litry), więc na 100 km maleje jak 1/długość – potrzebne są
  pasma gęste na dole (poniżej 2 km to głównie manewry i dojazdy, 2–5 km „po bułki”, 5–10 km dojazd do pracy) i szerokie
  u góry, gdzie silnik już jest ciepły; jeden próg ukryłby, że 3 km i 8 km to inne światy. 10 km jest granicą „krótkiej”
  i początkiem odniesienia (konwencja, nie pomiar – silnik osiąga temperaturę pracy zwykle w ciągu kilku do kilkunastu km).
- **Odniesienie = wszystkie trasy ≥ 10 km** jako jedna grupa (Σ paliwo / Σ km), nie „ostatnie pasmo”: większa próba, brak
  wyboru między 10–25 a ≥ 25 (dla tego archiwum F19 pokazuje, że trasa i płynna jazda mają zbliżone spalanie). Pasma
  10–25 i ≥ 25 są nadal wyświetlane jako wiersze, bez nadwyżki (są odniesieniem).
- **Zimny start:** `COLD_GAP = 3 h` (`≥ 3 h` od końca poprzedniej trasy = zimny; `< 3 h` = ciepły; 21 dni i brak = nieznany).
  Stała nazwana, jedna na oba języki kodu; zmiana wartości jest jednolinijkowa.
- **(d) Co liczymy na pasmo** (`BandStats`): liczba tras, udział tras (całe %, HALF_UP, od wszystkich użytych tras),
  km (0,1) i udział km, paliwo (litry), **l/100 km = Σ litrów / Σ km × 100** (0,01, `divideLikePythonDecimal`, nigdy średnia
  średnich), zł/100 km = Σ kosztu / Σ km tras z ceną (bez ceny `null` i „brak ceny”, nigdy 0 zł; trasa `NONE` liczy się jak
  bez ceny), udział jazdy na prądzie jak w F19 (Σ km EV przycięte do dystansu trasy / Σ km pasma, całe %).
- **Nadwyżka względem odniesienia (tylko pasma < 10 km):**
  `nadwyżka l/100 km = l/100(pasmo) − l/100(odniesienie)` (0,01, ze znakiem);
  `nadwyżka litrów = (Σ litrów pasma − stawka_odniesienia × Σ km pasma / 100)`, stawka bez wcześniejszego zaokrąglania,
  zaokrąglenie tylko na końcu (0,01 l, HALF_UP);
  `nadwyżka zł = nadwyżka litrów × cena litra pasma`, cena = Σ kosztu / Σ litrów tras pasma z ceną i litrami > 0; bez takiej
  ceny `null`. Wartość ujemna jest prawdziwa (krótkie trasy na prądzie) i pokazywana ze znakiem, bez przycinania do 0.
  Suma „Razem krótkie trasy (< 10 km)” sumuje tylko pasma z policzoną nadwyżką.
- **Minimalna liczność:** pasmo pokazuje l/100 km od **5 tras** (`SHORT_MIN_TRIPS_SHOWN`, jak komórka F24); poniżej –
  tylko „N tras – za mało”. Nadwyżka (wniosek) wymaga **≥ 10 tras w paśmie i ≥ 10 w odniesieniu** (`SHORT_MIN_TRIPS_COMPARE`,
  jak F19); inaczej `null` i brak zdania o różnicy.
- **Zimny a ciepły start w paśmie** (`ColdSplit`, dla każdego pasma osobno, żeby nie mieszać długości z zimnem): trasy,
  km i l/100 km zimnych i ciepłych; różnica „zimny − ciepły” tylko gdy obie strony mają ≥ 10 tras; trasy z nieznanym startem
  liczone w paśmie, ale poza podziałem (licznik w wyniku).
- **Zakres czasu:** całe archiwum wybranego pojazdu (jak F19/F10). Sezon (zima) – poza zakresem, patrz pkt 8 i pytania.
- **Wynik funkcji nie zawiera nic poza liczbami:** brak id tras, współrzędnych i VIN.
- **(e) Gdzie w UI:** ekran **„Statystyki”** (§18, `StatsScreen.kt`), nowa karta zaraz po „Tempo jazdy a spalanie” (F19).
  Uzasadnienie: ta sama logika „Σ paliwo / Σ km w pasmach” co F19/F24, ten sam odczyt `SyncEngine.trips()`, te same
  stałe liczności. „Styl jazdy” (§19) dotyczy wyniku eco i odcinków z GPS – nie pasuje. **Wzorzec kontrolera:** bez nowego
  kontrolera – `StatsController.computeStats` (odczyt w callbacku nawigacji, `ensureLoaded` po śmierci procesu, `reset()`
  przy wylogowaniu, token generacji i `loadJob` z C22 D5 już są) dostaje pole `shortTrips: ShortTripSummary` w `StatsView`.
  Karta ukrywa się, gdy nie ma żadnej użytej trasy (jak karta ruchu: `if (view.traffic.isNotEmpty())`).
- **(f) Domena:** `core/domain/ShortTripOps.kt` + `backend/app/domain/shorttrips.py`, testy `ShortTripOpsTest.kt` /
  `test_domain_shorttrips.py` z tymi samymi nazwami. Python jest wzorcem (SPEC §Model domenowy), Kotlin go portuje z
  `divideLikePythonDecimal` i `RoundingMode.HALF_UP` (jak `BudgetOps`).

### Proponowany interfejs (nazwy takie same w obu językach, Python w snake_case)

```
enum LengthBand { UNDER_2, FROM_2, FROM_5, FROM_10, FROM_25 }    // dolna granica włącznie
enum StartKind  { COLD, WARM, UNKNOWN }

data BandStats(band, trips, distanceKm, tripSharePct, kmSharePct, fuelL, lPer100Km, costPer100Km?, evSharePct?,
               excessLPer100Km?, excessL?, excessPln?, coldSplit: ColdSplit?, unknownStartTrips)
data ColdSplit(coldTrips, coldLPer100Km, warmTrips, warmLPer100Km, differenceLPer100Km)
data ShortTripSummary(bands: List<BandStats>, reference: ReferenceStats?, shortTrips, shortTripSharePct,
                      shortKmSharePct, totalExcessL?, totalExcessPln?, usedTrips, leftOut)

lengthBandOf(distanceKm) -> LengthBand
tripStartKinds(trips) -> Map<tripId, StartKind>          // z WSZYSTKICH tras, po (start, id)
shortTripSummary(trips, costs) -> ShortTripSummary
```
Stałe: `SHORT_MIN_KM = 0.1`, `SHORT_REFERENCE_FROM_KM = 10`, `COLD_GAP_HOURS = 3`, `MAX_GAP_DAYS = 21`,
`SHORT_MIN_TRIPS_SHOWN = 5`, `SHORT_MIN_TRIPS_COMPARE = 10`.

## 4. Pliki

| Warstwa | Plik |
|---|---|
| `core/domain` | `ShortTripOps.kt` (nowy): pasma, `tripStartKinds`, `shortTripSummary`, modele wyniku |
| `backend/app/domain` | `shorttrips.py` (nowy, wzorzec), eksporty w `__init__.py` |
| `core` testy | `ShortTripOpsTest.kt` (nowy) |
| `backend/tests` | `test_domain_shorttrips.py` (nowy, te same nazwy) |
| `app/ui` | `StatsController.kt`: `StatsView.shortTrips`, wywołanie w `computeStats` (ten sam `trips` i `costs`) |
| `app/ui` | `StatsScreen.kt`: karta „Krótkie trasy i zimny silnik”; `ShortTripsFormatting.kt` (nowy, czyste funkcje wierszy) |
| `app/ui` | `Texts.kt`: tytuł, hint, wiersze, „za mało”, nadwyżka, zimny/ciepły, odmiana (PL/EN) |
| docs | PRD F50, SPEC §50 (po akceptacji planu), CHANGELOG, SECURITY (jedno zdanie), ROADMAP NEXT→DONE (po stronie koordynatora) |

Bez zmian: Room (schemat i migracje), `settings`, Manifest, Gradle, `SyncEngine`, backend poza `app/domain`.

## 5. Testy

Nazwy identyczne w Kotlinie (`ShortTripOpsTest`) i Pythonie (`test_domain_shorttrips.py`), w stylu `BudgetOpsTest`:
- pasma: granice 0,099 → odrzucona, 0,1 → `<2`, 1,999 → `<2`, 2,0 → `2–5`, 4,999, 5,0, 9,999, 10,0, 24,999, 25,0 (dolna
  granica włącznie), `NaN`/`Infinity`/ujemny dystans → pominięte i policzone w `leftOut`; ujemne paliwo → pominięte.
- l/100 km pasma = Σ litrów / Σ km (**nie** średnia średnich): dwie trasy 1 km/0,2 l i 9 km/0,3 l w jednym paśmie.
- trasa z 0 l wchodzi do sum (pasmo samej jazdy na prądzie → 0,00 l/100 km, nadwyżka ujemna ze znakiem).
- udziały tras i km (całe %, HALF_UP, suma ≈ 100 z zaokrąglenia – nie wymuszamy), udział EV przycięty do dystansu.
- koszt: pasmo bez żadnej ceny → `costPer100Km == null` (nigdy 0 zł), `source = NONE` jak bez ceny, cena tylko z tras z ceną,
  zł/100 km na km tras z ceną.
- odniesienie = wszystkie ≥ 10 km razem; brak tras ≥ 10 km → `reference == null` i brak nadwyżek.
- progi liczności: 4 trasy → bez l/100 km, 5 → jest; nadwyżka: 9 tras w paśmie albo 9 w odniesieniu → `null`, 10 i 10 → jest.
- nadwyżka litrów liczona ze stawki bez zaokrąglenia (przypadek, w którym zaokrąglona stawka dałaby inny wynik na 0,01 l).
- nadwyżka zł = litry × cena litra pasma; pasmo bez ceny → `null`; suma „razem” pomija pasma bez nadwyżki.
- zimny start: przerwa 2 h 59 min = ciepły, dokładnie 3 h = zimny, 21 dni = zimny, 21 dni + 1 s = nieznany, pierwsza trasa = nieznany,
  przerwa ujemna = ciepły; poprzednia trasa jest także ta z odrzuconym paliwem lub < 0,1 km (ogrzewa silnik); niezależność
  od kolejności wejścia i remisów `(start, id)`.
- podział zimny/ciepły: różnica tylko przy ≥ 10 po obu stronach; nieznane starty poza podziałem, ale w paśmie.
- pusta lista, jedna trasa, same trasy poniżej 0,1 km → pusty wynik bez wyjątku.
- `app`: `computeStats` wypełnia `shortTrips`; `ShortTripsFormatting` (wiersz pasma, „za mało”, nadwyżka ze znakiem, brak ceny);
  `TextsTest`: nowe teksty w sweepie zakazanych ciągów (token, VIN1, p@ss) i w teście PL≠EN, z `Lang.current` ustawianym i
  przywracanym w `@Before`/`@After` (lekcja C26/C27/C29); test odmiany: 1 trasa, 2–4 trasy, 5, 12–14, 22 trasy (PL) oraz 1/N (EN).
- Lustro: te same przypadki liczbowe w obu językach (te same wartości oczekiwane w testach – „złoty zestaw” skopiowany ręcznie).

## 6. Prywatność

Funkcja używa tylko `start`, `end`, dystansu, paliwa, `evDistanceKm` i kosztu. Nie czyta współrzędnych, VIN ani identyfikatorów
(wynik ich nie zawiera), nie ma sieci, logów, CSV ani eksportu, nie budzi auta i nie dodaje uprawnień ani ustawień. Karta
nie wyświetla godzin ani dat poszczególnych tras – tylko zbiorcze liczby, więc nie ujawnia rytmu dnia właściciela.

## 7. Walidacja

- Python: `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Android: `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`
- gitleaks na historii. Gradle uruchamia tylko koordynator/autor zmiany (po akceptacji planu; w etapie 1 nic nie uruchamiam).

## 8. Kolejność

1. `shorttrips.py` + `test_domain_shorttrips.py` (wzorzec liczbowy) → ruff/mypy/pytest.
2. `ShortTripOps.kt` + `ShortTripOpsTest.kt` (port, te same wartości i nazwy).
3. `StatsController.computeStats`, `ShortTripsFormatting`, `Texts`, karta w `StatsScreen`.
4. Docs: PRD F50, SPEC §50, CHANGELOG, SECURITY; ROADMAP robi koordynator.
5. Walidacja, samokontrola diffu pod kątem C26–C29, osobny przegląd agenta 3, atomowy commit.

## 9. Poza zakresem

- Temperatura/pogoda (F15/F23) i filtr „tylko zima” – wymaga pobrania pogody (sieć) i innej definicji zimna; rozważyć jako
  osobny krok na `seasonOf` (bez sieci) po zobaczeniu wyników.
- Wykrywanie dziur w archiwum współrzędnymi (jak `unmatched` w F17) – wymagałoby `TripRecord` w domenie.
- Powiadomienia, wykresy słupkowe, eksport CSV, ustawienia progów, przewidywanie kosztu przyszłej trasy (osobna pozycja 1).
- Pomiar temperatury silnika, czas jałowy (`durationIdleS` jest puste), rozdzielanie paliwa na „start” i „jazdę”.
- Zmiana schematu Room i migracje.

## 10. Teksty PL/EN i odmiana (g)

- Tytuł: „Krótkie trasy i zimny silnik” / „Short trips and cold engine”.
- Hint (jedna stała z liczbą godzin jako argument): „Pasma wg długości trasy. Zimny start = od końca poprzedniej trasy minęły
  co najmniej 3 godziny – to przybliżenie, aplikacja nie zna temperatury silnika. Litry liczone do wszystkich km, także na
  prądzie.” / „Bands by trip length. Cold start = at least 3 hours since the previous trip ended – an approximation, the app
  does not know the engine temperature. Liters are counted for all km, including those on battery.”
- Odmiana przez `plPlural`/`enPlural`: **trasa / trasy / tras** (1, 2–4 bez 12–14, reszta), **godzina / godziny / godzin**
  (parametr z stałej `COLD_GAP_HOURS`), „zł” i „km” nie odmieniają się. Liczby po polsku z przecinkiem
  (`TripsFormatting.plain/money/signed/signedMoney`), `0,00 zł` tylko jako prawdziwa wartość z ceną, brak ceny = „brak ceny”.
- Wiersz pasma: `2–5 km · 41 tras (23 % tras, 9 % km) · 5,84 l/100 km · +1,62 l/100 km wobec tras od 10 km`.
- Pasmo poniżej progu: `< 2 km · 3 trasy – za mało`.
- Razem: „Krótkie trasy (poniżej 10 km): 38 % tras, 11 % km; nadwyżka ok. 14,2 l, 91,40 zł w całym archiwum.” Gdy brak nadwyżki:
  bez zdania o kosztach. EN: „Short trips (under 10 km): 38% of trips, 11% of km; extra about 14.2 l, 91.40 zł in the whole archive.”
- Nagłówek „zimny/ciepły” w wierszu pasma: „zimny start: 12 tras, 6,10 l/100 km · ciepły: 29 tras, 5,60 l/100 km”; bez wniosku
  przy < 10 tras po którejś stronie: „zimny start: N z M tras”.

## 11. Samokontrola pod kątem C26–C29

- **C26 B1** (goły VIN): wynik i teksty bez VIN/GPS; test sweepu zakazanych ciągów obejmuje nowe teksty.
- **C26 W3 / C29 #4–5** (wyścigi kontrolera): bez nowego kontrolera – korzysta z `StatsController` z tokenem generacji i `loadJob`.
- **C28 #3** (zaokrąglenie przed dzieleniem): sumy zaokrąglane raz na końcu, stawka odniesienia nie zaokrąglana przed
  liczeniem nadwyżki litrów; dzielenie przez 0 niemożliwe (dystans ≥ 0,1 km, odniesienie wymaga ≥ 10 tras).
- **C29 #1** (komunikat niezgodny z danymi): wniosek o nadwyżce tylko przy dostatecznej liczności; wartość ujemna pokazywana
  prawdziwie, nie jako „nadwyżka”; teksty nie twierdzą przyczyny („kosztują więcej” tylko gdy różnica > 0, inaczej „nie więcej”).
- **C27/C28**: brak CSV; ROADMAP aktualizuje koordynator; PL i EN różne (test), `Lang` przywracany w testach.

## 12. Odstępstwa przy implementacji (2026-11-10)

- Progi liczności obejmują wszystkie stawki pasma (l/100 km, zł/100 km, udział EV), nie tylko l/100 km; stawki odniesienia też są `null` poniżej 5 tras.
- Nadwyżka l/100 km to różnica wartości pokazanych (po zaokrągleniu do 0,01), żeby arytmetyka na ekranie się zgadzała; nadwyżka litrów i zł liczy się z niezaokrąglonej stawki odniesienia i niezaokrąglonych litrów.
- Suma w zł tylko gdy każde policzone pasmo ma cenę (inaczej `null`), a nie suma z pasm, które ją mają.
- Karta ma wiersz „Odniesienie – trasy od 10 km” i osobny blok „Zimny a ciepły start” zamiast linii zimny/ciepły przy każdym paśmie (jedna linia pomocnicza na pasmo); sformułowania wiersza pasma skrócone do „41 tras (23 %) · 9 % km · +1,62 l/100 km”.
