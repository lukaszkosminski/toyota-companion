# SPEC – tankowania i koszt tras

## Model domenowy (czysty Python, bez I/O, bez pytoyoda)
Katalog: `backend/app/domain/`. Tylko stdlib + dataclasses. Pieniądze i litry jako `decimal.Decimal`.
Port w Kotlinie: `android/core/src/main/kotlin/pl/lk/toyotacompanion/core/domain/` (te same nazwy testów).

### Typy
- `FuelReading(ts: datetime, level_pct: int, odometer_km: float, battery_level_pct: int | None = None, distance_to_empty_km: float | None = None)`
- `Refuel(id: str, ts: datetime, odometer_km: float, level_before: int, level_after: int, liters: Decimal | None, price_per_liter: Decimal | None, total_cost: Decimal | None, status: "pending" | "confirmed" | "dismissed", liters_source: "receipt" | "estimate" | "derived" | None = None, filled_to_full: bool | None = None)`
- `Trip(id: str, start: datetime, end: datetime, distance_km: float, fuel_l: Decimal, ev_distance_km: float | None)`
- `TripCost(trip_id: str, cost_pln: Decimal | None, price_per_liter: Decimal | None, refuel_id: str | None, source: "refuel" | "default" | "none")`
- `DEFAULT_TANK_CAPACITY_L = Decimal("43")` – pojemność baku (PRD §7, ADR-005).

`liters_source` mówi, skąd wzięły się litry: `receipt` (z paragonu), `derived` (wyliczone z kwoty i ceny),
`estimate` (szacunek z czujnika – nie weryfikuje komputera auta, F2a). `filled_to_full` = tankowanie do pełna.

## Funkcje

### §1 `detect_refuel(prev: FuelReading, cur: FuelReading, min_jump_pct: int = 8) -> Refuel | None`
- zwraca `Refuel(status="pending")` gdy `cur.level_pct - prev.level_pct >= min_jump_pct`, inaczej `None`
- `ts` = `cur.ts`, `odometer_km` = `cur.odometer_km`
- `id` deterministyczne: `refuel-<UTC ISO-8601 z Z>`, 0/3/6 cyfr ułamkowych (jak `Instant.toString()`
  w Kotlinie); naive `ts` traktowane jako UTC
- `cur.ts <= prev.ts` -> `ValueError`

### §2 `confirm_refuel(refuel, *, price_per_liter=None, liters=None, total_cost=None, liters_source=None, filled_to_full=None) -> Refuel`
- dozwolone kombinacje: (price), (liters+total_cost), (price+liters), (price+total_cost). Pozostałe -> `ValueError`.
- brakującą wielkość wylicz (price = total/liters, total = price*liters, liters = total/price); ceny zaokrągl do 0.001, kwoty do 0.01, litry do 0.001 (ROUND_HALF_UP)
- wartości <= 0 -> `ValueError`; zwraca nowy obiekt z `status="confirmed"` (immutability: `dataclasses.replace`)
- `liters_source`: gdy podane litry -> `receipt`, gdy litry wyliczone z kwoty i ceny -> `derived`;
  jawne `liters_source="estimate"` jest zachowane. `liters_source`/`filled_to_full` bez litrów -> `ValueError`
- `filled_to_full` jest przenoszone (domyślnie z wejściowego obiektu)
- `estimate` opisuje wyłącznie litry przyjęte z szacunku §2a, czyli kombinację (cena + litry). Domena zachowuje
  jawne `estimate` także przy litrach wyliczonych z ceny i kwoty, więc przy (cena + kwota) UI podaje `null`,
  a domena zapisze `derived`.
- przy `liters_source="estimate"` kwota wyliczona z ceny i litrów jest **szacunkowa** – mówi o tym samo źródło
  litrów, bez osobnej kolumny (F2a). Porównanie spalania „komputer vs faktyczne” (F9, §15) bierze
  litry z paragonu: `liters_source="receipt"` albo `"derived"` (wyliczone z kwoty i ceny z paragonu – decyzja
  właściciela 2026-10-31, D7); szacunek z czujnika (`"estimate"`) nie może weryfikować komputera auta (PRD F2a).

### §2a `estimate_refuel_liters(refuel, capacity_l=DEFAULT_TANK_CAPACITY_L) -> Decimal | None`
(PRD F2a) Szacunek litrów z czujnika poziomu paliwa, który aplikacja proponuje użytkownikowi.
- `(level_after − level_before) % × capacity_l`, zaokrąglone do 0.001 (ROUND_HALF_UP) – ta sama arytmetyka, której
  §6 używa, gdy podano samą cenę; dlatego przyjęty szacunek (`liters=szacunek, liters_source="estimate"`) daje tę
  samą cenę w baku co sama cena (test `accepted_estimate_matches_implicit_tank_price`).
- `None` gdy: `level_before` poza 0..100, `level_after` poza 1..100, brak wzrostu poziomu albo wynik zaokrąglony
  do 0 (domena odrzuca litry „rounds to 0”); `capacity_l <= 0` -> `ValueError`.
- Pojemność baku stała (`DEFAULT_TANK_CAPACITY_L`, ADR-005) – bez ustawienia użytkownika.
- Krok czujnika to 1 % (≈ 0,43 l), a wskazanie bywa nieliniowe przy pełnym baku – stąd „ok.” w UI.

### §3 `dismiss_refuel(refuel) -> Refuel`
- `status="dismissed"`

### §4 `compute_trip_cost(trip, refuels, default_price=None, capacity_l=DEFAULT_TANK_CAPACITY_L) -> TripCost`
- tankowania `status="confirmed"` do `trip.start` włącznie, w kolejności `(ts, id)` (deterministycznie)
- każde potwierdzone tankowanie z ceną aktualizuje cenę paliwa w baku (§6);
  ostatnia taka cena jest użyta do kosztu
- dodatnia `default_price` jest ceną paliwa w baku **przed pierwszym** potwierdzonym tankowaniem (poprawka ADR-005
  z 2026-11-08): pierwsze tankowanie miesza się z nią w §6 jak z każdym wcześniejszym tankowaniem; bez ceny domyślnej
  (albo przy `default_price <= 0`) pierwsze tankowanie ma własną cenę; `default_price <= 0` to brak ceny także dla tras
  bez tankowania (`source="none"`, nigdy 0,00 zł)
- koszt = `trip.fuel_l * cena` zaokrąglone do 0.01 (ROUND_HALF_UP), `source="refuel"`, `refuel_id` = użyte tankowanie
- brak takiego tankowania, ale jest `default_price` -> `source="default"`
- w ostateczności `cost_pln=None, price_per_liter=None, refuel_id=None, source="none"`
- tankowania `pending`/`dismissed` są ignorowane; tankowanie z samą ceną używa szacunku litrów z czujnika (§6)

### §5 `recompute_costs(trips, refuels, default_price=None, capacity_l=DEFAULT_TANK_CAPACITY_L) -> list[TripCost]`
- w kolejności wejścia (retroaktywne przeliczenie po potwierdzeniu ceny)

### §6 `tank_price_after_refuel(refuel, previous_tank_price=None, capacity_l=DEFAULT_TANK_CAPACITY_L) -> Decimal | None`
(ADR-005)
- `liters_before = level_before % × capacity_l`
- `added` = `refuel.liters` (dowolne `liters_source`, także `estimate`); gdy brak litrów (podana tylko cena):
  `added = estimate_refuel_liters(refuel, capacity_l)` (§2a)
- `tank_price = (liters_before × previous_tank_price + added × refuel.price_per_liter) / (liters_before + added)`
  – wynik zawsze między starą a nową ceną
- `refuels` to tankowania jednego pojazdu (filtr po VIN robi wołający, `SyncEngine`); `level_before` pochodzi z czujnika Toyoty
  i suma litrów nie jest przycinana do pojemności baku
- brak poprzedniej ceny (pierwsze tankowanie bez ceny domyślnej, §4), `previous_tank_price <= 0` albo pusty bak przed
  tankowaniem (`level_before = 0`) -> własna cena `refuel.price_per_liter`
- przykład (telefon właściciela, 08.11.2026): cena domyślna 6,30, tankowanie 67 % → 100 % po 5,92 bez litrów
  (szacunek 14,19 l): (28,81 × 6,30 + 14,19 × 5,92) / 43 = 6,175 zł/l, a nie 5,92
- `None` gdy: `status != "confirmed"`, brak ceny, `level_before` poza 0..100, `level_after` poza 1..100,
  albo brak litrów i brak wzrostu poziomu
- wynik zaokrąglony do 0.001 (ROUND_HALF_UP); `capacity_l <= 0` -> `ValueError`

### Przykład kontrolny
Trasa 14,6 km, `fuel_l=0.60`.
- pierwsze tankowanie 6,19 zł/l -> koszt 0,60 × 6,190 = 3,71 zł
- po drugim tankowaniu (poziom 20% → 80%, 25,8 l po 6,19 zł/l, poprzednia cena 5,990):
  cena w baku = (8,6 × 5,99 + 25,8 × 6,19) / (8,6 + 25,8) = 6,140 zł/l -> koszt 0,60 × 6,140 = 3,68 zł
- to samo tankowanie, ale paragon 30 l (czujnik pokazał 25,8 l): (8,6 × 5,99 + 30 × 6,19) / 38,6 = 6,145 zł/l

## §7 Warstwy poza domeną
- `android/core/.../data` + `.../sync` – repozytoria i synchronizacja (§8, czysty JVM)
- `android/app` – Room (implementacje repozytoriów z §8.2), WorkManager, UI Compose, backup (F6–F8)
- `backend/` pozostaje wykonywalną specyfikacją domeny; nie ma już FastAPI ani klienta pytoyoda (ADR-006)

## §8 Synchronizacja (`android/core/.../sync/SyncEngine`)

### §8.1 Źródło danych – `ToyotaGateway`
- `vehicles()`, `telemetry(vin)` (jeden bieżący odczyt), `trips(vin, from, to)` (wszystkie strony)
- bez `refresh_status` i bez komend (drenują akumulator 12 V)

### §8.2 Repozytoria
Interfejsy w `core/data`: trasy (`TripRecord` = `Trip` + pola archiwum), tankowania, odczyty paliwa,
koszty, pojazdy, stan synchronizacji. Każda implementacja (w pamięci w testach, Room w `app`) musi przejść
`RepositoryContractTest`: deduplikacja po `id`, rozdzielenie po VIN, kolejność po czasie,
`firstSeen` pojazdu nie zmienia się przy aktualizacji.
Scenariusze kontraktu to klasa `RepositoryContract` w `core/testFixtures` (bez frameworka testowego, asercje `kotlin.test`);
uruchamiają ją test JUnit 5 w `core` (pamięć) i test Robolectric w `app` (Room).

Zapis typów w Room (`android/app/.../data/db`, `AppDatabase` v1, schemat w `android/app/schemas`):
- `BigDecimal` → TEXT przez `toPlainString()` i `BigDecimal(String)`: skala zachowana (`0.600` ≠ `0.6`), bez notacji wykładniczej;
  pieniądze i litry nie są liczone w SQL (tylko zapis i odczyt, sortowanie po czasie);
- `Instant` → INTEGER, epoch w milisekundach (precyzja poniżej milisekundy jest tracona);
- enumy → TEXT (`name`); `Boolean` → INTEGER 0/1;
- klucze: trasy `id`; tankowania `id`; odczyty paliwa `(vin, ts)`; koszty `trip_id`; pojazdy `vin`; `sync_state` jeden wiersz (`id = 1`);
- zapisy wielowierszowe (`upsertAll` tras, `saveAll` kosztów, `upsert` pojazdu, `update` tankowania) w jednej transakcji;
- brak `fallbackToDestructiveMigration`: zmiana schematu wymaga jawnej migracji.

### §8.3 Kolejność kroków `fullSync()`
1. Brak danych logowania -> `Failed(NO_CREDENTIALS)`.
2. Pojazdy: zapis wszystkich; wybrany VIN = zapisany (jeśli nadal na koncie) albo jedyny; kilka i żaden
   nie wybrany -> `Failed(NO_VEHICLE)`. Nic nie jest usuwane. Aplikacja obsługuje **jedno konto z jednym autem**
   (ADR-019): UI nie ma wyboru pojazdu, więc konto z kilkoma pojazdami (i bez wcześniej zapisanego VIN) kończy się
   trwałym `NO_VEHICLE`, a komunikat mówi to wprost.
3. Telemetria: pierwszy odczyt tylko zapisywany; nowszy niż ostatni zapisany -> zapis + `detect_refuel`
   (§1) względem ostatniego zapisanego; nie nowszy (Toyota trzyma stary odczyt, gdy auto śpi) -> nic.
   Błąd telemetrii nie przerywa synchronizacji (`telemetryFailed = true`).
4. Trasy: pierwsza synchronizacja od `dziś − 365 dni`, kolejne od `znacznik − 2 dni` (spóźnione trasy)
   do dziś (UTC); zapis z deduplikacją po `id`.
5. Koszty: `recompute_costs` (§5) dla wszystkich tras pojazdu.
6. Sukces -> znacznik = dziś, błąd wyczyszczony.

### §8.4 Błędy
- Rodzaje: `LOGIN`, `NO_CREDENTIALS`, `NO_VEHICLE` (bez ponawiania, UI prosi o działanie),
  `API`, `NETWORK`, `DATA`, `STORAGE` (ponawiane przez WorkManager).
- HTTP 401 z API danych (token odrzucony przed swoim terminem): `ToyotaController.request` raz odnawia token
  (refresh, a gdy się nie uda – pełne logowanie) i powtarza żądanie. Nieudane odnowienie to `LOGIN` (UI prosi
  o ponowne logowanie); drugie 401 po odnowieniu to `API`, bez kolejnych prób. Odejście od pytoyody, która 401 nie obsługuje.
- `STORAGE` – lokalnego archiwum nie dało się odczytać ani zapisać (Room, pełny dysk). Błędy gatewaya są
  klasyfikowane tam, gdzie powstają (`classify`); wszystko, co mimo to wyjdzie z kroków synchronizacji, łapie
  `guarded` i zamienia na `STORAGE`. Komunikat zawiera tylko nazwę klasy wyjątku, nigdy jego treść. W kroku
  telemetrii miękko (`telemetryFailed`) kończy się wyłącznie samo żądanie do Toyoty; błąd zapisu odczytu albo
  wykrytego tankowania to `STORAGE`, żeby tankowanie nie przepadło po cichu za wynikiem `Ok`. W kroku kosztów
  `IllegalArgumentException` domeny to `DATA`, każdy inny wyjątek – `STORAGE`.
- Zapis błędu w stanie (`failAt`) jest odporny na zepsute archiwum: nieczytelny stan zastępuje `SyncState()`,
  a nieudany zapis jest pomijany – wynik `Failed` i tak wraca. Nieznana nazwa rodzaju w bazie (np. po powrocie
  do starszej wersji aplikacji) czyta się jako brak rodzaju, bez wyjątku.
- Błąd nigdy nie usuwa ani nie cofa danych; kroki wykonane przed błędem zostają zapisane;
  znacznik tras przesuwa się tylko po pełnym sukcesie. `isStale` = brak sukcesu albo błąd po ostatnim sukcesie.
- Komunikat zapisany w stanie przechodzi przez `ToyotaSecrets.sanitize` (bez VIN, tokenów, hasła).

### §8.5 Wynik i współbieżność
- Wynik jako `SyncOutcome` (`Ok`, `Failed`, `Skipped`), nie wyjątki (poza anulowaniem korutyny) – dotyczy
  `fullSync()` i `recomputeCosts()`; `confirmRefuel`, `dismissRefuel` i `recomputeCostsNow` przekazują wyjątek
  bazy wołającemu, a kontrolery UI zamieniają go na własny komunikat (§11.3, §12.3).
- Jedna synchronizacja naraz (mutex); równoległe wywołanie -> `Skipped`.
- `recomputeCosts()` bez sieci – po potwierdzeniu ceny tankowania (F4).
- Dług: koszty wszystkich tras są przeliczane przy każdej synchronizacji (O(trasy × tankowania));
  do optymalizacji, gdy archiwum urośnie.

## §9 Logowanie i status (`android/core/.../sync`, `android/app/.../ui`)

Ekran logowania MyToyota i ekran statusu synchronizacji. Wymaganie właściciela produktu
(ROADMAP NOW, ADR-007); kryteria poniżej są warunkiem akceptacji zmiany.

### §9.1 Weryfikacja danych w `core`
- `ToyotaGatewayFactory.verifyLogin(username, password): LoginCheck` – tylko logowanie
  (`ToyotaController.login()`), bez `refresh_status` i bez endpointów danych (AGENTS.md).
- Magazyn tokenów zawsze świeży (`InMemoryTokenStore`): ważny token z poprzedniej sesji
  nie może „zatwierdzić” błędnego hasła.
- Wynik jako `LoginCheck` (`Ok(token)`, `Failed(LoginFailure)`), nie wyjątki (§8.5).
  `Ok.token` pozwala zapisać token dopiero po sukcesie; `toString` nie zawiera tokenu.
- Klasyfikacja `classifyLoginFailure`: `ToyotaLoginException` (w tym `ToyotaInvalidUsernameException`)
  -> `INVALID_CREDENTIALS`, `ToyotaNetworkException` -> `NETWORK`, reszta (`API`, `DATA`, nieznane)
  -> `SERVICE`. `CancellationException` jest rzucana dalej.
  Ograniczenie: `ToyotaController` (jak pytoyoda) zgłasza `ToyotaLoginException` przy każdym statusie ≠ 200
  z endpointów logowania AMI, także 5xx – wtedy też wynik to `INVALID_CREDENTIALS`. Dlatego komunikat
  brzmi „Toyota odrzuciła logowanie – sprawdź login i hasło”, a nie „hasło jest błędne”.

### §9.2 Ekran logowania (kryteria akceptacji)
- Start: dane logowania w `CredentialStore` -> ekran statusu; brak -> ekran logowania.
- Login/hasło: pola oddzielone, hasło z maską (`PasswordVisualTransformation`), keyboard `Password`.
  Hasło trzymane tylko w pamięci ekranu (`remember`, nie `rememberSaveable`) – nie trafia
  do Bundle ani do stanu UI; żadna wartość stanu UI nie zawiera hasła (SECURITY.md).
- Pusty login (po `trim`) albo puste hasło -> komunikat „uzupełnij pola”, weryfikator NIE wołany.
- `Failed(INVALID_CREDENTIALS)` -> „Toyota odrzuciła logowanie – sprawdź login i hasło”, bez zapisu czegokolwiek.
- `Failed(NETWORK)` -> „Brak internetu…”, bez zapisu; `Failed(SERVICE)` -> „Serwis Toyoty
  niedostępny…”, bez zapisu. Login jest przycinany, hasło nie.
- Sukces: `CredentialStore.save`, zapis tokenu, `SyncScheduler.onLogin` (praca okresowa
  `toyota-sync` + jednorazowa `toyota-sync-now`), przejście na ekran statusu.
- Kliknięcie podczas trwającej weryfikacji jest ignorowane (jedna weryfikacja na kliknięcia).
- `start()` jest jednorazowe: kolejne wywołanie (obrót ekranu) nie zmienia stanu, dopóki nie jest `Loading` – nie odblokowuje formularza w trakcie weryfikacji ani przycisku w trakcie synchronizacji. Dopóki stan to `Loading`, formularz jest zablokowany, a `login` nie jest wołany.
- Wynik weryfikacji jest stosowany tylko, gdy ekran nadal na niego czeka (`LoggedOut(busy = true)`) – po wylogowaniu nic nie jest zapisywane ani planowane.
- Wygląd (C1b): `ToyotaTheme` (Material 3, jasny/ciemny wg systemu, dynamiczny kolor na Android 12+,
  poniżej własna paleta z czerwienią Toyoty), edge-to-edge (`enableEdgeToEdge` + przezroczyste paski
  w `res/values{,-night}/themes.xml`), treść w `Scaffold` (padding pasków systemowych) z pionowym
  scrollem i `imePadding`; formularz ma max 480 dp i jest wyśrodkowany; pola `OutlinedTextField`
  z typem klawiatury Email (login) / Password (hasło) oraz akcja IME `Done` na haśle wysyła formularz;
  pole pokazania/ukrycia hasła to przycisk tekstowy (bez ikon); autouzupełnianie przez
  `semantics { contentType = ContentType.Username / Password }`; błąd logowania w karcie `errorContainer`.
- Odmowa zapisu w Keystore po udanej weryfikacji (np. `ProviderException`) nie rozbija aplikacji: wyczyszczenie tego, co udało się zapisać (best-effort), brak planowania pracy, komunikat o niedostępnym serwisie.

### §9.3 Ekran statusu (kryteria akceptacji)
- Linia „Ostatnia synchronizacja” (C32 U20): krótki format `10.11 19:04` jak w reszcie aplikacji; rok dopisany tylko wtedy, gdy to nie bieżący rok (`Texts.lastSuccess`).
- Pokazuje z `SyncEngine.status()`: liczba tras, poziom paliwa, przebieg (km z najnowszego odczytu telemetrii), oczekujące tankowania, liczba odczytów
  (linia diagnostyczna „Telemetria: N odczytów”), aktualne/nieaktualne (`isFresh`), data ostatniego sukcesu,
  rodzaj ostatniego błędu (tylko `SyncErrorKind` przełożony na polski tekst; `lastErrorMessage` NIE jest pokazywany).
  Poziom paliwa to „paliwo teraz” z prognozy (§20.2): ostatni odczyt × pojemność minus paliwo tras po nim;
  bez odczytu kafelek pokazuje „–”.
  `NO_VEHICLE`: „Na koncie Toyoty nie ma pojazdu albo jest ich kilka – aplikacja obsługuje jedno konto z jednym autem.”
  (EN: „The Toyota account has no vehicle or has several – the app supports one account with one car.”) – bez obietnicy wyboru pojazdu (ADR-019).
- „Synchronizuj teraz” – synchronizacja w procesie (`SyncRunner`), przycisk zablokowany
  na czas pracy; wynik `Failed` pokazuje rodzaj błędu, `Ok`/`Skipped` go kasuje; po pracy
  status jest odświeżany.
- Gdy `status.state.needsLogin` -> komunikat „Zaloguj się ponownie” obok przycisku Wyloguj.
- Odświeżanie: `PullToRefreshBox` (gest przeciągnięcia w dół) woła `SessionController.syncNow()` – tę samą
  normalną synchronizację (`SyncRunner`) co przycisk „Synchronizuj teraz”, więc pociągnięcie naprawdę pobiera
  dane z Toyoty, nadal bez `refresh_status()` (SPEC §9 wstęp). Ignorowane, gdy synchronizacja trwa (spinner gestu
  to `state.syncing`); przycisku „Odśwież” nie ma. Sam ODCZYT lokalnej bazy (`SyncEngine.status()`) idzie
  automatycznie po każdym przebiegu `SyncRunner` (także `SyncWorker` w tle, gdy ekran jest otwarty) i przy powrocie
  aplikacji na pierwszy plan (`MainActivity.onResume`), więc osobny przycisk nie jest potrzebny.
- Wygląd (C1b): `Scaffold` z `TopAppBar` (tytuł + „Wyloguj” jako przycisk tekstowy w `actions`),
  liczniki w jednej karcie, dwa rzędy po dwa (Trasy / Poziom paliwa / Przebieg / Oczekujące tankowania – liczba + podpis),
  chip stanu (`aktualne`/`nieaktualne`/`brak synchronizacji`/`wymaga logowania`), czas ostatniego
  sukcesu, linia „Telemetria: N odczytów” i `HorizontalDivider`; pozycja przewinięcia ekranu należy do `AppRoot` (`ScrollState`), więc po wejściu w kafelek
  i powrocie ekran wraca na to samo miejsce, a wylogowanie podbija „epokę” i kolejne logowanie zaczyna od góry (C32 U6); „Wyloguj” najpierw pokazuje `AlertDialog` z potwierdzeniem
  (archiwum Room zostaje), dopiero „Wyloguj” w oknie wykonuje wylogowanie.
- Wynik „Synchronizuj teraz” jest stosowany tylko, gdy ekran nadal pokazuje status – spóźniony wynik po „Wyloguj” nie przywraca ekranu statusu.
- „Synchronizuj teraz” nigdy nie zostaje w stanie „synchronizuję”: wyjątek z `SyncRunner` kończy się jak błąd
  `STORAGE`. Odczyt zapisanego logowania przy starcie, który rzuci, to „niezalogowany”, a „Wyloguj” wykonuje każdy
  krok na miarę możliwości i zawsze kończy na ekranie logowania. Ostatnią siatką jest `CoroutineExceptionHandler`
  na `uiScope` – zapisuje w logu tylko nazwę klasy wyjątku (docs/SECURITY.md), nigdy nie wywraca aplikacji.
- „Wyloguj”: wyczyszczenie tokena (po loginie), potem `CredentialStore.clear()`, anulowanie
  prac `toyota-sync` i `toyota-sync-now`. Archiwum Room NIE jest usuwane (F5, ADR-006) –
  ponowne logowanie dołącza dane. Stan UI -> ekran logowania.

## §10 Lista tras z kosztem (`SyncEngine.trips`, `android/app/.../ui`)

Ekran archiwum tras (PRD F3, F5): trasy bieżącego pojazdu pogrupowane na miesiące, każda z kosztem z §4/§5.
Wymaganie właściciela produktu (ROADMAP NOW). Czytany jest wyłącznie lokalny magazyn – ekran nie woła żadnego
API Toyoty, w tym `refresh_status()` (§9 wstęp, AGENTS.md).

### §10.1 Odczyt w `core` – `SyncEngine.trips(): List<StoredTrip>`
- Trasy pojazdu z `state.get().vin`; **brak VIN -> pusta lista** (nie błąd) – zmiana auta zachowuje archiwum
  starego VIN-u (§8.3), ale lista pokazuje tylko wybrany pojazd.
- Kolejność: najnowszy `trip.start` pierwszy (realizacja: `trips.all(vin)` od najstarszej + `asReversed()`).
- Koszt każdej trasy z `CostRepository`, czytany **jednym wywołaniem** `costs.all()` i zbudowany w mapę
  `tripId -> TripCost`. Zakazane jest zapytanie o koszt na każdą trasę (archiwum > 500 tras).
  Mapa zawiera też koszty innych pojazdów – nigdy nie są użyte, bo klucz to `trip.id` z bieżącego VIN-u.
