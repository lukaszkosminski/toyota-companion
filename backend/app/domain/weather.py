"""Weather buckets and their fuel cost (PRD F15, plan C14, SPEC §23).

Pure domain: it never fetches weather, never sees GPS and never rounds a coordinate. The
caller (the `app` module) resolves every trip to the reanalysis day of its start place and
passes ``weather`` keyed by trip id; a trip with no entry there is counted, never guessed.
Reanalysis is a model, not a weather station, so nothing here may be worded as a measurement.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Literal

from .models import (
    Trip,
    TripCost,
    as_decimal,
    round_consumption,
    round_distance,
    round_money,
)

#: A bucket under this many trips is a hint, not a comparison (owner decision on plan C14).
WEATHER_MIN_TRIPS_PER_BUCKET = 5

#: From this many millimetres a day counts as wet (drizzle is not a rainy day).
WET_DAY_MM = Decimal("0.5")

TemperatureBucket = Literal["deep_frost", "frost", "cold", "mild", "warm"]
PrecipitationBucket = Literal["dry", "wet"]

#: Reference of both splits: mild, dry driving is what a surcharge is counted against.
REFERENCE_TEMPERATURE_BUCKET: TemperatureBucket = "mild"
REFERENCE_PRECIPITATION_BUCKET: PrecipitationBucket = "dry"

_TEMPERATURE_ORDER: tuple[TemperatureBucket, ...] = ("deep_frost", "frost", "cold", "mild", "warm")
_PRECIPITATION_ORDER: tuple[PrecipitationBucket, ...] = ("dry", "wet")

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)


@dataclass(frozen=True)
class DayWeather:
    """Reanalysis of one day at one place; every value is ``None`` when the model had none.

    A day without values is **not** a zero: a missing temperature must not turn a frosty day
    into a mild one, so such a trip is left out of the buckets and only counted.
    """

    temp_mean: Decimal | None = None
    temp_min: Decimal | None = None
    temp_max: Decimal | None = None
    precip_mm: Decimal | None = None
    precip_hours: Decimal | None = None
    wind_max_kmh: Decimal | None = None


@dataclass(frozen=True)
class WeatherBucketStats:
    """One bucket: sums of its trips, l/100 km, zł/100 km, the gap to the reference.

    ``cost_per_100km`` is ``None`` when no trip in the bucket has a price - and is then always
    counted over the kilometres of the priced trips only, never over all of them.
    ``delta_l_per_100km`` is ``None`` without a usable reference bucket. ``wind_max_kmh`` is
    the strongest gust day of the bucket, reported and never used for a verdict.
    """

    bucket: str
    trips: int
    distance_km: Decimal
    fuel_l: Decimal
    l_per_100km: Decimal | None
    cost_pln: Decimal | None
    trips_without_cost: int
    cost_per_100km: Decimal | None
    ev_km: Decimal
    delta_l_per_100km: Decimal | None
    too_few_trips: bool
    wind_max_kmh: Decimal | None


@dataclass(frozen=True)
class WeatherImpact:
    """Buckets in their natural order (deep frost first, dry first), plus the trips with no weather.

    ``reference_bucket`` and ``reference_l_per_100km`` are ``None`` when the reference bucket
    is missing or too small - then no bucket carries a difference either.
    """

    buckets: list[WeatherBucketStats]
    total_trips: int
    trips_without_weather: int
    reference_bucket: str | None
    reference_l_per_100km: Decimal | None


def temperature_bucket(temp_mean: Decimal | None) -> TemperatureBucket | None:
    """Bucket of a day by its mean temperature, half-open intervals; ``None`` without a value.

    Deep frost is below -10 °C, frost is -10 °C up to 0 °C and a mean of exactly 0 °C is still
    "cold"; the upper edge of a bucket belongs to the next one, so -10 °C is frost, 10 °C is mild
    and 20 °C is warm.
    """
    if temp_mean is None:
        return None
    temperature = as_decimal(temp_mean)
    if temperature < -10:
        return "deep_frost"
    if temperature < 0:
        return "frost"
    if temperature < 10:
        return "cold"
    if temperature < 20:
        return "mild"
    return "warm"


def precipitation_bucket(precip_mm: Decimal | None) -> PrecipitationBucket | None:
    """A day is wet from ``WET_DAY_MM`` of precipitation inclusive, ``None`` without a value."""
    if precip_mm is None:
        return None
    return "wet" if as_decimal(precip_mm) >= WET_DAY_MM else "dry"


def weather_cost_by_temperature(
    trips: Iterable[Trip],
    costs: Iterable[TripCost],
    weather: Mapping[str, DayWeather],
    *,
    reference: TemperatureBucket | None = REFERENCE_TEMPERATURE_BUCKET,
    min_trips: int = WEATHER_MIN_TRIPS_PER_BUCKET,
) -> WeatherImpact:
    """Trips grouped by the temperature of their day, deep frost first.

    ``weather`` is keyed by trip id. A trip with no entry there, or with a day whose
    temperature the model did not give, is left out of the buckets and counted in
    ``trips_without_weather``.
    """
    return _impact(
        list(trips),
        list(costs),
        weather,
        lambda day: temperature_bucket(day.temp_mean),
        _TEMPERATURE_ORDER,
        reference,
        min_trips,
    )


def weather_cost_by_precipitation(
    trips: Iterable[Trip],
    costs: Iterable[TripCost],
    weather: Mapping[str, DayWeather],
    *,
    reference: PrecipitationBucket | None = REFERENCE_PRECIPITATION_BUCKET,
    min_trips: int = WEATHER_MIN_TRIPS_PER_BUCKET,
) -> WeatherImpact:
    """The same trips grouped by a dry or a wet day, dry first."""
    return _impact(
        list(trips),
        list(costs),
        weather,
        lambda day: precipitation_bucket(day.precip_mm),
        _PRECIPITATION_ORDER,
        reference,
        min_trips,
    )


def _impact(
    trips: Sequence[Trip],
    costs: Sequence[TripCost],
    weather: Mapping[str, DayWeather],
    bucket_of: Callable[[DayWeather], str | None],
    order: Sequence[str],
    reference: str | None,
    min_trips: int,
) -> WeatherImpact:
    """Shared body of both splits: group the trips, sum each bucket, then measure the gap."""
    by_trip = {cost.trip_id: cost for cost in costs}
    groups: dict[str, list[tuple[Trip, DayWeather]]] = {name: [] for name in order}
    without_weather = 0
    for trip in trips:
        day = weather.get(trip.id)
        name = None if day is None else bucket_of(day)
        if day is None or name is None:
            without_weather += 1
        else:
            groups[name].append((trip, day))

    members = [(name, group) for name in order if (group := groups[name])]
    totals = {name: _bucket(name, group, by_trip, min_trips) for name, group in members}
    reference_row = None if reference is None else totals.get(reference)
    reference_consumption = None if reference_row is None else reference_row.l_per_100km
    if (
        reference is None
        or reference_row is None
        or reference_consumption is None
        or reference_row.too_few_trips
    ):
        # No reference bucket, too few trips in it, or no values at all: differences stay hidden.
        return WeatherImpact(
            buckets=[totals[name] for name, _ in members],
            total_trips=len(trips),
            trips_without_weather=without_weather,
            reference_bucket=None,
            reference_l_per_100km=None,
        )

    rows: list[WeatherBucketStats] = []
    for name, group in members:
        row = totals[name]
        gap = None if row.l_per_100km is None else row.l_per_100km - reference_consumption
        rows.append(_bucket(name, group, by_trip, min_trips, delta=gap))
    return WeatherImpact(
        buckets=rows,
        total_trips=len(trips),
        trips_without_weather=without_weather,
        reference_bucket=reference,
        reference_l_per_100km=reference_row.l_per_100km,
    )


def _bucket(
    name: str,
    members: Sequence[tuple[Trip, DayWeather]],
    by_trip: Mapping[str, TripCost],
    min_trips: int,
    delta: Decimal | None = None,
) -> WeatherBucketStats:
    """Sums of one bucket; ``delta`` is its difference in l/100 km against the reference."""
    trips = [trip for trip, _ in members]
    distance = sum((as_decimal(trip.distance_km) for trip in trips), _ZERO)
    fuel = sum((trip.fuel_l for trip in trips), _ZERO)
    ev = sum(
        (as_decimal(trip.ev_distance_km) for trip in trips if trip.ev_distance_km is not None),
        _ZERO,
    )
    priced = [
        (trip, cost)
        for trip in trips
        if (cost := by_trip.get(trip.id)) is not None
        and cost.cost_pln is not None
        and cost.source != "none"
    ]
    cost_sum = (
        sum((cost.cost_pln for _, cost in priced if cost.cost_pln is not None), _ZERO)
        if priced
        else None
    )
    priced_distance = sum((as_decimal(trip.distance_km) for trip, _ in priced), _ZERO)
    gusts = [gust for day in (day for _, day in members) if (gust := day.wind_max_kmh) is not None]
    return WeatherBucketStats(
        bucket=name,
        trips=len(trips),
        distance_km=round_distance(distance),
        fuel_l=fuel,
        l_per_100km=(round_consumption(fuel * _HUNDRED / distance) if distance > _ZERO else None),
        cost_pln=round_money(cost_sum) if cost_sum is not None else None,
        trips_without_cost=len(trips) - len(priced),
        cost_per_100km=(
            round_money(cost_sum * _HUNDRED / priced_distance)
            if cost_sum is not None and priced_distance > _ZERO
            else None
        ),
        ev_km=round_distance(ev),
        delta_l_per_100km=round_consumption(delta) if delta is not None else None,
        too_few_trips=len(trips) < min_trips,
        wind_max_kmh=max(gusts) if gusts else None,
    )
