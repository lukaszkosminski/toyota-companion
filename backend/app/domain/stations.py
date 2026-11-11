"""Stations at refuelling: names, averages and pooled consumption (PRD F57, SPEC §57)."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal

from .models import (
    EconomyInterval,
    Refuel,
    StationSummary,
    round_consumption,
    round_distance,
    round_price,
)

#: Longest station name kept; longer text is cut, never refused.
STATION_MAX_LENGTH = 60

#: How many recent stations the form and the prompt suggest.
STATION_SUGGESTIONS = 6

#: Closed full-tank intervals a station needs before its consumption is shown.
STATION_MIN_INTERVALS = 3

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)


def normalize_station(text: str | None) -> str | None:
    """Trim, collapse whitespace, turn an empty text into ``None`` and cut to 60 chars.

    Two names equal after this (ignoring case) are one station; the spelling shown
    is the one of the newest refuel (PRD F57).
    """
    if text is None:
        return None
    collapsed = " ".join(text.split())
    if not collapsed:
        return None
    return collapsed[:STATION_MAX_LENGTH].rstrip()


def _key(name: str) -> str:
    return name.lower()


@dataclass
class _Accumulator:
    name: str
    refuels: int = 0
    priced_refuels: int = 0
    priced_liters: Decimal = _ZERO
    priced_total: Decimal = _ZERO
    intervals: int = 0
    interval_liters: Decimal = _ZERO
    interval_distance: Decimal = _ZERO


def station_summaries(
    refuels: Iterable[Refuel],
    intervals: Iterable[EconomyInterval],
    min_intervals: int = STATION_MIN_INTERVALS,
) -> list[StationSummary]:
    """Stations of ``refuels`` with counts, weighted average price and pooled consumption.

    Only confirmed refuels count; a refuel without a station is not a row (the
    screen counts those separately). Mirrors Kotlin ``stationSummaries``.
    """
    confirmed = sorted(
        (refuel for refuel in refuels if refuel.status == "confirmed"),
        key=lambda refuel: (refuel.ts, refuel.id),
    )
    by_key: dict[str, _Accumulator] = {}
    for refuel in confirmed:
        name = normalize_station(refuel.station)
        if name is None:
            continue
        entry = by_key.setdefault(_key(name), _Accumulator(name))
        # Oldest first, so the last spelling of the name wins.
        entry.name = name
        entry.refuels += 1
        liters = refuel.liters
        total = refuel.total_cost
        if liters is not None and liters > _ZERO and total is not None:
            entry.priced_refuels += 1
            entry.priced_liters += liters
            entry.priced_total += total

    station_of = {
        refuel.id: _key(name)
        for refuel in confirmed
        if (name := normalize_station(refuel.station)) is not None
    }
    for interval in intervals:
        if interval.status != "ok" or interval.end_refuel_id is None:
            continue
        key = station_of.get(interval.start_refuel_id)
        if key is None:
            continue
        owner = by_key.get(key)
        if owner is None or interval.actual_liters is None:
            continue
        owner.intervals += 1
        owner.interval_liters += interval.actual_liters
        owner.interval_distance += interval.distance_km

    summaries: list[StationSummary] = []
    for entry in by_key.values():
        average = (
            round_price(entry.priced_total / entry.priced_liters)
            if entry.priced_liters > _ZERO
            else None
        )
        consumption = (
            round_consumption(entry.interval_liters * _HUNDRED / entry.interval_distance)
            if entry.intervals >= min_intervals and entry.interval_distance > _ZERO
            else None
        )
        summaries.append(
            StationSummary(
                name=entry.name,
                refuels=entry.refuels,
                priced_refuels=entry.priced_refuels,
                avg_price_per_liter=average,
                intervals=entry.intervals,
                distance_km=round_distance(entry.interval_distance),
                l_per_100km=consumption,
            )
        )
    return sorted(summaries, key=lambda summary: (-summary.refuels, _key(summary.name)))


@dataclass
class _Suggestion:
    name: str
    newest: datetime
    count: int = 0


def station_suggestions(
    refuels: Iterable[Refuel],
    limit: int = STATION_SUGGESTIONS,
) -> list[str]:
    """Station names used by ``refuels``, most often first, then the most recent.

    Mirrors Kotlin ``stationSuggestions``.
    """
    if limit <= 0:
        return []
    confirmed = sorted(
        (refuel for refuel in refuels if refuel.status == "confirmed"),
        key=lambda refuel: (refuel.ts, refuel.id),
    )
    by_key: dict[str, _Suggestion] = {}
    for refuel in confirmed:
        name = normalize_station(refuel.station)
        if name is None:
            continue
        entry = by_key.setdefault(_key(name), _Suggestion(name, newest=refuel.ts))
        entry.name = name
        entry.count += 1
        entry.newest = refuel.ts
    ordered = sorted(
        by_key.values(),
        key=lambda entry: (-entry.count, -entry.newest.timestamp(), _key(entry.name)),
    )
    return [entry.name for entry in ordered[:limit]]
