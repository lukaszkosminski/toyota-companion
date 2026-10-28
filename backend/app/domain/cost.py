"""Trip cost computation (pure domain logic)."""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from decimal import Decimal

from .models import Refuel, Trip, TripCost, as_decimal, round_money

DecimalLike = Decimal | float | int | str | None


def compute_trip_cost(
    trip: Trip,
    refuels: Iterable[Refuel],
    default_price: DecimalLike = None,
) -> TripCost:
    """Cost of a trip based on refuel prices.

    Picks the latest refuel with ``status="confirmed"``, a set
    ``price_per_liter`` and ``ts <= trip.start``; cost is
    ``trip.fuel_l * price`` rounded to 0.01 zl (ROUND_HALF_UP),
    ``source="refuel"``. With no such refuel but a ``default_price``,
    ``source="default"``. Otherwise everything is ``None`` and
    ``source="none"``. Pending/dismissed refuels are ignored.
    """
    latest: Refuel | None = None
    latest_price: Decimal | None = None
    for refuel in refuels:
        price = refuel.price_per_liter
        if refuel.status != "confirmed" or price is None:
            continue
        if refuel.ts > trip.start:
            continue
        if latest is None or refuel.ts >= latest.ts:
            latest = refuel
            latest_price = price

    if latest is not None and latest_price is not None:
        return TripCost(
            trip_id=trip.id,
            cost_pln=round_money(as_decimal(trip.fuel_l) * latest_price),
            price_per_liter=latest_price,
            refuel_id=latest.id,
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
) -> list[TripCost]:
    """Recompute costs for all trips in input order (retroactive recount
    after a price is confirmed)."""
    refuels = list(refuels)
    return [compute_trip_cost(trip, refuels, default_price) for trip in trips]
