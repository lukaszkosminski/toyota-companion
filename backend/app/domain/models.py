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

#: Quantization steps (ROUND_HALF_UP everywhere).
PRICE_QUANTUM = Decimal("0.001")  # price per liter, zl/l
MONEY_QUANTUM = Decimal("0.01")  # amounts, zl
LITER_QUANTUM = Decimal("0.001")  # liters, l


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


@dataclass(frozen=True)
class FuelReading:
    ts: datetime
    level_pct: int
    odometer_km: float


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
