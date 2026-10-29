"""Refuel detection and confirmation (pure domain logic)."""

from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime
from decimal import Decimal

from .models import (
    DEFAULT_TANK_CAPACITY_L,
    FuelReading,
    LitersSource,
    Refuel,
    as_decimal,
    round_liters,
    round_money,
    round_price,
)

DecimalLike = Decimal | float | int | str | None
PositiveDecimalLike = Decimal | float | int | str

_LITERS_SOURCES: frozenset[str] = frozenset({"receipt", "estimate", "derived"})


def _canonical_utc_isoformat(ts: datetime) -> str:
    """Canonical UTC ISO-8601 with ``Z`` and 0/3/6 fractional digits.

    Matches Kotlin ``Instant.toString()`` for the precision available in
    Python's ``datetime`` (microseconds). Naive input is interpreted as UTC
    (PRD: all dates are UTC).
    """
    aware = ts.replace(tzinfo=UTC) if ts.tzinfo is None else ts.astimezone(UTC)
    base = aware.strftime("%Y-%m-%dT%H:%M:%S")
    micro = aware.microsecond
    if micro == 0:
        return f"{base}Z"
    fraction = f"{micro // 1000:03d}" if micro % 1000 == 0 else f"{micro:06d}"
    return f"{base}.{fraction}Z"


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
            f"cur.ts ({cur.ts.isoformat()}) must be strictly after prev.ts ({prev.ts.isoformat()})"
        )
    if cur.level_pct - prev.level_pct < min_jump_pct:
        return None
    return Refuel(
        id=f"refuel-{_canonical_utc_isoformat(cur.ts)}",
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
    liters_source: LitersSource | None = None,
    filled_to_full: bool | None = None,
) -> Refuel:
    """Return a copy of ``refuel`` with ``status="confirmed"`` and finalized
    money fields.

    Admissible combinations of provided values:
    ``(price)``, ``(liters + total_cost)``, ``(price + liters)``,
    ``(price + total_cost)``. Any other combination raises ``ValueError``.

    The missing magnitude is derived (price = total/liters,
    total = price*liters, liters = total/price). Prices are rounded to 0.001,
    amounts to 0.01, liters to 0.001 (ROUND_HALF_UP). Any provided value
    ``<= 0`` raises ``ValueError``. The input object is never mutated.

    ``liters_source`` records where the liters came from. When omitted it is
    inferred: user-provided liters -> ``"receipt"``, liters derived from
    ``price + total_cost`` -> ``"derived"``. ``filled_to_full`` is meaningful
    only with liters (F9 requires full-to-full receipts).
    """
    if liters_source is not None and liters_source not in _LITERS_SOURCES:
        raise ValueError(
            f"liters_source must be one of {sorted(_LITERS_SOURCES)} (got {liters_source!r})"
        )
    liters_was_provided = liters is not None
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

    price_v: Decimal | None = None
    liters_v: Decimal | None = None
    total_v: Decimal | None = None
    if price_per_liter is not None:
        price_v = round_price(price_per_liter)
    if liters is not None:
        liters_v = round_liters(liters)
    if total_cost is not None:
        total_v = round_money(total_cost)

    for name, value in (
        ("price_per_liter", price_v),
        ("liters", liters_v),
        ("total_cost", total_v),
    ):
        if value is not None and value <= 0:
            raise ValueError(f"{name} rounds to 0; value too small")

    # The combination was validated above; branching on None-checks keeps
    # mypy narrowing precise without asserts.
    if price_v is not None and liters_v is not None:
        total_v = round_money(price_v * liters_v)
    elif price_v is not None and total_v is not None:
        liters_v = round_liters(total_v / price_v)
    elif price_v is None and liters_v is not None and total_v is not None:
        price_v = round_price(total_v / liters_v)
    # price-only: liters_v and total_v stay unknown (None).

    if liters_v is None:
        if liters_source is not None:
            raise ValueError("liters_source requires liters")
        if filled_to_full is not None:
            raise ValueError("filled_to_full requires liters")
        liters_source_v: LitersSource | None = None
    elif liters_source is None:
        liters_source_v = "receipt" if liters_was_provided else "derived"
    elif liters_source == "receipt" and not liters_was_provided:
        # Liters came from total/price, not from the receipt.
        liters_source_v = "derived"
    else:
        liters_source_v = liters_source

    return replace(
        refuel,
        liters=liters_v,
        price_per_liter=price_v,
        total_cost=total_v,
        status="confirmed",
        liters_source=liters_source_v,
        filled_to_full=(filled_to_full if filled_to_full is not None else refuel.filled_to_full),
    )


def _level_to_liters(level_pct: int, capacity_l: Decimal) -> Decimal:
    return Decimal(level_pct) / Decimal(100) * capacity_l


def tank_price_after_refuel(
    refuel: Refuel,
    previous_tank_price: PositiveDecimalLike | None = None,
    capacity_l: PositiveDecimalLike = DEFAULT_TANK_CAPACITY_L,
) -> Decimal | None:
    """Average price of fuel in the tank after a confirmed refuel (ADR-005).

    ``liters_before = level_before% * capacity``; ``added`` = ``refuel.liters``
    (receipt, derived or sensor estimate) or, when only the price was given,
    the sensor estimate ``(level_after - level_before)% * capacity``::

        tank_price = (liters_before * previous_tank_price + added * price)
                     / (liters_before + added)

    The denominator is the fuel that actually mixed, so the result always lies
    between the previous price and the new price. Returns ``None`` when the
    refuel is not confirmed, has no price, has invalid levels or no
    determinable added liters. The first refuel (no previous tank price), an
    empty tank before the refuel, or a non-positive previous price uses its own
    ``price_per_liter``. The result is rounded to 0.001 zl/l with ROUND_HALF_UP.
    """
    capacity = as_decimal(capacity_l)
    if capacity <= 0:
        raise ValueError(f"capacity_l must be > 0 (got {capacity_l!r})")
    price = refuel.price_per_liter
    if (
        refuel.status != "confirmed"
        or price is None
        or not 0 <= refuel.level_before <= 100
        or not 0 < refuel.level_after <= 100
    ):
        return None

    if refuel.liters is not None:
        added = refuel.liters
    else:
        jump = refuel.level_after - refuel.level_before
        if jump <= 0:
            return None
        added = round_liters(_level_to_liters(jump, capacity))
    if added <= 0:
        return None

    liters_before = _level_to_liters(refuel.level_before, capacity)
    previous = as_decimal(previous_tank_price) if previous_tank_price is not None else None
    if previous is None or previous <= 0 or liters_before <= 0:
        return round_price(price)

    weighted = (liters_before * previous + added * price) / (liters_before + added)
    return round_price(weighted)


def dismiss_refuel(refuel: Refuel) -> Refuel:
    """Return a copy of ``refuel`` with ``status="dismissed"``."""
    return replace(refuel, status="dismissed")
