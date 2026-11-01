"""Tests for app.domain.weather: weather buckets of fuel cost (PRD F15, SPEC §23).

The weather always arrives keyed by trip id - the domain never sees coordinates, dates or a
zone, so these fixtures stop where the `app` module's job starts (plan C14 D7).
"""

from datetime import UTC, datetime, timedelta
from decimal import Decimal

from app.domain import (
    REFERENCE_PRECIPITATION_BUCKET,
    REFERENCE_TEMPERATURE_BUCKET,
    DayWeather,
    Trip,
    TripCost,
    precipitation_bucket,
    temperature_bucket,
    weather_cost_by_precipitation,
    weather_cost_by_temperature,
)

D = Decimal


def trip(
    tid: str,
    km: float = 10.0,
    fuel: str = "0.500",
    ev: float | None = None,
) -> Trip:
    start = datetime(2026, 1, 5, 8, 0, tzinfo=UTC)
    return Trip(
        id=tid,
        start=start,
        end=start + timedelta(minutes=30),
        distance_km=km,
        fuel_l=D(fuel),
        ev_distance_km=ev,
    )


def cost(tid: str, pln: str | None = "20.00", source: str = "refuel") -> TripCost:
    return TripCost(
        trip_id=tid,
        cost_pln=None if pln is None else D(pln),
        price_per_liter=None if pln is None else D("6.190"),
        refuel_id=None,
        source=source,
    )


def day(
    temp: str | None = "5.0",
    precip: str | None = "0.0",
    wind: str | None = None,
) -> DayWeather:
    return DayWeather(
        temp_mean=None if temp is None else D(temp),
        precip_mm=None if precip is None else D(precip),
        wind_max_kmh=None if wind is None else D(wind),
    )


def frosty(
    count: int, km: float = 10.0, fuel: str = "0.700"
) -> tuple[list[Trip], dict[str, DayWeather]]:
    trips = [trip(f"t{index}", km=km, fuel=fuel) for index in range(count)]
    return trips, {item.id: day(temp="-4.0") for item in trips}


class TestTemperatureBuckets:
    def test_bucket_of_negative_mean_is_frost(self) -> None:
        assert temperature_bucket(D("-0.1")) == "frost"
        assert temperature_bucket(D("-14.6")) == "frost"

    def test_bucket_of_zero_mean_is_cold(self) -> None:
        assert temperature_bucket(D("0")) == "cold"
        assert temperature_bucket(D("0.0")) == "cold"

    def test_bucket_boundaries_are_half_open(self) -> None:
        assert temperature_bucket(D("9.999")) == "cold"
        assert temperature_bucket(D("10")) == "mild"
        assert temperature_bucket(D("19.99")) == "mild"
        assert temperature_bucket(D("20")) == "warm"
        assert temperature_bucket(None) is None

    def test_reference_bucket_is_mild_dry(self) -> None:
        assert REFERENCE_TEMPERATURE_BUCKET == "mild"
        assert REFERENCE_PRECIPITATION_BUCKET == "dry"
        mild, mild_weather = frosty(5, fuel="0.500")
        weather = {key: day(temp="14.0") for key in mild_weather}
        impact = weather_cost_by_temperature(mild, [], weather)
        assert impact.reference_bucket == "mild"
        assert impact.reference_l_per_100km == D("5.00")
        assert [row.delta_l_per_100km for row in impact.buckets] == [D("0.00")]

    def test_comparison_none_without_reference(self) -> None:
        trips, weather = frosty(6)
        impact = weather_cost_by_temperature(trips, [], weather, min_trips=1)
        assert impact.reference_bucket is None
        assert impact.reference_l_per_100km is None
        assert [row.delta_l_per_100km for row in impact.buckets] == [None]


