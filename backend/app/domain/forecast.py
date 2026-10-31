"""Refuel forecast: when the tank reaches its reserve (PRD F14, SPEC §20)."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import ROUND_HALF_UP, Decimal

from .models import (
    DEFAULT_TANK_CAPACITY_L,
    FuelReading,
    Trip,
    as_decimal,
    round_consumption,
    round_distance,
    round_liters,
    round_percent,
)

#: Below this share of the tank the forecast says "refuel" (10 % of 43 l = 4.3 l).
RESERVE_PCT = Decimal("10")

#: Days of trips that define the usual consumption and daily distance.
WINDOW_DAYS = 28

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)
_TENTH = Decimal("0.1")


@dataclass(frozen=True)
class RefuelForecast:
    """Fuel left now and how far and how long it lasts until the reserve."""

    liters_left: Decimal
    level_pct: Decimal
    l_per_100km: Decimal
    daily_km: Decimal
    km_to_reserve: Decimal
    days_to_reserve: Decimal


def refuel_forecast(
    reading: FuelReading | None,
    trips: Iterable[Trip],
    now: datetime,
    capacity_l: Decimal = DEFAULT_TANK_CAPACITY_L,
    reserve_pct: Decimal = RESERVE_PCT,
    window_days: int = WINDOW_DAYS,
) -> RefuelForecast | None:
    """Forecast of **one** vehicle from its latest fuel reading and its trips.

    Fuel now = ``level% x capacity`` minus the fuel of the trips that started at or after
    the reading (the sensor is read only when the app synchronizes). Consumption and
    daily distance come from the trips of the last ``window_days`` days; an archive
    younger than that uses the days it has (at least one). Toyota's own range is not
    used - it proved unreliable. ``None`` without a reading, without trips in the window
    or with zero distance or fuel there.
    """
    if reading is None:
        return None
    trip_list = list(trips)
    window_start = now - timedelta(days=window_days)
    in_window = [trip for trip in trip_list if window_start <= trip.start <= now]
    distance = sum((as_decimal(trip.distance_km) for trip in in_window), _ZERO)
    fuel = sum((trip.fuel_l for trip in in_window), _ZERO)
    if distance <= _ZERO or fuel <= _ZERO:
        return None

    first_start = min(trip.start for trip in trip_list)
    archive_days = (now - first_start).total_seconds() / 86_400
    days = Decimal(max(1, min(window_days, int(-(-archive_days // 1)))))

    capacity = as_decimal(capacity_l)
    used_since = sum((trip.fuel_l for trip in trip_list if trip.start >= reading.ts), _ZERO)
    left = max(_ZERO, Decimal(reading.level_pct) / _HUNDRED * capacity - used_since)
    consumption = fuel * _HUNDRED / distance
    daily = distance / days
    reserve = as_decimal(reserve_pct) / _HUNDRED * capacity
    km_to_reserve = max(_ZERO, left - reserve) * _HUNDRED / consumption
    return RefuelForecast(
        liters_left=round_liters(left),
        level_pct=round_percent(left * _HUNDRED / capacity),
        l_per_100km=round_consumption(consumption),
        daily_km=round_distance(daily),
        km_to_reserve=round_distance(km_to_reserve),
        days_to_reserve=(km_to_reserve / daily).quantize(_TENTH, rounding=ROUND_HALF_UP),
    )