- `cost == null` tylko gdy dla trasy w ogóle nie ma wiersza kosztu — `trips()` zwraca zapisany
  wiersz bez zmian (`costPln == null` czy `source == NONE` przechodzą tak, jak są zapisane).
  O napisie „brak ceny” decyduje warstwa UI przez `hasNoCost` (§10.2), a nie `core`.
- Taki sam charakter odczytu jak `status()` (§8.5): bez sieci, bez `mutex` synchronizacji, bez `SyncOutcome`
  (wyjątek idzie do warstwy UI). Skutek braku blokady: lista odczytana w trakcie synchronizacji może nie mieć
  jeszcze kosztów przeliczonych w kroku 5 (§8.3) – UI pokazuje je po następnym wejściu na ekran.
- Jedna wspólna kopia listy (C22 Q6): po synchronizacji widżet, prognoza, karta spalania, pogoda i otwarte ekrany dostają tę samą listę,
  czytaną z bazy raz; czytelnicy, którzy przyjdą razem, czekają na jeden odczyt. Kopia jest porzucana przed i po każdym zapisie tras albo kosztów
  w `SyncEngine` (synchronizacja, import archiwum, przeliczenie kosztów po cenie, poprawce albo zmianie ceny domyślnej, wybór pojazdu) – także gdy zapis
  się nie uda – i nie jest zachowywana, gdy zapis zdarzył się w trakcie odczytu. Jedynym zapisującym trasy i koszty jest `SyncEngine`.
- Bez zmian w schemacie Room (brak migracji) i bez nowych metod w interfejsach repozytoriów.

### §10.2 Grupowanie i formatowanie (warstwa `app`, funkcje czyste, bez Compose)
- Miesiąc liczony z `trip.start` w **przekazanej strefie** (`ZoneId` jako parametr – nie `ZoneId.systemDefault()`
  w ciele funkcji); grupy od najnowszego miesiąca; klucz miesiąca to rok+miesiąc (archiwum wieloletnie).
- Dla grupy: suma km, suma litrów, suma kosztu tras z kosztem (`null`, gdy żadna trasa nie ma kosztu),
  liczba tras bez kosztu.
- Formatowanie polskie, **niezależne od `Locale.getDefault()`** – bez `String.format("%.2f")` i bez `%02d`:
  `setScale(n, HALF_UP).toPlainString()` z przecinkiem w miejscu kropki.
  koszt `3,68 zł` (2 miejsca), cena `6,14 zł/l`, paliwo `0,60 l`, dystans `12,3 km` (1 miejsce),
  start `29.10 14:05` (w strefie jako parametr).
- Nazwa miesiąca w mianowniku (`październik 2026`) z własnej tablicy 12 nazw – bez `TextStyle` i bez `Locale`.
- `costPln == null` albo `source == NONE` -> „brak ceny”; `source == DEFAULT` -> dopisek „cena domyślna”.
- Pod kosztem wiersza trasy: cena za litr (`TripsFormatting.priceLine`), a gdy `source == DEFAULT`
  to w tej samej linii po `Texts.SEPARATOR` dopisek „cena domyślna”; bez ceny zostaje tylko „brak ceny”.
  Linia ceny pokazuje się tylko tam, gdzie się zmienia (`tripIdsWithPriceLine`). Lista idzie od najnowszych, więc cena stoi przy **najnowszej** trasie każdej serii o tej samej cenie: gdy linia różni się od linii chronologicznie
  **następnej** trasy (po `(start, id)`, przez granice miesięcy), przy najnowszej trasie w ogóle oraz zawsze przy najnowszej trasie każdego miesiąca – góra ekranu i góra miesiąca zawsze mają cenę;
  „cena domyślna” jest częścią linii, więc przejście tankowanie ↔ cena domyślna to zmiana (C32 U12).
- Nagłówek grupy: gdy suma kosztu jest `null` -> „brak ceny”; dopisek „bez ceny: N” tylko gdy `N > 0`.
- Wszystkie teksty w `Texts`; żadnych Icons w UI.

### §10.3 Ekran „Trasy” (kryteria akceptacji)
- Wejście: przycisk „Trasy” na ekranie statusu. Wyjście: `TextButton` „Wstecz” w `TopAppBar` (bez Icons)
  i `BackHandler` z `androidx.activity.compose` – oba robią to samo.
- Przełączanie ekranów w `AppRoot`: enum `Screen` w `rememberSaveable` (to nie jest sekret).
  Wylogowanie zawsze wraca do logowania, a ekran resetuje się do statusu przez `LaunchedEffect` na warunku
  „czy nadal zalogowany” – nie przez przypisanie w ciele kompozycji.
- `LazyColumn`: nagłówek miesiąca (nazwa + sumy) i wiersze tras (data/godzina, km, litry, koszt,
  a pod kosztem cena za litr z dopiskiem „cena domyślna”; bez ceny tylko „brak ceny”). Nagłówki mają klucze z przedrostkiem `month-`, wiersze trasy
  `key = trip.id`; klucze muszą być unikalne na całej liście. `innerPadding` z `Scaffold` wchodzi jako
  `contentPadding` `LazyColumn`.
- Stany: ładowanie (`CircularProgressIndicator`), pusto („Brak tras – zsynchronizuj dane”), błąd odczytu
  (własny komunikat, bez treści wyjątku). Trzymany jako prywatny `sealed interface` w `TripsScreen.kt`.
- Odczyt na `Dispatchers.IO` (`produceState` + `withContext`); `CancellationException` zawsze przepuszczana dalej.
- Ekran nie pokazuje współrzędnych GPS ani VIN (`docs/SECURITY.md`); nie pokazuje też `lastErrorMessage`.

## §11 Cena tankowania (`SyncEngine.confirmRefuel`, `android/app/.../ui`)

Formularz ceny tankowania (PRD F2, F4): użytkownik podaje cenę albo litry i kwotę dla wpisu `PENDING`,
brakującą wielkość wylicza domena (§2), a koszty tras są przeliczane wstecz (§5). Wymaganie właściciela
produktu (ROADMAP NOW). Operacje nie wołają żadnego API Toyoty, w tym `refresh_status()` (§9 wstęp, AGENTS.md).
Szacunek litrów z czujnika (F2a) – §2a, §11.2 i §11.3; powiadomienia – §13. Poprawka litrów potwierdzonego tankowania – §15.3.
- Dotknięcie wiersza trasy z położeniem (start i/lub koniec) od razu otwiera mapę w aplikacji (§26) z tymi końcami, które mają
  współrzędne, zaokrąglonymi jak w §18 (ok. 100 m). Trasa bez żadnego położenia nie reaguje na dotknięcie. Archiwum nie ma przebiegu
  trasy, więc mapa łączy początek z końcem prostą przerywaną linią.
- Wiersz trasy pokazuje pogodę dnia i miejsca startu (§23), gdy jest w pamięci: symbol, słowo i średnią temperaturę w pełnych stopniach, np. „🌧️ deszcz · 8 °C”, „☀️ słonecznie · 12 °C”, „❄️ śnieg · -2 °C”. Symbol i słowo z kodu pogody WMO dnia (`weather_code`: bezchmurnie, zachmurzenie, mgła, mżawka, deszcz, śnieg, burza); bez kodu tylko temperatura, bez pogody nic. Otwarcie listy uruchamia pobieranie brakujących dni (§23), a lista dociąga je w miarę ich pojawiania się. Dni zapisane przed dodaniem kodu są pobierane jeszcze raz (raz).
- Wiersz trasy pokazuje też rodzaj jazdy wg średniej prędkości trasy (pasma z §22): „wolna jazda · 12 km/h” (< 15), „miasto · 19 km/h” (15–25), „płynnie · 32 km/h” (25–40), „trasa · 52 km/h” (≥ 40); trasa poniżej 2 km to „krótki odcinek”, bez prędkości – brak linii. To miara pośrednia (wolna jazda to też sygnalizacja i krótkie odcinki, nie tylko korek), więc etykieta mówi „wolna jazda”, a nie „korek”.

### §10.4 Układ ekranu statusu (czytelność)
- Kolejność: stan (chip świeżości, godzina ostatniej synchronizacji, linia „Telemetria: N odczytów”), liczniki w dwóch rzędach po dwa
  (Trasy / Poziom paliwa / Przebieg / Oczekujące tankowania),
  błąd synchronizacji (jeśli jest), przycisk ceny oczekującego tankowania (jeśli jest), przycisk „Synchronizuj teraz” na pełną szerokość
  (gest przeciągnięcia w dół robi to samo), potem karty: „Prognoza” (🔮), „Spalanie” (⛽), cel miesiąca, „Koszty miesięczne” (💰) i budżet, a na dole pierwszej strony **menu kafelków** (z przypomnieniem o kopii i linią „Ostatnia kopia archiwum”). Kafelki wróciły na dół decyzją właściciela z 2026-11-10 (propozycja U13 z przeglądu C32 wycofana po wypróbowaniu w v0.2.7); ekran pamięta przewinięcie po powrocie z podstrony (§9.3), więc kafelki zostają pod ręką.
- Karta to zaokrąglony kontener z ikoną i tytułem, jedno miejsce na odstępy, kształty i typografię (`DashboardCard`, `Headline`, `Pill`, `MenuTile` w `ui/Dashboard.kt`); kolory z motywu
  (jasny, ciemny i dynamiczny). Spalanie: duża liczba „4,13 l/100 km” z podpisem, trend jako kolorowa pigułka (rosnący – kolor błędu, malejący – kolor główny, stabilny – neutralny),
  słupki z zaokrąglonymi górami; kierunek trendu w pigułce pokazuje tylko ikona (tekst „rosnące (+8,2 %)” bez strzałki; strzałka zostaje w linii widżetu). Koszty: duża suma „1 182,89 zł”, słupki od zera, trzy najnowsze miesiące w szczegółach, reszta za „Pokaż wszystkie miesiące (N)”.
- Menu to kafelki po dwa w rzędzie, o równej wysokości w rzędzie, z ikoną nad etykietą, każdy kafelek co najmniej 88 dp wysoki: Trasy, Dziennik tankowań, Statystyki, Styl jazdy,
  Podsumowanie roku, Pogoda i spalanie, Wydatki, Postoje, Stan pojazdu (ze „Stanem technicznym” jako kartą, §44), Lokalizacja, Historia serwisowa, Powiadomienia, Diagnostyka, Kopia i eksport oraz Opcje. Cena domyślna nie ma kafelka: to cena paliwa w baku przed pierwszym potwierdzonym tankowaniem (trasy sprzed niego i mieszanie z pierwszym tankowaniem, §4), więc jest w „Opcjach” (§12.3). Przypomnienie o kopii zostaje pod menu.
- Liczby do wyświetlania grupują tysiące twardą spacją („1 182,89 zł”, „4 625,9 km”, „4 887 km”), żeby długa kwota nie łamała się w środku; jedna funkcja `TripsFormatting.number`
  (`count` dla liczników) obsługuje wszystkie ekrany i powiadomienia; eksport CSV i `plain` (też wartości wpisywane z powrotem w pola formularzy) nie grupują (C32 U4).
- Kafelek liczby (`StatTile`) trzyma wartość w jednej linii: jednostka łączy się z liczbą twardą spacją, a za szeroka wartość zmniejsza czcionkę (do ok. 55 %) zamiast łamać „zł” do drugiej
  linii (C32 U5). Wykres słupkowy (`BarChart`) nie ucina podpisów: wartość i podpis są w jednej linii o własnej szerokości, a gdy najszerszy podpis nie mieści się pod słupkiem, drukuje się
  co drugi (trzeci…) licząc od najnowszego; pominięte podpisy zostają dla czytnika ekranu (C32 U2–U3).
- Kontrast (WCAG AA): tekst w kolorze palety (`Palette` w `ui/ChartKit.kt`: wartości `StatTile`, `MeterRow`, tytuł `SectionHeader`, etykieta `MenuTile`, treść `Pill`, kolorowe wiersze
  ekranów) idzie przez `readable` z `ui/Contrast.kt`: ta sama barwa przyciemniana (jasny motyw) albo rozjaśniana (ciemny) aż do 4,5:1 względem `surfaceContainerHigh`, z tłem kafelka
  lub pigułki nałożonym na kartę. Wypełnienia (słupki, pierścienie, paski) zostają w kolorach palety. Kolor główny jasnego motywu to czerwień Toyoty przyciemniona do `#C8001A`
  (≥ 4,5:1 na wszystkich tłach kart, także dla przycisków tekstowych; biały na niej 6:1); ikona aplikacji zostaje w `#EB0A1E`.
- Słupki i paski: wartość 0 (miesiąc bez kosztu, 0 km) nie rysuje słupka, tylko linię bazową; dodatnia kwota w „Kosztach miesięcznych” ma co najmniej 3 % wysokości (`costBarShare`).
- Ikony: Material Symbols Outlined (Apache 2.0) jako ścieżki w `ui/AppIcons.kt`, bez biblioteki ikon; karty, kafelki, odznaki wierszy, nagłówki sekcji, przyciski
  (`IconLabel`), pola formularzy, pigułki i chip stanu mają ikonę zamiast emoji, a wiersze z liczbami (km, litry, liczniki) – sam tekst. Emoji zostają tylko jako symbole pogody
  (`WeatherSymbols`, etykiety słupków pogody). Ranking jazdy na prądzie numeruje miejsca („1.”, „2.”), wybór języka bez flag.
- Czytnik ekranu: ikony są ozdobne (bez opisu; czytany jest tytuł lub etykieta), poza przyciskiem usuwania wydatku („Usuń”); kolumna słupka (wartość i miesiąc) czytana jako jedna pozycja; wykres poziomu
  paliwa w „Dzienniku tankowań” ma opis (najniższy i najwyższy poziom, ostatni odczyt, liczba tankowań).

### §11.1 Operacje w `core`
- `pendingRefuels(): List<Refuel>` — oczekujące tankowania wybranego VIN (`state.get().vin`), najnowsze pierwsze
  (`RefuelRepository.pending(vin)`); brak VIN -> pusta lista. Odczyt bez `mutex` i bez gatewaya (jak `status()`, §8.5).
- `confirmRefuel(id, pricePerLiter, liters, totalCost, filledToFull, litersSource)`, `dismissRefuel(id)` i `amendRefuel` (§15.3) zwracają `RefuelChange`
  (`Done(refuel, tripsCosted)`, `NotFound`, `NotPending`, `NotConfirmed` – tylko poprawka §15.3, `Invalid`, `NoVehicle`) — bez
  wyjątków i bez `SyncOutcome`.
- Kolejność decyzji: brak VIN -> `NoVehicle`; `refuels.byId(id) == null` -> `NotFound`; `status != PENDING` ->
  `NotPending`; wpis `PENDING`, ale nienależący do bieżącego VIN-u (ma go brak w `pending(vin)`) -> `NotFound`,
  bo cudze tankowania są dla tego ekranu niewidoczne i nie mają mieć własnego rodzaju błędu.
- Walidacja i wyliczenia WYŁĄCZNIE przez domenowe `confirmRefuel`/`dismissRefuel` (§2, §3).
  `IllegalArgumentException` z domeny -> `Invalid`, nic nie jest zapisane. `litersSource` (domyślnie `null`)
  formularz podaje tylko jako `ESTIMATE`, po jawnym przyjęciu szacunku z §2a; w pozostałych przypadkach `RECEIPT`
  albo `DERIVED` ustala domena. `litersSource` albo `filledToFull` bez litrów daje `Invalid`, bo tak rzuca domena.
- Zapis i przeliczenie kosztów (`recomputeAndSave`, krok 5 z §8.3) dzieją się pod tym samym `mutex` co
  synchronizacja, ale przez `mutex.withLock`, czyli **czekając** na blokadę — w przeciwieństwie do `guarded()`
  (`tryLock` + `Skipped`). Potwierdzenie ceny nie może zostać pominięte tylko dlatego, że właśnie trwa
  synchronizacja. `CancellationException` jest rzucana dalej. Skutek uboczny: operacja może czekać tak długo
  jak pełna synchronizacja.
- `tripsCosted` to liczba przeliczonych tras pojazdu, a nie liczba zmienionych wierszy; UI jej nie pokazuje.
- Bez zmian w schemacie Room i bez nowych metod w interfejsach repozytoriów.
- Znany niuans kolejności: `pending` w Room ma `ORDER BY ts DESC, id`, a repozytorium w pamięci sortuje tylko
  po `ts`, więc przy dwóch tankowaniach z identycznym `ts` kolejność może się różnić. Odnotowane; kontrakt
  repozytoriów tego nie rozstrzyga i nie zmienia go ta zmiana.

### §11.2 Walidacja i formatowanie (warstwa `app`, funkcje czyste, bez Compose)
- Parser kwot z pola tekstowego: `trim`, akceptuje przecinek i kropkę jako separator dziesiętny, puste -> `null`,
  a `6,1,9`, `abc` i wartość `<= 0` -> błąd pola. Bez `Locale` i bez `NumberFormat`.
- Formularz sam sprawdza dozwoloną kombinację (§2: cena | litry+kwota | cena+litry | cena+kwota) i znak wartości,
  z polskimi komunikatami z `Texts`. `RefuelChange.Invalid` z `core` pozostaje zabezpieczeniem i pokazuje
  komunikat ogólny — treść wyjątku domeny nigdy nie trafia na ekran. Świadomie: znajomość kombinacji jest w dwóch
  miejscach (app dla UX, domena jako źródło prawdy), a testy app pilnują obu stron do §2.
- Szacunek litrów (F2a): `checkRefuelForm(..., litersFromEstimate)` zwraca `litersSource = ESTIMATE` wyłącznie dla
  pary (cena + litry). Flaga z litrami i kwotą bez ceny to błąd pola „Cena” (`REFUEL_ESTIMATE_NEEDS_PRICE`):
  domena wyliczyłaby cenę z szacunku obarczonego błędem czujnika. Każda inna kombinacja z flagą daje `null`
  (domena wybiera `RECEIPT`/`DERIVED`). Domyślne `false` zostawia okno §13.3 bez zmian. Reguła jest warstwy UI –
  test tylko w `RefuelFormTest`, bez odpowiednika w Pythonie.
- Litry do pola: `RefuelFormatting.litersInput` – pełna precyzja domeny bez zer końcowych (`14,19`, `25,8`, `30`),
  przecinek, bez `Locale`; `liters` dodaje ` l`.
- Lista tankowań: czas `29.10 14:05` (strefa jako parametr, przez `TripsFormatting.startDateTime`), poziom
  `20% → 80%`, przebieg `12 345 km` — twarda spacja jako separator tysięcy (`TripsFormatting.number`), `HALF_UP` do pełnych kilometrów,
  bez `Locale`.

### §11.3 Ekrany „Tankowania” i formularz (kryteria akceptacji)
- Wejście: na ekranie statusu przycisk „Podaj cenę tankowania (N)”, gdy `pendingRefuels > 0`. Przycisk **nie jest
  nigdy wyłączany** na czas synchronizacji – potwierdzenie ceny czeka na `mutex` (§11.1), nie musi być blokowane.
  Wyjście:
  `TextButton` „Wstecz” w `TopAppBar` i `BackHandler` (jak §10.3). Powrót z listy na status woła
  `session.reload()`, żeby licznik się odświeżył — reload jest w callbacku nawigacji, a nie w `LaunchedEffect`,
  więc obrót ekranu nie generuje zbędnego odczytu bazy.
- Enum `Screen` rozszerzony o `Refuels` i `RefuelForm`; id edytowanego wpisu w `rememberSaveable` (to nie sekret).
- Stan trzyma `RefuelController` (wzorzec `SessionController`: `StateFlow`, scope aplikacji z `AppContainer`),
  bo zapis musi przeżyć wyjście z ekranu; `io` jest wstrzykiwane. `reset()` jest wołane przy wylogowaniu (w
  istniejącym `LaunchedEffect(loggedIn)` w `AppRoot`), a operacje stosują wynik tylko wtedy, gdy nie było
  `reset()` w międzyczasie (licznik generacji). Sam zapis w bazie nie jest anulowany — należy do scope'u aplikacji.
- Lista: ładowanie (`CircularProgressIndicator`), pusto („Brak tankowań czekających na cenę”), błąd odczytu
  (własny komunikat, bez treści wyjątku). Wiersze mają `key = refuel.id`; `innerPadding` z `Scaffold` wchodzi
  jako `contentPadding` `LazyColumn`.
- Formularz: pola „Cena za litr”, „Litry”, „Kwota” (`KeyboardType.Decimal`), wartości pól w `rememberSaveable`;
  przełącznik „Do pełna” aktywny tylko gdy podano litry; „Zapisz” blokuje przycisk i ignoruje drugi klik,
  „Odrzuć” ma `AlertDialog` z potwierdzeniem; błąd w karcie `errorContainer` (`ErrorCard`). Po sukcesie formularz
  wraca na odświeżoną listę; po błędzie zostaje z wpisanymi wartościami.
- Pod polem „Litry” wiersz szacunku, gdy `estimate_refuel_liters` (§2a) daje wartość: „Szacunek z czujnika:
  ok. 14,19 l” z `TextButton` „Użyj szacunku”, który wpisuje `litersInput(szacunek)` i ustawia flagę
  (`rememberSaveable`). Z flagą wiersz mówi „Litry z szacunku czujnika”, a „Z paragonu” zdejmuje samą flagę –
  wartość zostaje. Każda ręczna zmiana pola „Litry”, także wyczyszczenie, zdejmuje flagę. Szacunek liczony z
  wpisu na ekranie, bez odczytu bazy; wiersz żyje tylko w gałęzi z istniejącym wpisem. Wejście z powiadomienia
  (§13.1) startuje z flagą `false`. Pola formularza i flaga są w `rememberSaveable(id)`: dotknięcie powiadomienia
  innego wpisu przy otwartym formularzu zeruje je, więc wartości jednego tankowania nie trafią do drugiego.
- `openForm(id)` czyta listę ponownie, gdy jej jeszcze nie ma (`loading`) albo gdy odczyt był błędny
  (`loadFailed`). Po śmierci procesu formularz jest pierwszym kompozablem z pustym stanem i bez tego
  odczytu czekałby na `Texts.LOADING` w nieskończoność.
- `NotFound`, `NotPending` i `NoVehicle` pokazują komunikat o nieaktualnym wpisie (`REFUEL_STALE_ENTRY`)
  i przeładowują listę – wpis przestał czekać (potwierdził go ktoś inny albo go nie ma). Wyjątek: przy `NoVehicle`
  wpis nadal czeka, więc jego powiadomienie (§13.1) zostaje — `onResolved` idzie tylko dla `Done`, `NotFound`
  i `NotPending`.
  `Invalid` dostaje komunikat o wartościach (`REFUEL_WRITE_ERROR`), a wyjątek bazy osobny (`REFUEL_SAVE_FAILED`).
- Ekran nie pokazuje VIN-u ani współrzędnych GPS (`docs/SECURITY.md`); wszystkie teksty UI w `Texts`, bez Icons;
  komentarze i KDoc po angielsku.
## §12 Cena domyślna paliwa jako ustawienie (PRD F2b)

### §12.1 `core`: dostawca ceny i przeliczenie na żądanie
- `SyncEngine` dostaje `defaultPriceProvider: suspend () -> BigDecimal? = { config.defaultPrice }` i czyta go przy
  **każdym** `recomputeAndSave` (krok 5 synchronizacji §8.3, przeliczenie po potwierdzeniu ceny §11.1, przeliczenie
  z ekranu ceny domyślnej §12.2). Zmiana ceny nie czeka na następną synchronizację i nie wymaga restartu aplikacji.
- `SyncConfig.defaultPrice` zostaje wartością domyślną dostawcy (testy i użycie biblioteki poza aplikacją);
  aplikacja zawsze wstrzykuje `SettingsStore`, więc konfiguracja nie jest drugim źródłem prawdy (test
  `default_price_provider_is_read_on_every_recount`).
- Dostawca jest wołany wewnątrz `mutex`, więc musi być tani: w aplikacji to odczyt `SettingsStore` na
  `Dispatchers.IO` (nigdy na wątku UI), bez sieci i bez bazy danych.
- `suspend fun recomputeCostsNow(): CostRecompute` (`CostRecompute.Done(tripsCosted)` / `CostRecompute.NoVehicle`)
  liczy pod `mutex.withLock`, czyli czeka na trwającą synchronizację zamiast być pominięty (jak §11.1, inaczej niż
  `recomputeCosts()` z `tryLock` -> `SyncOutcome.Skipped`). Bez wywołania `gateway` i bez zmian w `SyncState`:
  przeliczenie z ekranu nie jest synchronizacją i nie może nadpisać jej statusu (§8.5).
- Koszt uboczny: przeliczenie trzyma `mutex` na czas zapisu kosztów (jedno `costs.saveAll`, setki wierszy), więc
  `fullSync` startujący w tym momencie dostanie `Skipped` i wróci z WorkManagera; tak samo działa już
  potwierdzenie ceny tankowania (§11.1).
- Bez zmian w schemacie Room i bez nowych metod w interfejsach repozytoriów. Od poprawki ADR-005 (2026-11-08) cena domyślna jest też ceną paliwa w baku przed pierwszym potwierdzonym tankowaniem (§4, §6), więc jej zmiana albo usunięcie przelicza również trasy po pierwszym tankowaniu.

### §12.2 Przechowywanie ceny (warstwa `app`)
- `settings/SettingsStore.kt` trzyma cenę w SharedPreferences `settings` — nie w `secrets` (cena nie jest sekretem
  w rozumieniu `docs/SECURITY.md` i nie trafia do Keystore). Klucz `default_price`, wartość to tekst
  `BigDecimal.toPlainString()`.
- Odczyt jest odporny na śmieci: tekst pusty albo nieprawidłowy zwraca `null` (czyli brak ceny), bez wyjątku i
  bez logowania treści; nadpisuje go następny zapis. Tak samo czytana jest wartość niedodatnia (`0`, `-5`) —
  zerowa cena kosztowałaby trasy 0,00 zł, więc jest traktowana jak usunięta (test
  `a price that is not positive reads as null`).
- `clearDefaultPrice()` usuwa klucz. **Wylogowanie nie czyści ceny**: to ustawienie telefonu, nie konta — tak jak
  archiwum zostaje na telefonie (ADR-006). `DefaultPriceController.reset()` czyści tylko stan w pamięci.
- Odczyt i zapis pliku preferencji idą z `Dispatchers.IO`: przez `defaultPriceProvider` w `AppContainer` i przez
  `DefaultPriceController` w scope'ie aplikacji.
