"""Trip cost calculator: litres and zloty of a planned trip from the own archive (PRD F52, SPEC
§52).

Mirrors ``core/domain/PlannedTripOps.kt``. The kind of drive comes with each sample (it is a speed
band, and the
speed is not part of the domain trip), so this module only needs the distance, the litres and the
kind.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from enum import Enum

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)

#: Trips shorter than this are no sample of a kind, as in F19 (docs/SPEC.md §22).
PLAN_MIN_KM = Decimal(2)
#: The fewest trips of a kind that give a range, as in F19.
PLAN_MIN_TRIPS = 10
#: The similar-length population is used when it has at least this many trips, otherwise the whole
#: kind.
PLAN_SIMILAR_MIN_TRIPS = 10
#: Similar length: from this share of the planned distance up to this multiple of it (inclusive).
PLAN_SIMILAR_LOW = Decimal("0.5")
PLAN_SIMILAR_HIGH = Decimal(2)
#: The longest planned trip accepted, in km.
PLAN_MAX_KM = Decimal(5000)


class TrafficClass(Enum):
    """Kind of drive by the average speed (PRD F19): slow, city, flowing, highway; the order is the
    tie order."""

    SLOW = "slow"
    CITY = "city"
    FLOWING = "flowing"
    HIGHWAY = "highway"


class PlanPopulation(Enum):
    SIMILAR_LENGTH = "similar_length"
    WHOLE_KIND = "whole_kind"


@dataclass(frozen=True)
class PlanSample:
    """One trip of the archive: its distance, litres and kind of drive."""

    distance_km: Decimal
    fuel_l: Decimal
    band: TrafficClass


@dataclass(frozen=True)
class PlannedRange:
    """The range of a planned trip; ``trips`` is the population size, ``kind_trips`` all trips of
    the kind."""

    band: TrafficClass
    population: PlanPopulation
    trips: int
    kind_trips: int
    litres_low: Decimal
    litres_high: Decimal
    cost_low: Decimal | None
    cost_high: Decimal | None


@dataclass(frozen=True)
class PlannedBadKm:
    """The distance is no trip: not above zero, or above ``PLAN_MAX_KM``."""


@dataclass(frozen=True)
class PlannedTooFew:
    """Too few trips of the kind for a range; ``trips`` is how many there are."""

    trips: int


PlannedTripResult = PlannedBadKm | PlannedTooFew | PlannedRange


def _rate(sample: PlanSample) -> Decimal:
    return sample.fuel_l * _HUNDRED / sample.distance_km


def planned_trip_cost(
    samples: list[PlanSample],
    km: Decimal,
    band: TrafficClass,
    price_per_liter: Decimal | None,
) -> PlannedTripResult:
    """The range for ``km`` of a trip of kind ``band`` (PRD F52, SPEC §52).

    Samples count only when they are of that kind, at least ``PLAN_MIN_KM`` and with fuel ≥ 0; trips
    with 0 l stay in.
    The population is the trips of a similar length when there are ``PLAN_SIMILAR_MIN_TRIPS`` of
    them, else all trips of
    the kind; below ``PLAN_MIN_TRIPS`` trips of the kind there is no range. Quartiles are the rates
    at index
    ``(n-1)//4`` and ``3*(n-1)//4`` of the sorted rates (no interpolation). Litres are ROUND_HALF_UP
    to 0.1 l, zloty to
    whole zloty of the unrounded litres times the price.
    """
    if km <= _ZERO or km > PLAN_MAX_KM:
        return PlannedBadKm()
    kind = [
        s
        for s in samples
        if s.band is band
        and s.distance_km > _ZERO
        and s.distance_km >= PLAN_MIN_KM
        and s.fuel_l >= _ZERO
    ]
    if len(kind) < PLAN_MIN_TRIPS:
        return PlannedTooFew(len(kind))
    low = km * PLAN_SIMILAR_LOW
    high = km * PLAN_SIMILAR_HIGH
    similar = [s for s in kind if low <= s.distance_km <= high]
    use_similar = len(similar) >= PLAN_SIMILAR_MIN_TRIPS
    population = similar if use_similar else kind
    rates = sorted(_rate(s) for s in population)
    n = len(rates)
    litres_low = km * rates[(n - 1) // 4] / _HUNDRED
    litres_high = km * rates[3 * (n - 1) // 4] / _HUNDRED
    return PlannedRange(
        band=band,
        population=PlanPopulation.SIMILAR_LENGTH if use_similar else PlanPopulation.WHOLE_KIND,
        trips=n,
        kind_trips=len(kind),
        litres_low=litres_low.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP),
        litres_high=litres_high.quantize(Decimal("0.1"), rounding=ROUND_HALF_UP),
        cost_low=(litres_low * price_per_liter).quantize(Decimal(1), rounding=ROUND_HALF_UP)
        if price_per_liter is not None
        else None,
        cost_high=(litres_high * price_per_liter).quantize(Decimal(1), rounding=ROUND_HALF_UP)
        if price_per_liter is not None
        else None,
    )


def most_driven_band(samples: list[PlanSample]) -> TrafficClass | None:
    """The kind with the most samples (PRD F52); a tie goes to the earlier kind; ``None`` without a
    sample."""
    counts: dict[TrafficClass, int] = {}
    for s in samples:
        if s.distance_km >= PLAN_MIN_KM and s.fuel_l >= _ZERO:
            counts[s.band] = counts.get(s.band, 0) + 1
    best: TrafficClass | None = None
    for kind in TrafficClass:
        if kind in counts and (best is None or counts[kind] > counts[best]):
            best = kind
    return best
