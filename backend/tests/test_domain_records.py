"""Tests for app.domain.records: personal records (F62), SPEC §62."""

from datetime import UTC, datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

from app.domain import Trip, compute_records

D = Decimal
WARSAW = ZoneInfo("Europe/Warsaw")


def utc(year: int, month: int, day: int, hour: int = 10, minute: int = 0) -> datetime:
    return datetime(year, month, day, hour, minute, tzinfo=UTC)


def trip(
    tid: str,
    km: float = 10.0,
    ev: float | None = 4.0,
    fuel: str = "0.500",
    start: datetime | None = None,
    minutes: int = 30,
) -> Trip:
    begin = start or utc(2026, 10, 5)
    return Trip(
        id=tid,
        start=begin,
        end=begin + timedelta(minutes=minutes),
        distance_km=km,
        fuel_l=D(fuel),
        ev_distance_km=ev,
    )


def by_id(records):
    return {r.record_id: r for r in records.items}


class TestEmpty:
    def test_no_trips_returns_empty(self) -> None:
        records = compute_records([], WARSAW)
        assert records.empty is True
        assert records.items == []


class TestLongestTrip:
    def test_longest_trip_picks_max_distance(self) -> None:
        trips = [
            trip("a", km=12.34, start=utc(2026, 10, 5)),
            trip("b", km=312.4, start=utc(2026, 6, 12, 8)),
            trip("c", km=45.0, start=utc(2026, 9, 1)),
        ]
        records = by_id(compute_records(trips, WARSAW))
        assert records["longest_trip"].value == D("312.4")
        assert records["longest_trip"].unit == "km"
        assert records["longest_trip"].when.isoformat() == "2026-06-12"

    def test_longest_trip_rounds_to_tenth(self) -> None:
        trips = [trip("a", km=99.96)]
        records = by_id(compute_records(trips, WARSAW))
        assert records["longest_trip"].value == D("100.0")


class TestLongestEvTrip:
    def test_longest_ev_trip_uses_ev_distance(self) -> None:
        trips = [
            trip("a", km=50.0, ev=12.34, start=utc(2026, 3, 1)),
            trip("b", km=80.0, ev=45.6, start=utc(2026, 7, 20)),
        ]
        records = by_id(compute_records(trips, WARSAW))
        assert records["longest_ev_trip"].value == D("45.6")
        assert records["longest_ev_trip"].when.isoformat() == "2026-07-20"

    def test_longest_ev_trip_omitted_without_ev_data(self) -> None:
        trips = [trip("a", km=50.0, ev=None), trip("b", km=80.0, ev=None)]
        records = by_id(compute_records(trips, WARSAW))
        assert "longest_ev_trip" not in records


class TestBestEvShare:
    def test_best_ev_share_requires_twenty_km(self) -> None:
        trips = [
            trip("short", km=19.9, ev=19.9),  # 100 % but too short
            trip("ok", km=25.0, ev=20.0),  # 80.0 %
        ]
        records = by_id(compute_records(trips, WARSAW))
        assert records["best_ev_share"].value == D("80.0")
        assert records["best_ev_share"].unit == "%"
        assert records["best_ev_share"].context == "25.0 km"

    def test_best_ev_share_clips_at_hundred(self) -> None:
        trips = [trip("a", km=30.0, ev=30.5)]
        records = by_id(compute_records(trips, WARSAW))
        assert records["best_ev_share"].value == D("100.0")

    def test_best_ev_share_omitted_when_all_short(self) -> None:
        trips = [trip("a", km=5.0, ev=4.0), trip("b", km=10.0, ev=9.0)]
        records = by_id(compute_records(trips, WARSAW))
        assert "best_ev_share" not in records


class TestBestEconomyTrip:
    def test_best_economy_trip_requires_fifty_km(self) -> None:
        # 10 km at 0.2 l -> 2.0 l/100 km but too short; 60 km at 3.0 l -> 5.0 l/100 km wins.
        trips = [
            trip("short", km=10.0, fuel="0.200", start=utc(2026, 1, 1)),
            trip("long", km=60.0, fuel="3.000", start=utc(2026, 5, 5)),
        ]
        records = by_id(compute_records(trips, WARSAW))
        assert records["best_economy_trip"].value == D("5.00")
        assert records["best_economy_trip"].context == "60.0 km"

    def test_best_economy_trip_picks_minimum(self) -> None:
        trips = [
            trip("a", km=100.0, fuel="6.00", start=utc(2026, 2, 1)),  # 6.00
            trip("b", km=200.0, fuel="8.00", start=utc(2026, 3, 1)),  # 4.00
        ]
        records = by_id(compute_records(trips, WARSAW))
        assert records["best_economy_trip"].value == D("4.00")
        assert records["best_economy_trip"].when.isoformat() == "2026-03-01"

    def test_best_economy_trip_omitted_when_all_short(self) -> None:
        trips = [trip("a", km=49.9, fuel="2.0")]
        records = by_id(compute_records(trips, WARSAW))
        assert "best_economy_trip" not in records


