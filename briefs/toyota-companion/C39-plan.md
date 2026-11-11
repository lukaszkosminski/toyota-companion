# C39 – plan: odznaki i serie przy celach eco (PRD F58, SPEC §58)

Status: **szkic do akceptacji koordynatora** (pozycja 11 w `docs/ROADMAP.md` NEXT). Bez kodu, docs i testów do akceptacji.
Data: 2026-11-11. Autor: agent 2.

## 1. Cel

Pokazać w karcie „Cel miesiąca” (SPEC §40), ile miesięcy z rzędu trzymasz się celu spalania albo celu jazdy na prądzie,
oraz kilka rekordów (najniższe spalanie miesiąca, najwyższy udział prądu, najdłuższa seria). Dwie, najwyżej trzy linie
tekstu, bez ikon-nagród i animacji. Wartości są **pochodne**: liczone z lokalnego archiwum tras i celów przy każdym
przeliczeniu, bez nowej tabeli i bez zapisu historii. Bez sieci, bez budzenia auta, bez nowych uprawnień i zależności.

## 2. Ustalenia z kodu (co już istnieje)

- **Nic o seriach ani odznakach nie istnieje.** `grep -rn -i "streak\|odznak\|badge\|seria"` w `docs/*.md`, `README.md`,
  `CHANGELOG.md` i w `android/*/src/main` trafia tylko w niezwiązane miejsca: „seria” jako seria tras o tej samej cenie
  (`docs/SPEC.md:278`), „odznaki wierszy” jako znaczniki w UI (`docs/SPEC.md:331`), wpis w `docs/HANDOFF.md:17` i pozycja
  ROADMAP 11. W `ui/EcoGoals.kt` i `ui/EcoGoalsCard.kt` nie ma żadnego licznika miesięcy.
- **Cele są już policzone miesiąc po miesiącu:** `ecoMonth(goals, trips, zone, month)` (`ui/EcoGoals.kt:73`) zwraca
  `EcoMonth` z `km`, `enoughDriving`, `consumption` i `ev` (`GoalResult`: cel, wartość, `met`). `previousMonth` (`:105`)
  już obsługuje przejście przez styczeń.
- **Próg 50 km:** `ECO_MIN_MONTH_KM = 50.0` (`ui/EcoGoals.kt:19`). Poniżej nic nie jest oceniane (`met = null`).
- **Tylko dwa miesiące w stanie:** `ecoProgress` (`:112`) zwraca bieżący i poprzedni miesiąc. Historia do serii nie jest
  nigdzie zbierana.
- **Cele nie mają historii:** zapis przez `settingsStore.saveEcoGoals` (`settings/SettingsStore.kt`, klucze
  `eco_goal_consumption` i `eco_goal_ev_pct`, `:353–354`) nadpisuje wartość. Nie ma daty zmiany.
- **Źródło tras:** `EcoGoalsController` dostaje `readTrips: suspend () -> List<StoredTrip>` (`ui/EcoGoals.kt:191–195`),
  w `AppContainer.kt:324` to `syncEngine.trips()`. Cała lista tras jest w pamięci, więc przeliczenie historii to zwykła pętla.
- **Miesiąc:** `MonthKey` (`core`, data class z `year` i `month`), strefa z `zone()`, miesiąc = data startu trasy (jak §40).
- **Istniejąca podobna logika:** miesięczny wynik stylu jazdy (`ui/DrivingStyle.kt:33–55`, F12/§19) ma już grupowanie tras
  po miesiącu; można wydzielić wspólną funkcję zamiast drugiej pętli.

## 3. Decyzje (propozycje, do akceptacji)

1. **Brak tabeli i brak zapisu wyników.** Serie i rekordy są funkcją `(cele, trasy, strefa, dziś)`, jak `ecoProgress`.
   Uzasadnienie: zgodnie z zasadą F39 nic nie jest przechowywane, a archiwum i eksport się nie zmieniają.
2. **Seria = kolejne pełne miesiące z celem osiągniętym, kończące się na ostatnim pełnym miesiącu** (bieżący miesiąc
   nie liczy się do serii, dopóki się nie skończy; jego stan pokazuje już istniejąca linia). Cel spalania i cel prądu
   liczone osobno.
3. **Miesiąc poniżej 50 km:** propozycja **pomija** (nie zrywa serii, nie dodaje do niej), z dopiskiem „pominięte: za mało
   jazdy”. Alternatywa „zrywa” jest ostrzejsza, ale jeden krótki miesiąc (np. zimą) kasowałby wielomiesięczną serię.
4. **Zmiana celu w trakcie:** historia nie jest zapisana, więc po zmianie wartości serie przeliczają się od nowa wg
   nowego celu. Propozycja: dodać jeden klucz `eco_goal_changed_on` (miesiąc, od którego liczy się seria; zapis w
   `saveEcoGoals`, tylko gdy wartość celu się zmieniła). Bez tego seria pokazuje wynik, którego nikt nie osiągnął
   w tamtym czasie. To jedyna zmiana w ustawieniach i nie dotyka archiwum (cele są poza eksportem, §40).
