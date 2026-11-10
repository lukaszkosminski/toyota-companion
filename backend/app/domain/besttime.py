"""The best time to leave for one repeated route (PRD F53, SPEC §53).

Mirrors ``core/domain/BestTimeOps.kt``. The app chooses the route;
this module takes the trips of one route.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, tzinfo
from decimal import ROUND_HALF_UP, Decimal
from enum import Enum

from .models import Trip, as_decimal

#: A cell needs this many trips to be shown and to count (docs/SPEC.md §53).
BEST_TIME_MIN_CELL_TRIPS = 3
#: A conclusion needs this many cells with data.
BEST_TIME_MIN_CELLS = 2
#: Two medians closer than this many minutes are "no clear difference".
BEST_TIME_MIN_DIFFERENCE_MINUTES = 2

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)
_TWO = Decimal(2)
_SIXTY = Decimal(60)
_CENT = Decimal("0.01")


class DayKind(Enum):
    """Working days (Monday to Friday) and the weekend, by the day of the start."""

    WORKING = "working"
    WEEKEND = "weekend"


class DepartureBand(Enum):
    """Departure bands by the local hour of the start.

    The night band runs from 21 to 5 over midnight and is one band.
    """

    H5_7 = "5_7"
    H7_9 = "7_9"
    H9_12 = "9_12"
    H12_15 = "12_15"
    H15_18 = "15_18"
    H18_21 = "18_21"
    NIGHT = "night"


def day_kind_of(weekday: int) -> DayKind:
    """``weekday`` as ``datetime.weekday()`` (Monday is 0): Saturday and Sunday are the weekend."""
    return DayKind.WEEKEND if weekday >= 5 else DayKind.WORKING


def departure_band_of(hour: int) -> DepartureBand:
    """The band of a local hour 0-23: 5-7, 7-9, 9-12, 12-15, 15-18, 18-21 and the night 21-5."""
    if hour >= 21 or hour <= 4:
        return DepartureBand.NIGHT
    if hour <= 6:
        return DepartureBand.H5_7
    if hour <= 8:
        return DepartureBand.H7_9
    if hour <= 11:
        return DepartureBand.H9_12
    if hour <= 14:
        return DepartureBand.H12_15
    if hour <= 17:
        return DepartureBand.H15_18
    return DepartureBand.H18_21


@dataclass(frozen=True)
class BestTimeCell:
    """One cell: ``trips`` of the route that started on ``day`` in ``band``.

    ``median_minutes`` is the median drive time rounded to a whole minute (HALF_UP).
    ``l_per_100km`` is ``sum(litres) * 100 / sum(km)``, ``None`` only without any distance.
    """

    day: DayKind
    band: DepartureBand
    trips: int
    median_minutes: int
    l_per_100km: Decimal | None


@dataclass(frozen=True)
class BestTimeGrid:
    """Cells with at least ``BEST_TIME_MIN_CELL_TRIPS`` trips, in order.

    ``trips`` is how many trips were counted.
    """

    trips: int
    cells: tuple[BestTimeCell, ...]

    @property
    def fastest(self) -> BestTimeCell | None:
        """The cell with the lowest median; it exists only with ``BEST_TIME_MIN_CELLS`` cells."""
        if len(self.cells) < BEST_TIME_MIN_CELLS:
            return None
        return min(self.cells, key=lambda cell: cell.median_minutes)

    @property
    def slowest(self) -> BestTimeCell | None:
        """The cell with the highest median; it exists only with ``BEST_TIME_MIN_CELLS`` cells."""
        if len(self.cells) < BEST_TIME_MIN_CELLS:
            return None
        return max(self.cells, key=lambda cell: cell.median_minutes)


def median_minutes(seconds: list[int]) -> int:
    """The median of drive times in seconds as whole minutes.

    For an even count it is the mean of the two middle values; then HALF_UP to a minute.
    """
    ordered = sorted(seconds)
    middle = len(ordered) // 2
    if len(ordered) % 2 == 1:
        median = Decimal(ordered[middle])
    else:
        median = Decimal(ordered[middle - 1] + ordered[middle]) / _TWO
    return int((median / _SIXTY).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def best_time_grid(trips: Iterable[Trip], zone: tzinfo = UTC) -> BestTimeGrid:
    """The grid of one route (PRD F53).

    A trip counts when its drive time is positive, its distance is finite and above zero and its
    fuel is not negative; a trip with 0 l stays in. The start decides the day and the band, in the
    local time of ``zone``, so a time change and a midnight crossing are handled by the start alone.
    """
    groups: dict[tuple[DayKind, DepartureBand], list[tuple[int, Decimal, Decimal]]] = {}
    counted = 0
    for trip in trips:
        seconds = int((trip.end - trip.start).total_seconds())
        distance = as_decimal(trip.distance_km)
        if seconds <= 0 or not distance.is_finite() or distance <= _ZERO or trip.fuel_l < _ZERO:
            continue
        local = trip.start.astimezone(zone)
        key = (day_kind_of(local.weekday()), departure_band_of(local.hour))
        groups.setdefault(key, []).append((seconds, distance, trip.fuel_l))
        counted += 1
    cells: list[BestTimeCell] = []
    order = {day: index for index, day in enumerate(DayKind)}
    band_order = {band: index for index, band in enumerate(DepartureBand)}
    for (day, band), samples in sorted(
        groups.items(), key=lambda item: (order[item[0][0]], band_order[item[0][1]])
    ):
        if len(samples) < BEST_TIME_MIN_CELL_TRIPS:
            continue
        km = sum((s[1] for s in samples), _ZERO)
        fuel = sum((s[2] for s in samples), _ZERO)
        cells.append(
            BestTimeCell(
                day=day,
                band=band,
                trips=len(samples),
                median_minutes=median_minutes([s[0] for s in samples]),
                l_per_100km=(fuel * _HUNDRED / km).quantize(_CENT, rounding=ROUND_HALF_UP)
                if km > _ZERO
                else None,
            )
        )
    return BestTimeGrid(trips=counted, cells=tuple(cells))
