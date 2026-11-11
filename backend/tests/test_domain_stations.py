"""Tests for app.domain.stations: names, averages and pooled consumption (PRD F57, SPEC §57)."""

from datetime import datetime, timedelta
from decimal import Decimal

from app.domain import (
    EconomyInterval,
    Refuel,
    normalize_station,
    station_suggestions,
    station_summaries,
)
from app.domain.stations import STATION_MAX_LENGTH, STATION_MIN_INTERVALS

D = Decimal
DAY_1 = datetime(2026, 10, 1, 12, 0, 0)


def at(days: float) -> datetime:
    return DAY_1 + timedelta(days=days)


def refuel(
    rid: str,
    day: float,
    *,
    station: str | None = None,
    liters: str | None = "30.000",
    total: str | None = "186.00",
    status: str = "confirmed",
) -> Refuel:
    return Refuel(
        id=rid,
        ts=at(day),
        odometer_km=1000.0 + day,
        level_before=20,
        level_after=90,
        liters=D(liters) if liters is not None else None,
        price_per_liter=D("6.190") if status == "confirmed" else None,
        total_cost=D(total) if total is not None else None,
        status=status,  # type: ignore[arg-type]
        filled_to_full=True if liters is not None and status == "confirmed" else None,
        station=station,
    )


def interval(
    start_id: str,
    liters: str,
    distance: str,
    status: str = "ok",
) -> EconomyInterval:
    return EconomyInterval(
        start_refuel_id=start_id,
        end_refuel_id="next",
        status=status,  # type: ignore[arg-type]
        trips=1,
        distance_km=D(distance),
        ev_distance_km=D("0"),
        computer_liters=D("0"),
        actual_liters=D(liters),
        odometer_km=None,
        computer_l_per_100km=None,
        actual_l_per_100km=None,
        difference_pct=None,
    )


class TestNormalizeStation:
    def test_trims_and_collapses_whitespace(self) -> None:
        assert normalize_station("  Auchan   Szczęśliwa \n") == "Auchan Szczęśliwa"

    def test_blank_becomes_none(self) -> None:
        assert normalize_station(None) is None
        assert normalize_station("") is None
        assert normalize_station("   ") is None

    def test_long_name_is_cut_to_the_limit(self) -> None:
        assert STATION_MAX_LENGTH == 60
        assert normalize_station("x" * 61) == "x" * 60

    def test_a_cut_name_does_not_keep_trailing_space(self) -> None:
        assert normalize_station("a" * 59 + " b") == "a" * 59


