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
  `added = (level_after − level_before) % × capacity_l`, zaokrąglone do 0.001
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
  `API`, `NETWORK`, `DATA` (ponawiane przez WorkManager).
- Błąd nigdy nie usuwa ani nie cofa danych; kroki wykonane przed błędem zostają zapisane;
  znacznik tras przesuwa się tylko po pełnym sukcesie. `isStale` = brak sukcesu albo błąd po ostatnim sukcesie.
- Komunikat zapisany w stanie przechodzi przez `ToyotaSecrets.sanitize` (bez VIN, tokenów, hasła).

### §8.5 Wynik i współbieżność
- Wynik jako `SyncOutcome` (`Ok`, `Failed`, `Skipped`), nie wyjątki (poza anulowaniem korutyny).
- Jedna synchronizacja naraz (mutex); równoległe wywołanie -> `Skipped`.
- `recomputeCosts()` bez sieci – po potwierdzeniu ceny tankowania (F4).
- Dług: koszty wszystkich tras są przeliczane przy każdej synchronizacji (O(trasy × tankowania));
  do optymalizacji, gdy archiwum urośnie.
