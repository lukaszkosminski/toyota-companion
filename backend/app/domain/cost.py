"""Trip cost computation (pure domain logic)."""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from decimal import Decimal

from .models import (
    DEFAULT_TANK_CAPACITY_L,
    Refuel,
    Trip,
    TripCost,
    as_decimal,
    round_money,
)
from .refuel import PositiveDecimalLike, tank_price_after_refuel

DecimalLike = Decimal | float | int | str | None


def _confirmed_in_order(refuels: Iterable[Refuel]) -> list[Refuel]:
    """Confirmed refuels in deterministic chronological order."""
    return sorted(
        (refuel for refuel in refuels if refuel.status == "confirmed"),
        key=lambda refuel: (refuel.ts, refuel.id),
    )


def compute_trip_cost(
    trip: Trip,
    refuels: Iterable[Refuel],
    default_price: DecimalLike = None,
    capacity_l: PositiveDecimalLike = DEFAULT_TANK_CAPACITY_L,
) -> TripCost:
    """Cost of a trip based on the weighted tank price (ADR-005).

    Confirmed refuels up to ``trip.start`` are applied in chronological order;
    each one with a price updates the average price in the tank (see
    :func:`tank_price_after_refuel`; liters may be a sensor estimate). The latest such price is
    used: cost is ``trip.fuel_l * tank_price`` rounded to 0.01 zl
    (ROUND_HALF_UP), ``source="refuel"``. With no usable refuel but a
    ``default_price``, ``source="default"``. Otherwise everything is ``None``
    and ``source="none"``. Pending/dismissed refuels are ignored.
    """
    tank_price: Decimal | None = None
    used_refuel: Refuel | None = None
    for refuel in _confirmed_in_order(refuels):
        if refuel.ts > trip.start:
            break
        updated = tank_price_after_refuel(refuel, tank_price, capacity_l)
        if updated is not None:
            tank_price = updated
            used_refuel = refuel

    if used_refuel is not None and tank_price is not None:
        return TripCost(
            trip_id=trip.id,
            cost_pln=round_money(as_decimal(trip.fuel_l) * tank_price),
            price_per_liter=tank_price,
            refuel_id=used_refuel.id,
            source="refuel",
        )

    if default_price is not None:
        return TripCost(
            trip_id=trip.id,
            cost_pln=round_money(as_decimal(trip.fuel_l) * as_decimal(default_price)),
            price_per_liter=as_decimal(default_price),
            refuel_id=None,
            source="default",
        )

    return TripCost(
        trip_id=trip.id,
        cost_pln=None,
        price_per_liter=None,
        refuel_id=None,
        source="none",
    )


def recompute_costs(
    trips: Sequence[Trip],
    refuels: Iterable[Refuel],
    default_price: DecimalLike = None,
    capacity_l: PositiveDecimalLike = DEFAULT_TANK_CAPACITY_L,
) -> list[TripCost]:
    """Recompute costs for all trips in input order (retroactive recount
    after a price is confirmed)."""
    refuels = list(refuels)
    return [compute_trip_cost(trip, refuels, default_price, capacity_l) for trip in trips]