- Ten sam plik trzyma inne ustawienia telefonu, m.in. `monthly_budget` (budżet miesięczny, F48/§49) jako tekst
  `BigDecimal` z dwoma miejscami i wartością dodatnią; śmieci, zero, wartość ujemna albo zaokrąglająca się do 0,00
  czytają się jak brak budżetu. `budget_alert` (przełącznik powiadomienia, domyślnie on) i `budget_alert_last`
  (klucz `RRRR-MM:80|100`) należą do F49/§49. Wylogowanie ich nie usuwa (ADR-006).

### §12.3 Ekran „Cena domyślna” (kryteria akceptacji)
- Wejście: karta „Cena domyślna” w „Opcjach” (nie na ekranie statusu: to cena paliwa w baku przed pierwszym potwierdzonym
  tankowaniem – liczy trasy sprzed niego i miesza się z pierwszym tankowaniem, potem liczy się cena z baku, §4) z jednozdaniowym wyjaśnieniem i `OutlinedButton` z aktualną wartością w etykiecie
  (`Cena domyślna paliwa: 6,19 zł/l`) albo bez niej (`Ustaw cenę domyślną paliwa`). Nie jest blokowany na czas
  synchronizacji — przeliczenie czeka na `mutex` (§12.1). Wyjście: `TextButton` „Wstecz” i `BackHandler` (jak §10.3) wracają do „Opcji”;
  powrót woła `session.reload()`, więc liczniki statusu się odświeżą.
- Enum `Screen` rozszerzony o `DefaultPrice`. Stan trzyma `DefaultPriceController` (wzorzec §11.3: `StateFlow`,
  scope aplikacji, wstrzykiwane `io`, `reset()` przy wylogowaniu, licznik generacji odrzuca późne wyniki).
  Cenę czytamy przez `DefaultPriceController.ensureLoaded()` w `LaunchedEffect(loggedIn)` (dla zalogowanego
  użytkownika) i aktualizujemy przez `save()`/`remove()`. Ten efekt startuje ponownie przy odtworzeniu Activity,
  więc odczyt jest strzeżony: plik jest czytany tylko gdy stan jest jeszcze nieznany — obrót ekranu nie czyta pliku preferencji
  ponownie (test `ensureLoaded does not read the file again after a good read`).
- Walidacja w UI (`DefaultPriceForm`): `parseRefuelAmount` (przecinek albo kropka), pole puste to błąd (usunięcie ma
  własny przycisk), zaokrąglenie do 0,001 ROUND_HALF_UP (§2) i dopiero potem sprawdzenie `<= 0` —
  wartość, która zaokrągla się do zera, jest odrzucana, bo przy zerowej cenie trasy kosztowały 0,00 zł
  (test `the sign is checked after the rounding`). `roundPrice` w `core.domain`
  jest `internal`, więc reguła jest powtórzona w UI — świadomie, tak jak §11.2 powtarza dozwolone kombinacje;
  `DefaultPriceFormTest` przypina obie strony.
- Formularz: jedno pole „Cena za litr” z `KeyboardType.Decimal`, wartość pola w `rememberSaveable`, notka pod polem
  (cena paliwa w baku przed pierwszym potwierdzonym tankowaniem), „Zapisz” (blokada na czas zapisu, drugi klik
  ignorowany), „Usuń cenę domyślną” z `AlertDialog`, błąd w karcie `errorContainer`. Po sukcesie formularz wraca na
  status.
- `CostRecompute.NoVehicle` jest traktowany jako sukces bez komunikatu: cena jest ustawiona, a koszty przeliczy
  najbliższa synchronizacja. Tak samo traktowany jest wyjatek samego przeliczenia — zapisanej ceny nie cofamy, bo
  najbliższa synchronizacja i tak policzy trasy z nią (`DefaultPriceControllerTest: a failing recount keeps the
  saved price`).

- Wyjątek zapisu pokazuje `Texts.REFUEL_SAVE_FAILED` („Nie udało się zapisać…”), treść wyjątku nie trafia do UI
  (`docs/SECURITY.md`).
- Ekran nie pokazuje VIN-u ani GPS; wszystkie teksty w `Texts`; bez Icons; komentarze i KDoc po angielsku.

## §13 Pytanie o cenę zaraz po tankowaniu (F2c)

Dwie drogi tej samej informacji: powiadomienie, gdy aplikacji nie ma na pierwszym planie (§13.1), oraz okno w
aplikacji (§13.3). Obie prowadzą do tej samej ścieżki ceny z §11 — bez nowych tabel i bez zmian w `core.domain`.

### §13.1 Powiadomienie z tła (`notify/RefuelNotifier`)
- Hak: dekorator `SyncRunner` w `AppContainer` (`fullSync()`, potem `notifier.onSyncOutcome(outcome)`), więc działa
  i dla `SyncWorker`, i dla „Synchronizuj teraz”. `SyncEngine` i `SyncOutcome` bez zmian — `core` bez nowych testów.
- Powiadamia tylko `SyncOutcome.Ok` z `refuelsDetected > 0`. Czyta `syncEngine.pendingRefuels()` i wysyła dla
  wpisów, o których jeszcze nie powiadomił. Zbiór powiadomionych id w `SettingsStore` (`refuel_notified_ids`) —
  plain text, nie sekret: bez duplikatów po kolejnej synchronizacji i po restarcie telefonu.
- Powiadomienie wysyłamy PRZED zapisaniem id w `SettingsStore`: jeśli wysłanie się nie uda (wyjątek
  serwisu powiadomień albo `post` zwraca `false`, bo uprawnienie cofnięto w międzyczasie), wpis zostaje nieoznaczony i następna
  synchronizacja spróbuje ponownie.
- Kanał `refuels` / „Tankowania”, `IMPORTANCE_HIGH` (wysuwane), tworzony leniwie i idempotentnie przy wysyłaniu.
- Id powiadomienia: `NOTIFICATION_ID_BASE + Math.floorMod(entry.id.hashCode(), 100000)`, baza 1000 (`hashCode` bywa ujemny);
  `requestCode` `PendingIntent` to samo, `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`, `setAutoCancel(true)`.
- Treść z `Texts` + `RefuelFormatting.time` i `RefuelFormatting.levelJump` (strefa `ZoneId.systemDefault()`):
  tytuł `Wykryto tankowanie`, tekst `30.10 14:05 · 20% → 80%. Podaj cenę za litr.` Bez VIN, GPS, przebiegu
  i tokenów: `RefuelFormatting.odometer` nie jest tu używany (docs/SECURITY.md).
- Dotknięcie otwiera `MainActivity` (`launchMode="singleTop"` + `onNewIntent`) z extra `refuel_id`; `AppRoot`
  przechodzi na pełny formularz ceny tego tankowania z fokusem w polu ceny i otwartą klawiaturą. Extra jest czytane
  tylko gdy `savedInstanceState == null` (obrót nie odtwarza wejścia); `onNewIntent` woła też `setIntent`.
  Zimny start z powiadomienia (proces zabity, typowe po `SyncWorker`): sesja jest jeszcze `Loading`, więc efekt ma
  klucz `(deepLinkRefuelId, loggedIn)` i formularz otwiera się, gdy tylko zapisane logowanie zostanie wczytane. Bez
  zalogowanej sesji nic się nie dzieje, a wpis, który nie jest już oczekujący, zostawia ekran statusu (decyzja
  właściciela).
- `cancel(refuelId)` przy `Confirmed`/`Rejected` z dowolnego ekranu (`RefuelController.onResolved`,
  `RefuelPromptController`) usuwa to id ze zbioru — zbiór jest ograniczony liczbą oczekujących wpisów. `cancel`
  jest `suspend`: powiadomienie znika od razu, a zbiór w `SettingsStore` zmienia się na `io`, nigdy z wątku UI
  (§12.2); `AppContainer` woła je w `uiScope.launch`.
- Brak uprawnienia (`canPost() == false`): notifier nie wysyła i nie zaznacza nic, bez wyjątku; po przyznaniu
  uprawnienia trafia to, co w międzyczasie wpadło. Wyjątki notifiera są połykane — powiadomienie nie może przerwać
  synchronizacji ani wywrócić `SyncWorker`.

### §13.2 Uprawnienie `POST_NOTIFICATIONS` (API 33+)
- Manifest + jedna prośba w czasie działania, po zalogowaniu, na ekranie statusu
  (`rememberLauncherForActivityResult` z `activity-compose`). Ścieżka jest wymuszona przez kompilator:
  `StatusScreen` i `AppRoot` nie mają wartości domyślnej dla `askNotificationPermission`, więc
  nieprzekazanie decyzji się nie skompiluje. Decyzja to `AppContainer.askNotificationPermission`;
  `MainActivity` podaje ją do `AppRoot`, a `AppRoot` do `StatusScreen`.
- Decyzja w czystej funkcji `shouldAskNotificationPermission(sdkInt, granted, askedBefore)`; flaga `askedBefore`
  w `SettingsStore` (`notifications_asked`) — odmowy nie odpytujemy ponownie. Flaga czytana z `Dispatchers.IO`, nie
  z wątku UI (§12.2). Na API 29-32 uprawnienie nie istnieje, liczy się sam kanał.
- Odmowa nie degraduje aplikacji: okno z §13.3 i przycisk z §11.3 działają dalej.

### §13.3 Okno w aplikacji (`ui/RefuelPrompt*`)
- Decyzja czysta i testowana bez Compose: `refuelPromptTarget(RefuelPromptInput(loggedIn, pending, deferred,
  onFormScreen, busy))` zwraca najnowszy wpis nieodłożony albo `null`; `deferred` to id odłożone „Później”.
  Kontroler podaje `loggedIn = true`: strażnikiem sesji jest gałąź `UiState.LoggedIn` w `AppRoot` (tylko tam okno
  jest rysowane) i `reset()` przy wylogowaniu, który czyści stan i unieważnia spóźnione odczyty.
- „Później” wycisza **tylko to jedno tankowanie** — w pamięci kontrolera, do końca procesu; `reset()` przy
  wylogowaniu kasuje wyciszenie. Nowe tankowanie wykryte w tym samym procesie pokazuje okno — to sedno F2c (test
  `later for A still prompts for a newer B`).
- Stan trzyma `RefuelPromptController` (scope aplikacji, wstrzykiwane `io`): `entry`, `priceText`, `error`, `busy`.
  `priceText` w kontrolerze, więc obrót ekranu go nie gubi; kontroler nie używa stanu `RefuelController` — `busyId`
  i `formError` należą do formularza z §11.3.
- `refresh()` woła dekorator `SyncRunner` po każdej synchronizacji z `Ok && refuelsDetected > 0`, a `ensureLoaded()`
  przy wejściu zalogowanego czyta tylko gdy stan jest jeszcze nieznany (jak §12.3), więc obrót nie czyta bazy
  ponownie.
- Zmiana ekranu (`LaunchedEffect(screen)` → `screenChanged()`, także po obrocie) decyduje ponownie z ostatniego
  odczytu, bez bazy. Wpisy wycenione albo odrzucone w C3 wypadają z tego odczytu przez `resolvedElsewhere(id)`,
  które `AppContainer` woła z `RefuelController.onResolved`. `onFormScreen` ustawia `SideEffect`, więc pierwsza
  decyzja kontrolera już widzi właściwy ekran.
- Zapis ceny: `checkRefuelForm(priceText, "", "")` (§11.2) i lambda `confirmRefuel` (`SyncEngine.confirmRefuel`,
  `withLock`). `Confirmed` -> `onResolved(id)` (anuluje powiadomienie) + `session.reload()` + następny oczekujący
  wpis albo koniec. `NotFound`/`NotPending` -> przejście do następnego wpisu bez błędu (id traktowane jak odłożone).
  `NoVehicle` -> okno się zamyka bez błędu. `Failed` -> błąd w oknie, okno zostaje (tak jak §11.3).
- Zamknięcie okna obok przycisków (dotknięcie poza oknem, Wstecz) działa jak „Później” dla tego wpisu.
- Pokazanie okna anuluje powiadomienie tego tankowania: użytkownik przed ekranem widzi okno, a nie okno i nagłówek;
  użytkownik w tle dostaje tylko powiadomienie.
- „Więcej” i dotknięcie powiadomienia otwiera pełny formularz C3 (`Screen.RefuelForm`) z litrami i kwotą;
  `RefuelFormScreen` dostaje `focusPriceOnOpen`. Fokus i klawiatura po pierwszej narysowanej klatce
  (`withFrameNanos` przed `requestFocus()` — przed pierwszym layoutem nie ma komu przekazać fokusu), a
  kontroler klawiatury brany z composition (`LocalSoftwareKeyboardController`), czyli z okna dialogu, nie
  z aktywności. W formularzu efekt fokusu żyje w gałęzi z istniejącym wpisem, nie na górze ekranu: przy zimnym
  starcie formularz najpierw pokazuje „Ładowanie…”, a fokus poproszony, zanim pole powstało, przepadłby.
  Flaga „wejście z powiadomienia” w `AppRoot` jest w `remember`, nie w `rememberSaveable`: klawiatura jest
  proszona raz na dotknięcie, więc obrót ekranu nie przenosi kursora z „Litry” z powrotem do ceny.
- Okno nie pojawia się, gdy aktywny ekran to `Screen.Refuels`, `Screen.RefuelForm` albo `Screen.DefaultPrice`
  (każdy formularz), gdy brak zalogowanej sesji i gdy trwa zapis.

## §14 Kopia archiwum i eksport (PRD F6, F7, F8)

Toyota usuwa trasy starsze niż ok. 12 miesięcy, a kopię w chmurze robi Auto Backup Androida na Dysk Google właściciela (ADR-015, SECURITY.md), do tego ręczna kopia (ADR-006). Kopia to jeden plik JSON; import dodaje tylko to, czego w telefonie jeszcze nie ma.
Arkusz tras w CSV (F6) – §14.4.

### §14.1 Plik i reguły w `core`
- `core/data/ArchiveFile.kt`: koperta `{"format":"toyota-companion-archive","version":1,"exportedAt":…,"vehicles":[…],
  "trips":[…],"refuels":[…],"readings":[…]}`, UTF-8. Każda trasa, tankowanie i odczyt niesie swój `vin`. Czas jako
  ISO-8601 (`Instant`), kwoty, ceny i litry jako tekst dziesiętny (`BigDecimal.toPlainString`, skala zachowana),
  `NaN`/`Infinity` w liczbach GPS zapisywane jako `null`. Modele domeny zostają bez `@Serializable` – plik mapują
  prywatne DTO z własnymi serializerami `Instant`/`BigDecimal` (kotlinx-serialization ich nie ma).
- `decodeArchive(bytes)` → `Ok(snapshot)` | `Unsupported` (inny `format` albo `version` wyższa niż obsługiwana) |
  `Corrupt` (zły JSON, brak pola, nieznana nazwa enuma, zły czas lub liczba); nigdy wyjątek. Nieznane dodatkowe klucze
  są pomijane.
- Zawartość: wszystkie pojazdy (`vehicles.all()`) i dla każdego jego trasy (pełny `TripRecord`, z GPS), tankowania
  (każdy stan, `liters_source`, `filled_to_full`) i odczyty paliwa (`since(Instant.EPOCH, vin)`). **Bez:** kosztów tras
  (liczone ponownie), `sync_state`, sekretów, ustawień telefonu (cena domyślna, data kopii, zbiór powiadomień).
- `SyncEngine.exportArchive()` i `importArchive(snapshot)` działają pod `mutex.withLock` – czekają na trwającą
  synchronizację, bez sieci. Wyjątek bazy trafia do wołającego (§8.5).
- Import po tożsamości wiersza: trasy i tankowania po `id`, odczyty po `(vin, ts)`, pojazdy po VIN. To, co jest w
  telefonie, zostaje – z jednym wyjątkiem: tankowanie `PENDING` w telefonie, a `CONFIRMED`/`DISMISSED` w pliku, bierze
  wersję z pliku (`refuelsUpdated`). Tankowanie odrzucone w telefonie, a wycenione w pliku, zostaje odrzucone i jest
  liczone jako pominięte (`refuelsSkipped`). Po imporcie koszty wybranego pojazdu są przeliczane. Reguła kolizji należy
  do `core/sync`, nie do domeny – bez odpowiednika w Pythonie. Ponowny import tego samego pliku nic nie dodaje.
- Import nie jest jedną transakcją: plik jest w całości dekodowany i sprawdzany **przed** pierwszym zapisem, a zapisy
  są idempotentne, więc import przerwany w połowie naprawia ponowny import tego samego pliku.

### §14.2 Ekran „Kopia i eksport” (kryteria akceptacji)
- Wejście: przycisk „Kopia i eksport” na ekranie statusu (`Screen.Backup`); wyjście „Wstecz”/`BackHandler`, powrót
  odświeża liczniki statusu (`session.reload()` w callbacku nawigacji).
- „Eksportuj archiwum” → `AlertDialog` „Plik z danymi osobowymi” (GPS, VIN – SECURITY.md) → systemowy wybór pliku
  `CreateDocument("application/json")` z nazwą `toyota-companion-RRRR-MM-DD.json` (bez VIN) → zapis. Data ostatniej kopii
  jest zapisywana tylko po udanym zapisie pliku.
- „Importuj archiwum” → `OpenDocument` (`application/json`, `application/octet-stream`, `text/plain`) → odczyt najwyżej
  20 MB (`readArchiveBytes`, większy plik: „Plik jest za duży…”) → dekodowanie → import. Wynik po polsku bez form
  mnogich („trasy: 3, tankowania: 1, …”), z liczbą uzupełnionych i pominiętych tankowań i tras innego pojazdu.
  `Unsupported`/`Corrupt` mają własne komunikaty („Nic nie zostało zapisane.”); inne błędy – komunikat ogólny bez treści
  wyjątku.
- Stan trzyma `BackupController` (scope aplikacji, licznik generacji, `reset()` przy wylogowaniu). Pliki są dla niego
  tekstem `Uri` – otwiera je `ContentResolver` w `AppContainer`, więc kontroler nie zna typów Androida.
- Karta „Kopia w chmurze Google” (ADR-015): wyjaśnienie, że Auto Backup kopiuje bazę archiwum i ustawienia, ale nie
  login ani tokeny MyToyota (po odtworzeniu na nowym telefonie wymagane jest ponowne logowanie). Przycisk „Zrób kopię
  w chmurze teraz” wywołuje `BackupManager(context).dataChanged()` (podłączone w `AppContainer`, kontroler widzi lambda
  `() -> Unit`), po którym ekran pisze „poproszono”, nigdy „zrobiono” – Android sam wybiera moment kopii;
  poniżej podpowiedź „Ustawienia Androida → Google → Kopia zapasowa”. Błąd żądania ma własny komunikat bez treści wyjątku.

### §14.3 Przypomnienie (F8)
- `SettingsStore.lastArchiveExport` (`last_archive_export`, milisekundy) – ustawienie telefonu: przeżywa wylogowanie,
  nie trafia do pliku kopii. Odczyt raz na zalogowanie (`ensureLoaded`, jak §12.3).
- `backupReminder(lastExport, now, tripsStored)` (czysta): puste archiwum → brak przypomnienia; kopii nie było →
  „Zrób kopię archiwum – trasy starsze niż rok są już tylko w tym telefonie.”; ostatnia kopia starsza niż 30 pełnych
  dni → „Od ostatniej kopii archiwum minęło N dni – zrób nową.”. Data w przyszłości (cofnięty zegar) liczy się jako
  świeża. Dopóki data nie jest odczytana, przypomnienia nie ma.
- Na ekranie statusu karta przypomnienia (kolory `tertiaryContainer` – to nie błąd), przycisk „Kopia i eksport” i linia
  „Ostatnia kopia archiwum: 31.10.2026” albo „Archiwum nie ma jeszcze kopii”; data bez `Locale` (`backupDate`).
- Bez powiadomień systemowych – przypomnienie żyje tylko w aplikacji.

### §14.4 Arkusz tras w CSV (F6)
- Trasy wybranego pojazdu – te same co na liście tras (`SyncEngine.trips()`, §10.1), z tymi samymi zapisanymi
  kosztami. Czysta funkcja `tripsCsv(trips, zone)` w `app/ui/TripsCsv.kt` (formatowanie to warstwa `app`).
- Format pod polski arkusz: separator `;`, przecinek dziesiętny, UTF-8 z BOM (doklejany przy zamianie na bajty – bez
  niego Excel na Windows psuje ogonki), końce linii CRLF, komórka w cudzysłowie, gdy zawiera `;`, `"`, CR lub LF
  (cudzysłów podwojony, RFC 4180).
- Kolumny (`Texts.CSV_HEADER`): `Start`, `Koniec` (`RRRR-MM-DD GG:MM`, strefa listy tras), `Dystans [km]` (1 miejsce),
  `Paliwo [l]` (2), `Dystans EV [km]` (1, pusta przy braku), `Cena [zł/l]` (3 – pełna precyzja domeny), `Koszt [zł]` (2),
  `Źródło ceny` (`tankowanie` / `cena domyślna`). Liczby bez jednostek, zaokrąglenie HALF_UP wspólne z listą tras
  (`TripsFormatting.plain`), bez `Locale`. Wiersze od najstarszej trasy (remis – po id).
- Cena, koszt i źródło są puste dokładnie wtedy, gdy lista tras pisze „brak ceny” (`hasNoCost`). Koszt jest wartością
  autorytatywną: „paliwo × cena” w arkuszu może się od niego różnić o 0,01 zł (lista tras pokazuje cenę z 2 miejscami).
- Bez GPS, VIN, id trasy, ocen jazdy i wiersza „Razem” – sumy liczy arkusz; do pełnej kopii służy §14.1.
- Komórka tekstowa (nie liczba), której pierwszy **nie-biały** znak to `=`, `+`, `-` albo `@`, dostaje na samym początku `'` – arkusz
  nie wykona jej jako formuły (CSV injection), nawet gdy Excel obetnie wiodący biały znak; liczba (np. `-1`) nie dostaje `'`.
  Komórki z wiodącym/końcowym białym znakiem lub tabulatorem są w cudzysłowie, żeby dane zostały wiernie zapisane.
  Tekstami są już nie tylko stałe aplikacji: od F47 notatki wydatków pochodzą od użytkownika.
- UI: sekcja „Arkusz kalkulacyjny” na ekranie „Kopia i eksport”, przycisk „Eksportuj trasy do CSV” →
  `CreateDocument("text/csv")`, nazwa `toyota-companion-trasy-RRRR-MM-DD.csv` (nazwa nie rozróżnia pojazdów;
  bez VIN). Przycisk nieaktywny bez tras – systemowy wybór pliku tworzy plik, zanim aplikacja cokolwiek sprawdzi;
  lista pusta w chwili zapisu daje „Brak tras do eksportu.” bez zapisu. Bez ostrzeżenia o danych osobowych (brak GPS
  i VIN). Eksport CSV nie zmienia daty ostatniej kopii archiwum (F8 dotyczy tylko kopii).

## §15 Dziennik tankowań i spalanie „komputer vs faktyczne” (PRD F9)

### §15.1 Domena: `fuel_economy_intervals(refuels, trips, min_distance_km=100)` → `list[EconomyInterval]`
(Python `app/domain/economy.py`, Kotlin `core/domain/EconomyOps.kt` – te same nazwy testów.) Dane **jednego** pojazdu.
- Odcinek otwiera potwierdzone tankowanie z `filled_to_full = true` (A) i zamyka następne takie (B); kolejność po
  `(ts, id)`. Tankowania przed pierwszym „do pełna” nie otwierają niczego; po ostatnim jest jeden odcinek `open`.
- Paliwo faktyczne = suma litrów potwierdzonych tankowań w (A, B] (częściowe w środku + B); litry A nie wchodzą.
  Dystans i paliwo komputera = sumy `distance_km` i `fuel_l` tras ze **startem** w [A.ts, B.ts) – ten sam zbiór tras
  po obu stronach. `Refuel.ts` to chwila odczytu, który wykrył tankowanie (do 30 min po nim, §8.3), więc granica jest
  przybliżona, ale przesuwa obie strony tak samo; trasa przekraczająca B należy do odcinka, w którym się zaczęła.
- Status (pierwszy pasujący): `pending_refuel` (tankowanie w (A, B) czeka na cenę), `dismissed_refuel` (odrzucone –
  paliwo wlano, litrów nie ma), `missing_receipt` (tankowanie w (A, B] bez litrów albo z `estimate`), `no_trips` (zerowy
  dystans albo paliwo tras), `too_short` (dystans < 100 km – rozrzut „do pełna” ok. 0,5 l, decyzja D8), inaczej `ok`.
- Wynik: `computer_l_per_100km`, gdy jest dystans; `actual_l_per_100km` i `difference_pct` = (faktyczne − komputer) /
  komputer × 100 tylko przy `ok`. Zaokrąglenia HALF_UP: l/100 km do 0,01 (`round_consumption`), % do 0,1
  (`round_percent`), km do 0,1 (`round_distance`). Licznik (`B.odometer − A.odometer`) tylko informacyjnie; ≤ 0 → brak.

### §15.2 Odczyt i ekran
- `SyncEngine.refuelLog()` – potwierdzone tankowania wybranego VIN od najnowszego i odcinki z §15.1; bez sieci i bez
  mutexu (jak `trips()`, §10.1); brak VIN → pusty dziennik.
- Ekran „Dziennik tankowań” z przycisku na statusie; odczyt w callbacku nawigacji (`RefuelLogController.load`), nie w
  `LaunchedEffect` – obrót nie czyta bazy; `ensureLoaded` tylko po śmierci procesu. `reset()` przy wylogowaniu.
- Wiersz tankowania: czas, skok poziomu, litry ze źródłem („z paragonu”, „wyliczone z paragonu”, „szacunek z
  czujnika”) i „do pełna”, cena i kwota. Pod tankowaniem kończącym odcinek karta: „Spalanie: faktyczne 5,00 l/100 km ·
  komputer 4,50 l/100 km (+11,1 %)” albo powód braku porównania, pod nią „600,0 km tras · licznik 600,0 km · w tym EV
  70,3 km”. Odcinek otwarty jako pierwsza karta „Bieżący odcinek”. Pusty stan z podpowiedzią „Do pełna” + paragon.
  Liczby przez `TripsFormatting.plain`, bez `Locale`; różnica ze znakiem (`+`, `−`). Bez VIN i GPS.
- Każde potwierdzone tankowanie ma przycisk „Popraw litry” (§15.3).
- Wykres „Poziom paliwa w czasie” nad dziennikiem (gdy są ≥ 2 odczyty): oś pionowa ma podziałkę 0/25/50/75/100 % z podpisami,
  oś pozioma trzy daty (`dd.MM`: pierwszy odczyt, środek zakresu, ostatni odczyt). Tankowania mają pomarańczową kropkę na linii
  i krótką kreskę na osi czasu; pod wykresem legenda z liczbą tankowań w zakresie (`Texts.levelChartLegend`). Rysowanych jest
  400 najnowszych odczytów; opis dla czytnika ekranu zostaje (`Texts.levelChartDescription`).

