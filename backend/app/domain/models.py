"""Domain types for toyota-companion (pure stdlib, no I/O, no pytoyoda).

Money and liters are always ``decimal.Decimal``; all dataclasses are frozen.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import ROUND_HALF_UP, Decimal
from typing import Literal

RefuelStatus = Literal["pending", "confirmed", "dismissed"]
TripCostSource = Literal["refuel", "default", "none"]
LitersSource = Literal["receipt", "estimate", "derived"]
EconomyStatus = Literal[
    "ok",
    "open",
    "pending_refuel",
    "dismissed_refuel",
    "missing_receipt",
    "no_trips",
    "too_short",
]

#: Quantization steps (ROUND_HALF_UP everywhere).
PRICE_QUANTUM = Decimal("0.001")  # price per liter, zl/l
MONEY_QUANTUM = Decimal("0.01")  # amounts, zl
LITER_QUANTUM = Decimal("0.001")  # liters, l
CONSUMPTION_QUANTUM = Decimal("0.01")  # fuel economy, l/100 km
PERCENT_QUANTUM = Decimal("0.1")  # differences, %
DISTANCE_QUANTUM = Decimal("0.1")  # distances, km

#: Tank capacity of the owner's Corolla Hybrid 2025 (PRD §7, ADR-005).
DEFAULT_TANK_CAPACITY_L = Decimal("43")


def as_decimal(value: Decimal | float | int | str) -> Decimal:
    """Coerce a numeric input to Decimal without binary-float artifacts."""
    if isinstance(value, Decimal):
        return value
    return Decimal(str(value))


def round_price(value: Decimal | float | int | str) -> Decimal:
    """Round a price to 0.001 zl/l, ROUND_HALF_UP."""
    return as_decimal(value).quantize(PRICE_QUANTUM, rounding=ROUND_HALF_UP)


def round_money(value: Decimal | float | int | str) -> Decimal:
    """Round a money amount to 0.01 zl, ROUND_HALF_UP."""
    return as_decimal(value).quantize(MONEY_QUANTUM, rounding=ROUND_HALF_UP)


def round_liters(value: Decimal | float | int | str) -> Decimal:
    """Round liters to 0.001 l, ROUND_HALF_UP (pump precision)."""
    return as_decimal(value).quantize(LITER_QUANTUM, rounding=ROUND_HALF_UP)


def round_consumption(value: Decimal | float | int | str) -> Decimal:
    """Round fuel economy to 0.01 l/100 km, ROUND_HALF_UP."""
    return as_decimal(value).quantize(CONSUMPTION_QUANTUM, rounding=ROUND_HALF_UP)


def round_percent(value: Decimal | float | int | str) -> Decimal:
    """Round a percentage to 0.1 %, ROUND_HALF_UP."""
    return as_decimal(value).quantize(PERCENT_QUANTUM, rounding=ROUND_HALF_UP)


def round_distance(value: Decimal | float | int | str) -> Decimal:
    """Round a distance to 0.1 km, ROUND_HALF_UP."""
    return as_decimal(value).quantize(DISTANCE_QUANTUM, rounding=ROUND_HALF_UP)


@dataclass(frozen=True)
class FuelReading:
    ts: datetime
    level_pct: int
    odometer_km: float
    battery_level_pct: int | None = None
    distance_to_empty_km: float | None = None


@dataclass(frozen=True)
class Refuel:
    id: str
    ts: datetime
    odometer_km: float
    level_before: int
    level_after: int
    liters: Decimal | None
    price_per_liter: Decimal | None
    total_cost: Decimal | None
    status: RefuelStatus
    liters_source: LitersSource | None = None
    filled_to_full: bool | None = None


@dataclass(frozen=True)
class Trip:
    id: str
    start: datetime
    end: datetime
    distance_km: float
    fuel_l: Decimal
    ev_distance_km: float | None


@dataclass(frozen=True)
class TripCost:
    trip_id: str
    cost_pln: Decimal | None
    price_per_liter: Decimal | None
    refuel_id: str | None
    source: TripCostSource


@dataclass(frozen=True)
class EconomyInterval:
    """Fuel economy between two full-tank refuels (PRD F9, docs/SPEC.md §15).

    ``end_refuel_id`` is ``None`` for the open interval after the last full refuel.
    Distances and the computer side come from the trips that start in
    ``[start.ts, end.ts)``; the actual side from the liters of the confirmed refuels
    in ``(start, end]``. The ``*_l_per_100km`` and ``difference_pct`` values are set
    only where they mean something (see :func:`fuel_economy_intervals`).
    """

    start_refuel_id: str
    end_refuel_id: str | None
    status: EconomyStatus
    trips: int
    distance_km: Decimal
    ev_distance_km: Decimal
    computer_liters: Decimal
    actual_liters: Decimal | None
    odometer_km: Decimal | None
    computer_l_per_100km: Decimal | None
    actual_l_per_100km: Decimal | None
    difference_pct: Decimal | None
