"""Fuel economy "computer vs actual" between full-tank refuels (PRD F9, SPEC §15)."""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from decimal import Decimal

from .models import (
    EconomyInterval,
    EconomyStatus,
    Refuel,
    Trip,
    as_decimal,
    round_consumption,
    round_distance,
    round_percent,
)

#: Below this trip distance an interval is too short to compare (owner decision D8).
MIN_COMPARABLE_DISTANCE_KM = Decimal("100")

#: Liters that measure fuel taken: from the receipt, or derived from its amount and price
#: (owner decision D7). A sensor estimate never checks the car's computer (PRD F2a).
_COMPARABLE_SOURCES = frozenset({"receipt", "derived"})

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)


def fuel_economy_intervals(
    refuels: Iterable[Refuel],
    trips: Iterable[Trip],
    min_distance_km: Decimal = MIN_COMPARABLE_DISTANCE_KM,
) -> list[EconomyInterval]:
    """Intervals between consecutive full-tank refuels of **one** vehicle, oldest first.

    An interval opens at a confirmed refuel with ``filled_to_full`` and closes at the
    next one. Its actual fuel is the sum of the liters of the confirmed refuels in
    ``(start, end]`` (partial ones in between and the closing one); distance and the
    computer's fuel are sums over the trips that *start* in ``[start.ts, end.ts)`` -
    the same trips on both sides, so the delay with which a refuel is detected moves
    both sides alike. A trip that crosses the end belongs where it started.

    Status, first match wins: ``pending_refuel`` (a refuel inside still waits for a
    price), ``dismissed_refuel`` (fuel went in but has no liters), ``missing_receipt``
    (a refuel inside has no liters, or only a sensor estimate), ``no_trips`` (no
    distance or no fuel on the trips), ``too_short`` (distance below
    ``min_distance_km``), else ``ok``. After the last full refuel one ``open`` interval
    follows, with the trips so far. Refuels before the first full one open nothing.

    Economy figures: ``computer_l_per_100km`` whenever there is distance;
    ``actual_l_per_100km`` and ``difference_pct`` only for ``ok``. The difference is
    ``(actual - computer) / computer`` from the liters, rounded to 0.1 %. The odometer
    difference is informational and ``None`` when it is not positive.
    """
    ordered: list[Refuel] = sorted(refuels, key=lambda refuel: (refuel.ts, refuel.id))
    trip_list: Sequence[Trip] = sorted(trips, key=lambda trip: (trip.start, trip.id))
    anchors = [
        index
        for index, refuel in enumerate(ordered)
        if refuel.status == "confirmed"
        and refuel.filled_to_full is True
        and refuel.liters is not None
    ]

    intervals: list[EconomyInterval] = []
    for position, start_index in enumerate(anchors):
        start = ordered[start_index]
        if position + 1 < len(anchors):
            end_index = anchors[position + 1]
            end = ordered[end_index]
            inside = ordered[start_index + 1 : end_index + 1]
            in_window = [trip for trip in trip_list if start.ts <= trip.start < end.ts]
            intervals.append(_closed(start, end, inside, in_window, min_distance_km))
        else:
            in_window = [trip for trip in trip_list if trip.start >= start.ts]
            intervals.append(_open(start, in_window))
    return intervals


def _totals(in_window: Sequence[Trip]) -> tuple[Decimal, Decimal, Decimal]:
    distance = sum((as_decimal(trip.distance_km) for trip in in_window), _ZERO)
    ev = sum(
        (as_decimal(trip.ev_distance_km) for trip in in_window if trip.ev_distance_km is not None),
        _ZERO,
    )
    fuel = sum((trip.fuel_l for trip in in_window), _ZERO)
    return distance, ev, fuel


def _per_100(liters: Decimal, distance: Decimal) -> Decimal:
    return round_consumption(liters * _HUNDRED / distance)


def _closed(
    start: Refuel,
    end: Refuel,
    inside: Sequence[Refuel],
    in_window: Sequence[Trip],
    min_distance_km: Decimal,
) -> EconomyInterval:
    distance, ev, fuel = _totals(in_window)
    confirmed = [refuel for refuel in inside if refuel.status == "confirmed"]
    measured = all(
        refuel.liters is not None and refuel.liters_source in _COMPARABLE_SOURCES
        for refuel in confirmed
    )
    actual = (
        sum((refuel.liters for refuel in confirmed if refuel.liters is not None), _ZERO)
        if measured
        else None
    )

    status: EconomyStatus
    if any(refuel.status == "pending" for refuel in inside):
        status = "pending_refuel"
    elif any(refuel.status == "dismissed" for refuel in inside):
        status = "dismissed_refuel"
    elif not measured:
        status = "missing_receipt"
    elif distance <= _ZERO or fuel <= _ZERO:
        status = "no_trips"
    elif distance < min_distance_km:
        status = "too_short"
    else:
        status = "ok"

    odometer = as_decimal(end.odometer_km) - as_decimal(start.odometer_km)
    ok = status == "ok" and actual is not None
    return EconomyInterval(
        start_refuel_id=start.id,
        end_refuel_id=end.id,
        status=status,
        trips=len(in_window),
        distance_km=round_distance(distance),
        ev_distance_km=round_distance(ev),
        computer_liters=fuel,
        actual_liters=actual if status in ("ok", "too_short", "no_trips") else None,
        odometer_km=round_distance(odometer) if odometer > _ZERO else None,
        computer_l_per_100km=_per_100(fuel, distance) if distance > _ZERO else None,
        actual_l_per_100km=_per_100(actual, distance) if ok and actual is not None else None,
        difference_pct=round_percent((actual - fuel) * _HUNDRED / fuel)
        if ok and actual is not None
        else None,
    )


def _open(start: Refuel, in_window: Sequence[Trip]) -> EconomyInterval:
    distance, ev, fuel = _totals(in_window)
    return EconomyInterval(
        start_refuel_id=start.id,
        end_refuel_id=None,
        status="open",
        trips=len(in_window),
        distance_km=round_distance(distance),
        ev_distance_km=round_distance(ev),
        computer_liters=fuel,
        actual_liters=None,
        odometer_km=None,
        computer_l_per_100km=_per_100(fuel, distance) if distance > _ZERO else None,
        actual_l_per_100km=None,
        difference_pct=None,
    )
