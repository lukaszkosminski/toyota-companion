# C34 – plan: najlepsza godzina wyjazdu (PRD F53, SPEC §53)

Status: **projekt do akceptacji**. Data: 2026-11-10. Pozycja 3 z `docs/ROADMAP.md` NEXT. Tylko plan – bez kodu, testów, Gradle i gita.
Numer F53 jest proponowany: F50 (krótkie trasy) i F51 (CO₂) są zajęte; F52 nie jest użyte w PRD, więc agent główny zdecyduje, czy zostawić lukę.

## 1. Cel

Pokazać, o której godzinie i w jakim dniu tygodnia **ten sam odcinek** (dom–praca i inne powtarzane trasy) idzie najszybciej i najoszczędniej.
Dla każdego odcinka i każdego przedziału startu: czas przejazdu (mediana), średnia prędkość i l/100 km. Wyłącznie lokalne archiwum tras;
bez sieci, bez budzenia auta, bez nowych uprawnień, zależności, zmiany schematu Room i zmian w `core/domain` (chyba że agent główny zdecyduje inaczej).

## 2. Ustalenia z kodu

**Powtarzane odcinki dziś** (`ui/SameRoutes.kt`, `sameRoutes`):
- Trasy sortowane po `(start, id)`; trasa trafia do **pierwszej** grupy, której wzorzec (pierwsza trasa grupy) ma start i koniec w promieniu
  `ROUTE_MATCH_M = 300 m` od jej startu i końca. Kierunek ma znaczenie (A→B ≠ B→A). Trasa bez czterech współrzędnych nie trafia do żadnej grupy.
- Odcinek od `MIN_ROUTE_TRIPS = 3` przejazdów; ekran „Styl jazdy” pokazuje do 10 (`ROUTE_SHOWN`), od najczęstszego.
- Dom–Praca: `ui/TripTypes.kt` (`classifyTrips`, `TripType.COMMUTE`) – trasa między „Dom” i „Praca” w obu kierunkach; miejsca z postojów (F17, `knownPlaces`).
  Dom to miejsce z największym łącznym postojem (≥ 5 postojów), Praca – inne miejsce z największą liczbą porannych postojów w dni robocze.

**Co wiemy o przejeździe** (`core/data/Models.kt` `TripRecord`, `core/domain/Models.kt` `Trip`):
- `start`, `end` (czas jazdy = `end − start`, pewny), `distanceKm`, `fuelL` (z komputera), `averageSpeed` (Toyota), `durationIdleS` (zawsze puste – §22),
  współrzędne start/koniec (tylko na ekranie i w pamięci, nigdy w statystykach ponad ekran), `hdcEvTimeS` (czas na prądzie, nie zawsze).
- Strefa czasu: godzina i dzień tygodnia liczone w strefie listy tras (`zone`), jak w §10 i §36.

**Co nie wiemy:** temperatury, korków (poza pośrednią miarą prędkości z §22), ruchu drogowego w danej chwili, czy kierowca wyjechał od razu po postoju.
Dlatego wynik to **korelacja w archiwum**, nie prognoza.

## 3. Decyzje proponowane

- **Odcinki:** te same grupy co F12 (`sameRoutes`, 300 m, kierunek, ≥ 3 przejazdy) – nic nowego nie jest wymyślane; „Dom–Praca” to odcinek, który
  `classifyTrips` oznacza jako `COMMUTE`, więc nie wymaga osobnej definicji miejsc. Pokazujemy odcinki z ≥ 5 przejazdami (`BEST_TIME_MIN_TRIPS = 5`),
  bo z 3 przejazdami najlepsza godzina to przypadek. Najwyżej 3 odcinki na ekranie, od najczęstszego.
- **Przedziały godzin startu** (lokalnie, dolna granica włącznie): 5–7, 7–9, 9–12, 12–15, 15–18, 18–21, 21–5. Szczyt poranny i popołudniowy
  dostaje własne pasma (7–9, 15–18), reszta dnia jest szersza. Nie więcej niż 7 pasm, żeby kolumna mieściła się na 360 dp.
