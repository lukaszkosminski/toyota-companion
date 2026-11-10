"""Tests for app.domain.besttime (PRD F53, SPEC §53); the names mirror core BestTimeOpsTest.kt."""

from datetime import UTC, datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

from app.domain import Trip
from app.domain.besttime import (
    BestTimeCell,
    DayKind,
    DepartureBand,
    best_time_grid,
    day_kind_of,
    departure_band_of,
    median_minutes,
)

D = Decimal
WARSAW = ZoneInfo("Europe/Warsaw")


def trip(ident: str, start: str, minutes: int, km: str, fuel: str) -> Trip:
    begin = datetime.fromisoformat(start.replace("Z", "+00:00")).astimezone(UTC)
    return Trip(ident, begin, begin + timedelta(minutes=minutes), float(km), D(fuel), None)


def cell(grid, day: DayKind, band: DepartureBand) -> BestTimeCell | None:
    return next((c for c in grid.cells if c.day is day and c.band is band), None)


def test_the_departure_band_is_the_local_hour() -> None:
    assert departure_band_of(4) is DepartureBand.NIGHT
    assert departure_band_of(5) is DepartureBand.H5_7
    assert departure_band_of(6) is DepartureBand.H5_7
    assert departure_band_of(7) is DepartureBand.H7_9
    assert departure_band_of(8) is DepartureBand.H7_9
    assert departure_band_of(9) is DepartureBand.H9_12
    assert departure_band_of(11) is DepartureBand.H9_12
    assert departure_band_of(12) is DepartureBand.H12_15
    assert departure_band_of(14) is DepartureBand.H12_15
    assert departure_band_of(15) is DepartureBand.H15_18
    assert departure_band_of(17) is DepartureBand.H15_18
    assert departure_band_of(18) is DepartureBand.H18_21
    assert departure_band_of(20) is DepartureBand.H18_21
    assert departure_band_of(21) is DepartureBand.NIGHT
    assert departure_band_of(23) is DepartureBand.NIGHT
    assert departure_band_of(0) is DepartureBand.NIGHT


def test_the_day_kind_is_working_from_monday_to_friday_and_weekend_otherwise() -> None:
    assert day_kind_of(0) is DayKind.WORKING
    assert day_kind_of(4) is DayKind.WORKING
    assert day_kind_of(5) is DayKind.WEEKEND
    assert day_kind_of(6) is DayKind.WEEKEND


def test_the_median_of_an_odd_count_is_the_middle_drive() -> None:
    assert median_minutes([600, 1200, 3600]) == 20


def test_the_median_of_an_even_count_is_the_mean_of_the_two_middle_drives() -> None:
    # 10, 12, 14, 30 minutes: the middle two are 12 and 14, the median 13.
    assert median_minutes([600, 720, 840, 1800]) == 13


def test_the_median_rounds_half_up_to_a_whole_minute() -> None:
    # 15 and 16 minutes: the median is 15.5 minutes, which is 16 (half up).
    assert median_minutes([900, 960]) == 16


def test_a_cell_needs_three_trips() -> None:
    trips = [
        trip("a", "2026-11-09T06:30:00Z", 20, "10", "0.6"),
        trip("b", "2026-11-10T06:30:00Z", 20, "10", "0.6"),
    ]
    assert best_time_grid(trips, WARSAW).cells == ()
    three = [*trips, trip("c", "2026-11-11T06:30:00Z", 20, "10", "0.6")]
    assert len(best_time_grid(three, WARSAW).cells) == 1


def test_litres_per_100km_is_the_sum_over_the_sum() -> None:
    # 06:30 UTC is 07:30 in Warsaw in November: the band 7–9.
    # 0.8 l on 10 km is 8.00; the mean of the rates would be 9.17.
    trips = [
        trip("a", "2026-11-09T06:30:00Z", 5, "1", "0.1"),
        trip("b", "2026-11-10T06:30:00Z", 5, "1", "0.1"),
        trip("c", "2026-11-11T06:30:00Z", 5, "8", "0.6"),
    ]
    assert cell(
        best_time_grid(trips, WARSAW), DayKind.WORKING, DepartureBand.H7_9
    ).l_per_100km == D("8.00")


def test_a_trip_on_the_battery_stays_in_the_cell() -> None:
    trips = [trip(f"z{i}", f"2026-11-{9 + i:02d}T06:30:00Z", 20, "10", "0") for i in range(3)]
    c = cell(best_time_grid(trips, WARSAW), DayKind.WORKING, DepartureBand.H7_9)
    assert c.trips == 3
    assert c.l_per_100km == D("0.00")


def test_the_hour_is_the_local_hour_so_a_summer_time_start_is_not_an_hour_early() -> None:
    # 2026-07-01 05:30 UTC is 07:30 in Warsaw (summer time): the band is 7–9, not 5–7.
    trips = [trip(f"s{i}", f"2026-07-0{i + 1}T05:30:00Z", 20, "10", "0.6") for i in range(3)]
    grid = best_time_grid(trips, WARSAW)
    assert cell(grid, DayKind.WORKING, DepartureBand.H5_7) is None
    assert cell(grid, DayKind.WORKING, DepartureBand.H7_9).trips == 3


def test_a_trip_across_midnight_belongs_to_the_band_of_its_start() -> None:
    # Friday 2026-07-03 19:50 UTC is 21:50 in Warsaw: the night band of a working day,
    # though the drive ends after midnight.
    trips = [trip(f"n{i}", "2026-07-03T19:50:00Z", 40, "12", "0.8") for i in range(3)]
    assert cell(best_time_grid(trips, WARSAW), DayKind.WORKING, DepartureBand.NIGHT).trips == 3


def test_a_weekend_start_is_its_own_day_kind() -> None:
    # Saturday 2026-07-04 07:00 UTC is 09:00 in Warsaw.
    trips = [trip(f"w{i}", "2026-07-04T07:00:00Z", 20, "10", "0.6") for i in range(3)]
    assert cell(best_time_grid(trips, WARSAW), DayKind.WEEKEND, DepartureBand.H9_12).trips == 3


def test_broken_trips_are_not_counted() -> None:
    trips = [
        trip("a", "2026-11-09T06:30:00Z", 0, "10", "0.6"),
        trip("b", "2026-11-10T06:30:00Z", 20, "0", "0.6"),
        trip("c", "2026-11-11T06:30:00Z", 20, "10", "-0.1"),
        trip("d", "2026-11-12T06:30:00Z", 20, "10", "0.6"),
    ]
    assert best_time_grid(trips, WARSAW).trips == 1


def test_the_fastest_and_the_slowest_need_two_cells_and_are_the_lowest_and_the_highest_median() -> (
    None
):
    trips = [trip(f"m{i}", f"2026-11-{9 + i:02d}T06:30:00Z", 24, "10", "0.6") for i in range(3)] + [
        trip(f"n{i}", f"2026-11-{9 + i:02d}T08:30:00Z", 17, "10", "0.6") for i in range(3)
    ]
    grid = best_time_grid(trips, WARSAW)
    assert grid.fastest.band is DepartureBand.H9_12
    assert grid.fastest.median_minutes == 17
    assert grid.slowest.band is DepartureBand.H7_9
    assert grid.slowest.median_minutes == 24
    single = best_time_grid(trips[:3], WARSAW)
    assert single.fastest is None
    assert single.slowest is None