### §15.3 Poprawka potwierdzonego tankowania (C9.1)
- `SyncEngine.amendRefuel(id, liters, totalCost?, filledToFull)` – tylko dla `CONFIRMED` (inaczej `NotConfirmed`:
  „Najpierw podaj cenę tego tankowania.”); przynależność do pojazdu przez `refuels.all(vin)` (potwierdzonego wpisu nie
  ma w `pending(vin)`), inny VIN → `NotFound`. Ta sama ścieżka zapisu co cena (`mutex.withLock`, przeliczenie kosztów
  wszystkich tras pojazdu – litry zmieniają cenę w baku, §6). Walidacja tylko w domenie (`confirm_refuel` nie sprawdza
  stanu wpisu): bez kwoty – cena zostaje, kwota = cena × litry; z kwotą z paragonu – kombinacja litry + kwota, cena
  wyliczona z paragonu. Litry zawsze `receipt`, „do pełna” ustawione wprost. Synchronizacja ani import kopii (§14)
  potwierdzonego wpisu nie nadpiszą (straż `PENDING`).
- Okno „Litry z paragonu”: pole litrów **startuje puste** (szacunek z czujnika nigdy nie staje się „z paragonu”),
  opcjonalna „Kwota z paragonu”, przełącznik „Do pełna” aktywny dopiero z litrami. Litry ≤ 0 albo puste → „Podaj litry
  z paragonu.”; zła kwota → „To nie jest liczba”. Po zapisie dziennik czyta się ponownie, karta odcinka może się zmienić.
  `busy` nie przeżywa wylogowania (licznik generacji).

## §16 Jazda na prądzie (PRD F11)
Domena: Python `app/domain/tripstats.py`, Kotlin `core/domain/TripStatsOps.kt` – te same nazwy testów.
- `electric_share(trip)`: `ev_distance_km / distance_km` × 100, do 0,1 % (HALF_UP). Brak `hdc` (`ev_distance_km = None`)
  albo dystans 0 → `None` („brak danych”, **nie** 0 %). `ev > distance` (Toyota zaokrągla metry) → 100 % i
  `inconsistent = true`, pokazane jako „(dane Toyoty niespójne)”, nie ukryte.
- `aggregate_electric_share(trips)`: `sum(ev) / sum(distance)` tylko po trasach z danymi `hdc`, licznik przycięty do
  mianownika; `None`, gdy żadna trasa nie ma danych.
- `electric_trip_ranking(trips, limit=10, min_distance_km=1)`: od największego udziału, remis – dłuższa trasa, potem id;
  trasy bez danych i krótsze niż 1 km tylko liczone (`without_data`, `too_short`) i pokazane pod listą.
- Udział czasowy (`hdc.evTime`) poza zakresem: mianownikiem byłby czas jazdy, którego archiwum nie zapisuje.

## §17 Statystyki wieloletnie (PRD F10)
- `period_stats(trips, costs, zone, kind)`: okres `year` albo `season` (meteorologiczny: zima XII–II, wiosna III–V,
  lato VI–VIII, jesień IX–XI; grudzień należy do zimy nazwanej rokiem stycznia, np. „zima 2025/26”). Trasa należy do
  okresu swojego startu w strefie listy tras. Okresy od najstarszego.
- Na okres: liczba tras, km (0,1), paliwo z komputera, l/100 km (0,01), koszt (0,01) tylko z tras z ceną
  (`source != none` i kwota; cena domyślna się liczy) – bez cen `None`, nigdy 0 zł; trasy bez ceny; zł/100 km liczone
  na dystansie tras z ceną; udział EV (§16); średnia trasa (0,1).
- `year_over_year`: rok ostatniej trasy od 1 stycznia do dnia ostatniej trasy kontra **ten sam zakres dni** rok
  wcześniej; `None` bez tras w tamtym oknie – bez tego styczeń nowego roku wyglądałby na spadek o ponad 90 %.
- `SyncEngine.trips()` (§10.1) jest jedynym odczytem: statystyki liczą się w pamięci z tras wybranego pojazdu.

## §18 Ekrany „Statystyki” i „Postoje” (PRD F10, F11, F17)
- „Statystyki” ze statusu: udział EV łączny, ranking (data trasy, udział, km), pominięte, rok do roku („1.01–31.10
  2026 vs 2025”), lata i pory roku od najnowszych. Bez GPS i VIN. Odczyt w callbacku nawigacji
  (`StatsController.load`), `ensureLoaded` tylko po śmierci procesu, `reset()` przy wylogowaniu.
- „Postoje” ze statusu (`app/ui/ParkingStops.kt`, czyste funkcje na `TripRecord` jednego pojazdu, bez lustra w
  Pythonie – warstwa danych z GPS, nie domena): kolejne trasy po `(start, id)`; przerwa > 21 dni → `longGaps`; < 30 min
  → pominięta; koniec i start dalej niż 150 m (haversine) → `unmatched` (brak trasy w archiwum); brak współrzędnych →
  postój bez położenia; inaczej postój w średnim punkcie obu końców. Miejsca: postoje z położeniem łączone zachłannie
  od najstarszego z pierwszym miejscem, którego środek (pierwszy postój) jest ≤ 150 m; sortowane po łącznym czasie.
  Ekran: miejsca (`54.372, 18.638` – 0,001°, ok. 100 m; liczba postojów i łączny czas tylko w pasku miejsca, linia pod nazwą mówi „ostatnio …”), 50 ostatnich postojów, liczniki pominiętych. Położenie tylko
  na tym ekranie – nie w logach, CSV ani nazwach plików. Jedyny wyjątek (decyzja właściciela 2026-11-01): przycisk
  „Otwórz w mapie” przy miejscu wysyła intent `geo:` z położeniem zaokrąglonym tak jak na ekranie, wyłącznie po
  dotknięciu; bez aplikacji z mapą – komunikat. Nic nie pyta auta o pozycję (`refresh_status()` zakazany).

## §19 Styl jazdy i anomalie spalania (PRD F12, F13)
Funkcje czyste w `app` na `StoredTrip` (wynik eco i GPS są w `TripRecord`, nie w domenie – jak §18, ADR-008), bez lustra
w Pythonie.
- **Ten sam odcinek** (`SameRoutes.kt`): trasy od najstarszej łączone zachłannie z pierwszą grupą, której wzorzec
  (pierwsza trasa) ma start i koniec ≤ 300 m od startu i końca trasy; kierunek ma znaczenie; trasy bez czterech
  współrzędnych pomijane. Wzorzec się nie przesuwa, a trasa trafia do **najstarszej** pasującej grupy, nie do
  najbliższej. Odcinek od 3 przejazdów, od najczęstszego (remis – jeżdżony najpóźniej); ekran pokazuje 10, a anomalie
  dotyczą tylko pokazanych odcinków (numer anomalii = numer na ekranie).
- **Układ wiersza odcinka:** tekst zajmuje całą szerokość kolumny, a przycisk „Pokaż przebiegi na mapie” stoi pod tekstem (nie obok; obok zostawiał ok. 100 px i łamał każde słowo; C32 U1).
- **Bez położenia na ekranie:** odcinek opisany numerem, typową długością (mediana km), liczbą przejazdów, typową
  godziną startu (najczęstsza pełna godzina, czas lokalny), średnim wynikiem eco ważonym dystansem i medianą l/100 km.
- **Wynik eco po miesiącach** (`DrivingStyle.kt`): średnia `scoreGlobal` Toyoty ważona dystansem, 0,1 pkt, miesiąc startu
  w strefie listy tras, od najnowszego; trasy bez wyniku pominięte. Podpisy słupków to skróty miesięcy jak na statusie („kwi”, „cze”; styczeń z rokiem), nie pełne nazwy (C32 U3).
- **Anomalie:** w pokazanym odcinku z ≥ 5 przejazdami (≥ 2 km, paliwo > 0) przejazd, którego l/100 km przekracza medianę
  odcinka o ≥ 25 %; mediana 0 (jazda na prądzie) – brak anomalii. „Przejazdy” odcinka to wszystkie trasy, mediana
  spalania liczy się tylko z przejazdów ≥ 2 km z paliwem > 0; trasy z dystansem niezapisanym poprawnie są pomijane. Od najnowszej, do 20. Aplikacja nie rozpoznaje
  przyczyny – stała podpowiedź (zimno, korki, bagaż, ciśnienie w oponach).
- Ekran „Styl jazdy” ze statusu; odczyt w callbacku nawigacji, `reset()` przy wylogowaniu.

## §20 Prognoza tankowania (PRD F14)

### §20.1 Domena: `refuel_forecast(reading, trips, now, capacity_l=43, reserve_pct=10, window_days=28)`
(Python `app/domain/forecast.py`, Kotlin `core/domain/ForecastOps.kt` – te same nazwy testów.) Dane jednego pojazdu.
- W baku = `level_pct × 43 l` z ostatniego odczytu minus `fuel_l` tras ze startem ≥ chwila odczytu (czujnik jest
  czytany tylko przy synchronizacji), nie mniej niż 0. `distance_to_empty_km` Toyoty **nie** jest używane – w
  archiwum podaje 730 km zarówno przy 30 %, jak i przy 94 %.
- Spalanie = Σ `fuel_l` / Σ km tras ze startem w ostatnich 28 dniach; dzienny dystans = Σ km / liczba dni okna
  (archiwum młodsze niż 28 dni – tyle dni, ile ma, w górę, min. 1). Dni liczone od pierwszej trasy archiwum, nie od
  pierwszej trasy w oknie – dni bez jazdy też są dniami. Okno obejmuje oba brzegi; trasy z dystansem, który nie jest
  skończoną liczbą ≥ 0, są pomijane.
- Rezerwa = 10 % baku (4,3 l). Wynik: litry w baku (0,001), poziom (0,1 %), l/100 km (0,01), dzienne km (0,1), km do
  rezerwy (0,1), dni do rezerwy (0,1). `None` bez odczytu, bez tras w oknie, przy zerowym dystansie albo paliwie.
- „Najtańsza stacja na trasie” – poza zakresem (brak źródła cen paliw, PRD F14).

### §20.2 Odczyt i ekran
- `SyncEngine.refuelForecast(now)` – bez sieci, bez `refresh_status()`, bez mutexu; brak VIN → `None`.
- Linia na ekranie statusu pod datą synchronizacji: „Tankowanie za ok. 17 dni (ok. 17.11) · w baku ok. 21,5 l (50 %) ·
  ok. 344 km do rezerwy”; poniżej pół dnia – „Tankowanie dziś”, jeden dzień – „Tankowanie jutro”, powyżej 90 dni –
  „Tankowanie za ponad 90 dni”, data z rokiem, gdy wypada w innym roku; dni liczone od chwili odczytu prognozy
  (ostatniej synchronizacji), nie od chwili wyświetlenia; na rezerwie – „Paliwo na rezerwie – zatankuj”. Brak
  prognozy chowa linię. `ForecastController`: odczyt raz po zalogowaniu i po każdej udanej synchronizacji
  (`AppContainer.syncRunner`), `reset()` przy wylogowaniu.

## §21 Typy tras (PRD F16)
Funkcje czyste w `app/ui/TripTypes.kt` na dzienniku postojów (§18) – GPS, więc bez lustra w Pythonie (ADR-008).
- **Dom** = miejsce z największym łącznym czasem postoju (≥ 5 postojów). **Praca** = inne miejsce z największą liczbą
  postojów w dni robocze, zaczętych 6:00–10:59 czasu lokalnego i trwających ≥ 4 h (≥ 5 takich; remis – większy łączny
  czas). Nazwy nadawane automatycznie (decyzja właściciela 2026-10-31).
- Typ trasy (pierwszy pasujący): **dojazd** – start przy Domu i koniec przy Pracy albo odwrotnie (≤ 150 m);
  **weekend** – start w sobotę albo niedzielę; **sprawy** – dzień roboczy, koniec ani przy Domu, ani przy Pracy, a
  następna trasa startuje < 2 h później; **inne** – reszta (także ostatnia trasa archiwum).
- Ekran: sekcja „Typy tras” w „Statystykach” – liczba tras, km, koszt (bez cen: „brak ceny”), bez położenia; etykiety
  „Dom” i „Praca” przy miejscach w „Postojach” (jedynym ekranie z położeniem).

## 22. Tempo jazdy a spalanie (PRD F19)
Funkcje czyste w `app/ui/TrafficIndex.kt` na `StoredTrip` – bez lustra w Pythonie (nie używają GPS, ale są tylko prezentacją).
- Źródło: `TripRecord.averageSpeed` (średnia Toyoty; na archiwum zgodna z dystansem/czasem). `durationIdle` jest puste we
  wszystkich trasach, a hybryda na postoju nie pracuje silnikiem – dlatego bieg jałowy nie jest miarą korka.
- Trasa liczy się od 2 km, z dodatnią średnią prędkością i nieujemnym paliwem. Pasma (dolna granica włącznie): wolno < 15,
  miasto 15–25, płynnie 25–40, trasa ≥ 40 km/h.
- Na pasmo: liczba tras, km (0,1), l/100 km = Σ paliwo / Σ km (0,01), zł/100 km = Σ koszt / Σ km tras z ceną (bez ceny: „brak ceny”).
  Pasmo bez tras pomijane.
- Dopłata za wolną jazdę = wolno − płynnie (l/100 km i, gdy oba pasma mają cenę, zł/100 km); tylko gdy oba pasma mają ≥ 10 tras.
- Ekran: sekcja „Tempo jazdy a spalanie” w „Statystykach”: przy paśmie liczba tras, km, l/100 km, zł/100 km i udział jazdy na prądzie, pod nagłówkiem licznik tras pominiętych (poniżej 2 km albo bez prędkości); dopłata ze znakiem (+/-) liczona w `computeStats`; uwaga, że to wskaźnik pośredni i że litry liczone są do wszystkich km, także na prądzie. Archiwum 408 tras ≥ 2 km:
  wolno 4,94, miasto 4,16, płynnie 3,81, trasa 4,12 l/100 km (udział EV 62/64/60/36 %, liczony jako Σ km na prądzie / Σ km pasma, każda trasa przycięta do własnego dystansu).

## 23. Pogoda i spalanie (PRD F15, ADR-009)
Domena: Python `backend/app/domain/weather.py`, Kotlin `core/domain/WeatherOps.kt` (te same nazwy testów). Klient: `core/weather`.
Złączenie trasy z pogodą, pobieranie i ekran: `app/ui` (GPS, więc bez lustra w Pythonie, jak §18, §21, §22).
- Źródło: Open-Meteo `GET https://archive-api.open-meteo.com/v1/archive`, bez klucza, jedno miejsce na żądanie. Parametry:
  `latitude`, `longitude`, `start_date`, `end_date`, `daily=temperature_2m_mean,temperature_2m_min,temperature_2m_max,
  precipitation_sum,precipitation_hours,wind_speed_10m_max,weather_code`, `timezone=auto`, `timeformat=iso8601`. Żadnych innych parametrów,
  nagłówków uwierzytelniających ani identyfikatorów; `User-Agent: toyota-companion`.
- Prywatność: współrzędne startu trasy zaokrąglone do 0,01° (HALF_UP, ok. 1 km) – i w kluczu cache, i w żądaniu (klient
  zaokrągla jeszcze raz). Nigdy surowy GPS, VIN, konto, token ani id trasy. Ekran nie pokazuje położenia.
- Ekran „Pogoda i spalanie”: kafelki „Trasy / km” raz, na górze (pod kartą pobierania), nie pod każdym wykresem; wiersz koszyka: nazwa, pod nią jedna krótka linia „24 trasy · 312,4 km”, po prawej duże l/100 km z podpisem jednostki,
  a zł/100 km (albo „brak ceny”) idzie do linii pomocniczej pod wierszem razem z różnicą, udziałem prądu i ostrzeżeniem o małej próbie, żeby wiersz nie łamał się na 360 dp (C32 U11). Stopka z atrybucją ma własne tło i leży nad paskiem nawigacji systemu.
- Przypisanie: trasa dostaje pogodę dnia (lokalna data startu w strefie telefonu) w punkcie startu. Brak współrzędnych (albo
  poza zakresem/NaN) lub brak wartości dla dnia → trasa „bez pogody”, liczona osobno, nie wpada do żadnego koszyka.
- Koszyki (granice półotwarte): temperatura dnia (`temperature_2m_mean`) – silny mróz < −10, mróz −10…0, chłodno 0–10, łagodnie 10–20, ciepło ≥ 20 °C;
  opad (`precipitation_sum`) – sucho < 0,5 mm, mokro ≥ 0,5 mm. Odniesienie: łagodnie / sucho.
- Miary na koszyk: liczba tras, km (0,1), paliwo, l/100 km = Σ paliwo / Σ km (0,01), zł/100 km = Σ koszt / Σ km tras z ceną
  (`null` bez ceny, nigdy 0 zł), km na prądzie, różnica l/100 km wobec odniesienia (tylko gdy odniesienie ma ≥ 5 tras),
  `tooFewTrips` poniżej 5 tras. Wiatr tylko zgłaszany („wiatr do N km/h” przy koszyku), bez wniosków. Udział jazdy na prądzie nie przekracza 100 %.
- Cache: tabela `weather` (klucz `lat_r`, `lon_r`, `date`; wartości, `fetched_at`), Room v2 przez migrację automatyczną
  (schemat `2.json`), bez VIN i id trasy; nie wchodzi do kopii archiwum (§14) ani CSV. Przeżywa wylogowanie (to nie sekret).
  Dzień bez wartości z usługi zapisany z `null`.
- Pobieranie (decyzja właściciela 2026-11-01: automatycznie): po każdej udanej synchronizacji i przy otwarciu ekranu „Pogoda i spalanie”
  aplikacja sama pobiera brakujące dni – jeden przebieg do 100 zapytań (pierwsze zasilenie archiwum mieści się w jednym), po jednym
  zapytaniu naraz; przycisk „Pobierz pogodę” robi to samo ręcznie (do 10 zapytań na dotknięcie). Przełącznik „Pobieraj pogodę
  automatycznie” na ekranie (domyślnie włączony, zapisany w ustawieniach) wyłącza pobieranie automatyczne. Przy włączonym przełączniku
  przebieg trwa dalej po wyjściu z ekranu; przy wyłączonym wyjście z ekranu i wylogowanie przerywają pobieranie ręczne. Pobieranie
  automatyczne bez nic do zrobienia jest ciche, a błąd (429, sieć…) kończy przebieg z zwykłym komunikatem. Brakujące dni miejsca
  dzielone są na odcinki (dni bliższe niż 14 dni dzielą jedno zapytanie), najliczniejsze pierwsze. „Dziś” i „wczoraj” liczone są w strefie
  telefonu – tej samej, która datuje trasy; koniec odcinka przycinany do wczoraj (archiwum nie zna dziś i odrzuca przyszłość; klient
  dodatkowo trzyma twardy sufit UTC). Dzień pominięty w odpowiedzi zapisywany jest pusty, żeby nie był pytany w nieskończoność;
  odpowiedź bez ani jednego dnia to błąd „nieczytelna odpowiedź”. Dni z ostatnich 10 dni bez temperatury lub opadu (opóźnienie reanaliz)
  są pytane ponownie; starsze puste nie. Po pobraniu cache jest przycinany do dnia sprzed pierwszej trasy archiwum.
- Dzisiejszy dzień: archiwum go nie zna, więc miejsce z trasą z dzisiaj (data lokalna) pyta usługę prognoz `GET https://api.open-meteo.com/v1/forecast` (te same zmienne, `forecast_days=1`, ten sam zaokrąglony punkt; poza tym żadnych parametrów). To wartość modelowa, która później zostaje zastąpiona przez archiwum (dzień staje się „wczoraj”, a wiersz z kodu `fetched_at` sprzed pobrania archiwum jest brany od nowa przez ogólną regułę); wiersz z dzisiaj starszy niż 3 godziny jest pytany ponownie.
- Klient nie podąża za przekierowaniami (zapytanie z punktem nie może trafić do obcej domeny); 3xx = błąd usługi.
- Błędy: 429 (z `Retry-After`) → „usługa prosi o przerwę”, bez pętli; inne HTTP → błąd usługi; brak sieci; nieczytelna odpowiedź
  (także tablica JSON zamiast obiektu). Komunikaty bez treści odpowiedzi, adresu i współrzędnych. Ekran zostaje na cache'u.
- Atrybucja (CC BY 4.0) w stałej stopce ekranu (nie przewija się): „Weather data by Open-Meteo.com”. Uwaga na ekranie: to korelacja, nie pomiar przyczyny
  (reanaliza to model; zimą inne trasy; mróz skraca jazdę na prądzie, dlatego pokazujemy udział prądu).

## 24. Spalanie na ekranie statusu (PRD F20)
Funkcje czyste w `app/ui/ConsumptionTrend.kt` na `StoredTrip` (prezentacja, bez GPS – bez lustra w Pythonie, jak §22). Kontroler
`ConsumptionController` czyta archiwum po zalogowaniu i po każdej udanej synchronizacji (jak prognoza §20.2), bez pytania auta;
błąd odczytu chowa kartę; `reset()` przy wylogowaniu.
- Średnia z całego okresu = Σ paliwo / Σ km wszystkich tras (0,01 l/100 km; trasy z nieskończonym lub niedodatnim dystansem albo
  ujemnym paliwem pominięte). Pokazywana z łączną liczbą km.
- Słupki: miesiąc (data startu w strefie telefonu) = Σ paliwo / Σ km miesiąca; miesiąc liczy się od 50 km; najwyżej 12 ostatnich,
  od najstarszego. Podpis nad słupkiem: wartość (0,1), pod słupkiem: skrót miesiąca (styczeń z rokiem). Oś nie zaczyna się od zera:
  początek = 70 % najniższego miesiąca w dół do 0,1 (jeden miesiąc lub równe słupki → 0); ekran podaje początek osi.
- Trend: prosta metodą najmniejszych kwadratów przez słupki (odstęp w miesiącach kalendarzowych, więc dziura w miesiącach liczy
  się jak czas); zmiana = nachylenie × rozpiętość / średnia słupków. |zmiana| ≤ 5 % → stabilne (→), > 0 rosnące (↗), < 0 malejące
  (↘); poniżej 3 słupków brak trendu. Regresja tylko klasyfikuje obraz, więc liczona na liczbach zmiennoprzecinkowych; wszystkie
  pokazane liczby to zaokrąglone `BigDecimal`. Ekran przypomina, że zimą spalanie zwykle rośnie (sezonowość, nie usterka).

## 25. Cena ze stacji (PRD F22, ADR-011)
`core/prices/StationPrices.kt` (klient), `app/ui/StationPriceController.kt`, `StationPricePicker.kt`.
- Źródło: publiczna strona https://www.trojmiasto.pl/moto/paliwa/ (`robots.txt` nie blokuje tej ścieżki; w regulaminie portalu nie
  znaleziono zakazu automatycznego pobierania – regulaminu nie przeczytano w całości). Jedno zwykłe `GET` bez parametrów, cookie,
  konta i położenia; `User-Agent: toyota-companion`; bez przekierowań. Dane pochodzą od użytkowników portalu i stacji: mogą być
  nieaktualne lub błędne (w komentarzach pod stroną pojawiają się zarzuty o nieprawdziwą cenę jednej ze stacji).
- Stacje właściciela (na stałe w kodzie): Auchan Gdańsk, Szczęśliwa 3 (id 33831) i WATIS Gdańsk, Trakt Świętego Wojciecha 1 (id 97185).
  Cena 95 i wiek ceny („dziś”, „wczoraj”, starsza) czytane z wiersza tabeli `tr#id` (`td[title=95]`); brak liczby = brak ceny;
  strona bez żadnego wiersza stacji = błąd „nieczytelna odpowiedź” (nigdy zgadywana cena).
- Wybór źródła ceny na ekranach „Cena domyślna” i formularza tankowania: „Ręcznie” (domyślnie, pole jak dotąd), „Auchan Szczęśliwa”
  albo „WATIS”. Dotknięcie stacji pobiera stronę (tylko na dotknięcie, ponownie najwcześniej po 10 minutach) i wstawia cenę do pola
  jednorazowo na wybór; pole zostaje edytowalne, nic nie jest zapisywane przed „Zapisz” (SPEC §12.3: pole nie jest wypełniane
  samo). Pod przyciskami: stacja, cena, wiek ceny, uwaga o źródle, a dla ceny starszej niż dzisiejsza ostrzeżenie.
- Błędy (brak sieci, odpowiedź inna niż 2xx, zmiana układu strony): stały komunikat bez treści strony i adresu, „Wpisz cenę ręcznie”.
- Czego nie robi: nie wybiera najtańszej stacji, nie pobiera w tle, nie zapisuje historii cen, nie wysyła niczego o właścicielu.

## 26. Mapa w aplikacji (PRD F21, ADR-010)
`app/ui/MapScreen.kt`, `MapLink.kt`, strona `app/src/main/assets/map/map.html` z dołączonym Leaflet 1.9.4 (BSD-2-Clause) w WebView.
Bez nowych zależności Gradle.
- Wejścia: dotknięcie trasy na liście (§10.3: start i koniec, tylko te z położeniem) i „Otwórz w mapie” przy miejscu w „Postojach”
  (§18: jeden punkt). Mapa otwiera się od razu; przycisk „Otwórz w innej aplikacji z mapą” wysyła intent `geo:` jak dotąd.
- Punkty początku i końca (oraz miejsca w „Postojach”) trafiają do strony we fragmencie adresu, zaokrąglone do 0,001° (ok. 100 m); przebieg trasy dochodzi osobno (§27); w zapisanym stanie ekranu też tylko
  zaokrąglone. Etykiety to stałe teksty aplikacji („Start”, „Koniec”, „Miejsce”), nigdy dane użytkownika. Początek i koniec
  łączy prosta przerywana linia (archiwum nie ma przebiegu); ekran o tym mówi.
- Podkład: kafelki OpenStreetMap `https://tile.openstreetmap.org/{z}/{x}/{y}.png`. Serwer kafelków widzi więc okolicę punktów
  (kafelek ~100–1000 m zależnie od przybliżenia), nie dokładne położenie; ekran o tym informuje. `User-Agent` aplikacji, atrybucja
  „© OpenStreetMap contributors” w mapie (ODbL), użycie niekomercyjne i lekkie zgodnie z polityką kafelków OSM.