- **Dni tygodnia:** dwie grupy, **dzień roboczy** (pon.–pt.) i **weekend** (sob.–ndz.), nie siedem dni – siedem dni dzieli zbyt mało przejazdów.
- **Komórka** (odcinek × przedział × dzień): pokazujemy wynik tylko przy **≥ 3 przejazdach** w komórce (`BEST_TIME_MIN_CELL = 3`); poniżej
  „za mało” z liczbą przejazdów, bez liczby do wniosku (jak komórka F24). Wniosek „najlepsza godzina” wymaga **≥ 3 komórek z danymi** w odcinku.
- **Miara czasu:** **mediana** czasu przejazdu (`end − start`), nie średnia – jeden korek nie przesuwa wyniku. Mediana parzystej liczby to średnia
  dwóch środkowych wartości, zaokrąglona do minuty (jak `medianConsumption`, ale w minutach całych).
- **Średnia prędkość** w komórce = Σ km / Σ godzin przejazdu (nie średnia średnich). **l/100 km** = Σ litrów / Σ km × 100 (0,01). Trasa z 0 l zostaje w sumach km i litrów (jak w F50), żeby jazda na prądzie nie zawyżała spalania; mediana l/100 km
  liczy się z przejazdów ≥ 2 km (jak anomalie F13), bo na krótkim odcinku wynik jest szumem.
- **Wniosek:** w odcinku „najlepsza godzina” to przedział z najniższą medianą czasu spośród komórek z ≥ 3 przejazdami; „najoszczędniejsza” to przedział
  z najniższym l/100 km. Gdy to ten sam przedział – jedno zdanie; gdy różne – dwa. Różnica poniżej 2 minut albo 0,3 l/100 km to „bez różnicy”.
- **Zakres czasu:** całe archiwum wybranego pojazdu (jak F10/F19). Sezon poza zakresem (jak w F50).

## 4. Prezentacja (bez ściany tekstu)

- Karta „Najlepsza godzina wyjazdu” w **„Statystykach”** (obok „Krótkich tras” F50, tak samo jak tam: `DashboardCard`, `MeterRow`, jedna krótka linia pomocnicza).
  Alternatywa – „Styl jazdy” (§19), gdzie są już odcinki; to odrzucone, bo „Styl jazdy” ma anomalie i wynik eco, a statystyki mają czasy i godziny.
- Dla każdego z ≤ 3 odcinków: nagłówek „Odcinek 2 · 9,4 km · 14 przejazdów” (bez współrzędnych, jak w §19), pod nim **siatka 7 przedziałów × 2 dni**
  jako wiersze `MeterRow`: nazwa przedziału („7–9”), po prawej mediana czasu („24 min”), pasek względem najszybszej komórki odcinka, jedna linia pomocnicza
  „3 przej. · 38 km/h · 6,20 l/100 km”. Liczby przez `TripsFormatting.number`/`count`, jednostki przy liczbach (C32).
- Wniosek na górze odcinka jedną linią: „Najszybciej: 5–7 rano w dni robocze (21 min)”; „bez różnicy” gdy różnica mała.
- Brak dat i godzin pojedynczych tras; brak współrzędnych (jak w F12).
- Gdy żaden odcinek nie ma komórki z ≥ 3 przejazdami: jedno zdanie „Za mało powtarzanych przejazdów o tej samej porze”.

## 5. Pliki

| Warstwa | Plik |
|---|---|
| `backend/app/domain/besttime.py` (nowy) + `tests/test_domain_besttime.py` | wzorzec: przedziały, mediana minut, komórki, wniosek (czysta logika na `Trip`/`TripCost`-podobnych danych; odcinek przychodzi gotowy jako lista przejazdów z id i grupą) |
| `android/core/.../core/domain/BestTimeOps.kt` + `BestTimeOpsTest.kt` | port, te same nazwy testów |
| `app/ui/BestTimeFormatting.kt` (nowy) | teksty wierszy i wniosku, bez Locale |
| `app/ui/StatsController.kt` | `StatsView.bestTimes` z `sameRoutes(trips)` (ta sama lista co „Styl jazdy”) |
| `app/ui/StatsScreen.kt` | karta „Najlepsza godzina wyjazdu” |
| `app/ui/Texts.kt` | tytuł, podpowiedź, nazwy przedziałów i dni, „za mało”, wniosek PL/EN |
| `ui/SameRoutes.kt`, `ui/TripTypes.kt`, `ui/DrivingStyle.kt` | bez zmian (tylko czytane) |
| docs | PRD F53, SPEC §53, SECURITY (jedno zdanie: liczone lokalnie z archiwum), CHANGELOG, README (lista funkcji), ROADMAP (pozycja 3 NEXT → DONE, po stronie koordynatora) |

