"""Tests for app.domain.economy: fuel_economy_intervals (PRD F9, SPEC §15)."""

from datetime import datetime, timedelta
from decimal import Decimal

from app.domain import (
    Refuel,
    Trip,
    fuel_economy_intervals,
    round_consumption,
    round_distance,
    round_percent,
)

D = Decimal
DAY_1 = datetime(2026, 10, 1, 12, 0, 0)


def at(days: float) -> datetime:
    return DAY_1 + timedelta(days=days)


def refuel(
    rid: str,
    day: float,
    *,
    liters: str | None = "30.000",
    full: bool | None = True,
    status: str = "confirmed",
    source: str | None = "receipt",
    odometer: float = 1000.0,
) -> Refuel:
    return Refuel(
        id=rid,
        ts=at(day),
        odometer_km=odometer,
        level_before=20,
        level_after=90,
        liters=D(liters) if liters is not None else None,
        price_per_liter=D("6.190") if status == "confirmed" else None,
        total_cost=None,
        status=status,  # type: ignore[arg-type]
        liters_source=source if liters is not None else None,  # type: ignore[arg-type]
        filled_to_full=full if liters is not None else None,
    )


def trip(
    tid: str, day: float, km: float = 200.0, fuel: str = "9.000", ev: float | None = None
) -> Trip:
    return Trip(
        id=tid,
        start=at(day),
        end=at(day) + timedelta(hours=2),
        distance_km=km,
        fuel_l=D(fuel),
        ev_distance_km=ev,
    )


def three_trips() -> list[Trip]:
    return [trip("t1", 1), trip("t2", 2), trip("t3", 3)]


