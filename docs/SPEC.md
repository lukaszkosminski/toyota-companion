# SPEC – tankowania i koszt tras

## Model domenowy (czysty Python, bez I/O, bez pytoyoda)
Katalog: `backend/app/domain/`. Tylko stdlib + dataclasses. Pieniądze i litry jako `decimal.Decimal`.

### Typy
- `FuelReading(ts: datetime, level_pct: int, odometer_km: float)`
- `Refuel(id: str, ts: datetime, odometer_km: float, level_before: int, level_after: int, liters: Decimal | None, price_per_liter: Decimal | None, total_cost: Decimal | None, status: "pending" | "confirmed" | "dismissed")`
- `Trip(id: str, start: datetime, end: datetime, distance_km: float, fuel_l: Decimal, ev_distance_km: float | None)`
- `TripCost(trip_id: str, cost_pln: Decimal | None, price_per_liter: Decimal | None, refuel_id: str | None, source: "refuel" | "default" | "none")`

### Funkcje
1. `detect_refuel(prev: FuelReading, cur: FuelReading, min_jump_pct: int = 8) -> Refuel | None`
   - zwraca `Refuel(status="pending")` gdy `cur.level_pct - prev.level_pct >= min_jump_pct`, inaczej `None`
   - `ts` = `cur.ts`, `odometer_km` = `cur.odometer_km`; `id` deterministyczne z `cur.ts` (isoformat)
     Kotlin: `id = refuel-<Instant ISO-8601 z Z>`
   - `cur.ts <= prev.ts` -> `ValueError`
2. `confirm_refuel(refuel, *, price_per_liter=None, liters=None, total_cost=None) -> Refuel`
   - dozwolone kombinacje: (price), (liters+total_cost), (price+liters), (price+total_cost). Pozostałe -> `ValueError`.
   - brakującą wielkość wylicz (price = total/liters, total = price*liters, liters = total/price); ceny zaokrągl do 0.001, kwoty do 0.01 (ROUND_HALF_UP)
   - wartości <= 0 -> `ValueError`; zwraca nowy obiekt z `status="confirmed"` (immutability: `dataclasses.replace`)
3. `dismiss_refuel(refuel) -> Refuel` -> `status="dismissed"`
4. `compute_trip_cost(trip, refuels, default_price=None) -> TripCost`
   - bierze ostatnie tankowanie ze `status="confirmed"` i `price_per_liter` ustawioną, z `ts <= trip.start`
   - koszt = `trip.fuel_l * price` zaokrąglone do 0.01 (ROUND_HALF_UP), `source="refuel"`
   - brak takiego tankowania, ale jest `default_price` -> `source="default"`
   - w ostateczności `cost_pln=None, price_per_liter=None, refuel_id=None, source="none"`
   - tankowania `pending`/`dismissed` są ignorowane
5. `recompute_costs(trips, refuels, default_price=None) -> list[TripCost]` – w kolejności wejścia (retroaktywne przeliczenie po potwierdzeniu ceny)

### Przykład kontrolny
Trasa 14,6 km, `fuel_l=0.60`, cena 6,19 zł/l -> koszt 3,71 zł.

## Warstwy poza domeną (osobne zadania)
- `backend/app/storage` – SQLite (trips, refuels), upsert po `trip.id`
- `backend/app/api` – FastAPI
- `android/` – Compose UI: lista tras z kosztem, powiadomienie o tankowaniu, formularz ceny