- WebView zamknięty: JavaScript tylko dla dołączonej strony, `allowFileAccess` i `allowContentAccess` wyłączone, brak mostu JS,
  nawigacja zablokowana, a każde żądanie poza `file:///android_asset/map/` i `https://tile.openstreetmap.org` kończy się 403
  (`mapRequestAllowed`). Strona dopasowuje wysokość do okna (`fit`), bo WebView w Compose bywa najpierw zmierzony na zerową wysokość.

## 27. Przebieg trasy na mapie (PRD F23, ADR-012)
`core/routes/TripRoutes.kt`, `app/ui/TripRouteController.kt`, `MapScreen.kt`, tabela `trip_routes` (Room v3, migracja automatyczna 2→3).
- Źródło: `GET /v1/trips/{id}` (szczegół jednej trasy, z nagłówkiem VIN – ten sam klient, konto i token co synchronizacja). Lista tras nie
  niesie przebiegu od 2026-09-30. Z odpowiedzi brane są punkty `lat`, `lon`, `isEv`; punkty z `null`, nieskończone lub spoza zakresu
  odrzucane (nigdy przycinane). Tylko odczyt: nigdy `refresh_status()`.
- Kiedy: tylko przy otwarciu mapy trasy (dotknięcie trasy), nigdy w synchronizacji ani w tle – jedno zapytanie na trasę, potem kopia na
  telefonie (tabela `trip_routes`: `trip_id`, punkty jako tekst `lat,lon,e;…` z pięcioma miejscami po przecinku ≈ 1 m, `fetched_at`).
  Toyota trzyma trasy ok. 12 miesięcy, więc po tym czasie kopia na telefonie jest jedyną.
- Wyniki: `Ready` (rysujemy przebieg: zielony – jazda na prądzie `isEv`, niebieski – silnik spalinowy lub brak informacji), `Missing`
  (pusty przebieg albo HTTP 404; zapamiętane jako pusta kopia, więc nie pytamy ponownie; mapa zostaje z początkiem, końcem i prostą linią),
  `Failed` (brak sieci, logowanie, błąd usługi: stały komunikat bez treści odpowiedzi; nic nie zapisujemy, kolejne otwarcie pyta ponownie).
- Mapa dostaje punkty przebiegu wyłącznie jako liczby (`showRoute([[lat,lon,e],…])`), strona nie ma dostępu do niczego poza kafelkami
  OpenStreetMap (§26). Serwer kafelków widzi okolicę, którą oglądasz (kafelki), więc obejmuje to cały obrys trasy, nie dokładne punkty.
- Poza kopią archiwum (§14) i CSV: przebiegi nie wchodzą do pliku eksportu (rozmiar), ale zostają w bazie na telefonie.

## 28. Prędkość i temperatura (PRD F24)
`app/ui/WeatherMatrix.kt` (funkcje czyste na `StoredTrip` i mapie pogody po id trasy – bez współrzędnych, bez lustra w Pythonie), sekcja na
ekranie „Pogoda i spalanie” (§23).
- Wiersze: pasma tempa z §22 (wolno, miasto, płynnie, trasa; trasy od 2 km z dodatnią prędkością). Kolumny: koszyki temperatury z §23 (silny mróz, mróz,
  chłodno, łagodnie, ciepło wg średniej temperatury dnia). Wchodzą tylko trasy, które mają pasmo i dzień z temperaturą; pasmo bez żadnej takiej
  trasy nie ma wiersza, koszyk bez tras – komórki.
- Komórka: liczba tras, km (0,1), l/100 km = Σ paliwo / Σ km (0,01), udział jazdy na prądzie (każda trasa przycięta do własnego dystansu).
  Poniżej 5 tras (`WEATHER_MIN_TRIPS_PER_BUCKET`) komórka pokazuje tylko liczbę („N tras – za mało”), bez liczby do wniosku.
- „Zimą przy tej prędkości” = mróz − łagodnie w tym samym paśmie, tylko gdy obie komórki mają ≥ 5 tras; inaczej brak (nie zgadujemy różnicy z kilku ttras).
- Ekran przypomina, że to korelacja, a nie pomiar przyczyny (jak §23); pogodę trzeba pobrać (§23), bez niej tabela jest pusta.
- Koszty miesięczne (ta sama karta, pod słupkami spalania): na miesiąc – suma kosztów tras z ceną (`null` i „–” bez ceny, nigdy 0 zł; trasa ze źródłem
  kosztu `NONE` liczy się jak bez ceny), km (0,1), zł/100 km = Σ koszt / Σ km tras z ceną i liczba tras bez ceny; miesiące z trasami, także krótkie
  i niepełny bieżący, najwyżej 12 ostatnich od najstarszego. Słupki od zera (kwoty), podpis nad słupkiem – pełne złotówki; pod słupkami lista
  miesięcy od najnowszego z dokładną kwotą i km. Nagłówek karty to średni koszt na miesiąc (suma kosztów ukończonych miesięcy z ceną podzielona przez ich liczbę, 0,01 zł; bieżący miesiąc nie jest pełny i odpada; bez takiego miesiąca brak nagłówka), a pod nim „Razem (kwi 2026 – lis 2026): 1 182,89 zł” – suma kosztów tras z ceną CAŁEGO archiwum (nie roku) z okresem od pierwszego do ostatniego miesiąca z trasami i liczbą tras bez ceny.

## 29. Podsumowanie roku (PRD F28)
`app/ui/YearSummary.kt`, `YearSummaryController.kt`, `YearSummaryScreen.kt` (prezentacja na `StoredTrip`, bez GPS, bez lustra w Pythonie). Wejście: przycisk
„Podsumowanie roku” na ekranie statusu.
- Lata z trasami (dystans dodatni, paliwo nieujemne) od najnowszego jako przyciski; domyślnie najnowszy. Rok wg daty startu w strefie telefonu.
- Rok: liczba tras, km i litry (wyświetlane bez miejsc po przecinku), średnie spalanie Σ paliwo / Σ km (0,01), koszt paliwa (suma tras z ceną; `null` i „Koszt: brak
  ceny”, nigdy 0 zł; zł/100 km z km tras z ceną; liczba tras bez ceny), udział jazdy na prądzie (każda trasa przycięta do własnego dystansu, całe %), liczba dni
  z jazdą, najdłuższa trasa z datą, najlepszy i najwyższy miesiąc spalania (miesiące od 50 km, jak w §24; gdy to ten sam miesiąc – tylko najlepszy), najdroższy
  miesiąc, oraz zmiana wobec poprzedniego roku (km i koszt w %, spalanie w l/100 km ze znakiem), jeśli poprzedni rok ma trasy.
- „Udostępnij podsumowanie” wysyła te same linie jako zwykły tekst do systemowego okna udostępniania (`ACTION_SEND`, `text/plain`) tylko po dotknięciu; w tekście nie
  ma położenia, VIN ani danych konta.

## 30. Spalanie deklarowane (PRD F29)
`app/ui/DeclaredConsumption.kt`, ustawienie w `SettingsStore` (`declared_consumption`), karta „Spalanie” na ekranie statusu (§24).
- Właściciel sam wpisuje spalanie mieszane swojego modelu (z danych producenta albo dowodu rejestracyjnego); aplikacja go nie zna i niczego nie zakłada.
  Pole: przecinek lub kropka, liczba > 0 i ≤ 20 l/100 km, zaokrąglona do 0,01 (HALF_UP); puste pole usuwa ustawienie; wartość spoza zakresu jest błędna (nigdy przycinana).
- Karta po wpisaniu pokazuje: „Deklarowane: 4,50 l/100 km · Twoje średnie: -8 % (taniej niż deklarowane)” – różnica średniej z całego okresu (§24) względem
  deklarowanego w % (zaokrąglone, ze znakiem). Bez ustawienia przycisk „Ustaw spalanie deklarowane”, po ustawieniu „Zmień deklarowane”.
- Wartość zostaje na telefonie, nie wchodzi do kopii archiwum ani CSV.

## 31. Alert o rosnącym spalaniu (PRD F30)
`app/ui/ConsumptionAlert.kt` (reguła, czysta), `app/notify/ConsumptionAlertNotifier.kt`, wywołany po każdej udanej synchronizacji obok powiadomienia o tankowaniu (§13).
- Reguła: trzy ostatnie ukończone miesiące ze słupków §24 (miesiąc bieżący nie jest ukończony i odpada) to kolejne miesiące kalendarzowe; każdy krok rośnie o co
  najmniej 3 % poprzedniego, a całość o co najmniej 8 % pierwszego; ostatni z trzech miesięcy przypada w marcu–październiku (zimą spalanie rośnie samo, alert tylko
  powtórzyłby sezon). Inaczej brak alertu.
- Powiadomienie: „Spalanie rośnie trzeci miesiąc z rzędu” z trzema wartościami i wzrostem, np. „l/100 km: 4,0 → 4,3 → 4,7 (+18 %). Poza zimą warto sprawdzić np.
  ciśnienie w oponach. To tylko podpowiedź, aplikacja nie diagnozuje.” Jedno miejsce powiadomienia (nowy alert zastępuje stary), kanał „Spalanie”.
- Bramki liczą dokładny wzrost (nie zaokrąglony do 0,1 pp), zaokrąglona jest tylko pokazywana liczba. „Można wysłać” znaczy: uprawnienie do powiadomień, powiadomienia aplikacji włączone i kanał „Spalanie” nie wyciszony; inaczej nic nie jest zapisywane, więc alert nie przepada.
- Raz na ostatni miesiąc: klucz `RRRR-MM` zapisany w ustawieniach. Bez uprawnienia do powiadomień nic nie jest wysyłane ani zapisywane, więc późniejsze nadanie
  go dostarczy alert bieżącego miesiąca. Błąd odczytu lub powiadomień nie przerywa synchronizacji.

## 32. Widżet na ekran główny (PRD F31)
`app/widget/ConsumptionWidgetProvider.kt` (klasyczny `AppWidgetProvider` z `RemoteViews`, bez nowych zależności), `WidgetSnapshot.kt`, układ `res/layout/widget_consumption.xml`.
- Trzy linie: średnie spalanie z całego okresu (§24, np. „4,60 l/100 km”), trend (↗/↘/→) i cena domyślna („↗ rosnące (+8,2 %) · cena 6,30 zł/l”) oraz prognoza tankowania
  (§20). Brak spalania = „Otwórz aplikację”; pusta linia jest ukryta. Dotknięcie otwiera aplikację.
- Widżet niczego nie liczy i nie pyta sieci: aplikacja zapisuje migawkę (trzy teksty) w prywatnym pliku ustawień po każdej udanej synchronizacji i odmalowuje widżet.
  Poza synchronizacją migawka jest też odświeżana po zmianie ceny domyślnej i przeliczeniu kosztów; bez tego (`updatePeriodMillis = 0`) nie ma odświeżania w tle. Wylogowanie czyści migawkę i odmalowuje widżet, więc nie zostają na nim liczby konta.
- W migawce nie ma położenia ani VIN. Użytkownik dodaje widżet z listy widżetów launchera („Toyota Companion”).

## 33. Zapis przebiegów starszych tras (PRD F32)
`app/ui/RouteBackfillController.kt`, karta na górze ekranu „Trasy” (§10.3); korzysta z tabeli `trip_routes` i klienta z §27.
- Toyota usuwa trasy po ok. 12 miesiącach; przebieg zapisany na telefonie zostaje (§27). Karta pokazuje „Przebiegi zapisane na telefonie: N z M tras” (M = trasy z jakimkolwiek
  położeniem; pusty zapis „Toyota nie ma przebiegu” też liczy się jako zapisany) i przycisk „Zapisz przebiegi starszych tras”, dopóki jakiegoś brakuje.
- Jedno dotknięcie: najwyżej 100 zapytań `GET /v1/trips/{id}` (tylko odczyt, jak §27), od najstarszych tras, po jednym naraz z przerwą 300 ms, wyłącznie po dotknięciu – nigdy w synchronizacji
  i w tle; „Zatrzymaj” przerywa, a to, co zapisano, zostaje. Trasa, której Toyota już nie ma (HTTP 404), zapisuje się jako brak przebiegu i nie jest pytana ponownie. Każdy inny błąd (brak sieci,
  logowanie, błąd usługi) kończy przebieg stałym komunikatem z liczbą zapisanych. Wylogowanie zeruje stan.
- Przebiegi nie wchodzą do kopii archiwum ani CSV (§27).

## 34. Mapa ciepła ulubionych tras (PRD F33)
`app/ui/RouteHeatController.kt`, przycisk „Pokaż przebiegi na mapie” przy każdym odcinku na ekranie „Styl jazdy” (§19), strona mapy z §26.
- Wszystkie przejazdy jednego odcinka (te same dwa miejsca w tym samym kierunku, §19), które mają zapisany przebieg (§27, §33) i spalanie (§19: od 2 km, paliwo > 0), rysowane na jednej mapie;
  kolor linii = spalanie przejazdu między najlepszym (zielony) a najgorszym (czerwony) z narysowanych (`share` = (wartość − min) / (max − min); równe = środek skali). Dotknięcie linii
  pokazuje spalanie przejazdu („4,3 l/100 km”). Pod mapą: zakres kolorów i „Przejazdy z przebiegiem: N z M”.
- Tylko odczyt z telefonu: żadne zapytanie do Toyoty; przejazd bez zapisanego przebiegu jest policzony, nie pobierany. Bez żadnego przebiegu: „Żaden z M przejazdów tej trasy nie ma
  zapisanego przebiegu. Zapisz je przyciskiem na ekranie „Trasy”.” Początek i koniec pierwszego przejazdu jako znaczniki (zaokrąglone do 0,001°, jak każdy punkt mapy).
- Strona dostaje wyłącznie liczby (punkty przebiegu, udział 0–1) i tekst spalania z cyfr i stałych słów. Wylogowanie i wyjście z mapy zerują stan.

## 35. Android Auto (PRD F34, ADR-014)
`app/car/ConsumptionCarAppService.kt` (`CarAppService`, kategoria `androidx.car.app.category.IOT`), zależność `androidx.car.app:app` 1.7.0.
- Ekran szablonowy tylko do odczytu (`PaneTemplate`): wiersze „Średnie spalanie”, „Trend i cena” i „Tankowanie” z migawki zapisanej dla widżetu (§32); bez migawki jeden wiersz „Brak danych – otwórz aplikację na
  telefonie i zsynchronizuj”. Liczby to migawka z ostatniej synchronizacji – ekran odświeża je dopiero po ponownym otwarciu. Nic do dotknięcia, więc kierowca niczego nie obsługuje. Ekran niczego nie liczy i nie pyta sieci, archiwum ani położenia.
- Walidacja hosta: wersja debugowalna przyjmuje każdego hosta (instalacja z boku i Desktop Head Unit), wersja wydana tylko hosty Google (Android Auto i host szablonów Automotive) z własnej listy `res/values/car_hosts_allowlist.xml` – kopii `hosts_allowlist_sample` z androidx.car.app 1.7.0 (prywatny zasób biblioteki, mógł zniknąć przy aktualizacji); przy aktualizacji biblioteki trzeba ją porównać.
- Ograniczenie platformy: Android Auto uruchamia z Google Play tylko aplikacje z kategorii dopuszczonych przez Google (nawigacja, POI, IoT, pogoda…), a aplikacja ze statystykami do żadnej
  nie pasuje, więc w Google Play nie przeszłaby weryfikacji. Do użytku własnego działa po włączeniu w Android Auto (telefon: Ustawienia Android Auto → kilkukrotne dotknięcie „Wersja” →
  Ustawienia programisty → „Nieznane źródła”); aplikacja pojawia się wtedy w ekranie głównym Android Auto. Sprawdzona 2026-11-12 w Desktop Head Unit (wersja debugowalna, emulator z Android Auto 18.0): aplikacja jest na liście, pokazuje „Brak danych” bez migawki i trzy wiersze z migawką widżetu; na prawdziwym zestawie samochodu i w wersji wydanej (walidacja hosta) nie była tu uruchamiana.
- Przy zmianie tej funkcji zmienia się też `*.gradle.kts` / `libs.versions.toml` – za zgodą właściciela (AGENTS.md).

## 36. Jazda na prądzie w tygodniach (PRD F35)
`app/ui/EvWeeks.kt`, sekcja „Jazda na prądzie w tygodniach” w „Statystykach” (§16–§17). Prezentacja na `StoredTrip` (bez GPS, bez lustra w Pythonie).
- Źródło: dane z tras, które i tak przychodzą w synchronizacji (`hdc.evDistance`, `hdc.evTime`); żadnych nowych zapytań. Toyota nie podaje dla tego auta (zwykła hybryda, `fuel_type` `B`) poziomu naładowania
  baterii hybrydowej (`batteryLevel` pusty w odczytach) ani napięcia akumulatora 12 V (pola nie ma w używanym API, a wywołania, które mogłyby je dać, budzą auto i są zakazane), więc to jedyny obraz baterii z danych.
- Tydzień zaczyna się w poniedziałek (data lokalna startu trasy w strefie telefonu); 12 ostatnich tygodni, które mają trasy, od najstarszego; tydzień bez tras nie występuje. Do tygodnia wchodzą trasy z dodatnim,
  skończonym dystansem.
- Na tydzień: liczba tras, km, km na prądzie (każda trasa przycięta do własnego dystansu; trasa bez danych o prądzie dodaje tylko km), udział w całych % oraz minuty na prądzie (suma `hdc.evTime`
  tras, które go podają; bez żadnej – brak minut). Słupki: udział 0–100 % na stałej osi, pod słupkiem poniedziałek („26.10”, cały podpis; przy 12 słupkach na wąskim ekranie co drugi, patrz §10.4); pod słupkami lista tygodni od najnowszego: **jeden wiersz na tydzień** – po lewej „od 09.11 · 36,2 z 62,5 km · 7 tras” (liczba tras odmieniona),
  po prawej udział („58 %”), pod spodem pasek udziału w kolorze oceny; minuty na prądzie zostają w modelu (`EvWeek.evMinutes`), ale nie są na liście (C32 U16).

## 37. Aktualizacja aplikacji z GitHuba (PRD F36, ADR-016)
`core/update/AppUpdates.kt` (`GithubReleaseClient`, `AppVersion`), `app/ui/UpdateController.kt`, `app/update/AndroidApkInstaller.kt`, karta „Aktualizacja aplikacji” w „Opcjach”.
- Wersja: `MAJOR.MINOR.PATCH` (MINOR i PATCH 0–99), `versionCode = MAJOR·10000 + MINOR·100 + PATCH` – ten sam wzór w `AppVersion.codeOf` i `app/build.gradle.kts`
  (`-PappVersion=…`, domyślnie `0.1.0` = 100). Wydanie to tag `vMAJOR.MINOR.PATCH`; `.github/workflows/release.yml` uruchamia testy i lint, buduje `assembleRelease`
  podpisane kluczem z sekretów repozytorium (`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`), sprawdza podpis (`apksigner`: jedynym certyfikatem
  musi być klucz wydań, SHA-256 `4047aa03…7127` w `RELEASE_CERT_SHA256`) i `versionCode`/`versionName`
  w APK względem tagu (`aapt2 dump badging`); checkout bez zapisanego tokenu (`persist-credentials: false`). Publikuje Release z jednym plikiem
  `toyota-companion-<wersja>.apk`. Bez klucza build release jest niepodpisany i workflow kończy się błędem przed publikacją.
- Sprawdzenie tylko po dotknięciu „Sprawdź aktualizacje”: `GET https://api.github.com/repos/lukaszkosminski/toyota-companion/releases/latest` (User-Agent `toyota-companion`, bez tokenu
  i parametrów). 404 = brak wydań albo brak repozytorium: wtedy drugie `GET https://api.github.com/repos/<repo>` (te same nagłówki, bez tokenu) – 200 = brak wydań = „Masz najnowszą wersję”, 404 = „Nie znaleziono repozytorium wydań…” (błąd, nie „masz najnowszą wersję”); wydanie z `versionCode` ≤ zainstalowanego – to samo; nowsze – „Dostępna wersja X” i „Pobierz i zainstaluj”.
  Wydanie musi mieć tag będący wersją i dokładnie jeden plik `.apk` z adresu `https://github.com/<repo>/releases/download/` (porównany jako sparsowany URL: schemat, host, port i ścieżka,
  więc `..` nie wyprowadza poza wydania), rozmiarem 1 B – 200 MB; inaczej błąd „nieczytelne wydanie”.
- „Pobierz i zainstaluj”: bez zgody „Instalowanie nieznanych aplikacji” karta prosi o nią (przycisk otwiera ustawienia tej aplikacji) i nic nie pobiera. Pobranie do `cache/updates/update.apk`
  (przekierowanie GitHuba tylko HTTPS → HTTPS) z postępem w %, potem kontrola: rozmiar równy zapowiedzianemu, SHA-256 równy `digest` z API (gdy API go podaje), nazwa pakietu, `versionCode`
  równy wersji z tagu i certyfikat podpisu zainstalowanej aplikacji wśród podpisów pliku (z historią rotacji). Plik niezgodny jest usuwany i nie trafia do instalatora.
- Instalacja przez sesję `PackageInstaller`; system zawsze pokazuje własne okno potwierdzenia. Anulowanie wraca do „Dostępna wersja X”; błąd instalatora – „Instalacja się nie udała”.
  W czasie instalacji przycisk „Pobierz i zainstaluj” zostaje dostępny: gdy okno systemu się nie pokaże (Android blokuje start okna z tła), dotknięcie zaczyna od nowa.
  Plik jest usuwany po każdym wyniku. Po udanej instalacji system zastępuje proces; dane (Room, ustawienia, sekrety) zostają, bo klucz podpisu jest ten sam.
- Błędy: brak sieci, GitHub (HTTP ≠ 2xx, w tym limit 60 zapytań/h bez tokenu), brak repozytorium wydań (404 także na `GET /repos/<repo>`), nieczytelne wydanie lub plik, zapis pliku w telefonie (`STORAGE`, np. brak miejsca – nie „brak sieci”), plik nie tej aplikacji / innego klucza, błąd instalacji – tylko rodzaj, bez treści odpowiedzi.
- Build release jest zminifikowany przez R8 (ADR-017, `proguard-rules.pro`): CI buduje `:app:assembleRelease` przy każdym pushu, więc błąd reguł wychodzi przed tagiem.
- Stan nie zależy od logowania do MyToyota i nie jest czyszczony przy wylogowaniu. Brak sprawdzania w tle i powiadomień (decyzja właściciela).

## 38. Wydatki poza paliwem (PRD F37, ADR-018)
`app/ui/Expenses.kt` (`ExpenseCategory`, `Expense`, `parseExpenseAmount`, `categoryTotals`, `monthTotals`), `ExpensesController.kt`, `ExpensesScreen.kt`; tabela `expenses` w `data/db`.
- Wejście: kafel „Wydatki” w menu ekranu statusu. Ekran nie dotyka API Toyoty, `refuel_status` ani synchronizacji; wszystko jest lokalne.
- Kategorie (`ExpenseCategory`): serwis, przegląd, opony, ubezpieczenie, części, myjnia, opłaty, inne. Kategoria zapisana w bazie jako nazwa enuma; nieznana nazwa (np. z nowszej wersji) czyta się jako „inne”.
- Formularz („Dodaj wydatek”): data (domyślnie dziś, dowolny dzień, także wstecz), kategoria, kwota, opis (opcjonalny, po `trim`; pusty = brak opisu). Kwota (`parseExpenseAmount`): po usunięciu wszystkich białych znaków (także twardej spacji `\u00A0`, którą aplikacja wstawia przy grupowaniu tysięcy), przecinek = kropka;
  do 7 cyfr całkowitych i do 2 miejsc po przecinku, większa od zera. Akceptowane: „1 234,50”, „1234.5”, „350”; odrzucane: puste, `0`, `0,00`, ujemne, 8 cyfr, „1,234” (3 miejsca), tekst. Odrzucenie pokazuje „Podaj kwotę, np. 350 lub 1234,50.” i nic nie zapisuje.
- Lista: od najnowszej daty (przy tej samej – po `id`); usunięcie jednym dotknięciem („Usuń”, opisane dla czytnika ekranu). Każdy zapis i usunięcie przeładowuje listę.
- Sumy: „Paliwo i reszta” (paliwo z tras ze spalania §24 wobec sumy wydatków, pierścień z udziałem), „Według rodzaju” – sumy kategorii **malejąco**, bez kategorii bez wydatków (`categoryTotals`), a wśród nich wiersz „Paliwo (z tras)” (suma paliwa z tras z ceną, §24) ułożony tą samą kolejnością, „Według miesięcy (z paliwem)” – słupki
  miesięcy **od najstarszego** (`monthTotals`, ostatnie 12; wydatki miesiąca plus paliwo z tras tego miesiąca, miesiąc tylko z paliwem też ma słupek), pokazywane gdy jest więcej niż jeden miesiąc.
  Paliwo jest częścią ekranu także bez żadnego wydatku: wtedy są karty „Paliwo i reszta”, „Według rodzaju” (sam wiersz paliwa) i podpowiedź o dodaniu pierwszego wydatku; sam komunikat „Brak wydatków…” tylko bez wydatków i bez kosztu paliwa.
  Wydatków nie ma na liście „Historia” dla paliwa: paliwo to koszty tras (§10), nie wpisy do usunięcia.
- Dane: tabela `expenses (id, date_epoch_day, category, amount_pln TEXT, note)` w Room v5 (migracja 4 → 5, auto), indeks po dacie; kwota jako `BigDecimal` w tekście (§8.2). Wydatki są w eksporcie i imporcie archiwum (§14, import po `id`, bez duplikatów)
  i w kopii Auto Backup (ADR-015). Wylogowanie czyści listę w pamięci ekranu, nie bazę.
- Błędy: nieudany odczyt – „Nie udało się odczytać wydatków.”; nieudany zapis lub usunięcie – wiersze zostają jak były, stan oznaczony jako błąd, bez treści wyjątku. Brak wydatków – „Brak wydatków. Dodaj przegląd, opony, serwis – także z datą wstecz.”
- Testy: `ExpensesTest` (parser, sumy), `ExpensesControllerTest` (kontroler na sztucznym `ExpenseDao`), `AppDatabaseMigrationTest` (migracja 4 → 5). Brak lustra w Pythonie: to prezentacja, nie domena (ADR-018).

