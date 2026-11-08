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
    level_before: int = 20,
    level_after: int = 80,
    liters_source: str | None = "receipt",
) -> Refuel:
    return Refuel(
        id=rid,
        ts=ts or datetime(2026, 10, 1, 12, 0, 0),
        odometer_km=1000.0,
        level_before=level_before,
        level_after=level_after,
        liters=D("25.800") if price else None,
        price_per_liter=D(price) if price else None,
        total_cost=None,
        status=status,
        liters_source=liters_source if price else None,
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
        # ADR-005: (8.6 * 5.99 + 25.8 * 6.19) / 34.4 = 6.140
        assert result.price_per_liter == D("6.140")
        assert result.cost_pln == D("3.68")

    def test_uses_weighted_average_across_refuels(self):
        first = refuel(rid="first", ts=datetime(2026, 9, 1), price="5.99")
        second = refuel(rid="second", ts=datetime(2026, 10, 4), price="6.19")
        result = compute_trip_cost(trip(), [first, second])
        assert result.source == "refuel"
        assert result.refuel_id == "second"
        assert result.price_per_liter == D("6.140")
        assert result.cost_pln == D("3.68")

    def test_estimate_liters_update_tank_price(self):
        estimate = refuel(rid="estimate", liters_source="estimate")
        result = compute_trip_cost(trip(), [estimate], default_price=D("6.00"))
        assert result.source == "refuel"
        assert result.refuel_id == "estimate"
        # 8,6 l at the default 6,00 + 25,8 l at 6,19 -> 6,143 zl/l
        assert result.price_per_liter == D("6.143")
        assert result.cost_pln == D("3.69")

    def test_ignores_confirmed_refuel_without_liters(self):
        no_liters = refuel(price=None)
        result = compute_trip_cost(trip(), [no_liters], default_price=D("6.00"))
        assert result.source == "default"

    def test_price_only_refuel_updates_tank_price_with_sensor_liters(self):
        price_only = Refuel(
            id="price-only",
            ts=datetime(2026, 10, 1, 12, 0, 0),
            odometer_km=1000.0,
            level_before=20,
            level_after=80,
            liters=None,
            price_per_liter=D("6.19"),
            total_cost=None,
            status="confirmed",
            liters_source=None,
        )
        result = compute_trip_cost(trip(), [price_only], default_price=D("6.00"))
        assert result.source == "refuel"
        assert result.refuel_id == "price-only"
        # sensor liters 25,8 mixed with 8,6 l at the default 6,00
        assert result.price_per_liter == D("6.143")
        assert result.cost_pln == D("3.69")

    def test_refuel_beats_default_price(self):
        result = compute_trip_cost(trip(), [refuel(price="6.19")], D("10.00"))
        assert result.source == "refuel"
        assert result.refuel_id == "r1"
        # the default is only the price of the 8,6 l already in the tank
        assert result.price_per_liter == D("7.143")
        assert result.cost_pln == D("4.29")


class TestDefaultPriceInTank:
    """ADR-005 amendment 2026-11-08: the default price is the fuel already in the tank.

    It applies before the first confirmed refuel only.
    """

    def test_default_price_is_the_tank_price_before_the_first_refuel(self):
        # The owner's case: 67 % -> 100 % at 5,92 zl/l (price only, sensor liters 14,19);
        # the tank was priced at the default 6,30 before.
        first = Refuel(
            id="r-08-11",
            ts=datetime(2026, 11, 8, 10, 42, 0),
            odometer_km=12000.0,
            level_before=67,
            level_after=100,
            liters=None,
            price_per_liter=D("5.92"),
            total_cost=None,
            status="confirmed",
            liters_source=None,
        )
        result = compute_trip_cost(
            trip(start=datetime(2026, 11, 8, 17, 21, 0), fuel="0.77"),
            [first],
            default_price=D("6.30"),
        )
        assert result.source == "refuel"
        assert result.refuel_id == "r-08-11"
        assert result.price_per_liter == D("6.175")
        assert result.cost_pln == D("4.75")

    def test_first_refuel_without_default_price_uses_its_own_price(self):
        result = compute_trip_cost(trip(), [refuel(price="6.19")])
        assert result.price_per_liter == D("6.19")

    def test_first_refuel_on_an_empty_tank_ignores_the_default_price(self):
        empty = refuel(price="6.19", level_before=0, level_after=60)
        result = compute_trip_cost(trip(), [empty], default_price=D("10.00"))
        assert result.price_per_liter == D("6.19")

    def test_default_price_mixes_only_into_the_first_refuel(self):
        first = refuel(rid="r1", ts=datetime(2026, 10, 1, 12, 0, 0), price="6.19")
        second = refuel(rid="r2", ts=datetime(2026, 10, 3, 12, 0, 0), price="5.00")
        result = compute_trip_cost(trip(), [first, second], default_price=D("10.00"))
        # first: 7,143 (see test_refuel_beats_default_price);
        # second: (8,6 x 7,143 + 25,8 x 5,00) / 34,4
        assert result.refuel_id == "r2"
        assert result.price_per_liter == D("5.536")

    def test_zero_default_price_is_no_tank_price(self):
        result = compute_trip_cost(trip(), [refuel(price="6.19")], default_price=D("0"))
        assert result.price_per_liter == D("6.19")

    def test_zero_default_price_before_any_refuel_is_no_price(self):
        for default in (D("0"), D("-5")):
            result = compute_trip_cost(trip(), [], default_price=default)
            assert result.source == "none"
            assert result.cost_pln is None

    def test_changing_the_default_price_changes_the_first_refuel(self):
        trips = [trip("a")]
        first = [refuel(price="6.19")]
        at_ten = recompute_costs(trips, first, default_price=D("10.00"))
        at_six = recompute_costs(trips, first, default_price=D("6.00"))
        removed = recompute_costs(trips, first)
        assert [c.price_per_liter for c in at_ten + at_six + removed] == [
            D("7.143"),
            D("6.143"),
            D("6.19"),
        ]

    def test_a_refuel_with_unusable_levels_leaves_the_default_price(self):
        broken = refuel(price="6.19", level_before=20, level_after=0)
        result = compute_trip_cost(trip(), [broken], default_price=D("6.30"))
        assert result.source == "default"
        assert result.price_per_liter == D("6.30")


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
                    "liters": D("25.800"),
                    "price_per_liter": D("6.19"),
                    "liters_source": "receipt",
                    "status": "confirmed",
                }
            )
        ]
        after = recompute_costs(trips, confirmed, default_price=D("6.00"))
        assert [c.source for c in after] == ["refuel", "refuel"]
        assert [c.cost_pln for c in after] == [D("3.69"), D("3.69")]

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
        "r",
        datetime(2026, 1, 1, tzinfo=UTC),
        1.0,
        10,
        90,
        D("34.400"),
        D("6.19"),
        None,
        "confirmed",
        "receipt",
        True,
    )
    assert compute_trip_cost(t, [r]).cost_pln == D("3.71")
