"""C45: the pure rules of a refuel the owner enters by hand (mirrors ManualRefuelTest.kt)."""

from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest

from app.domain import (
    FuelReading,
    Refuel,
    Trip,
    find_nearby_refuel,
    level_before_manual_refuel,
    manual_refuel,
)

T0 = datetime(2026, 11, 10, 8, 0, tzinfo=UTC)
MOMENT = datetime(2026, 11, 10, 10, 0, tzinfo=UTC)


def reading(ts: datetime, level: int) -> FuelReading:
    return FuelReading(ts=ts, level_pct=level, odometer_km=12_000.0)


def trip(start: datetime, fuel: str) -> Trip:
    return Trip(
        id=f"t-{start.isoformat()}",
        start=start,
        end=start + timedelta(minutes=10),
        distance_km=10.0,
        fuel_l=Decimal(fuel),
        ev_distance_km=None,
    )


def stored(refuel_id: str, ts: datetime) -> Refuel:
    return Refuel(
        id=refuel_id,
        ts=ts,
        odometer_km=1.0,
        level_before=10,
        level_after=90,
        liters=Decimal("30"),
        price_per_liter=Decimal("6"),
        total_cost=Decimal("180"),
        status="confirmed",
    )


def test_level_before_is_the_reading_minus_the_trips_after_it():
    # 50 % of 43 l = 21.5 l; the trip after the reading burns 2 l -> 19.5 l -> 45 %.
    level = level_before_manual_refuel(
        reading(T0, 50), [trip(T0 + timedelta(seconds=60), "2.0")], MOMENT
    )
    assert level == 45


def test_trips_before_the_reading_and_after_the_moment_do_not_count():
    trips = [trip(T0 - timedelta(seconds=60), "5.0"), trip(MOMENT + timedelta(seconds=60), "5.0")]
    assert level_before_manual_refuel(reading(T0, 50), trips, MOMENT) == 50


def test_no_reading_or_a_reading_after_the_moment_or_too_old_gives_no_level():
    assert level_before_manual_refuel(None, [], MOMENT) is None
    assert (
        level_before_manual_refuel(reading(MOMENT + timedelta(seconds=1), 50), [], MOMENT) is None
    )
    assert level_before_manual_refuel(reading(MOMENT - timedelta(days=31), 50), [], MOMENT) is None


def test_the_level_never_goes_below_zero():
    assert level_before_manual_refuel(reading(T0, 10), [trip(T0, "20.0")], MOMENT) == 0


def test_nearby_refuel_is_the_closest_within_twelve_hours():
    far = stored("far", MOMENT - timedelta(hours=13))
    near = stored("near", MOMENT - timedelta(hours=3))
    assert find_nearby_refuel([far, near], MOMENT).id == "near"
    assert find_nearby_refuel([far], MOMENT) is None


def test_manual_refuel_is_confirmed_price_from_total_level_after_from_liters():
    refuel = manual_refuel(
        "manual-1",
        ts=MOMENT,
        odometer_km=12_000.0,
        level_before=20,
        liters=Decimal("30"),
        total_cost=Decimal("180.00"),
    )
    assert refuel.status == "confirmed"
    assert refuel.price_per_liter == Decimal("6.000")
    assert refuel.level_after == 90


def test_refuel_to_full_ends_at_100_percent():
    refuel = manual_refuel(
        "m",
        ts=MOMENT,
        odometer_km=1.0,
        level_before=20,
        liters=Decimal("30"),
        price_per_liter=Decimal("6"),
        filled_to_full=True,
    )
    assert refuel.level_after == 100
    assert refuel.filled_to_full is True


def test_liters_alone_are_refused():
    with pytest.raises(ValueError):
        manual_refuel("m", ts=MOMENT, odometer_km=1.0, level_before=20, liters=Decimal("30"))