## 39. Koszt całkowity auta (PRD F38, ADR-020)
Domena: `core/domain/OwnershipOps.kt` (`ownershipCost`, `ownershipMonths`, model `OwnershipCost` w `Models.kt`) i lustro `backend/app/domain/ownership.py`, testy o tych samych nazwach
(`OwnershipOpsTest`, `test_domain_ownership.py`). Prezentacja: `app/ui/YearSummary.kt` (`yearOwnership`, `ownershipLines`), `YearSummaryController` (czyta też wydatki), `YearSummaryScreen`.
- `ownershipCost(koszt paliwa?, km, wydatki, miesiące)`: suma = paliwo (0, gdy żadna trasa nie ma ceny) + wydatki; `null`, gdy nie ma ani kosztu paliwa (> 0), ani wydatków.
  zł/km i zł/100 km dzielą sumę przez **wszystkie** km roku (`null` przy 0 km), średnia na miesiąc przez liczbę miesięcy (`null` przy 0), udział paliwa w całych %
  (`null` bez kosztu paliwa). Kwoty 0,01 zł, wszystko HALF_UP. Ujemne wydatki to błąd programu.
- `ownershipMonths(rok, pierwszy miesiąc, dziś)`: od pierwszego miesiąca roku z trasą albo wydatkiem do grudnia, w bieżącym roku do bieżącego miesiąca włącznie,
  0 dla przyszłego roku – auto kupione w kwietniu nie jest uśredniane od stycznia.
- Rok: paliwo = koszt tras z ceną z §29 (trasy bez ceny nie są dokładane ani szacowane – dopisek o nich jest w karcie roku), wydatki = wpisy z F37 z datą w tym roku.
- Karta „Koszt całkowity” w „Podsumowaniu roku” (po „Statystykach”, przed „Rok do roku”), tylko gdy rok ma koszt: pasek „Udział paliwa”, linie
  „Koszt całkowity: 1 456,50 zł (3,10 zł/km · 309,89 zł/100 km)”, „Średnio 161,83 zł na miesiąc (z 9 miesięcy)”, „Paliwo 106,00 zł (7 %) · wydatki 1 350,50 zł”,
  „Najwięcej poza paliwem: Opony 800,50 zł, Serwis 500,00 zł, Myjnia 40,00 zł” (trzy największe kategorie roku) i „Koszt całkowity wobec poprzedniego roku: +51 %”
  (gdy poprzedni rok ma koszt), pod nimi skąd są liczby. Te same linie kończą tekst „Udostępnij podsumowanie” (§29), bez położenia i VIN.
- Nic nie idzie do sieci; wydatki czytane z tej samej bazy co ekran „Wydatki” (§38).

## 40. Cele eco (PRD F39)
`app/ui/EcoGoals.kt` (`EcoGoals`, `parseEvGoal`, `ecoMonth`, `ecoProgress`, `ecoWidgetLine`, `EcoGoalsController`), `ui/EcoGoalsCard.kt` (karta i okno), `settings/SettingsStore.kt` (dwa klucze), `widget/WidgetSnapshot.kt`.
- Dwa niezależne cele, oba opcjonalne: (1) spalanie miesiąca **najwyżej** X l/100 km – liczba z przedziału (0; 20], przecinek lub kropka, dwa miejsca HALF_UP, ten sam parser co spalanie deklarowane (`parseDeclaredConsumption`, §30);
  (2) udział jazdy na prądzie **co najmniej** Y % – liczba całkowita 1–100 (dopuszczone „40” i „40 %”; ułamek, 0, 101, ujemna i tekst są odrzucane, nigdy przycinane). Puste pole usuwa dany cel. Zapis dotyczy obu pól naraz: gdy któreś jest błędne, nic się nie zmienia, a okno zostaje otwarte.
- Cele są w ustawieniach telefonu (`settings`, klucze `eco_goal_consumption` – tekst `BigDecimal`, `eco_goal_ev_pct` – liczba), jak §30: poza archiwum, eksportem JSON i CSV; przeżywają wylogowanie. Zapis poza przedziałem czyta się jak brak celu.
- Miesiąc = miesiąc kalendarzowy w strefie telefonu (data startu trasy). Spalanie miesiąca = Σ paliwa / Σ km tras miesiąca (0,01 HALF_UP). Udział prądu jak w §29: prąd każdej trasy przycięty do jej dystansu, Σ prądu / Σ km, całe % HALF_UP;
  gdy żadna trasa miesiąca nie podaje jazdy na prądzie – brak danych (to nie 0 %), linia „Jazda na prądzie: brak danych w tym miesiącu”; trasa z danymi i 0 km prądu daje 0 %. Trasy z niedodatnim lub nieskończonym dystansem albo ujemnym paliwem są pomijane (jak na karcie spalania).
- Cel jest osiągnięty, gdy spalanie ≤ X albo udział ≥ Y (równość to osiągnięcie). **Poniżej 50 km w miesiącu** (`ECO_MIN_MONTH_KM`, jak próg słupka §24) nic nie jest oceniane: „Za mało jazdy, by ocenić (N km)” (km w całych).
- Karta „Cel miesiąca” na ekranie statusu pod „Spalaniem” (przed „Kosztami miesięcznymi”), tylko gdy jest choć jeden cel: linie „Spalanie: 4,30 / 4,50 l/100 km ✓” i „Jazda na prądzie: 42 / 40 % ✓” (✓ – w celu, ✗ – poza celem; w bieżącym miesiącu to stan na dziś, nie wynik),
  pod nimi „Poprzedni miesiąc: cel spalania – osiągnięty · cel jazdy na prądzie – nieosiągnięty” (albo „za mało jazdy, by ocenić (N km)”; bez linii, gdy poprzedni miesiąc nie miał tras) i „Zmień cele”. Bez celów w karcie „Spalanie” jest przycisk „Ustaw cel”.
  Okno „Cele eco”: dwa pola wypełnione bieżącymi wartościami.
- Przeliczenie po zalogowaniu, po każdej udanej synchronizacji (obok odświeżenia karty spalania) i po zmianie celów; nieudany odczyt tras zostawia cele i ukrywa liczby. Wylogowanie czyści liczby, nie cele.
- Widżet (§32): czwarta linia „Cel: 4,30 / 4,50 l/100 km ✓ · prąd 42 / 40 % ✓” (albo „Cel: za mało jazdy (N km)”), ukryta, gdy nie ma celu albo liczb; odświeżana razem z pozostałymi liniami i po zmianie celów. Brak linii dla celu prądu bez danych.
- Teksty PL/EN w `Texts` (blok „Cele eco”); liczebniki odmieniane w tych tekstach nie występują (km i % są nieodmienne), więc `plPlural` nie jest potrzebne.
- Bez sieci i bez API Toyoty; czyste funkcje i kontroler bez Compose. Testy: `EcoGoalsTest`, `EcoGoalsControllerTest`, `WidgetSnapshotTest`, `TextsTest`, `SettingsStoreTest`.

## 41. Autostrada i przekroczenia prędkości w trasach (PRD F40, ADR-021)
`core/toyota/dto/Dto.kt` (`TripSummaryDto`), `ToyotaMapper.toTripRecord`, `core/data/Models.kt` (`TripRecord`), `data/db` (`TripEntity`, schemat 6), `ArchiveFile`, `app/ui/TripDriveExtras.kt`.
- Źródło: pola podsumowania trasy `lengthOverspeed`, `durationOverspeed`, `lengthHighway`, `durationHighway` (pytoyoda `trips.py`, fixtury testowe: metry i sekundy, jak `hdc`). To te same odpowiedzi, które synchronizacja już pobiera; **nie ma nowych żądań** ani `refresh_status`.
  Nie wiemy, czy konto zwraca je dla każdej trasy: brak pola = „nie podano” (`null`), nigdy 0.
- `TripRecord`: `overspeedLengthM`, `overspeedDurationS`, `highwayLengthM`, `highwayDurationS` (`Long?`, domyślnie `null`, na końcu konstruktora). Nie wchodzą do `Trip` (domeny kosztów), więc nie ma lustra w Pythonie.
- Room: schemat **6**, auto-migracja 5 → 6, cztery kolumny `INTEGER` bez `NOT NULL` (`overspeed_length_m`, `overspeed_duration_s`, `highway_length_m`, `highway_duration_s`); trasy zapisane wcześniej mają `null`.
  Dane sprzed tej wersji nie są uzupełniane: synchronizacja zapisuje trasy od znacznika z zakładką 2 dni (§8.3), więc pola mają tylko nowe trasy.
- Archiwum (§14): cztery opcjonalne klucze w trasie (`overspeedLengthM`, `overspeedDurationS`, `highwayLengthM`, `highwayDurationS`), format nadal w wersji 1; plik bez nich wczytuje się z `null`. Nie ma ich w CSV (§14.4).
- Wiersz trasy (§10.3): dodatkowa linia po rodzaju jazdy, `autostrada 4,2 km · przekroczenia 1,3 km (9 %)`: kilometry z jednym miejscem po przecinku (HALF_UP), udział przekroczeń to całe % dystansu trasy (nie więcej niż 100 %, przy niepoprawnym dystansie 0 %).
  Części z `null` lub 0 są pomijane; bez żadnej części linii nie ma. EN: „motorway … km”, „speeding … km (…) %”.
- Nie robimy (jeszcze): zdarzeń jazdy (`behaviours`: pole `good`, `severity`, współrzędne), sum miesięcznych ani wykresu – zależą od tego, co konto naprawdę zwraca (sonda struktury, HANDOFF).
- Testy: `ToyotaMapperArchiveTest`, `ArchiveFileTest`, `AppDatabaseMigrationTest` (v5 → v6 i seed każdej wersji), `TripDriveExtrasTest`.


## §42 Stan pojazdu (PRD F41)
`core/toyota/dto/RemoteDto.kt` (`VehicleStatusResponse`, `RemoteStatusDto`), `core/toyota/VehicleDataMapper.kt`, `core/domain/VehicleData.kt`, `core/vehicle/VehicleDataGateway.kt`, `app/ui/RemoteDataController.kt`, `RemoteDataScreens.kt`, `VehicleDataFormatting.kt`.
- Źródło: `GET /v1/vehicle/status` (pytoyoda `status.py:59-95`) – odczyt **cache w chmurze Toyoty**, bez `refresh_status()` i bez komend; auto nie jest budzone (AGENTS.md, PRD §6).
  Pola: `doors` (driver/passenger/rearLeft/rearRight/rearBack/hood) z `lockStatus`/`openStatus`, `windows` (4), `overallWarningCounts`, `lastUpdateTimestamp`.
- Ekran z kafelka „Stan pojazdu” w menu statusu: odczyt **raz przy każdym otwarciu** (zwykły GET po dotknięciu), bez odpytywania w tle i bez `LaunchedEffect` z pętlą.
  Karty: „Stan z …” + „Aktywne ostrzeżenia: N”, „Drzwi i maska”, „Okna” oraz „Stan techniczny” (§44); części, których Toyota nie przysłała, po prostu nie ma na liście.
  Na górze karty stanu jedno zdanie (`vehicleSummary`): „Wszystko zamknięte i zablokowane”, gdy co najmniej jedna część jest zgłoszona, żadna nie jest otwarta ani odblokowana i żadna nie jest bez danych;
  inaczej, gdy są odstępstwa, „Do sprawdzenia: N – otwarte lub odblokowane”; bez żadnego z tych przypadków zdania nie ma. Odstępstwa (otwarte, odblokowane) są w liście pogrubione w kolorze ostrzeżenia z palety (C32 U17).
- Wartości: `status` `open`/`close` → „otwarte”/„zamknięte”, `locked`/`unlocked` → „zablokowane”/„odblokowane”; nieznana wartość i brak części = „brak danych”,
  nigdy zgadywanie. Maska raportuje tylko otwarcie (bez zamka). Kolejność drzwi i okien stała, niezależna od kolejności w JSON.
- Błędy: 403/404 → „Toyota nie udostępnia tych danych dla tego auta.”; inny błąd → komunikat odczytu + „Spróbuj ponownie”; 200 bez payloadu → „Toyota nie przysłała tych danych.”.
- Zawartość pól jest **niezweryfikowana** na żywym koncie (sonda struktury, HANDOFF). Światła, o których mówi opis endpointu w pytoyoda, nie są mapowane, bo model ich nie ma – nic nie jest zgadywane.
- Prywatność: same stany (bez pozycji); nic nie trafia do bazy, archiwum ani kopii.

## §43 Lokalizacja (PRD F42, ADR-022)
- Źródło: `GET /v1/location` (pytoyoda `location.py`) – ostatnia znana pozycja, aktualizowana przez Toyotę po zaparkowaniu; cache w chmurze, bez budzenia auta.
- Ekran: nazwa miejsca (`displayName`, np. adres), **dokładne** współrzędne `54.36850, 18.61420` (kropka dziesiętna, 5 miejsc ≈ 1 m; `BigDecimal.valueOf` + HALF_UP,
  więc 54.3685 daje 54.36850, bez artefaktów binarnych), „Pozycja z …”, „Ostatnia aktualizacja …” (tylko gdy różni się od czasu pozycji o co najmniej godzinę albo gdy brak czasu pozycji; minuta różnicy to ten sam moment) i przycisk „Pokaż na mapie” (jedna dokładna pozycja, tylko po dotknięciu).
- Nazwa miejsca Toyoty: tekst `Last Parked` czyta się „Ostatnie parkowanie”; inna wartość zostaje bez zmian (referencja opisuje pole tylko jako „human-readable name”, więc żadnej innej wartości nie tłumaczymy na zgadywanie).
  Gdy pozycja leży do 150 m (`PARKING_MATCH_M`) od znanego miejsca z „Postojów” (Dom, Praca; `knownPlaces`), nazwa tego miejsca stoi nad współrzędnymi. Porównanie liczy się **wyłącznie w pamięci** (`knownPlaceNear`, `distanceMeters`): dokładna pozycja
  nie trafia do bazy, ustawień ani logów; miejsca idą z archiwum tras przez `loadTrips` w `AppRoot` i są zapominane razem z pozycją (C32 U8).
- Dokładna pozycja to **świadomy wyjątek** od §18 (Postoje pokazują zaokrąglenie do ok. 100 m): decyzja właściciela 2026-11-08 (ADR-022).
  Pozycja i adres są **tylko w pamięci**: bez bazy, eksportu archiwum, CSV, `rememberSaveable`/zapisanego stanu, logów i komunikatów błędów; `reset()` przy wyjściu z ekranu i przy wylogowaniu.
  Mapa lub intent `geo:` tylko po dotknięciu, z dokładną pozycją (5 miejsc po przecinku; reszta aplikacji zostaje przy ok. 100 m): `MapScreen(exact = true)`, `ParkingFormatting.mapUri(…, exact = true)`; serwer kafelków OSM widzi oglądaną okolicę,
  a aplikacja wybrana w „Otwórz w innej aplikacji z mapą” dostaje dokładną pozycję (SECURITY.md). Punkt mapy leży w `remember`, nie w `rememberSaveable` (`AppRoot.exactMap`): po zabiciu procesu mapa wraca na ekran statusu, a dokładna pozycja nie trafia do zapisanego stanu.
- Odczyt przy każdym otwarciu ekranu (świeża pozycja), także po obrocie ekranu lub odtworzeniu ekranu z zapisanego stanu (zwykły `GET` do chmury, nie budzi auta); `reset()` przy „Wstecz” i wylogowaniu, a przejście do mapy zostawia pozycję w pamięci do powrotu, który czyta ją od nowa; błędy jak w §42. Brak współrzędnych = „Toyota nie przysłała tych danych.”, bez mapy.

## §44 Stan techniczny (PRD F43)
- Źródło: `GET /v1/vehiclehealth/status` (pytoyoda `vehicle_health.py`) – ostrzeżenia i dane o oleju silnika z chmury Toyoty; bez budzenia auta.
- „Stan techniczny” jest **kartą na ekranie „Stan pojazdu”** (§42), nie osobnym kafelkiem i ekranem (C32 U14): osobny odczyt (`RemoteDataController<VehicleHealth>`) i osobny komunikat błędu, więc awaria jednego odczytu nie czyści drugiego.
  Karta: „Aktywne ostrzeżenia: N”, informacja, czy Toyota przysłała dane o oleju, „Ostrzeżenia z …”, podpowiedź o granicach danych (bez słowa „backend”).
- Payload ma `warning` i `quantityOfEngOilIcon` jako `list[Any]` w referencji, więc aplikacja **liczy wpisy i nie interpretuje ich treści** (nie zgaduje kodów ani ikon).
  Znaczenie i dostępność pól zależą od sondy struktury odpowiedzi (HANDOFF); brak listy = brak danych, nie 0.
- Błędy jak w §42; nic nie jest zapisywane w bazie ani w kopii.

## §45 Historia serwisowa (PRD F44)
- Źródło: `GET /v1/servicehistory/vehicle/summary` (pytoyoda `service_history.py:12-66`) – wpisy serwisowe zapisane przez Toyotę; odczyt **cache w chmurze**, bez `refresh_status()` i bez komend, więc auto nie jest budzone.
  Pliki: `core/toyota/ToyotaConst.kt` (`SERVICE_HISTORY_ENDPOINT`), `core/toyota/dto/ServiceHistoryDto.kt`, `ToyotaApi.getServiceHistory`, `core/toyota/VehicleDataMapper.kt` (`toServiceHistory`), `core/domain/VehicleService.kt` (`ServiceRecord`, `ServiceHistory`), `core/vehicle/VehicleDataGateway.kt`, `app/ui/ServiceFormatting.kt`, `app/ui/ServiceScreens.kt`, `app/ui/AppRoot.kt`, `app/ui/StatusScreen.kt`.
- Ekran z kafelka „Historia serwisowa” w menu statusu: odczyt **raz przy każdym otwarciu** (zwykły GET), bez odpytywania w tle i bez `LaunchedEffect` z pętlą.
- Wpis pokazuje wiersze etykieta–wartość (`serviceDetails`): „Przebieg”, „Kategoria”, „Dealer”, „Czynności”. Kategoria znanych kodów ma opis z kodem w nawiasie: `MNT` → „Przegląd okresowy (MNT)”, `VDE` → „Wydanie auta (VDE)”; każdy inny kod zostaje bez zmian, bez zgadywania. **Znaczenia `MNT` i `VDE` pochodzą z potwierdzenia właściciela 2026-11-10 na podstawie jego własnych wpisów, a nie z dokumentacji Toyoty** (C32 U9).
- Wpis pokazuje: datę (`serviceDate`, `DD.MM.RRRR`), przebieg (`mileage` z jednostką `unit` – grupowany, bez jednostki gdy Toyota jej nie przysłała), kategorię (`serviceCategory`), czynności (`operationsPerformed`) i dealera. Lista od najnowszego wpisu; wpis bez daty sortuje się na końcu, nie znika.
- **Decyzja właściciela 2026-11-09:** etykieta „Dealer” = `servicingDealer`, a gdy go brak – `serviceProvider`; gdy oba puste: „brak danych”. **Nie pokazujemy:** `serviceHistoryId`, `roNumber`, `notes`, `customerCreatedRecord`.
- `operationsPerformed` jest `Any` w referencji, więc aplikacja **nie interpretuje treści**: tekst → jedna pozycja, tablica tekstów → lista, pusta tablica → brak pozycji, inny kształt → „Toyota nie przysłała opisu czynności.”.
- Brak `payload` = „Toyota nie przysłała tych danych.”; pusta lista wpisów = „Toyota nie ma wpisów serwisowych dla tego auta.”; 403/404 = „Toyota nie udostępnia tych danych dla tego auta.”; inny błąd = komunikat odczytu + „Spróbuj ponownie”.
- Kształt pól jest **niezweryfikowany** na koncie właściciela: decyzję o dealerze potwierdza sonda struktury (`TOYOTA_PROBE=structure`, `Probe.kt` poz. [7]), którą właściciel odpala sam, bo wymaga loginu. DTO jest tolerancyjne – wszystkie pola nullable, nieznane klucze ignorowane – więc wynik sondy nie wymusza przeróbek, tylko ewentualną korektę mapowania.
- Prywatność: tylko w pamięci; bez bazy, archiwum, eksportu, CSV i `rememberSaveable`; `reset()` przy wylogowaniu.

## §46 Powiadomienia (PRD F45)
- Źródło: `GET /v2/notification/history` (pytoyoda `notifications.py:22-90`) – historia powiadomień konta; odczyt cache w chmurze, bez budzenia auta. Endpoint **nie ma stronicowania ani oznaczania jako przeczytane** (`api.py:302-320`), więc aplikacja niczego nie zmienia po stronie Toyoty.
  Pliki: `ToyotaConst.kt` (`NOTIFICATION_HISTORY_ENDPOINT`), `core/toyota/dto/NotificationDto.kt`, `ToyotaApi.getNotifications`, `VehicleDataMapper.toNotifications`, `core/domain/VehicleService.kt` (`ToyotaNotification`), `VehicleDataGateway.kt`, `ServiceFormatting.kt`, `ServiceScreens.kt`.
- `payload` to lista stron (jedna na pojazd), a `notifications` może być `null` (konto bez powiadomień, pytoyoda rzuca wtedy `TypeError`) – to „brak danych”, nie błąd.
- Wpis pokazuje (C32 U18): datę (`notificationDate`, strefa lokalna, pogrubioną, gdy nieprzeczytany); stan – nieprzeczytany ma małą etykietę „nieprzeczytane” w wierszu daty w kolorze akcentu, przeczytany bez etykiety (stonowane „przeczytane”), brak = nic; w treści zdjęty prefiks maski VIN (`*************4408: ` → sama treść; `notificationMessageText`, tylko ten dokładny kształt, gwiazdki w środku zostają); etykieta kategorii i typu tylko dla wartości z referencji i fixture’ów (`General` → „Ogólne”, `Vehicle Alerts` → „Alerty pojazdu”, `Remote Commands` → „Zdalne polecenia”, typ `alert` → „ostrzeżenie”; EN bez zmian), reszta bez tłumaczenia. Kategoria (`displayCategory`, a gdy brak – `category`) i typ (`type`) są w jednej linii pod datą, a pod nimi treść (`message`). Najnowsze pierwsze; **wszystkie wpisy** (decyzja właściciela 2026-11-09), lista przewijana.
- VIN w treści powiadomienia jest maskowany do czterech ostatnich znaków; pełny VIN nie trafia na ekran, do logów ani komunikatów błędów (SECURITY.md). Pusty `vin` strony lub wpisu jest traktowany jak brak – maskowanie korzysta wtedy z VIN-u auta.
- Brak `payload` = „Toyota nie przysłała tych danych.”; pusta lista = „Toyota nie ma powiadomień dla tego auta.”; błędy jak w §42.
- Prywatność: tylko w pamięci; bez bazy, archiwum, eksportu i `rememberSaveable`; `reset()` przy wylogowaniu.

## §47 Diagnostyka synchronizacji (PRD F46)
- Ekran „Diagnostyka” z kafelka w menu statusu: odczyt **lokalny** (archiwum + `SyncState`), bez wywołania Toyoty i bez `refresh_status()`, więc auto nie jest budzone; poza licznikami czasu w `sync_state` nic nie jest zapisywane.
  Pliki: `core/data/Models.kt` (`SyncState.lastVehiclesSuccessAt`, `lastTelemetrySuccessAt`, `lastTripsSuccessAt`, `lastErrorStatus`, `SyncStatus.carStateAt`), `core/sync/SyncOutcome.kt` (`SyncError.status`), `core/sync/SyncEngine.kt`, `app/data/db` (schemat 7), `app/ui/RemoteDataController.kt`, `app/ui/DiagnosticsFormatting.kt`, `app/ui/DiagnosticsScreen.kt`, `app/ui/AppRoot.kt`, `app/ui/StatusScreen.kt`.
- Pokazuje: wersję aplikacji, czas ostatniej próby i ostatniego pełnego sukcesu, czasy ostatniego udanego pobrania **pojazdów/konta, telemetrii i tras** (każdy krok zapisany osobno, więc późniejszy błąd nie gubi wcześniejszych), czas stanu auta z Toyoty (`occurrence_date` = `ts` najnowszego odczytu telemetrii), ostatni błąd (czas, rodzaj, kod HTTP, gdy był – np. 429, 403) oraz liczniki archiwum (trasy, odczyty, tankowania bez ceny).
- Sukces kroku = **pobranie i zapis**: czas telemetrii jest zapisywany dopiero po zapisach odczytu i tankowania (jak czas tras po `upsertAll`), więc błąd archiwum (STORAGE) nie wygląda na udany krok.
- „Brak” zamiast zmyślonej daty: przed pierwszym krokiem wszystkie czasy są `null`; **miękki** błąd telemetrii nie rusza jej czasu, ale trasy i koszty idą dalej; kod HTTP jest pokazywany tylko z zakresu 100–599 (`-1` z `ToyotaController` oznacza brak odpowiedzi i nie jest kodem).
- **Ostatnie dobre dane zostają na ekranie** (dotyczy §42–§46): po błędzie lub 403/404 ponowny odczyt zachowuje poprzednie dane i pokazuje komunikat błędu oraz linię „Dane poniżej pochodzą z ostatniego udanego odczytu…” nad nimi, zamiast czyścić ekran; odpowiedź 200 bez payloadu to nadal „Toyota nie przysłała tych danych”. `reset()` (Wstecz, wylogowanie) czyści także zachowane dane.
- Ekran (C32 U20): wiersze etykieta–wartość (`diagnosticsRows`, ten sam komponent co historia serwisowa), czasy w krótkim formacie `10.11 19:04` (`TripsFormatting.shortStamp`, rok tylko gdy inny niż bieżący), bez powtórzonego tytułu; komunikat przechodzi te same dwa przebiegi maskowania co raport; „Kopiuj diagnostykę” z ikoną kopiowania (`AppIcons.ContentCopy`). Raport w schowku i „Udostępnij” to bez zmian – format ISO `RRRR-MM-DD GG:mm`, testy raportu bez zmian.
- „Kopiuj diagnostykę” i „Udostępnij” dają ten sam tekst; **bez VIN, współrzędnych GPS i tokenów**; komunikat błędu przechodzi `ToyotaSecrets.sanitize` przy zapisie i jeszcze raz przy budowie raportu, a każde wystąpienie gołego VIN (także bez klucza `vin=`) jest maskowane do czterech ostatnich znaków; komunikat pokazujemy tylko razem z rodzajem błędu, żeby stary wpis bez rodzaju nie wyglądał na „brak błędu” z komunikatem.
- Migracja Room 6→7: nowe kolumny `sync_state` są nullable, stary wiersz zostaje bez zmian (test migracji każdej wersji).