class TestFuelEconomyIntervals:
    def test_two_full_receipts_compare(self) -> None:
        refuels = [refuel("a", 0, odometer=1000.0), refuel("b", 10, odometer=1600.0)]
        [interval, tail] = fuel_economy_intervals(refuels, three_trips())
        assert interval.status == "ok"
        assert (interval.start_refuel_id, interval.end_refuel_id) == ("a", "b")
        assert interval.trips == 3
        assert interval.distance_km == D("600.0")
        assert interval.computer_liters == D("27.000")
        assert interval.actual_liters == D("30.000")
        assert interval.computer_l_per_100km == D("4.50")
        assert interval.actual_l_per_100km == D("5.00")
        assert interval.difference_pct == D("11.1")
        assert interval.odometer_km == D("600.0")
        assert tail.status == "open" and tail.start_refuel_id == "b" and tail.end_refuel_id is None

    def test_partial_receipt_inside_adds_liters(self) -> None:
        refuels = [
            refuel("a", 0),
            refuel("p", 5, liters="10.000", full=False),
            refuel("b", 10, liters="20.000"),
        ]
        interval = fuel_economy_intervals(refuels, three_trips())[0]
        assert interval.status == "ok"
        assert interval.actual_liters == D("30.000")

    def test_estimate_inside_is_missing_receipt(self) -> None:
        refuels = [
            refuel("a", 0),
            refuel("p", 5, liters="10.000", full=False, source="estimate"),
            refuel("b", 10),
        ]
        interval = fuel_economy_intervals(refuels, three_trips())[0]
        assert interval.status == "missing_receipt"
        assert interval.actual_liters is None
        assert interval.actual_l_per_100km is None
        assert interval.difference_pct is None
        assert interval.computer_l_per_100km == D("4.50")

    def test_price_only_inside_is_missing_receipt(self) -> None:
        refuels = [refuel("a", 0), refuel("p", 5, liters=None), refuel("b", 10)]
        assert fuel_economy_intervals(refuels, three_trips())[0].status == "missing_receipt"

    def test_derived_liters_count(self) -> None:
        refuels = [refuel("a", 0), refuel("b", 10, source="derived")]
        assert fuel_economy_intervals(refuels, three_trips())[0].status == "ok"

    def test_estimate_closing_refuel_is_missing_receipt(self) -> None:
        refuels = [refuel("a", 0), refuel("b", 10, source="estimate")]
        assert fuel_economy_intervals(refuels, three_trips())[0].status == "missing_receipt"

    def test_opening_refuel_liters_do_not_count(self) -> None:
        refuels = [refuel("a", 0, liters="99.000", source="estimate"), refuel("b", 10)]
        interval = fuel_economy_intervals(refuels, three_trips())[0]
        assert interval.status == "ok"
        assert interval.actual_liters == D("30.000")

    def test_pending_inside_blocks(self) -> None:
        refuels = [refuel("a", 0), refuel("p", 5, liters=None, status="pending"), refuel("b", 10)]
        interval = fuel_economy_intervals(refuels, three_trips())[0]
        assert interval.status == "pending_refuel"
        assert interval.actual_liters is None

    def test_dismissed_inside_blocks(self) -> None:
        refuels = [refuel("a", 0), refuel("p", 5, liters=None, status="dismissed"), refuel("b", 10)]
        assert fuel_economy_intervals(refuels, three_trips())[0].status == "dismissed_refuel"

    def test_pending_neither_opens_nor_closes(self) -> None:
        refuels = [
            refuel("p0", -1, liters=None, status="pending"),
            refuel("a", 0),
            refuel("p1", 5, liters=None, status="pending"),
        ]
        [only] = fuel_economy_intervals(refuels, three_trips())
        assert only.status == "open"
        assert only.start_refuel_id == "a"

    def test_refuels_before_the_first_full_open_nothing(self) -> None:
        refuels = [refuel("x", -5, full=False), refuel("a", 0)]
        intervals = fuel_economy_intervals(refuels, three_trips())
        assert [i.start_refuel_id for i in intervals] == ["a"]

    def test_no_full_refuel_gives_nothing(self) -> None:
        assert fuel_economy_intervals([refuel("x", 0, full=False)], three_trips()) == []

    def test_open_interval_counts_trips_so_far(self) -> None:
        [only] = fuel_economy_intervals([refuel("a", 0)], three_trips())
        assert only.status == "open"
        assert only.trips == 3
        assert only.distance_km == D("600.0")
        assert only.computer_l_per_100km == D("4.50")
        assert only.actual_l_per_100km is None

    def test_trip_on_the_boundaries(self) -> None:
        refuels = [refuel("a", 0), refuel("b", 10)]
        trips = [trip("at_start", 0, km=300.0), trip("at_end", 10, km=50.0)]
        [interval, tail] = fuel_economy_intervals(refuels, trips)
        assert interval.trips == 1 and interval.distance_km == D("300.0")
        assert tail.trips == 1 and tail.distance_km == D("50.0")

    def test_trip_crossing_the_end_belongs_where_it_started(self) -> None:
        refuels = [refuel("a", 0), refuel("b", 10)]
        crossing = Trip(
            id="long",
            start=at(9.99),
            end=at(10.5),
            distance_km=150.0,
            fuel_l=D("7.000"),
            ev_distance_km=None,
        )
        interval = fuel_economy_intervals(refuels, [*three_trips(), crossing])[0]
        assert interval.trips == 4
        assert interval.distance_km == D("750.0")

    def test_no_trips_status(self) -> None:
        interval = fuel_economy_intervals([refuel("a", 0), refuel("b", 10)], [])[0]
        assert interval.status == "no_trips"
        assert interval.computer_l_per_100km is None
        assert interval.actual_liters == D("30.000")

    def test_too_short_status(self) -> None:
        refuels = [refuel("a", 0), refuel("b", 10, liters="4.000")]
        interval = fuel_economy_intervals(refuels, [trip("t1", 1, km=80.0, fuel="3.600")])[0]
        assert interval.status == "too_short"
        assert interval.actual_liters == D("4.000")
        assert interval.actual_l_per_100km is None
        assert interval.computer_l_per_100km == D("4.50")

    def test_threshold_is_inclusive_at_the_minimum(self) -> None:
        refuels = [refuel("a", 0), refuel("b", 10, liters="5.000")]
        interval = fuel_economy_intervals(refuels, [trip("t1", 1, km=100.0, fuel="4.500")])[0]
        assert interval.status == "ok"

    def test_odometer_not_positive_is_none(self) -> None:
        refuels = [refuel("a", 0, odometer=1600.0), refuel("b", 10, odometer=1600.0)]
        assert fuel_economy_intervals(refuels, three_trips())[0].odometer_km is None

    def test_order_by_time_then_id(self) -> None:
        refuels = [refuel("b", 10), refuel("a", 0), refuel("c", 20)]
        intervals = fuel_economy_intervals(refuels, three_trips())
        assert [(i.start_refuel_id, i.end_refuel_id) for i in intervals] == [
            ("a", "b"),
            ("b", "c"),
            ("c", None),
        ]

    def test_ev_distance_is_summed(self) -> None:
        refuels = [refuel("a", 0), refuel("b", 10)]
        trips = [trip("t1", 1, ev=50.25), trip("t2", 2, ev=None), trip("t3", 3, ev=20.0)]
        assert fuel_economy_intervals(refuels, trips)[0].ev_distance_km == D("70.3")

    def test_rounding_half_up(self) -> None:
        refuels = [refuel("a", 0), refuel("b", 10, liters="4.600")]
        interval = fuel_economy_intervals(refuels, [trip("t1", 1, km=100.0, fuel="4.505")])[0]
        assert interval.computer_l_per_100km == D("4.51")
        assert interval.actual_l_per_100km == D("4.60")
        assert interval.difference_pct == D("2.1")

    def test_negative_difference_when_the_computer_overstates(self) -> None:
        refuels = [refuel("a", 0), refuel("b", 10, liters="24.000")]
        assert fuel_economy_intervals(refuels, three_trips())[0].difference_pct == D("-11.1")


class TestEconomyRounding:
    def test_round_consumption_half_up(self) -> None:
        assert round_consumption("4.505") == D("4.51")

    def test_round_percent_half_up(self) -> None:
        assert round_percent("2.05") == D("2.1")
        assert round_percent("-2.05") == D("-2.1")

    def test_round_distance_half_up(self) -> None:
        assert round_distance("70.25") == D("70.3")
