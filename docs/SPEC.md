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
- pierwsze tankowanie (brak poprzedniej ceny), `previous_tank_price <= 0` albo pusty bak przed tankowaniem
  (`level_before = 0`) -> własna cena `refuel.price_per_liter`
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
   nie wybrany -> `Failed(NO_VEHICLE)`. Nic nie jest usuwane.
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
- Pokazuje z `SyncEngine.status()`: liczba tras, liczba odczytów, oczekujące tankowania,
  aktualne/nieaktualne (`isFresh`), data ostatniego sukcesu, rodzaj ostatniego błędu
  (tylko `SyncErrorKind` przełożony na polski tekst; `lastErrorMessage` NIE jest pokazywany).
- „Synchronizuj teraz” – synchronizacja w procesie (`SyncRunner`), przycisk zablokowany
  na czas pracy; wynik `Failed` pokazuje rodzaj błędu, `Ok`/`Skipped` go kasuje; po pracy
  status jest odświeżany.
- Gdy `status.state.needsLogin` -> komunikat „Zaloguj się ponownie” obok przycisku Wyloguj.
- Odświeżanie bez sieci: `PullToRefreshBox` (gest przeciągnięcia w dół) i przycisk „Odśwież” wołają
  `SessionController.reload()`, który powtarza ODCZYT lokalnej bazy (`SyncEngine.status()`) – nie woła
  żadnego API Toyoty, w tym `refresh_status()` (SPEC §9 wstęp). Ignorowane, gdy synchronizacja trwa.
- Wygląd (C1b): `Scaffold` z `TopAppBar` (tytuł + „Wyloguj” jako przycisk tekstowy w `actions`),
  liczniki w jednej karcie (Trasy / Odczyty paliwa / Oczekujące tankowania – liczba + podpis),
  chip stanu (`aktualne`/`nieaktualne`/`brak synchronizacji`/`wymaga logowania`), czas ostatniego
  sukcesu i `HorizontalDivider`; „Wyloguj” najpierw pokazuje `AlertDialog` z potwierdzeniem
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
  `20% → 80%`, przebieg `12 345 km` — zwyczajna spacja jako separator tysięcy, `HALF_UP` do pełnych kilometrów,
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
- Bez zmian w `core/domain` (§4, §5), bez zmian w schemacie Room i bez nowych metod w interfejsach repozytoriów.

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

### §12.3 Ekran „Cena domyślna” (kryteria akceptacji)
- Wejście: `OutlinedButton` na ekranie statusu, widoczny zawsze, z aktualną wartością w etykiecie
  (`Cena domyślna paliwa: 6,19 zł/l`) albo bez niej (`Ustaw cenę domyślną paliwa`). Nie jest blokowany na czas
  synchronizacji — przeliczenie czeka na `mutex` (§12.1). Wyjście: `TextButton` „Wstecz” i `BackHandler` (jak §10.3);
  powrót na status woła `session.reload()`, więc liczniki się odświeżą.
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
  (cena dotyczy tras sprzed pierwszego potwierdzonego tankowania), „Zapisz” (blokada na czas zapisu, drugi klik
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

Toyota usuwa trasy starsze niż ok. 12 miesięcy, a `allowBackup=false` (SECURITY.md), więc ręczna kopia jest jedyną
ochroną archiwum (ADR-006). Kopia to jeden plik JSON; import dodaje tylko to, czego w telefonie jeszcze nie ma.
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
- Komórka tekstowa (nie liczba) zaczynająca się od `=`, `+`, `-` albo `@` dostaje na początku `'` – arkusz nie
  wykona jej jako formuły (CSV injection); dziś tekstami są tylko stałe aplikacji, wymóg dotyczy przyszłych kolumn.
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
  Ekran: miejsca (`54.372, 18.638` – 0,001°, ok. 100 m), 50 ostatnich postojów, liczniki pominiętych. Położenie tylko
  na tym ekranie – nie w logach, CSV, nazwach plików ani innych aplikacjach (bez intentu `geo:` – decyzja właściciela
  otwarta). Nic nie pyta auta o pozycję (`refresh_status()` zakazany).