## §48 Arkusze tankowań i wydatków w CSV (PRD F47)
- Rozszerzenie eksportu arkuszy z §14.4: obok tras dwa kolejne pliki CSV, w tym samym formacie (separator `;`,
  przecinek dziesiętny, UTF-8 z BOM doklejany przy zapisie, CRLF, cudzysłów i ochrona przed CSV injection).
  Pliki: `app/ui/RefuelsCsv.kt` (`refuelsCsv`, `refuelsCsvFileName`), `app/ui/ExpensesCsv.kt` (`expensesCsv`,
  `expensesCsvFileName`), `app/ui/TripsCsv.kt` (współdzielone `csvRow`/`csvDate`), `BackupController`,
  `BackupScreen`, `Texts`, `AppContainer`. Bez zmian w `core/domain` i w Pythonie – to formatowanie w warstwie `app`.
- **Tankowania** (`Texts.CSV_REFUELS_HEADER`): potwierdzone tankowania wybranego auta, te same co w dzienniku (§15.2),
  od najstarszego (`ts`, remis – po `id`). Kolumny: `Data` (`RRRR-MM-DD GG:MM`, strefa ekranu), `Licznik [km]` (1 miejsce),
  `Poziom przed [%]` i `Poziom po [%]` (liczby całkowite), `Litry [l]` (3 miejsca – pełna precyzja domeny; dziennik
  pokazuje je bez zer końcowych, więc `14,190` i `14,19` to ta sama wartość), `Cena [zł/l]` (3), `Koszt [zł]` (2),
  `Źródło litrów` (`Texts.LOG_SOURCE_*`), `Do pełna` (`tak`/`nie`; puste, gdy `filledToFull` jest `null`, a nie „nie”).
- **Koszt jest tylko zapisanym `totalCost`** – aplikacja nigdy nie liczy „litry × cena” w arkuszu; brak wartości daje
  pustą komórkę, nie 0. Puste są też litry, cena i źródło, gdy tankowanie ich nie ma; `Poziom przed/po` są zawsze liczbami.
- Tankowanie `PENDING` (bez potwierdzonej ceny) nie trafia do arkusza – to dziennik potwierdzonych (§15.2).
- **Wydatki** (`Texts.CSV_EXPENSES_HEADER`): wszystkie wydatki poza paliwem (F37, §38), od najstarszego (`date`, remis – po `id`).
  Kolumny: `Data` (`RRRR-MM-DD`, bez strefy), `Kategoria` (nazwa z ekranu, PL/EN wg języka UI; nieznana kategoria z nowszej
  wersji czyta się jako `Inne`), `Kwota [zł]` (2 miejsca), `Notatka` (treść nietrimowana; escapowanie CSV może dodać `'`
  przed tekstem przypominającym formułę, a komórka z wiodącym białym znakiem lub tabulatorem jest w cudzysłowie).
- Bez VIN, GPS, `id` tankowania/wydatku i wiersza „Razem”. Nazwy plików: `toyota-companion-tankowania-RRRR-MM-DD.csv`
  i `toyota-companion-wydatki-RRRR-MM-DD.csv` (bez VIN); `CreateDocument("text/csv")`, jak trasy.
- UI: sekcja „Arkusz kalkulacyjny” ma trzy przyciski; nowe są nieaktywne, gdy licznik jest znanym zerem, i przy `busy`.
  Liczniki tankowań i wydatków są czytane przy każdym otwarciu ekranu (osobny guard tylko na równoległy odczyt); błąd odczytu
  zostawia ostatnią znaną wartość, a `null` = nieznane → przycisk aktywny, żeby błąd licznika nie blokował eksportu.
- Pusta lista w chwili zapisu (np. wydatek usunięty w międzyczasie) daje informację „Brak tankowań/wydatków do eksportu.”
  i **aplikacja nic nie zapisuje**; systemowy wybór pliku mógł już utworzyć pusty plik, zanim lista została sprawdzona
  (tak jak w §14.4). To komunikat, nie czerwony błąd; przycisk jest nieaktywny, gdy licznik jest znanym zerem.
- Eksport nie zmienia daty ostatniej kopii archiwum (F8 dotyczy tylko kopii) i nie wymaga sieci. Prywatność: daty/godziny
  tankowań, licznik i notatki wydatków to dane osobowe (notatka może zawierać dowolny tekst); plik trafia tylko tam, gdzie
  wskaże użytkownik, bez ostrzeżenia jak przy kopii (SECURITY.md).

## §49 Budżet miesięczny (PRD F48)
- Karta „Budżet miesiąca” na ekranie statusu: **jeden opcjonalny limit na telefon**, nie na auto, obejmujący paliwo
  i wydatki poza paliwem. Limit jest w `settings/SettingsStore.kt` (klucz `monthly_budget`, tekst `BigDecimal`
  z dwoma miejscami, wartość dodatnia), poza archiwum, eksportem JSON/CSV i `sync_state`; przeżywa wylogowanie
  (ADR-006). Karta nie wykonuje żadnej sieci i nie budzi auta; `reset()` przy wylogowaniu czyści tylko stan w pamięci.
- Karta jest widoczna **także bez `SyncStatus`** – nie zależy od Toyoty, więc błąd synchronizacji nie ukrywa budżetu
  ani wejścia do ustawienia. Bez limitu pokazuje podpowiedź i „Ustaw budżet”; z limitem – wydane, paliwo, wydatki,
  zostało/przekroczono, procent i „Zmień budżet”.
- Domena: `core/domain/BudgetOps.kt` (`BudgetProgress`, `budgetProgress`), lustro `backend/app/domain/budget.py`
  (`BudgetProgress`, `budget_progress`) – te same nazwy testów w Kotlinie i Pythonie. Pliki `app`: `ui/Budget.kt`
  (`MonthlyBudget`, `BudgetState`, `parseBudgetLimit`, `monthFuelCost`, `monthExpenses`, `budgetProgressFor`),
  `ui/BudgetController.kt`, `ui/BudgetCard.kt`, `ui/StatusScreen.kt`, `ui/AppRoot.kt`, `AppContainer.kt`, `Texts`.
- Miesiąc kalendarzowy w strefie lokalnej (ta sama co lista tras). **Paliwo** = suma `costPln` tras wybranego auta,
  których start wypada w miesiącu i które mają cenę (`hasNoCost` wyklucza „brak ceny”); to koszt jazdy, nie zakupy
  paliwa. **Wydatki** = suma `amount` wszystkich wydatków z datą w miesiącu (data poniesienia). Karta łączy dwie
  podstawy, więc linie nazywają się „Paliwo” i „Wydatki”. Tylko PLN, bez przeliczeń walut.
- Kwoty: paliwo i wydatki zaokrąglane do 0,01 zł, suma raz na końcu; `remaining = limit - spent` (może być ujemne),
  procent całkowity HALF_UP (może przekroczyć 100), `overBudget = spent > limit` po zaokrągleniu, więc dokładnie limit
  nie jest przekroczeniem. Limit ≤ 0 albo zaokrąglający się do 0,00 (np. 0,004) jest odrzucany, żeby nie dzielić przez
  zero; ujemne kwoty to błąd programisty.
- Brak odczytu: limit zostaje (to ustawienie telefonu), a ostatnie dobre liczby **tego samego miesiąca** zostają na
  ekranie z komunikatem błędu; pierwszy błąd bez liczb pokazuje komunikat zamiast zmyślonych zer. `loading` pokazuje
  „Ładowanie…” tylko wtedy, gdy nie ma jeszcze liczb (lekcja C26: ostatnie dobre dane nie znikają).
- Odświeżanie: po zalogowaniu, przy każdym wejściu na ekran statusu i po udanej synchronizacji (`syncRunner`,
  `SyncOutcome.Ok`); brak sieci. Północ albo zmiana miesiąca przy otwartym ekranie nie odświeża karty – zrobi to
  następne wejście albo synchronizacja. Zmiana strefy może przenieść trasę między miesiącami (start liczony lokalnie).
- Dialog: jedno pole „Limit [zł]”; puste usuwa budżet, niepoprawna kwota (≤ 0, więcej niż 2 miejsca, tekst) zostawia
  okno otwarte. Zapis jest pojedynczą edycją `SharedPreferences` na wątku wywołującego, jak inne ustawienia (§12.2).
- Auto Backup (ADR-015): limit jest w `settings.xml` objętym kopią Google i transferem urządzenia, jak cena domyślna
  i cele eco; **nie jest sekretem**. Ręczny eksport archiwum (JSON) i arkusze CSV nie zawierają limitu (settings są
  poza archiwum). Karta nie wysyła nic sama; baza z trasami i wydatkami jest w Auto Backup, tak jak dotąd.
- **Powiadomienie przy progu (PRD F49)**: kanał „Budżet” (`budget`, `IMPORTANCE_DEFAULT`), progi **80%** (ostrzeżenie)
  i **100%** (przekroczenie). Próg 100 oznacza faktyczne przekroczenie (wydano > limit, `overBudget`); ostrzeżenie 80
  zaczyna się od 79,5% według całego procentu HALF_UP i obejmuje też 99,95% oraz dokładnie limit (treść z kwotą
  „Zostało X zł.”, także 0,00). Klucz `RRRR-MM:80`/`RRRR-MM:100` w `SettingsStore` (`budget_alert_last`); powiadamiamy
  tylko, gdy dla tego miesiąca nie powiadomiono jeszcze tego lub wyższego progu (80 po 100 jest ciszą, 100 po 80
  powiadomieniem; nowy miesiąc zaczyna od nowa). Klucz zapisywany **po** udanym `post`; bez uprawnienia, wyłączonych
  powiadomień, wyciszonego kanału albo wyłączonego przełącznika nic nie jest oznaczane, więc włączenie później dostarczy
  alert tego miesiąca. Trigger: udana synchronizacja (`SyncOutcome.Ok`) i udana zmiana wydatku (dodanie/usunięcie);
  **nie** przy samym wejściu na status (użytkownik widzi kartę) i nie przy zmianie limitu; zmiana limitu nie zeruje
  klucza miesiąca (po podniesieniu limitu po alercie 100 do końca miesiąca jest cisza). Kontrole są serializowane
  (`Mutex`, klucz czytany w blokadzie), a odczyty, `canPost` i publikacja idą na wątku I/O. Reuse `POST_NOTIFICATIONS`
  i jednorazowej prośby z §13.2 – bez nowej prośby i nowego uprawnienia; odmowa nie psuje karty ani synchronizacji.
  Treść: miesiąc, wydane, limit, procent; przy 80% dodatkowo kwota „Zostało”, przy 100% kwota przekroczenia; bez VIN
  i GPS. Przełącznik
  `budget_alert` (domyślnie włączony, jak automatyczna pogoda) w „Opcjach”, przeżywa wylogowanie.
  Pliki: `ui/BudgetAlert.kt` (czysta reguła progów), `notify/BudgetAlertNotifier.kt`, `ui/ExpensesController.kt`
  (`onChanged`), `ui/BudgetController.kt` (przełącznik), `ui/AppRoot.kt`, `AppContainer` (`syncRunner`, notifier),
  `settings/SettingsStore.kt`, `ui/OptionsScreen.kt`, `ui/Texts.kt`.

## §50 Krótkie trasy i zimny silnik (PRD F50)
Domena: Python `backend/app/domain/shorttrips.py` (wzorzec), Kotlin `core/domain/ShortTripOps.kt` (te same nazwy testów: `backend/tests/test_domain_shorttrips.py`, `ShortTripOpsTest.kt`). Ekran: karta w „Statystykach” (§18):
`StatsController.computeStats` (`StatsView.shortTrips`), `ui/ShortTripsFormatting.kt`, `StatsScreen.kt`, teksty w `Texts.kt`. Tylko lokalne archiwum tras (`Trip.start/end`, dystans, litry, `evDistanceKm`, koszt): bez sieci, bez GPS, bez budzenia auta,
bez nowych uprawnień i bez zmiany schematu Room; nic nie jest zapisywane.
- **Wejście:** trasa liczy się, gdy dystans jest skończony i `≥ 0,1 km` (`SHORT_MIN_KM`; poniżej to przestawienie auta) oraz litry `≥ 0`; pozostałe są tylko liczone w `leftOut`. Trasa z 0 l (jazda na prądzie) **zostaje** w sumach, tak jak w §22/§24.
- **Pasma** (dolna granica włącznie, jak w §22): poniżej 2, 2–5, 5–10, 10–25, od 25 km. Pasmo bez tras nie występuje. „Krótkie” to trzy dolne pasma (poniżej 10 km); **odniesienie** to wszystkie trasy od 10 km razem (`SHORT_REFERENCE_FROM_KM`).
- **Na pasmo:** liczba tras, udział tras i km (całe %, HALF_UP, od wszystkich użytych tras), km (0,1), litry, **l/100 km = Σ litrów / Σ km × 100** (0,01, nigdy średnia średnich), zł/100 km = Σ kosztu / Σ km tras z ceną
  (`source != none` i kwota; bez ceny `null`, nigdy 0 zł), udział jazdy na prądzie (Σ km EV przycięte do dystansu trasy / Σ km pasma, całe %; `null` bez żadnej trasy z danymi o prądzie).
- **Progi liczności:** liczby pasma (l/100 km, zł/100 km, udział EV) tylko od 5 tras (`SHORT_MIN_TRIPS_SHOWN`); poniżej – „za mało”. Wniosek wymaga **≥ 10 tras po każdej porównywanej stronie** (`SHORT_MIN_TRIPS_COMPARE`).
- **Nadwyżka** (tylko pasma poniżej 10 km, gdy pasmo ma ≥ 10 tras i odniesienie ≥ 10 tras): `l/100 km pasma − l/100 km odniesienia` (różnica wartości pokazanych, 0,01, ze znakiem); **litry** = `Σ litrów pasma − Σ litrów odniesienia × Σ km pasma / Σ km odniesienia`
  (stawka odniesienia bez wcześniejszego zaokrąglenia, wynik 0,01 l HALF_UP); **zł** = nadwyżka litrów (niezaokrąglona) × cena litra pasma (Σ kosztu / Σ litrów tras z ceną i litrami > 0; bez takiej ceny `null`). Wartość ujemna (krótkie trasy na prądzie) jest prawdziwa i zostaje ze znakiem.
  Suma krótkich pasm sumuje tylko pasma z policzoną nadwyżką; suma w zł tylko gdy **każde** policzone pasmo ma cenę, inaczej `null`.
- **Zimny start** (`tripStartKinds`, ze **wszystkich** tras archiwum, także tych, które pasma pomijają – silnik rozgrzewa się niezależnie od jakości odczytu): poprzednia trasa to ta przed nią wg `(start, id)`; pierwsza trasa = nieznany;
  przerwa `> 21 dni` (`MAX_GAP_DAYS`, jak w F17) = nieznany (możliwa dziura w archiwum); przerwa `≥ 3 h` (`COLD_GAP_HOURS`) = zimny; krótsza, także nakładanie się zapisów = ciepły. To przybliżenie – w archiwum nie ma temperatury silnika.
  Na pasmo: liczby zimnych, ciepłych i nieznanych startów oraz, gdy zimnych i ciepłych jest po ≥ 10, l/100 km każdej strony i różnica „zimny − ciepły” (osobno w każdym paśmie, żeby nie mieszać długości z zimnem).
- **Karta** (`ShortTripsCard`): tytuł „Krótkie trasy i zimny silnik”, jedna krótka podpowiedź (czym jest zimny start, że litry liczone są do wszystkich km), nagłówek „Krótkie trasy (poniżej 10 km): 38 % tras, 11 % km”, linia „Nadwyżka w archiwum: 14,2 l · 91,40 zł”
  (gdy suma ≤ 0: „Nie zużywają więcej paliwa niż trasy od 10 km”; bez żadnego porównania brak linii), potem **wiersz `MeterRow` na pasmo** (nazwa, po prawej l/100 km albo „za mało”, pasek względem najwyższej wartości na karcie) z **jedną** linią pomocniczą
  „41 tras (23 %) · 9 % km · +1,62 l/100 km” (liczba tras odmieniona; nadwyżka tylko przy krótkich pasmach, które ją mają), wiersz odniesienia „Odniesienie – trasy od 10 km” i blok „Zimny a ciepły start” („2–5 km: zimny 6,10 · ciepły 5,60 l/100 km (+0,50)” albo
  zdanie, że za mało tras). Liczby przez `TripsFormatting.number`/`count` (twarda spacja), linie krótkie, żeby nie łamały się na 360 dp (C32). Karta znika, gdy nie ma żadnej użytej trasy. Ekran nie pokazuje dat ani godzin pojedynczych tras.
- **Poza zakresem:** sezon/zima i temperatura (F15), wykrywanie dziur współrzędnymi, ustawienia progów, wykres, eksport, powiadomienia.

## §51 Ślad CO₂ (PRD F51)
Szacunek CO₂ z **przepalonych litrów**, wyliczany przy odczycie z lokalnego archiwum; nic nie jest zapisywane (bez Room, `settings` i sieci), auto nie jest budzone.
- **Formuła i stała:** `co2Kg(litry) = litry × 2,3 kg CO₂/l`, kg z jednym miejscem po przecinku, `HALF_UP`; ujemne litry to błąd programisty (`IllegalArgumentException`/`ValueError`).
  Stała `CO2_KG_PER_LITER_GASOLINE = 2,3` jest w `core/domain/Co2Ops.kt` z lustrem w `backend/app/domain/co2.py` (te same nazwy testów) i nigdzie indziej;
  tekst podpowiedzi bierze ją ze stałej. Źródło: Natural Resources Canada, „Learn the facts: Fuel consumption and CO2” – „Burning 1 L of gasoline produces approximately 2.3 kg of CO2”.
  To spalanie benzyny; nie jest to pomiar, tylko szacunek ze spalonych litrów.
- **Zakres:** tylko litry z `Trip.fuelL` (komputer pokładowy). Jazda na prądzie nie dodaje CO₂, więc miesiąc wyłącznie na baterii daje 0,0 kg. Bez prądu (mix energetyczny),
  produkcji i utylizacji auta, ogrzewania i paliw innych niż benzyna.
- **Litry bez wcześniejszego zaokrąglenia:** miesiąc używa `MonthGroup.totalLiters` (suma `fuelL`), rok sumy `fuelL` sprzed zaokrąglenia do 0,1 l (nie `YearSummary.fuelL`), a CO₂ liczy się raz
  z sumy, nie jako suma CO₂ tras (inaczej zaokrąglenia by się kumulowały).
- **Jednostki w UI** (`TripsFormatting.co2`): poniżej 1000 kg – „123,4 kg”, od 1000 kg – tony „2,05 t” (kg/1000, dwa miejsca, `HALF_UP`); liczba grupowana jak w §10.4 (`number`), a jednostka
  łączy się z liczbą twardą spacją, więc nie przechodzi do nowej linii.
- **Gdzie:** nagłówek miesiąca na liście tras – **osobna linia** „Ślad CO₂: …” pod km/litrami/kosztem (obok nich nagłówek łamałby się na wąskim ekranie); „Podsumowanie roku” – linia
  „Ślad CO₂: …” po koszcie (ekran i tekst do udostępnienia, razem z `yearSummaryLines`) oraz mała podpowiedź na ekranie „Szacunek ze spalonych litrów (ok. 2,3 kg CO₂ na litr benzyny); bez prądu,
  produkcji i utylizacji auta.” (w tekście do udostępnienia jej nie ma). Teksty PL/EN: „Ślad CO₂” / „CO₂ footprint”.
- **Prywatność:** zagregowana liczba bez VIN, GPS i nazw miejsc, bez sieci, logów i uprawnień.
- **Pliki:** `core/domain/Co2Ops.kt`, `backend/app/domain/co2.py`, `ui/TripsGrouping.kt` (`MonthGroup.co2Kg`), `ui/YearSummary.kt` (`YearSummary.co2Kg`, `yearSummaryLines`),
  `ui/TripsFormatting.kt` (`co2`), `ui/TripsScreen.kt`, `ui/YearSummaryScreen.kt`, `ui/Texts.kt`.

## §52 Kalkulator kosztu planowanej trasy (PRD F52)
Plan: `briefs/toyota-companion/C33-plan.md`. Domena: `core/domain/TrafficBands.kt` (pasma tempa przeniesione z F19 bez zmiany zachowania),
`core/domain/PlannedTripOps.kt` (`plannedTripCost`, `mostDrivenBand`); lustro Python `backend/app/domain/planned.py` z tymi samymi nazwami testów.
Ekran: karta na górze „Statystyk” (`ui/PlannedTripFormatting.kt`, `StatsScreen.kt`), dane z `StatsController` (`StatsView.planSamples`, `planPrice`).
- **Wejście:** kilometry (od 0 do 5000, z przecinkiem lub kropką, do dwóch miejsc) i rodzaj jazdy – jedno z czterech pasm tempa F19 (wolno < 15, miasto 15–25, płynnie 25–40, trasa ≥ 40 km/h).
  Domyślnie wybrany jest rodzaj z **największą liczbą tras** w archiwum (remis – węższe pasmo wolne → trasa). Pusta lub błędna wartość nie daje wyniku, tylko komunikat.
- **Próbka:** trasa z prędkością (`averageSpeed`), co najmniej 2 km (jak F19) i paliwem ≥ 0 należy do pasma wg prędkości. **Trasy z 0 l wchodzą** do populacji (jak F19); ich l/100 km to 0.
- **Populacja:** trasy wybranego rodzaju o długości od **0,5× do 2×** wpisanych km (włącznie). Gdy jest ich co najmniej **10**, to ona. Inaczej – wszystkie trasy rodzaju, ale tylko gdy jest ich co najmniej **10**.
  Poniżej 10 tras rodzaju wyniku nie ma: „za mało tras tego rodzaju: n z 10”.
- **Stawka trasy:** dla każdej trasy populacji `l/100 km = litry × 100 / km` (dokładnie, bez zaokrąglania pośredniego). Nie średnia ze średnich.
- **Kwartyle** (bez interpolacji): stawki posortowane rosnąco, n elementów; dolny = stawka o indeksie ⌊(n−1)/4⌋, górny = o indeksie ⌊3(n−1)/4⌋ (indeksy od zera).
- **Przedział:** litry = wpisane km × kwartyl / 100 (bez zaokrąglania pośredniego), zaokrąglone do **0,1 l** (HALF_UP). Zł = niezaokrąglone litry × cena, zaokrąglone do **całych złotych** (HALF_UP).
  To przedział zużycia z własnych tras, nie prognoza i nie średnia pasma.
- **Cena** (`StatsController`): 1) `pricePerLiter` najnowszej trasy z wyceną (`TripCost`, bez `hasNoCost`), 2) cena domyślna z Ustawień, 3) brak – wtedy tylko litry („brak ceny paliwa – ustaw cenę domyślną w Opcjach”).
- **Linia pomocnicza wyniku** mówi, której populacji użyto: „z 37 podobnych tras” (długość 0,5–2×) albo „z 212 tras tego rodzaju (za mało podobnej długości)”.
- **Poza zakresem wersji 1:** temperatura i sezon (F24), mapy, GPS, zapisywanie planów, powiadomienia.
- **Prywatność:** tylko lokalne archiwum i ustawienia; nic nie jest zapisywane ani logowane; bez sieci i budzenia auta.

## §53 Najlepsza godzina wyjazdu (PRD F53)
Plan: `briefs/toyota-companion/C34-plan.md`. Domena: `core/domain/BestTimeOps.kt` (`bestTimeGrid`, `departureBandOf`, `dayKindOf`, `medianMinutes`) z lustrem
`backend/app/domain/besttime.py` (te same nazwy testów). Odcinki wybiera app (`sameRoutes`, bez zmian), a siatka dostaje gotowe przejazdy, więc `SameRoutes` nie idzie do domeny.
Ekran: karta „Najlepsza godzina wyjazdu” w „Stylu jazdy”, zaraz pod „Ten sam odcinek” (`ui/BestTimeFormatting.kt`, `DrivingStyleScreen.kt`, `DrivingStyleController.kt` – `bestTimes`).
- **Odcinki:** te same co F12 (`sameRoutes`, 300 m, kierunek, numeracja „Odcinek N” zgodna z listą na ekranie). Pokazywane są najwyżej **5** najczęstszych
  z co najmniej **5** przejazdami (`BEST_TIME_ROUTES_SHOWN`, `BEST_TIME_MIN_ROUTE_TRIPS`). Trasy bez czterech współrzędnych nie należą do żadnego odcinka.
- **Pasma godziny startu** (lokalny czas strefy listy, dolna granica włącznie): 5–7, 7–9, 9–12, 12–15, 15–18, 18–21 i **noc 21–5 jako jedno pasmo** przez północ.
  O paśmie decyduje **godzina startu**; przejazd przez północ należy do pasma swojego startu. Zmiana czasu jest obsługiwana przez strefę (godzina lokalna, nie UTC).
- **Dzień:** robocze (poniedziałek–piątek) albo weekend (sobota, niedziela), wg daty startu w strefie.
- **Komórka** = odcinek × dzień × pasmo, pokazywana od **3 przejazdów** (`BEST_TIME_MIN_CELL_TRIPS`); puste komórki nie są pokazywane.
- **Miara:** mediana czasu przejazdu (`koniec − start`); dla parzystej liczby – średnia dwóch środkowych wartości; zaokrąglona do **całej minuty** (HALF_UP) od
  sekund, nie od minut. **Nie średnia**, żeby jeden korek nie przesuwał wyniku.
- **Spalanie** komórki = Σ litrów × 100 / Σ km (0,01 l/100 km), nie średnia ze stawek; przejazdy z 0 l wchodzą (jazda na prądzie, jak w F19); przejazd bez dodatniego czasu,
  dystansu lub z ujemnym paliwem jest pomijany.
- **Wniosek** (na górze odcinka, jedna linia): „Najszybciej: robocze 9–12, mediana 17 min; najwolniej: robocze 7–9, 24 min” – tylko gdy są **co najmniej 2 komórki** z danymi.
  Gdy różnica median jest mniejsza niż 2 min: „Czas podobny o każdej porze”. Przy jednej komórce – notatka, że porównanie wymaga dwóch pasm; przy zerze – notatka o zbyt małej liczbie przejazdów.
- **Wiersze:** jeden na komórkę z danymi: nazwa („robocze 9–12”), po prawej mediana („17 min”), pasek proporcjonalny do czasu (najwolniejsza komórka = pełny pasek, najszybsza na zielono), pod spodem „5 przejazdów · 6,20 l/100 km”.
  Odmiana „przejazd / przejazdy / przejazdów”. Liczby przez `TripsFormatting.number`/`count`.