class TestMostKmMonth:
    def test_most_km_month_sums_by_local_month(self) -> None:
        trips = [
            trip("a", km=100.0, start=utc(2026, 1, 15, 10)),  # January
            trip("b", km=200.0, start=utc(2026, 1, 20, 9)),  # January -> 300
            trip("c", km=250.0, start=utc(2026, 2, 10, 9)),  # February -> 250
        ]
        records = by_id(compute_records(trips, WARSAW))
        assert records["most_km_month"].value == D("300.0")
        assert records["most_km_month"].when.isoformat() == "2026-01-01"
        assert records["most_km_month"].context == "styczeń 2026"

    def test_most_km_month_uses_local_date_not_utc(self) -> None:
        # 2025-12-31 23:30 Warsaw is 2025-12-31 22:30 UTC -> still December 2025.
        trips = [
            trip("a", km=100.0, start=datetime(2025, 12, 31, 22, 30, tzinfo=UTC)),
            trip("b", km=50.0, start=utc(2026, 1, 15)),
        ]
        records = by_id(compute_records(trips, WARSAW))
        assert records["most_km_month"].context == "grudzień 2025"
        assert records["most_km_month"].value == D("100.0")


class TestBestEconomyMonth:
    def test_best_economy_month_requires_500_km(self) -> None:
        trips = [
            trip("a", km=300.0, fuel="18.0", start=utc(2026, 1, 10)),  # 6.00 but < 500 km
            trip("b", km=600.0, fuel="30.0", start=utc(2026, 2, 10)),  # 5.00
        ]
        records = by_id(compute_records(trips, WARSAW))
        assert records["best_economy_month"].value == D("5.00")
        assert records["best_economy_month"].context == "luty 2026"

    def test_best_economy_month_pooled_not_average(self) -> None:
        # Month A: 300 km + 300 km at 5.0 and 7.0 -> pooled 6.0 over 600 km.
        # Month B: 550 km at 5.5 -> 5.5 wins.
        trips = [
            trip("a1", km=300.0, fuel="15.0", start=utc(2026, 1, 5)),
            trip("a2", km=300.0, fuel="21.0", start=utc(2026, 1, 20)),
            trip("b1", km=550.0, fuel="30.25", start=utc(2026, 2, 5)),
        ]
        records = by_id(compute_records(trips, WARSAW))
        assert records["best_economy_month"].value == D("5.50")
        assert records["best_economy_month"].context == "luty 2026"

    def test_best_economy_month_omitted_when_all_months_short(self) -> None:
        trips = [trip("a", km=100.0, fuel="5.0", start=utc(2026, 1, 5))]
        records = by_id(compute_records(trips, WARSAW))
        assert "best_economy_month" not in records


class TestAllRecordsTogether:
    def test_full_archive_yields_all_six_records(self) -> None:
        trips = [
            trip("t1", km=12.0, ev=6.0, fuel="0.6", start=utc(2026, 1, 10)),
            trip("t2", km=312.4, ev=45.6, fuel="14.0", start=utc(2026, 6, 12, 8)),
            trip("t3", km=600.0, ev=100.0, fuel="27.0", start=utc(2026, 6, 20, 9)),
        ]
        records = compute_records(trips, WARSAW)
        assert records.empty is False
        ids = {r.record_id for r in records.items}
        assert ids == {
            "longest_trip",
            "longest_ev_trip",
            "best_ev_share",
            "best_economy_trip",
            "most_km_month",
            "best_economy_month",
        }

    def test_archive_of_only_short_trips_is_not_empty(self) -> None:
        # R1 and R5 still qualify even with only short trips.
        trips = [trip("a", km=3.0, ev=1.0, fuel="0.2", start=utc(2026, 1, 5))]
        records = compute_records(trips, WARSAW)
        assert records.empty is False
        ids = {r.record_id for r in records.items}
        assert "longest_trip" in ids
        assert "most_km_month" in ids
