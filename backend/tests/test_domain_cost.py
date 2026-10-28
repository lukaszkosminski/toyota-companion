"""Tests for app.domain.cost: compute_trip_cost, recompute_costs."""

from datetime import datetime
from decimal import Decimal

from app.domain import Refuel, Trip, compute_trip_cost, recompute_costs

D = Decimal


def trip(tid: str = "t1", start: datetime | None = None, fuel: str = "0.60") -> Trip:
    return Trip(
        id=tid,
        start=start or datetime(2026, 10, 5, 9, 0, 0),
        end=datetime(2026, 10, 5, 9, 30, 0),
        distance_km=14.6,
        fuel_l=D(fuel),
        ev_distance_km=None,
    )


def refuel(
    rid: str = "r1",
    ts: datetime | None = None,
    price: str | None = "6.19",
    status: str = "confirmed",
) -> Refuel:
    return Refuel(
        id=rid,
        ts=ts or datetime(2026, 10, 1, 12, 0, 0),
        odometer_km=1000.0,
        level_before=15,
        level_after=90,
        liters=D("60.000") if price else None,
        price_per_liter=D(price) if price else None,
        total_cost=None,
        status=status,
    )


class TestSpecExample:
    def test_control_example_14_6_km_0_60_l_6_19_pln(self):
        # Trasa 14,6 km, fuel_l=0.60, cena 6,19 zl/l -> koszt 3,71 zl.
        result = compute_trip_cost(trip(fuel="0.60"), [refuel(price="6.19")])
        assert result.trip_id == "t1"
        assert result.cost_pln == D("3.71")
        assert result.price_per_liter == D("6.19")
        assert result.refuel_id == "r1"
        assert result.source == "refuel"


class TestRefuelSelection:
    def test_ignores_pending(self):
        result = compute_trip_cost(trip(), [refuel(status="pending")], default_price=D("6.00"))
        assert result.source == "default"
        assert result.refuel_id is None

    def test_ignores_dismissed(self):
        result = compute_trip_cost(trip(), [refuel(status="dismissed")], default_price=D("6.00"))
        assert result.source == "default"

    def test_ignores_confirmed_without_price(self):
        result = compute_trip_cost(trip(), [refuel(price=None)], default_price=D("6.00"))
        assert result.source == "default"

    def test_ignores_refuel_after_trip_start(self):
        late = refuel(ts=datetime(2026, 10, 5, 9, 0, 1))  # > start
        result = compute_trip_cost(trip(), [late], default_price=D("6.00"))
        assert result.source == "default"

    def test_refuel_exactly_at_start_counts(self):
        at_start = refuel(ts=datetime(2026, 10, 5, 9, 0, 0))
        result = compute_trip_cost(trip(), [at_start])
        assert result.source == "refuel"
        assert result.refuel_id == "r1"

    def test_picks_latest_confirmed_before_start(self):
        old = refuel(rid="old", ts=datetime(2026, 9, 1), price="5.99")
        new = refuel(rid="new", ts=datetime(2026, 10, 4), price="6.19")
        after = refuel(rid="after", ts=datetime(2026, 10, 6), price="7.00")
        result = compute_trip_cost(trip(), [old, after, new])
        assert result.refuel_id == "new"
        assert result.price_per_liter == D("6.19")
        assert result.cost_pln == D("3.71")

    def test_refuel_beats_default_price(self):
        result = compute_trip_cost(trip(), [refuel(price="6.19")], D("10.00"))
        assert result.source == "refuel"
        assert result.cost_pln == D("3.71")


class TestDefaultAndNone:
    def test_default_price_used(self):
        result = compute_trip_cost(trip(fuel="0.60"), [], default_price=D("6.19"))
        assert result.source == "default"
        assert result.cost_pln == D("3.71")
        assert result.price_per_liter == D("6.19")
        assert result.refuel_id is None

    def test_no_refuel_no_default(self):
        result = compute_trip_cost(trip(), [])
        assert result.trip_id == "t1"
        assert result.cost_pln is None
        assert result.price_per_liter is None
        assert result.refuel_id is None
        assert result.source == "none"

    def test_empty_iterable_statuses_only_none_source(self):
        refuels = [
            refuel(rid="a", status="pending"),
            refuel(rid="b", status="dismissed"),
            refuel(rid="c", price=None),
        ]
        assert compute_trip_cost(trip(), refuels).source == "none"


class TestRounding:
    def test_cost_rounded_half_up_to_001(self):
        # 0.60 * 6.19 = 3.714 -> 3.71
        result = compute_trip_cost(trip(fuel="0.60"), [refuel(price="6.19")])
        assert result.cost_pln == D("3.71")
        # 0.50 * 6.19 = 3.095 -> 3.10 (ROUND_HALF_UP)
        result2 = compute_trip_cost(trip(fuel="0.50"), [refuel(price="6.19")])
        assert result2.cost_pln == D("3.10")

    def test_default_cost_rounded(self):
        result = compute_trip_cost(trip(fuel="0.50"), [], default_price=D("6.19"))
        assert result.cost_pln == D("3.10")


class TestRecomputeCosts:
    def test_returns_one_cost_per_trip_in_input_order(self):
        trips = [trip("a"), trip("b"), trip("c")]
        costs = recompute_costs(trips, [refuel()])
        assert [c.trip_id for c in costs] == ["a", "b", "c"]
        assert all(c.source == "refuel" for c in costs)

    def test_retroactive_recount_after_price_confirmed(self):
        trips = [trip("a"), trip("b")]
        pending = [refuel(rid="r1", status="pending", price=None)]

        before = recompute_costs(trips, pending, default_price=D("6.00"))
        assert [c.source for c in before] == ["default", "default"]

        confirmed = [
            Refuel(
                **{
                    **pending[0].__dict__,
                    "price_per_liter": D("6.19"),
                    "status": "confirmed",
                }
            )
        ]
        after = recompute_costs(trips, confirmed, default_price=D("6.00"))
        assert [c.source for c in after] == ["refuel", "refuel"]
        assert [c.cost_pln for c in after] == [D("3.71"), D("3.71")]

    def test_empty_trips(self):
        assert recompute_costs([], [refuel()]) == []


def test_float_fuel_from_pytoyoda_is_accepted() -> None:
    """pytoyoda returns Trip.fuel_consumed as float; it must not crash the cost."""
    from datetime import UTC, datetime

    from app.domain import Refuel, Trip, compute_trip_cost

    t = Trip(
        "t", datetime(2026, 1, 2, tzinfo=UTC), datetime(2026, 1, 2, 1, tzinfo=UTC), 14.6, 0.60, None
    )  # type: ignore[arg-type]
    r = Refuel(
        "r", datetime(2026, 1, 1, tzinfo=UTC), 1.0, 10, 90, None, D("6.19"), None, "confirmed"
    )
    assert compute_trip_cost(t, [r]).cost_pln == D("3.71")