## 6. Testy

- Przedziały: granice 5, 7, 9, 12, 15, 18, 21 (dolna włącznie), 4:59 i 21:00 w nocnym pasmie; dzień roboczy/weekend (sobota, niedziela, piątek 23:59).
- Mediana: nieparzysta i parzysta liczba przejazdów; jeden przejazd z korkiem nie zmienia wyniku (odporność); zaokrąglenie do minuty HALF_UP.
- Średnia prędkość = Σ km / Σ h, nie średnia średnich (dwie trasy: 1 km w 1 min i 9 km w 9 min → 60 km/h, nie 30).
- l/100 km z zerem litrów wchodzi do sum; mediana l/100 km pomija przejazdy poniżej 2 km.
- Progi: 2 przejazdy w komórce → „za mało”; 3 → liczba; odcinek z 2 komórkami z danymi → bez wniosku; 3 → wniosek.
- Wniosek: najszybszy i najoszczędniejszy ten sam przedział → jedno zdanie; różnica poniżej 2 min i 0,3 l/100 km → „bez różnicy”.
- Odcinek z przejazdami bez współrzędnych – pominięty (jak `sameRoutes`); odcinek jeżdżony w obu kierunkach – dwa odcinki.
- Strefa: przejazd o 23:30 UTC w Warszawie należy do dnia następnego (test z dwiema strefami).
- Teksty: PL/EN, odmiana „przejazd/przejazdy/przejazdów” (plPlural), `Lang` przywracany w @Before/@After; sweep zakazanych ciągów (VIN, token).
- Lustro Python ↔ Kotlin: te same wartości oczekiwane (złoty zestaw skopiowany ręcznie).

## 7. Prywatność

Czasy, dystanse, paliwo i dzień tygodnia z archiwum. Współrzędne służą tylko do dopasowania odcinka w pamięci (jak F12), nie trafiają do wyniku, CSV,
logów ani nazw. Karta nie pokazuje dat ani godzin pojedynczych tras, tylko zbiorcze liczby i przedziały. Bez sieci, bez `refresh_status()`.

## 8. Walidacja

- Python: `cd backend && .venv/bin/ruff format --check && .venv/bin/ruff check && .venv/bin/mypy && .venv/bin/python -m pytest -q`
- Android: `source ~/Android/env.sh && cd android && ./gradlew --no-daemon -q :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
  (uruchamia autor zmiany, po akceptacji planu).

## 9. Kolejność

1. `besttime.py` + testy (Python jako wzorzec) → ruff/mypy/pytest.
2. `BestTimeOps.kt` + testy Kotlin (te same wartości).
3. `StatsController`, `BestTimeFormatting`, `Texts`, karta w `StatsScreen`.
4. Docs: PRD F53, SPEC §53, SECURITY, CHANGELOG, README; ROADMAP przez koordynatora.
5. Walidacja, przegląd, atomowy commit.

## 10. Poza zakresem

- Prognoza korków, temperatury i pogody w tej karcie (F15/F23 – sieć i inna definicja); sezon/zima.
- Rekomendacja „wyjedź o X” jako powiadomienie; odcinki bez powtórzeń (poniżej 5 przejazdów).
- Zmiana `sameRoutes` (progi 300 m i 3 przejazdy zostają jak w F12).
- Zmiana schematu Room, `core/domain` poza nowym `BestTimeOps.kt`, Gradle i uprawnień.

## 11. Pytania otwarte do agenta głównego

1. Numer F53 i luka F52 – zostawić lukę czy użyć F52?
2. Próg 5 przejazdów na odcinek (propozycja) czy 3, jak w F12?
3. Dni: dwie grupy (robocze/weekend) – akceptujesz, czy siedem dni mimo mniejszej próby?
4. Czy karta ma być w „Statystykach” (propozycja), czy w „Stylu jazdy”?
5. Strefa 21–5 jako jedno pasmo nocne – wystarczy, czy rozbić na 21–24 i 0–5?
