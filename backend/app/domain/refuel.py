"""Refuel detection and confirmation (pure domain logic)."""

from __future__ import annotations

from dataclasses import replace
from decimal import Decimal

from .models import (
    FuelReading,
    Refuel,
    as_decimal,
    round_liters,
    round_money,
    round_price,
)

DecimalLike = Decimal | float | int | str | None

#: Admissible input combinations for :func:`confirm_refuel`.
_ALLOWED_COMBOS: frozenset[frozenset[str]] = frozenset(
    {
        frozenset({"price_per_liter"}),
        frozenset({"liters", "total_cost"}),
        frozenset({"price_per_liter", "liters"}),
        frozenset({"price_per_liter", "total_cost"}),
    }
)


def detect_refuel(
    prev: FuelReading,
    cur: FuelReading,
    min_jump_pct: int = 8,
) -> Refuel | None:
    """Detect a refuel from a jump in fuel level between two readings.

    Returns a ``Refuel`` with ``status="pending"`` when
    ``cur.level_pct - prev.level_pct >= min_jump_pct``, else ``None``.
    Raises ``ValueError`` when ``cur.ts <= prev.ts``.
    """
    if cur.ts <= prev.ts:
        raise ValueError(
            f"cur.ts ({cur.ts.isoformat()}) must be strictly after "
            f"prev.ts ({prev.ts.isoformat()})"
        )
    if cur.level_pct - prev.level_pct < min_jump_pct:
        return None
    return Refuel(
        id=f"refuel-{cur.ts.isoformat()}",
        ts=cur.ts,
        odometer_km=cur.odometer_km,
        level_before=prev.level_pct,
        level_after=cur.level_pct,
        liters=None,
        price_per_liter=None,
        total_cost=None,
        status="pending",
    )


def confirm_refuel(
    refuel: Refuel,
    *,
    price_per_liter: DecimalLike = None,
    liters: DecimalLike = None,
    total_cost: DecimalLike = None,
) -> Refuel:
    """Return a copy of ``refuel`` with ``status="confirmed"`` and finalized
    money fields.

    Admissible combinations of provided values:
    ``(price)``, ``(liters + total_cost)``, ``(price + liters)``,
    ``(price + total_cost)``. Any other combination raises ``ValueError``.

    The missing magnitude is derived (price = total/liters,
    total = price*liters, liters = total/price). Prices are rounded to 0.001,
    amounts to 0.01 (ROUND_HALF_UP); liters to 0.001. Any provided value
    ``<= 0`` raises ``ValueError``. The input object is never mutated.
    """
    given = {
        name
        for name, value in (
            ("price_per_liter", price_per_liter),
            ("liters", liters),
            ("total_cost", total_cost),
        )
        if value is not None
    }
    if frozenset(given) not in _ALLOWED_COMBOS:
        raise ValueError(
            "invalid combination of price_per_liter/liters/total_cost; "
            "allowed: (price), (liters+total_cost), (price+liters), "
            "(price+total_cost)"
        )

    for name, value in (
        ("price_per_liter", price_per_liter),
        ("liters", liters),
        ("total_cost", total_cost),
    ):
        if value is not None and as_decimal(value) <= 0:
            raise ValueError(f"{name} must be > 0 (got {value!r})")

    if price_per_liter is not None:
        price_per_liter = round_price(price_per_liter)
    if liters is not None:
        liters = round_liters(liters)
    if total_cost is not None:
        total_cost = round_money(total_cost)

    for name, value in (
        ("price_per_liter", price_per_liter),
        ("liters", liters),
        ("total_cost", total_cost),
    ):
        if value is not None and value <= 0:
            raise ValueError(f"{name} rounds to 0; value too small")

    if given == {"liters", "total_cost"}:
        price_per_liter = round_price(as_decimal(total_cost) / as_decimal(liters))
    elif given == {"price_per_liter", "liters"}:
        total_cost = round_money(as_decimal(price_per_liter) * as_decimal(liters))
    elif given == {"price_per_liter", "total_cost"}:
        liters = round_liters(as_decimal(total_cost) / as_decimal(price_per_liter))
    # given == {"price_per_liter"}: liters and total_cost stay unknown (None).

    return replace(
        refuel,
        liters=liters,
        price_per_liter=price_per_liter,
        total_cost=total_cost,
        status="confirmed",
    )


def dismiss_refuel(refuel: Refuel) -> Refuel:
    """Return a copy of ``refuel`` with ``status="dismissed"``."""
    return replace(refuel, status="dismissed")