class TestStationSummaries:
    def test_case_differences_are_one_station_with_the_newest_spelling(self) -> None:
        summaries = station_summaries(
            [refuel("a", 0, station="Auchan"), refuel("b", 1, station="auchan")],
            [],
        )
        assert [summary.name for summary in summaries] == ["auchan"]
        assert summaries[0].refuels == 2

    def test_pending_and_unassigned_refuels_are_not_rows(self) -> None:
        summaries = station_summaries(
            [
                refuel("a", 0, station="Auchan"),
                refuel("p", 1, station="WATIS", status="pending"),
                refuel("n", 2, station=None),
                refuel("b", 3, station="   "),
            ],
            [],
        )
        assert [summary.name for summary in summaries] == ["Auchan"]

    def test_average_price_is_weighted_by_liters(self) -> None:
        summaries = station_summaries(
            [
                refuel("a", 0, station="Auchan", liters="10.000", total="60.00"),
                refuel("b", 1, station="Auchan", liters="30.000", total="210.00"),
            ],
            [],
        )
        assert summaries[0].priced_refuels == 2
        assert summaries[0].avg_price_per_liter == D("6.75")

    def test_average_price_starts_from_one_refuel(self) -> None:
        summaries = station_summaries([refuel("a", 0, station="WATIS")], [])
        assert summaries[0].avg_price_per_liter == D("6.200")

    def test_a_refuel_without_both_fields_does_not_move_the_average(self) -> None:
        summaries = station_summaries(
            [
                refuel("a", 0, station="WATIS", liters="10.000", total="60.00"),
                refuel("b", 1, station="WATIS", liters=None, total=None),
            ],
            [],
        )
        assert summaries[0].refuels == 2
        assert summaries[0].priced_refuels == 1
        assert summaries[0].avg_price_per_liter == D("6.00")

    def test_consumption_needs_three_ok_intervals(self) -> None:
        refuels = [refuel("a", 0, station="WATIS"), refuel("b", 1, station="WATIS")]
        two = [interval("a", "5.000", "100.0"), interval("b", "5.000", "100.0")]
        assert station_summaries(refuels, two)[0].l_per_100km is None
        assert STATION_MIN_INTERVALS == 3

        three = two + [interval("a", "5.000", "100.0")]
        assert station_summaries(refuels, three)[0].l_per_100km == D("5.00")

    def test_consumption_is_pooled_not_an_average_of_averages(self) -> None:
        refuels = [refuel("a", 0, station="WATIS")]
        intervals = [
            interval("a", "5.000", "100.0"),
            interval("a", "15.000", "300.0"),
            interval("a", "3.000", "100.0"),
        ]
        # 23 l over 500 km = 4.60, while the mean of 5.00, 5.00 and 3.00 would be 4.33.
        assert station_summaries(refuels, intervals)[0].l_per_100km == D("4.60")

    def test_only_ok_intervals_count(self) -> None:
        refuels = [refuel("a", 0, station="WATIS")]
        intervals = [
            interval("a", "5.000", "100.0"),
            interval("a", "5.000", "100.0"),
            interval("a", "5.000", "100.0", status="too_short"),
        ]
        summary = station_summaries(refuels, intervals)[0]
        assert summary.intervals == 2
        assert summary.l_per_100km is None

    def test_an_interval_belongs_to_the_opening_station(self) -> None:
        refuels = [
            refuel("a", 0, station="Auchan"),
            refuel("b", 1, station="WATIS"),
            refuel("c", 2, station="WATIS"),
            refuel("d", 3, station="WATIS"),
        ]
        intervals = [
            interval("a", "5.000", "100.0"),
            interval("b", "5.000", "100.0"),
            interval("c", "5.000", "100.0"),
        ]
        summaries = {summary.name: summary for summary in station_summaries(refuels, intervals)}
        assert summaries["Auchan"].intervals == 1
        assert summaries["WATIS"].intervals == 2
        assert summaries["WATIS"].l_per_100km is None

    def test_rows_sort_by_refuel_count_then_name(self) -> None:
        summaries = station_summaries(
            [
                refuel("a", 0, station="WATIS"),
                refuel("b", 1, station="Auchan"),
                refuel("c", 2, station="Auchan"),
                refuel("d", 3, station="Moya"),
            ],
            [],
        )
        assert [summary.name for summary in summaries] == ["Auchan", "Moya", "WATIS"]


class TestStationSuggestions:
    def test_most_used_first_then_the_most_recent(self) -> None:
        names = station_suggestions(
            [
                refuel("a", 0, station="WATIS"),
                refuel("b", 1, station="Auchan"),
                refuel("c", 2, station="WATIS"),
                refuel("d", 3, station="Moya"),
            ],
        )
        assert names == ["WATIS", "Moya", "Auchan"]

    def test_pending_and_blank_are_skipped(self) -> None:
        names = station_suggestions(
            [
                refuel("a", 0, station="Auchan"),
                refuel("p", 1, station="WATIS", status="pending"),
                refuel("b", 2, station="   "),
                refuel("c", 3, station=None),
            ],
        )
        assert names == ["Auchan"]

    def test_limit_keeps_the_head(self) -> None:
        refuels = [refuel(str(index), index, station=f"S{index}") for index in range(8)]
        assert station_suggestions(refuels, limit=3) == ["S7", "S6", "S5"]
