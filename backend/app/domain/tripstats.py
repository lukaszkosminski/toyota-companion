"""Electric share of trips (PRD F11) and statistics over periods (PRD F10), SPEC §16-§17."""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime, tzinfo
from decimal import Decimal
from typing import Literal

from .models import (
    Trip,
    TripCost,
    as_decimal,
    round_consumption,
    round_distance,
    round_money,
    round_percent,
)

#: Below this distance a share is noise (0.15 km EV of a 0.2 km trip is 75 %), owner plan C11 D3.
MIN_RANKED_DISTANCE_KM = Decimal("1")

#: How many trips the electric ranking shows.
RANKING_LIMIT = 10

PeriodKind = Literal["year", "season"]
Season = Literal["winter", "spring", "summer", "autumn"]

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)


@dataclass(frozen=True)
class TripShare:
    """Electric share of one trip; ``share_pct`` is ``None`` without Toyota's ``hdc`` data."""

    trip_id: str
    distance_km: Decimal
    ev_distance_km: Decimal | None
    share_pct: Decimal | None
    inconsistent: bool


@dataclass(frozen=True)
class ElectricRanking:
    rows: list[TripShare]
    without_data: int
    too_short: int


@dataclass(frozen=True)
class PeriodStats:
    """Sums of one year or one meteorological season.

    December counts to the winter of the next year (the one January belongs to).
    """

    year: int
    season: Season | None
    trips: int
    distance_km: Decimal
    fuel_l: Decimal
    l_per_100km: Decimal | None
    cost_pln: Decimal | None
    trips_without_cost: int
    cost_per_100km: Decimal | None
    ev_share_pct: Decimal | None
    average_trip_km: Decimal | None


def _local(moment: datetime, zone: tzinfo) -> datetime:
    aware = moment if moment.tzinfo is not None else moment.replace(tzinfo=UTC)
    return aware.astimezone(zone)


def electric_share(trip: Trip) -> TripShare:
    """``ev / distance``, rounded to 0.1 %.

    Toyota rounds meters, so ``ev > distance`` is clipped to 100 % and marked
    ``inconsistent`` instead of being hidden; no ``hdc`` or no distance gives ``None``.
    """
    distance = as_decimal(trip.distance_km)
    if trip.ev_distance_km is None or distance <= _ZERO:
        ev = None if trip.ev_distance_km is None else round_distance(trip.ev_distance_km)
        return TripShare(trip.id, round_distance(distance), ev, None, False)
    ev_raw = as_decimal(trip.ev_distance_km)
    inconsistent = ev_raw > distance
    share = _HUNDRED if inconsistent else round_percent(ev_raw * _HUNDRED / distance)
    return TripShare(trip.id, round_distance(distance), round_distance(ev_raw), share, inconsistent)


def aggregate_electric_share(trips: Iterable[Trip]) -> Decimal | None:
    """``sum(ev) / sum(distance)`` over the trips with ``hdc`` data; ``None`` when none has it."""
    with_data = [trip for trip in trips if trip.ev_distance_km is not None]
    distance = sum((as_decimal(trip.distance_km) for trip in with_data), _ZERO)
    if distance <= _ZERO:
        return None
    ev = sum(
        (as_decimal(trip.ev_distance_km) for trip in with_data if trip.ev_distance_km is not None),
        _ZERO,
    )
    return round_percent(min(ev, distance) * _HUNDRED / distance)


def electric_trip_ranking(
    trips: Iterable[Trip],
    limit: int = RANKING_LIMIT,
    min_distance_km: Decimal = MIN_RANKED_DISTANCE_KM,
) -> ElectricRanking:
    """Trips with the highest electric share first; ties by distance (longer first), then id.

    Trips without ``hdc`` data and trips shorter than ``min_distance_km`` are only counted.
    """
    without_data = 0
    too_short = 0
    ranked: list[TripShare] = []
    for trip in trips:
        share = electric_share(trip)
        if share.share_pct is None:
            without_data += 1
        elif as_decimal(trip.distance_km) < min_distance_km:
            too_short += 1
        else:
            ranked.append(share)
    ranked.sort(key=lambda row: (-(row.share_pct or _ZERO), -row.distance_km, row.trip_id))
    return ElectricRanking(ranked[:limit], without_data, too_short)


