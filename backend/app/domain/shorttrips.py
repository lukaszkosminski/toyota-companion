"""Short trips and the cold engine: fuel per 100 km by trip length (PRD F50, SPEC §50).

Pure functions over the trips of one vehicle: the bands of length, the share of trips and km in
each,
litres per 100 km as ``sum(litres) / sum(km)`` (never an average of averages), the excess over the
reference (all trips from 10 km) and a cold-start split by the gap since the previous trip ended.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import timedelta
from decimal import ROUND_HALF_UP, Decimal
from enum import Enum

from .models import (
    Trip,
    TripCost,
    as_decimal,
    round_consumption,
    round_distance,
    round_liters,
    round_money,
)

#: Below this distance a drive is moving the car in a car park, not a trip (plan C30).
SHORT_MIN_KM = Decimal("0.1")

#: Trips from this length are the reference; the bands below it are "short".
SHORT_REFERENCE_FROM_KM = Decimal(10)

#: A start at least this long after the previous trip ended is a cold start (an approximation,
#: not a measurement).
COLD_GAP_HOURS = 3
COLD_GAP = timedelta(hours=COLD_GAP_HOURS)

#: A longer gap may be a hole in the archive, so the start is unknown (the same limit as the
#: parking diary, F17).
MAX_GAP_DAYS = 21
MAX_GAP = timedelta(days=MAX_GAP_DAYS)

#: A band shows its litres per 100 km from this many trips (as a cell of SPEC §28).
SHORT_MIN_TRIPS_SHOWN = 5

#: A conclusion (the excess, cold against warm) needs this many trips on every side compared
#: (as SPEC §22).
SHORT_MIN_TRIPS_COMPARE = 10

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)
_WHOLE = Decimal(1)
_TWO_PLACES = Decimal("0.01")


class LengthBand(Enum):
    """Length of a trip, the lower bound of each band belongs to it."""

    UNDER_2 = "under_2"
    FROM_2 = "from_2"
    FROM_5 = "from_5"
    FROM_10 = "from_10"
    FROM_25 = "from_25"


class StartKind(Enum):
    """How the engine started: after a long stand, after a short one, or unknown."""

    COLD = "cold"
    WARM = "warm"
    UNKNOWN = "unknown"


#: The bands below the reference, in the order of the screen.
SHORT_BANDS = (LengthBand.UNDER_2, LengthBand.FROM_2, LengthBand.FROM_5)

_LOWER_KM = {
    LengthBand.FROM_2: Decimal(2),
    LengthBand.FROM_5: Decimal(5),
    LengthBand.FROM_10: Decimal(10),
    LengthBand.FROM_25: Decimal(25),
}


@dataclass(frozen=True)
class ColdSplit:
    """Cold and warm starts of one band, only when both sides have enough trips."""

    cold_trips: int
    cold_l_per_100km: Decimal
    warm_trips: int
    warm_l_per_100km: Decimal
    difference_l_per_100km: Decimal


@dataclass(frozen=True)
class BandStats:
    """One band of trip length (SPEC §50).

    The rates are ``None`` below ``SHORT_MIN_TRIPS_SHOWN`` trips.
    """

    band: LengthBand
    trips: int
    distance_km: Decimal
    trip_share_pct: Decimal
    km_share_pct: Decimal
    fuel_l: Decimal
    l_per_100km: Decimal | None
    cost_per_100km: Decimal | None
    ev_share_pct: Decimal | None
    excess_l_per_100km: Decimal | None
    excess_l: Decimal | None
    excess_pln: Decimal | None
    cold_trips: int
    warm_trips: int
    unknown_start_trips: int
    cold_split: ColdSplit | None


@dataclass(frozen=True)
class ReferenceStats:
    """All trips from ``SHORT_REFERENCE_FROM_KM`` together."""

    trips: int
    distance_km: Decimal
    fuel_l: Decimal
    l_per_100km: Decimal | None
    cost_per_100km: Decimal | None


@dataclass(frozen=True)
class ShortTripSummary:
    """The bands that have trips, the reference and the totals of the short bands (SPEC §50)."""

    bands: tuple[BandStats, ...]
    reference: ReferenceStats | None
    short_trips: int
    short_trip_share_pct: Decimal | None
    short_km_share_pct: Decimal | None
    total_excess_l: Decimal | None
    total_excess_pln: Decimal | None
    used_trips: int
    left_out: int


def _is_usable(trip: Trip) -> bool:
    return (
        math.isfinite(trip.distance_km)
        and as_decimal(trip.distance_km) >= SHORT_MIN_KM
        and trip.fuel_l >= _ZERO
    )


def length_band_of(distance_km: float | Decimal) -> LengthBand:
    """The band of a trip; a distance below ``SHORT_MIN_KM`` or not finite is no trip."""
    if isinstance(distance_km, float) and not math.isfinite(distance_km):
        raise ValueError("distance must be finite")
    distance = as_decimal(distance_km)
    if distance < SHORT_MIN_KM:
        raise ValueError("distance is below the shortest trip")
    if distance >= _LOWER_KM[LengthBand.FROM_25]:
        return LengthBand.FROM_25
    if distance >= _LOWER_KM[LengthBand.FROM_10]:
        return LengthBand.FROM_10
    if distance >= _LOWER_KM[LengthBand.FROM_5]:
        return LengthBand.FROM_5
    if distance >= _LOWER_KM[LengthBand.FROM_2]:
        return LengthBand.FROM_2
    return LengthBand.UNDER_2


def trip_start_kinds(trips: Iterable[Trip]) -> dict[str, StartKind]:
    """The start of every trip by its id, from ALL the trips (also those the bands leave out).

    The previous trip is the one before it by ``(start, id)``: the first has no previous
    (unknown), a gap
    longer than ``MAX_GAP`` is unknown too (a hole in the archive), a gap of ``COLD_GAP`` or
    more is cold,
    anything shorter - also an overlap - is warm.
    """
    ordered = sorted(trips, key=lambda trip: (trip.start, trip.id))
    kinds: dict[str, StartKind] = {}
    for index, trip in enumerate(ordered):
        if index == 0:
            kinds[trip.id] = StartKind.UNKNOWN
            continue
        gap = trip.start - ordered[index - 1].end
        if gap > MAX_GAP:
            kinds[trip.id] = StartKind.UNKNOWN
        elif gap >= COLD_GAP:
            kinds[trip.id] = StartKind.COLD
        else:
            kinds[trip.id] = StartKind.WARM
    return kinds


def _per_100(fuel: Decimal, km: Decimal) -> Decimal:
    return fuel * _HUNDRED / km


def _percent(part: Decimal, whole: Decimal) -> Decimal:
    return (part * _HUNDRED / whole).quantize(_WHOLE, rounding=ROUND_HALF_UP)


def _priced(trip: Trip, by_trip: dict[str, TripCost]) -> TripCost | None:
    cost = by_trip.get(trip.id)
    if cost is None or cost.cost_pln is None or cost.source == "none":
        return None
    return cost


def _sums(group: Sequence[Trip]) -> tuple[Decimal, Decimal]:
    km = sum((as_decimal(trip.distance_km) for trip in group), _ZERO)
    fuel = sum((trip.fuel_l for trip in group), _ZERO)
    return km, fuel


def _cost_per_100(group: Sequence[Trip], by_trip: dict[str, TripCost]) -> Decimal | None:
    cost_sum = _ZERO
    priced_km = _ZERO
    for trip in group:
        cost = _priced(trip, by_trip)
        if cost is not None and cost.cost_pln is not None:
            cost_sum += cost.cost_pln
            priced_km += as_decimal(trip.distance_km)
    if priced_km <= _ZERO:
        return None
    return round_money(cost_sum * _HUNDRED / priced_km)


def _price_per_liter(group: Sequence[Trip], by_trip: dict[str, TripCost]) -> Decimal | None:
    cost_sum = _ZERO
    fuel_sum = _ZERO
    for trip in group:
        cost = _priced(trip, by_trip)
        if cost is not None and cost.cost_pln is not None and trip.fuel_l > _ZERO:
            cost_sum += cost.cost_pln
            fuel_sum += trip.fuel_l
    if fuel_sum <= _ZERO:
        return None
    return cost_sum / fuel_sum


def _ev_share(group: Sequence[Trip], km: Decimal) -> Decimal | None:
    known = [trip for trip in group if trip.ev_distance_km is not None]
    if not known:
        return None
    electric = _ZERO
    for trip in known:
        ev = trip.ev_distance_km
        if ev is not None and math.isfinite(ev) and ev > 0:
            electric += as_decimal(min(ev, trip.distance_km))
    return _percent(electric, km)


def _split(group: Sequence[Trip], kinds: dict[str, StartKind]) -> ColdSplit | None:
    cold = [trip for trip in group if kinds.get(trip.id) is StartKind.COLD]
    warm = [trip for trip in group if kinds.get(trip.id) is StartKind.WARM]
    if len(cold) < SHORT_MIN_TRIPS_COMPARE or len(warm) < SHORT_MIN_TRIPS_COMPARE:
        return None
    cold_km, cold_fuel = _sums(cold)
    warm_km, warm_fuel = _sums(warm)
    cold_rate = round_consumption(_per_100(cold_fuel, cold_km))
    warm_rate = round_consumption(_per_100(warm_fuel, warm_km))
    return ColdSplit(len(cold), cold_rate, len(warm), warm_rate, cold_rate - warm_rate)


def short_trip_summary(trips: Iterable[Trip], costs: Iterable[TripCost]) -> ShortTripSummary:
    """The trips by length band with the excess of the short ones over the trips from 10 km
    (SPEC §50).

    Trips with a distance below ``SHORT_MIN_KM``, not finite, or with negative fuel are only
    counted in
    ``left_out``; a trip with 0 l (driven on the battery) stays in every sum. Money is only
    from the trips
    with a price (``source != none`` and an amount); without any price it is ``None``, never 0 zl.
    """
    all_trips = list(trips)
    by_trip = {cost.trip_id: cost for cost in costs}
    kinds = trip_start_kinds(all_trips)
    used = [trip for trip in all_trips if _is_usable(trip)]
    total_km, _ = _sums(used)
    groups: dict[LengthBand, list[Trip]] = {band: [] for band in LengthBand}
    for trip in used:
        groups[length_band_of(trip.distance_km)].append(trip)

    reference_group = groups[LengthBand.FROM_10] + groups[LengthBand.FROM_25]
    reference: ReferenceStats | None = None
    ref_km = ref_fuel = _ZERO
    if reference_group:
        ref_km, ref_fuel = _sums(reference_group)
        enough = len(reference_group) >= SHORT_MIN_TRIPS_SHOWN
        reference = ReferenceStats(
            trips=len(reference_group),
            distance_km=round_distance(ref_km),
            fuel_l=round_liters(ref_fuel),
            l_per_100km=round_consumption(_per_100(ref_fuel, ref_km)) if enough else None,
            cost_per_100km=_cost_per_100(reference_group, by_trip) if enough else None,
        )

    rows: list[BandStats] = []
    for band in LengthBand:
        group = groups[band]
        if not group:
            continue
        km, fuel = _sums(group)
        enough = len(group) >= SHORT_MIN_TRIPS_SHOWN
        rate = round_consumption(_per_100(fuel, km)) if enough else None
        excess_rate: Decimal | None = None
        excess_l: Decimal | None = None
        excess_pln: Decimal | None = None
        comparable = (
            band in SHORT_BANDS
            and len(group) >= SHORT_MIN_TRIPS_COMPARE
            and reference is not None
            and reference.trips >= SHORT_MIN_TRIPS_COMPARE
            and reference.l_per_100km is not None
            and rate is not None
        )
        if (
            comparable
            and reference is not None
            and reference.l_per_100km is not None
            and rate is not None
        ):
            excess_rate = rate - reference.l_per_100km
            excess_raw = fuel - ref_fuel * km / ref_km
            excess_l = excess_raw.quantize(_TWO_PLACES, rounding=ROUND_HALF_UP)
            price = _price_per_liter(group, by_trip)
            excess_pln = round_money(excess_raw * price) if price is not None else None
        rows.append(
            BandStats(
                band=band,
                trips=len(group),
                distance_km=round_distance(km),
                trip_share_pct=_percent(Decimal(len(group)), Decimal(len(used))),
                km_share_pct=_percent(km, total_km),
                fuel_l=round_liters(fuel),
                l_per_100km=rate,
                cost_per_100km=_cost_per_100(group, by_trip) if enough else None,
                ev_share_pct=_ev_share(group, km) if enough else None,
                excess_l_per_100km=excess_rate,
                excess_l=excess_l,
                excess_pln=excess_pln,
                cold_trips=sum(1 for trip in group if kinds.get(trip.id) is StartKind.COLD),
                warm_trips=sum(1 for trip in group if kinds.get(trip.id) is StartKind.WARM),
                unknown_start_trips=sum(
                    1 for trip in group if kinds.get(trip.id) is StartKind.UNKNOWN
                ),
                cold_split=_split(group, kinds),
            )
        )

    short_group = [trip for band in SHORT_BANDS for trip in groups[band]]
    short_km, _ = _sums(short_group)
    counted = [row for row in rows if row.band in SHORT_BANDS and row.excess_l is not None]
    total_l = (
        sum((row.excess_l for row in counted if row.excess_l is not None), _ZERO)
        if counted
        else None
    )
    total_pln: Decimal | None = None
    if counted and all(row.excess_pln is not None for row in counted):
        total_pln = sum((row.excess_pln for row in counted if row.excess_pln is not None), _ZERO)
    return ShortTripSummary(
        bands=tuple(rows),
        reference=reference,
        short_trips=len(short_group),
        short_trip_share_pct=_percent(Decimal(len(short_group)), Decimal(len(used)))
        if used
        else None,
        short_km_share_pct=_percent(short_km, total_km) if used else None,
        total_excess_l=total_l,
        total_excess_pln=total_pln,
        used_trips=len(used),
        left_out=len(all_trips) - len(used),
    )