- **Poza zakresem:** prognoza ruchu i pogody, sezon, powiadomienia, zapisywanie wyników, zmiana progów F12.
- **Prywatność:** czasy, dystanse i paliwo z archiwum; współrzędne tylko do dopasowania odcinka w pamięci (jak F12); karta nie pokazuje dat ani godzin pojedynczych tras.

## §54 Podsumowanie okresu w powiadomieniu (PRD F54)
Plan: `briefs/toyota-companion/C35-plan.md`. Domena: `core/domain/PeriodSummaryOps.kt` (`lastCompletedWindow`, `previousWindow`, `periodTotals`, `percentChangeOf`,
`summaryDecision`) z lustrem `backend/app/domain/period_summary.py` (te same nazwy testów). Powiadomienie: `notify/PeriodSummaryNotifier.kt`; wybór: `ui/PeriodSummarySettings.kt`
i karta w „Opcjach”; treść: `ui/PeriodSummaryFormatting.kt`. Ustawienia: `period_summary` (`WEEK`, `MONTH`, brak = wyłączone) i `period_summary_last` w `SettingsStore`.
- **Okres:** tydzień od poniedziałku do niedzieli albo miesiąc kalendarzowy, w strefie telefonu. Raportowany jest tylko **ostatni zakończony** okres; okres bieżący nigdy.
- **Klucz:** `week:RRRR-MM-DD` (poniedziałek tygodnia) albo `month:RRRR-MM`. Tydzień jest nazwany poniedziałkiem, nie numerem ISO, więc przełom roku (tydzień 53/1) nie wymaga szczególnej obsługi.
- **Wyzwalacz:** wyłącznie po udanej synchronizacji (`SyncOutcome.Ok`) w `syncRunner`, bez własnego alarmu. Klucz zapisywany **po** udanym `post`.
- **Decyzja** (`summaryDecision`): wyłączone – nic; brak klucza – tylko zapis klucza (baseline, nic nie wysyłane); ten sam klucz – nic; godzina poza 8–22 – czeka (klucz nie zapisany);
  w przeciwnym razie – wysyła ostatni zakończony okres. Zmiana okresu w „Opcjach” kasuje klucz, więc nowy okres zaczyna od baseline.
- **Pusty okres** (bez tras i bez wydatków) jest pomijany z zapisem klucza, bez powiadomienia; okres z samym wydatkiem jest raportowany z kosztem.
- **Liczby:** trasa należy do dnia swojego startu w strefie telefonu; km i litry z tras o dodatnim dystansie i paliwie ≥ 0; l/100 km = Σ litrów × 100 / Σ km;
  udział jazdy na prądzie = Σ km na prądzie / Σ km tras z danymi o prądzie (jak §16); koszt paliwa tylko z tras z ceną, `null` (nie 0 zł) bez żadnej.
- **Treść:** zwięzła linia: „412 km · 58 zł · 4,6 l/100 km · 38 % na prądzie”, tytuł „Tydzień 2–8.11” albo „Październik 2026”. Kwoty w całych złotych, z części
  zaokrąglonych osobno (paliwo + wydatki), więc suma zgadza się z rozbiciem. W rozwinięciu (BigTextStyle): rozbicie „Paliwo 41 zł · wydatki 17 zł” i porównanie
  „Wobec poprzedniego tygodnia: km +12 %, koszt -5 %” (tylko gdy poprzedni okres ma dane).
- **Kanał** „Podsumowanie okresu” (`summary`, `IMPORTANCE_LOW`, ID 902). Wyciszony kanał nie zużywa okresu. Bez `POST_NOTIFICATIONS` nic nie jest oznaczane.
- **Współbieżność i I/O:** jak C29 – `Mutex` wokół całej decyzji, klucz czytany w blokadzie, ustawienia, archiwum i `post` na `Dispatchers.IO`.
- **Prywatność:** tylko zagregowane liczby i daty okresu; bez VIN, GPS i nazw miejsc; bez sieci i budzenia auta; bez nowego uprawnienia i bez Room.

## §55 Utrata wartości auta w koszcie całkowitym (PRD F55)
Plan: `briefs/toyota-companion/C36-plan.md`. Domena: `core/domain/ValueLossOps.kt` (`valueLossInYear`) i rozszerzone `OwnershipCost`
(`valueLossPln`, `valueLossPerKmPln`, `totalWithLossPln`, `totalWithLossPerKmPln`); lustro Python `backend/app/domain/ownership.py`
(`value_loss_in_year`, `ownership_cost`) z tymi samymi nazwami testów. Ekran: karta „Koszt całkowity” w „Podsumowaniu roku”
(`ui/YearSummary.kt`, `ui/YearSummaryScreen.kt`) i formularz w „Opcjach” (`ui/CarValuationForm.kt`, `settings/SettingsStore.kt`).
- **Dane właściciela:** cena zakupu > 0, data zakupu, szacowana wartość ≥ 0, data wyceny ≥ data zakupu. Ustawienie telefonu, nie archiwum
  i nie sekret (jak budżet, ADR-019: jedno konto = jedno auto); przeżywa wylogowanie i trafia do Auto Backup. Brak lub błędne dane = brak wyceny.
- **Strata całkowita:** `max(0, cena zakupu − wartość)`; wartość wyższa od ceny daje 0 zł, bez ujemnej straty i bez wyjątku.
- **Model liniowy w dniach:** dzienna strata = strata całkowita / liczba dni od daty zakupu do daty wyceny. Strata roku = dzienna strata × liczba dni
  roku w przedziale `[data zakupu, data wyceny)`. Rok przed zakupem i rok po dacie wyceny = 0 (bez ekstrapolacji w przyszłość). Data wyceny równa dacie
  zakupu = przedział pusty = 0. Dni liczone kalendarzowo, więc luty roku przestępnego wchodzi swoją długością.
- **Zaokrąglenie:** strata roku do 0,01 zł, HALF_UP, liczona z niezaokrąglonej dziennej stawki. `OwnershipCost` zachowuje dotychczasowe pola gotówkowe;
  strata i suma z utratą wartości mają własne pola, żeby gotówka i szacunek nigdy nie były w jednej liście kategorii. Zł/km straty i sumy z utratą
  liczone przez wszystkie km roku, tak jak dotychczasowy koszt.
- **Karta roku:** dotychczasowy wiersz „Koszt całkowity” bez zmian, pod nim osobno „Utrata wartości (szacunek): … zł (… zł/km)” i
  „Z utratą wartości: … zł (… zł/km)”. Rok bez paliwa i wydatków, ale ze stratą, ma kartę. „Średnio na miesiąc” dotyczy tylko gotówki.
  Gdy data wyceny jest starsza niż 12 miesięcy od dziś, karta pokazuje „Wycena z DD.MM.RRRR – zaktualizuj ją w Opcjach”; wycena nadal jest używana.
- **Udostępnianie:** `yearSummaryShareText` zawiera oba wiersze, a strata jest w nim jawnie opisana jako „szacunek”. Wydatki na wyposażenie zostają
  w „Wydatkach” i nie zwiększają ceny zakupu.
- **Teksty PL/EN:** „Utrata wartości (szacunek)” / „Depreciation (estimate)”, „Z utratą wartości” / „With depreciation”; liczby przez
  `TripsFormatting.number`/`money`, daty jako DD.MM.RRRR.
- **Poza zakresem:** wycena z internetu, krzywa degresywna, wiele aut na VIN, koszt energii, zmiany Room i Gradle.
- **Prywatność:** cena i wartość auta to dane właściciela, nie sekret; tylko w pliku `settings`, bez archiwum, CSV i logów; bez sieci i budzenia auta;
  karta nie pokazuje VIN.

## §56 Opony: dwa komplety, przebieg i przypomnienie (PRD F56)
Plan: `briefs/toyota-companion/C37-plan.md`. Domena: `core/domain/TyreOps.kt` (`parseTyreChanges`, `formatTyreChanges`, `parseTyreThresholds`,
`tyreUsage`, `dueTyreReminders`, `tyreReminderKey`, `checkTyreChange`) z lustrem `backend/app/domain/tyres.py` (te same nazwy testów). Ekran: karta „Opony”
na górze „Wydatków” (`ui/TyreCard.kt`, `ui/TyreController.kt`, `ui/TyreFormatting.kt`), bez kafelka. Powiadomienie: `notify/TyreReminderNotifier.kt`.
Dane: trzy klucze tekstowe w `SettingsStore` (`tyre_changes`, `tyre_thresholds`, `tyre_reminder_keys`); bez Room, bez wpisu do eksportu JSON
(Auto Backup obejmuje je jak inne ustawienia).
- **Ekran:** karta „Opony” jest zawsze pierwszą pozycją jednej przewijanej listy „Wydatków” (także bez wydatków i bez zmian: wtedy zachęta i przycisk „Dodaj pierwszą zmianę”), pod pływającym przyciskiem zostaje odstęp.
- **Zmiany:** `RRRR-MM-DD|S|przebieg` (S letni, W zimowy; przebieg opcjonalny). Wpis z błędną datą, literą albo przebiegiem jest pominięty;
  drugi wpis tego samego dnia odrzucony; odczyt posortowany od najstarszego. Śmieci w ustawieniu czytają się jako brak zmian.
- **Usuwanie:** „Usuń ostatnią zmianę” (widoczne, gdy są zmiany) kasuje najnowszą zmianę po potwierdzeniu i zapomina wysłane przypomnienia tylko tej zmiany (klucze z jej datą i literą), więc ponowne dodanie tej samej zmiany może przypomnieć ponownie.
- **Przed pierwszą zmianą:** trasy sprzed pierwszej zmiany są podane jako „592 trasy · 4 887 km” (odmiana liczby tras), bez przypisania do kompletu.
- **Walidacja formularza:** data nie w przyszłości, nie ta sama co inna zmiana, nie przed ostatnią zmianą; przebieg pusty albo liczba całkowita.
  Data wybierana kalendarzem jak w wydatkach.
- **Przypisanie tras:** trasa należy do kompletu wg **lokalnej daty startu** w strefie telefonu, od dnia zmiany do dnia następnej zmiany
  (komplet na aucie teraz trwa do dziś). Trasy przed pierwszą zmianą nie należą do żadnego kompletu i są liczone osobno. Trasy bez dodatniego dystansu są pomijane.
- **Progi:** per komplet, puste = brak przypomnienia tego rodzaju. Miesiące to pełne miesiące od dnia założenia; kilometry to przebieg tras
  z archiwum od dnia założenia (suma zaokrąglona do 0,1 km). Przypomnienie dotyczy tylko kompletu **na aucie teraz** (nie zdjętego).
- **Przypomnienie:** po udanej synchronizacji, między 8:00 a 22:00 czasu telefonu (`SUMMARY_FROM_HOUR`/`SUMMARY_UNTIL_HOUR`); klucz
  `LETNI:RRRR-MM-DD:MONTHS|KM` (`SUMMER:2026-04-15:MONTHS`) zapisywany po udanym `post`, raz na komplet, dzień założenia i rodzaj progu.
  Zmiana progu nie wysyła ponownie. Kanał „Opony” (`IMPORTANCE_LOW`, ID 903); wyciszony kanał nie zużywa klucza. Bez uprawnienia nic nie jest oznaczane.
- **Wariant temperaturowy** („średnia dobowa poniżej 7 °C przez N dni”) **odłożony**: cache pogody F15 ma tylko dni z trasami (`ui/WeatherController.kt`),
  więc ciągłego szeregu dziennego nie ma. Wpisany do ROADMAP LATER z warunkiem.
- **Prywatność:** daty, przebiegi i liczby miesięcy; bez VIN, GPS i nazw miejsc; bez sieci i budzenia auta; bez nowego uprawnienia.

## §57 Stacja przy tankowaniu (PRD F57)
Plan: `briefs/toyota-companion/C38-plan.md`. Domena: `core/domain/StationOps.kt` (`normalizeStation`, `stationSummaries`, `stationSuggestions`)
z lustrem `backend/app/domain/stations.py` (te same nazwy testów). Ekrany: `ui/StationInput.kt` (pole i podpowiedzi), `ui/RefuelPromptDialog.kt`,
`ui/RefuelFormScreen.kt`, `ui/RefuelLogScreen.kt` (wiersze stacji i licznik „Bez stacji”).
- **Model:** pole `station` na tankowaniu (`Refuel.station`, tekst lub brak). Room v8: kolumna `refuels.station` TEXT NULL,
  `AutoMigration(7, 8)`; stare wiersze dostają NULL. Schemat `app/schemas/.../8.json` z builda, w repo.
- **Normalizacja:** przycięcie, sklejenie spacji w jedną, pusta nazwa to brak stacji, nazwa cięta do 60 znaków (`STATION_MAX_LENGTH`) i bez spacji na końcu.
  Nazwy równe bez względu na wielkość liter to jedna stacja (klucz `lowercase(Locale.ROOT)`, w Pythonie `lower()`); pokazywana jest pisownia z najnowszego tankowania.
- **Wiersze:** tylko potwierdzone tankowania; bez stacji nie są wierszem, tylko licznik „Bez stacji”. Sortowanie: liczba tankowań malejąco, potem nazwa.
- **Średnia cena:** `Σ koszt / Σ litry` po tankowaniach z obiema wartościami (litry > 0, koszt podany), od jednego tankowania; wiersz bez obu pól nie zmienia średniej.
- **Spalanie:** interwał należy do stacji **otwarcia** (tankowanie startowe), tylko `OK` i zamknięty, z podanymi litrami. Spalanie = `Σ litry / Σ km × 100`
  (pulowane, nie średnia średnich). Pokazywane dopiero od `STATION_MIN_INTERVALS` = 3 interwałów i opisane jako orientacyjne.
- **Podpowiedzi:** `stationSuggestions` – najczęstsze, potem najnowsze; do 6 (`STATION_SUGGESTIONS`); okno „Wykryto tankowanie” pokazuje 4 pierwsze.
- **Okno „Wykryto tankowanie”:** cena pierwsza i z fokusem klawiatury (`ShowKeyboardOnFocus`), stacja pod ceną i opcjonalna (zapis bez stacji działa);
  treść przewijana z `imePadding`, więc przy klawiaturze da się dojść do każdego pola.
- **Formularz i korekta:** pole stacji po cenie; chipsy ostatnich stacji przewijane poziomo; wybór chipa tylko wypełnia pole. W korekcie w arkuszu tankowań stacja jest edytowalna.
- **Archiwum:** `RefuelDto.station` opcjonalne. Stary plik bez pola czyta się z `station = null`, nowy plik z polem – ze stacją. `ARCHIVE_VERSION` zostaje 1
  (nieznany klucz jest ignorowany, testowane w `ArchiveFileTest`).
- **CSV:** ostatnia kolumna „Stacja” / „Station”, przez `csvCell`: wartość zaczynająca się od `=`, `+`, `-` lub `@` (poza samą liczbą) dostaje apostrof;
  z wiodącą spacją albo średnikiem jest w cudzysłowie. Test `=SUM(1)`, ` +1` i `-2` w `RefuelsCsvTest`.
- **Prywatność:** nazwa stacji nie trafia do logów, powiadomień, tekstów udostępnianych ani diagnostyki; jest na ekranach
  i w plikach CSV/archiwum, które właściciel sam eksportuje. Kolumna jest w bazie `refuels`, a baza jest w Auto Backup
  (ADR-015), więc nazwa trafia też na Dysk Google właściciela razem z trasami i tankowaniami – jak cała ta baza.
  Bez sieci i bez budzenia auta.

## §58 Serie i rekordy przy celach eco (PRD F58)
Plan: `briefs/toyota-companion/C39-plan.md`. Logika: `ui/EcoStreaks.kt` (`ecoHistory`, `ecoStreakLine`, `ecoRecordLine`), na miesiącach
z `ecoMonth` (§40). Odstępstwo od planu: bez lustra w Pythonie, bo `ecoMonth` i cele są w `app/ui` (Python nie ma ich odpowiednika).
Teksty: `ui/Texts.kt` (PL/EN, odmiana „miesiąc/miesiące/miesięcy”); karta: `ui/EcoGoalsCard.kt`.
- **Miesiące:** tylko pełne miesiące do poprzedniego włącznie; bieżący miesiąc nie wchodzi do serii ani rekordów. Miesiąc w strefie telefonu.
- **Seria:** kolejne miesiące z osiągniętym celem (spalanie albo jazda na prądzie, każdy osobno). Miesiąc poniżej 50 km (`ECO_MIN_MONTH_KM`)
  albo bez danych o prądzie jest **pomijany**: nie zrywa serii i nie wydłuża jej. Miesiąc z celem nieosiągniętym zrywa serię.
- **Zmiana celu:** każdy cel ma miesiąc „od kiedy”: `eco_goal_consumption_since` i `eco_goal_ev_since` (tekst `RRRR-MM`, ustawienia telefonu).
  Zapis ustawia go na bieżący miesiąc tylko wtedy, gdy wartość celu faktycznie się zmieniła (porównanie liczbowe, `2,5` = `2.50`);
  cel usunięty kasuje miesiąc. Brak albo śmieci w kluczu = seria od początku archiwum.
- **Rekordy:** najniższe spalanie miesiąca (l/100 km) i najwyższy udział prądu (%) z pełnych miesięcy z jazdą; remis – wcześniejszy miesiąc.
  Rekordy nie zależą od celów i od miesiąca zmiany. Pokazywane dopiero od 3 pełnych miesięcy z jazdą (`ECO_RECORD_MIN_MONTHS`).
- **Prezentacja:** najwyżej dwie linie pod „Poprzedni miesiąc”, bez ikon i gratulacji:
  `Seria: spalanie 3 miesiące (najdłuższa: 5) · prąd przerwana (najdłuższa: 2)` (część tylko od 2 miesięcy, bieżącej albo najdłuższej)
  i `Rekordy: najniższe spalanie 3,92 l/100 km (wrzesień 2026) · najwyższy udział prądu 61 % (sierpień 2026)`. Liczby przez `TripsFormatting.number`.
- **Widżet (§32) i Android Auto bez zmian**; cele i daty są w ustawieniach telefonu, poza archiwum, eksportem JSON i CSV (jak §40).
- **Prywatność i dane:** wartości pochodne z lokalnego archiwum, bez zapisu historii, bez sieci i bez budzenia auta; bez VIN i GPS.

## §59 Zdjęcie paragonu przy tankowaniu lub wydatku (PRD F59)
Plan: `briefs/toyota-companion/C40-plan.md`, wariant (a). Pliki: `receipts/ReceiptFiles.kt` (reguły czyste: nazwa, rozmiary, osierocone i brakujące
pliki), `receipts/ReceiptStore.kt` (odczyt, skalowanie, zapis JPEG, podgląd), `receipts/ReceiptRepository.kt` (odnośniki w Room, sprzątanie),
`ui/ReceiptUi.kt` (wybór, miniatura, okno podglądu). Bez OCR, bez sieci, bez nowych zależności i bez nowego uprawnienia.
- **Wybór:** systemowy Photo Picker (`PickVisualMedia`, tylko obrazy). Aplikacja nie prosi o uprawnienie do zdjęć ani o aparat; zdjęcie robi
  się aplikacją aparatu i wybiera z galerii. Brak `FileProvider`.
- **Zapis:** `filesDir/receipts/<UUID>.jpg`. Dekodowanie z `inSampleSize` (`sampleSize`), skalowanie do najwyżej 1600 px dłuższego boku
  (`scaledSize`, bez powiększania), zapis JPEG jakości 80 przez `Bitmap` do pliku tymczasowego `tmp-<UUID>` i zmiana nazwy. Ponowny zapis
  usuwa metadane, w tym EXIF z GPS. Test `ReceiptStoreTest` wstrzykuje do źródła blok EXIF z tekstem GPS i sprawdza, że w zapisanym pliku go nie ma.
- **Wątki i pamięć:** dekodowanie i zapis na tle (`Dispatchers.IO`), pod jednym mutexem repozytorium; podgląd i miniatury dekodowane osobno z mniejszym limitem.
- **Model:** kolumna `receipt_file` TEXT NULL w `refuels` i `expenses`; Room v9 (`AutoMigration(8, 9)`), istniejące wiersze z `NULL`.
- **Tankowanie:** w korekcie zdjęcie dodaje się i usuwa od razu, jako osobny zapis (nie czeka na „Zapisz”); korekta nie kasuje odnośnika.
  Odrzucenie tankowania zostawia wpis i zdjęcie (wpis tylko zmienia status).
- **Wydatek:** zdjęcie w formularzu jest kopiowane od razu, a odnośnik zapisuje się przy „Zapisz”. Anulowanie formularza kasuje kopię.
  Usunięcie wydatku kasuje też zdjęcie.
- **Okno podglądu:** w aplikacji, przewijane; bez udostępniania na zewnątrz. Ikona w wierszu tylko gdy jest zdjęcie.
- **Sprzątanie przy starcie** (`ReceiptRepository.sweep`): odnośnik do brakującego pliku jest czyszczony (wpis zostaje; tak działa przywrócenie
  kopii z innego telefonu, bo baza wraca z nazwami, a plików nie ma); plik bez odnośnika jest usuwany; pliki `tmp-` też.
- **Poza zakresem kopii:** zdjęcia nie są w Auto Backup (reguły `backup_rules.xml` i `data_extraction_rules.xml` bez zmian), nie są w eksporcie JSON
  archiwum ani w CSV, nie są w tekstach udostępnianych, logach, powiadomieniach ani diagnostyce. Ekran „Kopia i eksport” ma jedno zdanie o tym.
  Nazwa pliku nie idzie do archiwum, więc po imporcie na innym telefonie nie zostaje martwy odnośnik (sprzątanie go czyści).
- **Ograniczenia:** rozmiar pliku nie jest zmierzony na urządzeniu (rząd kilkuset kB przy 1600 px, JPEG 80). Plik zapisany przez formularz, który
  nie doszedł do zapisu, zostaje do następnego startu. Wybór zdjęcia i aparat do sprawdzenia na emulatorze.
- **Prywatność:** paragon może mieć dane karty i NIP; zdjęcie zostaje w prywatnym katalogu aplikacji (docs/SECURITY.md).
- **Obrót i podgląd (przegląd przed wydaniem):** obrót zapisany przez aparat tylko w EXIF jest nanoszony na piksele przed zapisem, bo metadane
  są usuwane; podgląd w formularzu ma najwyżej 160 dp wysokości, żeby przyciski pod nim były na ekranie, pełne zdjęcie pokazuje okno podglądu.

## §60 Automatyczna kopia archiwum do folderu (PRD F60)
Plan: `briefs/toyota-companion/C42-plan.md`. Logika: `ui/AutoBackupOps.kt` (`autoCopyName`, `autoCopyDate`, `shouldCopyToday`,
`copiesToDelete`), kontroler `ui/AutoBackup.kt`, karta w `ui/BackupScreen.kt`, dostęp do folderu w `AppContainer`
(`DocumentsContract`, bez dodatkowej biblioteki). Ustawienia: `auto_backup_folder` (tekst `Uri` drzewa) i `auto_backup_last`
(dzień ostatniej kopii) w `SettingsStore`.
- **Folder:** wybierany raz systemowym wybierakiem (`OpenDocumentTree`); aplikacja bierze trwałe uprawnienie odczytu i zapisu do tego
  jednego folderu. Bez uprawnienia do pamięci w Manifeście. Folder bez trwałego uprawnienia nie jest zapisywany.
- **Kiedy:** po udanej synchronizacji (`SyncOutcome.Ok`), gdy folder jest ustawiony i dzisiejszego dnia (strefa telefonu) nie było
  jeszcze kopii. Jedna kopia dziennie, bez własnego alarmu. Kopia z datą w przyszłości (cofnięty zegar) nie blokuje dzisiejszej.
- **Plik:** `toyota-companion-RRRR-MM-DD.json`, ta sama treść co ręczny eksport (§14), więc da się go zaimportować. Plik o tej
  nazwie jest zastępowany (ponowna próba po błędzie nie tworzy „(1)”).
- **Rotacja:** po zapisie zostaje 10 najnowszych plików o nazwie tego wzorca (data z nazwy); starsze są kasowane. Każdy inny plik
  w folderze jest nietykalny, podfoldery nie są nigdy brane pod uwagę (nawet o nazwie wzorca). Kopia zapisana przed chwilą zostaje
  zawsze i zajmuje jedno z dziesięciu miejsc – także wtedy, gdy w folderze leżą pliki z późniejszą datą (cofnięty zegar).
  Błąd listowania albo kasowania nie jest błędem kopii.
- **Dostawca folderu:** jeśli zapisze plik pod inną nazwą („… (1).json”), aplikacja próbuje przywrócić nazwę z datą; dostawca,
  który tego nie pozwala, zostawia swoją nazwę i taki plik nie jest rotowany. Zmiana albo wyłączenie folderu oddaje trwałe
  uprawnienie do poprzedniego. Gdy system nie da trwałego dostępu do wybranego folderu, karta mówi to wprost i nic nie zapisuje.
- **Wynik:** udana kopia zapisuje dzień i datę ostatniego eksportu (§14.3), więc przypomnienie o kopii przestaje napominać.
  Nieudana nie zapisuje nic (następna synchronizacja spróbuje znowu), nie psuje synchronizacji i pokazuje na karcie jedno zdanie
  bez treści wyjątku i bez ścieżki.
- **Karta „Automatyczna kopia”:** bez folderu – „Wyłączona” i „Wybierz folder”; z folderem – nazwa folderu, dzień ostatniej kopii
  albo „Pierwsza kopia powstanie po następnej synchronizacji”, „Zmień folder” i „Wyłącz”. Zmiana folderu zeruje dzień ostatniej kopii, więc nowy folder dostaje kopię przy najbliższej
  synchronizacji, także tego samego dnia. Wyłączenie zapomina folder i dzień;
  pliki w folderze zostają.
- **Wylogowanie:** folder to ustawienie telefonu i zostaje; po wylogowaniu nie ma synchronizacji, więc nie ma też kopii.
- **Prywatność:** kopia zawiera to samo co ręczny eksport (trasy z położeniem, VIN, tankowania, wydatki) i leży tam, gdzie wskazał
  właściciel; jeśli folder synchronizuje inna aplikacja (np. chmura), to ona wysyła plik, nie ta aplikacja. `Uri` folderu nie
  trafia do logów, diagnostyki ani komunikatów.
- **Poza zakresem:** liczba kopii do wyboru, szyfrowanie, kopia o stałej godzinie, kopia zdjęć paragonów (§59).