def season_of(month: int) -> Season:
    """Meteorological season of a calendar month."""
    if month in (12, 1, 2):
        return "winter"
    if month in (3, 4, 5):
        return "spring"
    if month in (6, 7, 8):
        return "summer"
    return "autumn"


def _period_key(day: date, kind: PeriodKind) -> tuple[int, Season | None]:
    if kind == "year":
        return day.year, None
    season = season_of(day.month)
    # December opens the winter that continues in January: it is named by January's year.
    return (day.year + 1 if day.month == 12 else day.year), season


_SEASON_ORDER: dict[Season | None, int] = {
    None: 0,
    "winter": 0,
    "spring": 1,
    "summer": 2,
    "autumn": 3,
}


def period_stats(
    trips: Iterable[Trip],
    costs: Iterable[TripCost],
    zone: tzinfo,
    kind: PeriodKind,
) -> list[PeriodStats]:
    """Statistics per year or per season, oldest first. A trip belongs to the period of its
    start in ``zone`` (the zone of the trips list). Costs only from trips that have a price
    (``source != "none"`` and an amount); with none the cost is ``None``, never 0 zł.
    """
    by_trip = {cost.trip_id: cost for cost in costs}
    groups: dict[tuple[int, Season | None], list[Trip]] = {}
    for trip in trips:
        groups.setdefault(_period_key(_local(trip.start, zone).date(), kind), []).append(trip)
    ordered = sorted(groups.items(), key=lambda item: (item[0][0], _SEASON_ORDER[item[0][1]]))
    return [_stats(year, season, members, by_trip) for (year, season), members in ordered]


def _stats(
    year: int, season: Season | None, members: Sequence[Trip], by_trip: dict[str, TripCost]
) -> PeriodStats:
    distance = sum((as_decimal(trip.distance_km) for trip in members), _ZERO)
    fuel = sum((trip.fuel_l for trip in members), _ZERO)
    priced = [
        (trip, cost)
        for trip in members
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
    return PeriodStats(
        year=year,
        season=season,
        trips=len(members),
        distance_km=round_distance(distance),
        fuel_l=fuel,
        l_per_100km=round_consumption(fuel * _HUNDRED / distance) if distance > _ZERO else None,
        cost_pln=round_money(cost_sum) if cost_sum is not None else None,
        trips_without_cost=len(members) - len(priced),
        cost_per_100km=(
            round_money(cost_sum * _HUNDRED / priced_distance)
            if cost_sum is not None and priced_distance > _ZERO
            else None
        ),
        ev_share_pct=aggregate_electric_share(members),
        average_trip_km=round_distance(distance / len(members)) if members else None,
    )


def year_over_year(
    trips: Iterable[Trip],
    costs: Iterable[TripCost],
    zone: tzinfo,
) -> tuple[PeriodStats, PeriodStats] | None:
    """The latest year so far against the same days of the year before, ``None`` without trips
    in that earlier window. The window runs from 1 January to the day of the latest trip, in
    both years - otherwise January of a new year would look like a 92 % drop.
    """
    trip_list = list(trips)
    cost_list = list(costs)
    if not trip_list:
        return None
    latest = max(_local(trip.start, zone).date() for trip in trip_list)
    current_end = (latest.month, latest.day)

    def window(year: int) -> list[Trip]:
        return [
            trip
            for trip in trip_list
            if (local := _local(trip.start, zone).date()).year == year
            and (local.month, local.day) <= current_end
        ]

    current = window(latest.year)
    previous = window(latest.year - 1)
    if not previous:
        return None
    by_trip = {cost.trip_id: cost for cost in cost_list}
    return _stats(latest.year, None, current, by_trip), _stats(
        latest.year - 1, None, previous, by_trip
    )
