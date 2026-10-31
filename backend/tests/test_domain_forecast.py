"""Tests for app.domain.forecast: refuel_forecast (PRD F14, SPEC §20)."""

from datetime import UTC, datetime, timedelta
from decimal import Decimal

from app.domain import FuelReading, Trip, refuel_forecast

D = Decimal
NOW = datetime(2026, 10, 31, 12, 0, tzinfo=UTC)


def reading(level: int = 50, hours_ago: float = 0) -> FuelReading:
    return FuelReading(ts=NOW - timedelta(hours=hours_ago), level_pct=level, odometer_km=1000.0)


def trip(tid: str, days_ago: float, km: float = 20.0, fuel: str = "1.000") -> Trip:
    start = NOW - timedelta(days=days_ago)
    return Trip(
        id=tid,
        start=start,
        end=start + timedelta(minutes=30),
        distance_km=km,
        fuel_l=D(fuel),
        ev_distance_km=None,
    )


def month_of_trips() -> list[Trip]:
    # 28 days, one 20 km trip a day with 1 l: 5.00 l/100 km, 20 km a day.
    return [trip(f"t{day}", day + 0.5) for day in range(28)]


class TestRefuelForecast:
    def test_forecast_from_level_and_habits(self) -> None:
        forecast = refuel_forecast(reading(50), month_of_trips(), NOW)
        assert forecast is not None
        assert forecast.liters_left == D("21.500")
        assert forecast.level_pct == D("50.0")
        assert forecast.l_per_100km == D("5.00")
        assert forecast.daily_km == D("20.0")
        # (21.5 - 4.3) l / 5 l per 100 km = 344 km, 344 / 20 = 17.2 days.
        assert forecast.km_to_reserve == D("344.0")
        assert forecast.days_to_reserve == D("17.2")

    def test_fuel_used_after_the_reading_is_subtracted(self) -> None:
        trips = [*month_of_trips(), trip("after", 0.1, km=40.0, fuel="2.000")]
        forecast = refuel_forecast(reading(50, hours_ago=11), trips, NOW)
        assert forecast is not None
        # The trip of 12 h ago started before the reading (11 h ago) and does not count.
        assert forecast.liters_left == D("19.500")

    def test_level_below_reserve_gives_zero_days(self) -> None:
        forecast = refuel_forecast(reading(8), month_of_trips(), NOW)
        assert forecast is not None
        assert forecast.km_to_reserve == D("0.0")
        assert forecast.days_to_reserve == D("0.0")

    def test_fuel_never_goes_below_zero(self) -> None:
        trips = [*month_of_trips(), trip("big", 0.1, fuel="50.000")]
        forecast = refuel_forecast(reading(10, hours_ago=12), trips, NOW)
        assert forecast is not None
        assert forecast.liters_left == D("0.000")

    def test_young_archive_uses_its_own_days(self) -> None:
        trips = [trip("a", 1.5), trip("b", 0.5)]
        forecast = refuel_forecast(reading(50), trips, NOW)
        assert forecast is not None
        assert forecast.daily_km == D("20.0")

    def test_trips_older_than_the_window_are_ignored(self) -> None:
        trips = [*month_of_trips(), trip("old", 40, km=1000.0, fuel="100.000")]
        forecast = refuel_forecast(reading(50), trips, NOW)
        assert forecast is not None
        assert forecast.l_per_100km == D("5.00")

    def test_no_reading_no_forecast(self) -> None:
        assert refuel_forecast(None, month_of_trips(), NOW) is None

    def test_no_trips_in_window_no_forecast(self) -> None:
        assert refuel_forecast(reading(50), [trip("old", 40)], NOW) is None

    def test_trips_on_the_window_edges_count(self) -> None:
        trips = [trip("edge", 28, km=40.0, fuel="2.000"), trip("now", 0, km=40.0, fuel="2.000")]
        forecast = refuel_forecast(reading(50), trips, NOW)
        assert forecast is not None
        assert forecast.daily_km == D("2.9")

    def test_broken_distance_is_ignored(self) -> None:
        trips = [
            *month_of_trips(),
            trip("nan", 1, km=float("nan")),
            trip("inf", 1, km=float("inf")),
        ]
        forecast = refuel_forecast(reading(50), trips, NOW)
        assert forecast is not None
        assert forecast.l_per_100km == D("5.00")

    def test_zero_fuel_no_forecast(self) -> None:
        assert refuel_forecast(reading(50), [trip("ev", 1, fuel="0.000")], NOW) is None