5. **Rekordy:** najniższe spalanie miesiąca i najwyższy udział prądu liczone tylko z miesięcy z co najmniej 50 km
   i bez bieżącego miesiąca. Remis: wcześniejszy miesiąc. Rekordy nie zależą od celu (pokazują się także bez celu),
   ale karta pojawia się tylko przy celu, jak dziś.
6. **Najdłuższa seria** w historii (nie tylko bieżąca), liczona z tych samych zasad co w pkt 2–3.
7. **Prezentacja:** w „Cel miesiąca” pod linią „Poprzedni miesiąc”, np.
   - `Seria: 4 miesiące z celem spalania (najdłuższa: 6)`
   - `Rekord: najniższe spalanie 3,92 l/100 km (wrzesień)`
   Bez ikon, bez gratulacji, bez „odznak”. Nazwa F58 w UI: „Seria”, nie „Odznaka”.
8. **Widżet (§32) bez zmian.** Dodanie serii do widżetu to osobna decyzja (miejsce na jedną linię już jest zajęte).

## 4. Proponowany interfejs

Core, w `EcoStreaks.kt` (nowy plik, `core/domain` lub `ui` – do wyboru przy implementacji; lustro w Pythonie
`backend/app/domain/eco_streaks.py`, te same nazwy testów):

- `data class EcoStreak(val months: Int, val startMonth: MonthKey?, val endMonth: MonthKey?)`
- `data class EcoRecords(val lowestConsumption: EcoRecord?, val highestEvShare: EcoRecord?, val longestStreak: EcoStreak?)`
- `fun ecoStreaks(goals: EcoGoals, trips: List<StoredTrip>, zone: ZoneId, today: LocalDate, changedOn: MonthKey?): EcoStreaks`
  (jedno przejście po trasach: grupowanie po miesiącu, potem `ecoMonth` dla każdego miesiąca od `changedOn` lub od pierwszej trasy).
- `fun ecoStreakLine(s: EcoStreaks): String?` i `fun ecoRecordLine(r: EcoRecords): String?` – `null` bez danych (jak
  `ecoConsumptionLine`).

## 5. Pliki (przewidywane)

- Nowe: `core/domain/EcoStreaks.kt`, `core/test/.../EcoStreaksTest.kt`, `backend/app/domain/eco_streaks.py`,
  `backend/tests/test_domain_eco_streaks.py`.
- Zmiana: `ui/EcoGoals.kt` (stan i linie karty), `ui/EcoGoalsCard.kt` (dwie linie), `settings/SettingsStore.kt`
  (klucz `eco_goal_changed_on`, tylko jeśli decyzja 4 przyjęta), `ui/Texts.kt` (PL/EN, pointwise),
  `docs/PRD.md`, `docs/SPEC.md` (§58), `CHANGELOG.md`, `README.md`, `docs/ROADMAP.md` (11 z NEXT do DONE).
- Bez zmian: Room (brak schematu v9), archiwum (`ARCHIVE_VERSION`), eksport CSV, widżet, Gradle.

## 6. Testy (nazwy takie same w Kotlinie i Pythonie)

- seria liczy kolejne miesiące z osiągniętym celem i kończy się na ostatnim pełnym miesiącu;
- bieżący miesiąc nie wchodzi do serii;
- miesiąc poniżej 50 km jest pomijany, nie zrywa serii;
- zmiana celu z `changedOn` ogranicza serię do miesięcy od tej daty;
- cel prądu liczony niezależnie od celu spalania;
- rekord spalania: najniższy z miesięcy ≥ 50 km, remis → wcześniejszy, bez bieżącego miesiąca;
- brak celu → brak linii; brak tras → brak linii (`null`);
- przejście przez rok (styczeń) i strefa czasowa (dzień graniczny jak w `ecoMonth`).

## 7. Prywatność i walidacja

- Bez VIN, GPS i nazw miejsc; liczby tylko w ekranie, nie w logach, powiadomieniach ani widżecie.
- Walidacja: `ruff`, `mypy`, `pytest`; Gradle `:core:test :app:testDebugUnitTest`; liczby testów z raportu.

## 8. Pytania otwarte

1. Pominięcie czy przerwanie serii przy miesiącu poniżej 50 km (decyzja 3, rekomendacja: pominięcie)?
2. Czy zapisywać datę zmiany celu (decyzja 4, rekomendacja: tak, jeden klucz)? Bez tego serie po zmianie celu są mylące.
3. Czy seria i rekord mają wejść do widżetu, czy zostają w karcie?
4. Czy „najdłuższa seria” ma być tylko w karcie, a nie jako osobna pozycja?