class TestBucketMeasures:
    def test_consumption_is_total_fuel_over_total_km(self) -> None:
        trips = [trip("a", km=10.0, fuel="1.000"), trip("b", km=40.0, fuel="2.000")]
        weather = {item.id: day(temp="-3.0") for item in trips}
        impact = weather_cost_by_temperature(trips, [], weather, min_trips=1)
        row = impact.buckets[0]
        # 3.000 l over 50 km, not the mean of the two trips (which would be 7.50).
        assert row.l_per_100km == D("6.00")
        assert row.distance_km == D("50.0")
        assert row.fuel_l == D("3.000")

    def test_cost_none_when_no_trip_in_bucket_has_a_price(self) -> None:
        trips, weather = frosty(3)
        impact = weather_cost_by_temperature(trips, [], weather, min_trips=1)
        row = impact.buckets[0]
        assert row.cost_pln is None
        assert row.cost_per_100km is None
        assert row.trips_without_cost == 3

    def test_cost_counts_only_priced_distance(self) -> None:
        trips = [trip("a", km=10.0), trip("b", km=40.0)]
        weather = {item.id: day(temp="-3.0") for item in trips}
        impact = weather_cost_by_temperature(trips, [cost("a", "20.00")], weather, min_trips=1)
        row = impact.buckets[0]
        # 20.00 zl of the priced 10 km, not spread over the 50 km of the bucket.
        assert row.cost_per_100km == D("200.00")
        assert row.cost_pln == D("20.00")
        assert row.trips_without_cost == 1

    def test_bucket_below_minimum_reports_too_few(self) -> None:
        trips, weather = frosty(4)
        impact = weather_cost_by_temperature(trips, [], weather)
        assert impact.buckets[0].too_few_trips is True
        assert impact.reference_bucket is None

        enough, enough_weather = frosty(5)
        full = weather_cost_by_temperature(enough, [], enough_weather)
        assert full.buckets[0].too_few_trips is False

    def test_rounding_is_half_up(self) -> None:
        # 0.6036 l over 8.0 km is exactly 7.545 l/100 km; HALF_UP gives 7.55, not 7.54.
        trips = [trip("a", km=8.0, fuel="0.6036")]
        weather = {item.id: day(temp="-3.0") for item in trips}
        impact = weather_cost_by_temperature(trips, [], weather, min_trips=1)
        assert impact.buckets[0].l_per_100km == D("7.55")

    def test_wind_is_reported_but_not_judged(self) -> None:
        trips = [trip("a"), trip("b")]
        calm = {"a": day(temp="-3.0", wind="4.0"), "b": day(temp="-3.0", wind="11.0")}
        windy = {"a": day(temp="-3.0", wind="61.0"), "b": day(temp="-3.0", wind="11.0")}
        calm_row = weather_cost_by_temperature(trips, [], calm, min_trips=1).buckets[0]
        windy_row = weather_cost_by_temperature(trips, [], windy, min_trips=1).buckets[0]
        assert calm_row.wind_max_kmh == D("11.0")
        assert windy_row.wind_max_kmh == D("61.0")
        # A gust does not move the bucket, its consumption or its difference.
        assert windy_row.bucket == calm_row.bucket == "frost"
        assert windy_row.l_per_100km == calm_row.l_per_100km
        assert windy_row.too_few_trips == calm_row.too_few_trips


class TestPrecipitationBuckets:
    def test_wet_bucket_needs_half_a_millimetre(self) -> None:
        assert precipitation_bucket(D("0.5")) == "wet"
        assert precipitation_bucket(D("18.4")) == "wet"
        trips = [trip("a")]
        impact = weather_cost_by_precipitation(
            trips, [], {"a": day(temp="6.0", precip="0.5")}, min_trips=1
        )
        assert impact.buckets[0].bucket == "wet"

    def test_dry_bucket_below_half_a_millimetre(self) -> None:
        assert precipitation_bucket(D("0.49")) == "dry"
        assert precipitation_bucket(D("0")) == "dry"
        assert precipitation_bucket(None) is None
        trips = [trip("a")]
        impact = weather_cost_by_precipitation(
            trips, [], {"a": day(temp="6.0", precip="0.4")}, min_trips=1
        )
        assert impact.buckets[0].bucket == "dry"
        assert impact.reference_bucket == "dry"


class TestMissingWeather:
    def test_null_weather_day_is_not_a_zero(self) -> None:
        trips = [trip("a"), trip("b")]
        impact = weather_cost_by_temperature(
            trips,
            [],
            {"a": day(temp="-4.0"), "b": DayWeather()},
            min_trips=1,
        )
        assert [row.trips for row in impact.buckets] == [1]
        assert impact.trips_without_weather == 1

    def test_trips_without_weather_are_skipped_and_counted(self) -> None:
        trips, weather = frosty(3)
        trips = [*trips, trip("no-weather"), trip("empty-day")]
        weather = {**weather, "empty-day": DayWeather()}
        impact = weather_cost_by_temperature(trips, [], weather, min_trips=1)
        assert impact.total_trips == 5
        assert impact.trips_without_weather == 2
        assert sum(row.trips for row in impact.buckets) == 3

    def test_empty_inputs_give_no_buckets(self) -> None:
        impact = weather_cost_by_temperature([], [], {})
        assert impact.buckets == []
        assert impact.total_trips == 0
        assert impact.trips_without_weather == 0
        assert impact.reference_bucket is None
        assert weather_cost_by_precipitation([], [], {}).buckets == []
