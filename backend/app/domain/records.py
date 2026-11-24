"""Personal records from the local trip archive (PRD F62, SPEC §62).

Mirrors ``core/domain/RecordsOps.kt``. Everything is computed read-only from
the trips already stored; no I/O, no network, no new storage.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, date, datetime, tzinfo
from decimal import Decimal

from .models import Trip, as_decimal, round_consumption, round_distance, round_percent

#: Minimum trip distance for the "best economy of a trip" record (R4).
MIN_ECONOMY_TRIP_KM = Decimal("50")
#: Minimum trip distance for the "best EV share of a trip" record (R3).
MIN_EV_SHARE_TRIP_KM = Decimal("20")
#: Minimum monthly distance for the "best economy of a month" record (R6).
MIN_ECONOMY_MONTH_KM = Decimal("500")

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)


@dataclass(frozen=True)
class Record:
    """One personal record.

    ``record_id`` is a stable machine key (``"longest_trip"`` etc.), ``value``
    the record itself, ``unit`` its unit (``"km"``, ``"l/100 km"``, ``"%"``),
    ``when`` the local date of the trip or month, ``context`` an optional
    human note (e.g. the month name for monthly records).
    """

    record_id: str
    value: Decimal
    unit: str
    when: date | None
    context: str | None = None


@dataclass(frozen=True)
class Records:
    """All records; ``empty`` is true when the archive has no qualifying trips."""

    items: list[Record]
    empty: bool


def _local(moment: datetime, zone: tzinfo) -> datetime:
    aware = moment if moment.tzinfo is not None else moment.replace(tzinfo=UTC)
    return aware.astimezone(zone)


def _trip_economy(trip: Trip) -> Decimal | None:
    """``fuel / distance * 100`` rounded to 0.01 l/100 km; ``None`` at zero distance."""
    distance = as_decimal(trip.distance_km)
    if distance <= _ZERO:
        return None
    return round_consumption(trip.fuel_l * _HUNDRED / distance)


def _ev_share(trip: Trip) -> Decimal | None:
    """``ev / distance * 100`` rounded to 0.1 %; ``None`` without EV data or at zero distance."""
    distance = as_decimal(trip.distance_km)
    if trip.ev_distance_km is None or distance <= _ZERO:
        return None
    ev = as_decimal(trip.ev_distance_km)
    return round_percent(min(ev, distance) * _HUNDRED / distance)


def _month_key(day: date) -> tuple[int, int]:
    return day.year, day.month


_MONTH_NAMES_PL = (
    "",
    "styczeń",
    "luty",
    "marzec",
    "kwiecień",
    "maj",
    "czerwiec",
    "lipiec",
    "sierpień",
    "wrzesień",
    "październik",
    "listopad",
    "grudzień",
)


def compute_records(trips: Iterable[Trip], zone: tzinfo) -> Records:
    """Compute all personal records from the trip archive, oldest-first input irrelevant.

    Records:
    - R1 ``longest_trip``: the longest trip in km.
    - R2 ``longest_ev_trip``: the longest EV distance of one trip in km (needs ``ev_distance_km``).
    - R3 ``best_ev_share``: the highest EV share % of a trip of at least 20 km.
    - R4 ``best_economy_trip``: the lowest l/100 km of a trip of at least 50 km.
    - R5 ``most_km_month``: the most km driven in one calendar month.
    - R6 ``best_economy_month``: the lowest pooled l/100 km of a month with at least 500 km.

    A record is omitted when no trip or month qualifies. ``empty`` is true when
    all records were omitted (e.g. an archive of only short trips).
    """
    trip_list = sorted(trips, key=lambda t: t.start)
    if not trip_list:
        return Records([], empty=True)

    items: list[Record] = []

    # R1 – longest trip.
    longest = max(trip_list, key=lambda t: as_decimal(t.distance_km))
    items.append(
        Record(
            record_id="longest_trip",
            value=round_distance(longest.distance_km),
            unit="km",
            when=_local(longest.start, zone).date(),
        )
    )

    # R2 – longest EV distance of one trip.
    with_ev = [t for t in trip_list if t.ev_distance_km is not None]
    if with_ev:
        longest_ev = max(with_ev, key=lambda t: as_decimal(t.ev_distance_km))  # type: ignore[arg-type]
        items.append(
            Record(
                record_id="longest_ev_trip",
                value=round_distance(longest_ev.ev_distance_km),  # type: ignore[arg-type]
                unit="km",
                when=_local(longest_ev.start, zone).date(),
            )
        )

    # R3 – best EV share of a trip of at least 20 km.
    share_candidates = [
        (t, share)
        for t in trip_list
        if as_decimal(t.distance_km) >= MIN_EV_SHARE_TRIP_KM and (share := _ev_share(t)) is not None
    ]
    if share_candidates:
        best_trip, best_share = max(share_candidates, key=lambda pair: pair[1])
        items.append(
            Record(
                record_id="best_ev_share",
                value=best_share,
                unit="%",
                when=_local(best_trip.start, zone).date(),
                context=f"{round_distance(best_trip.distance_km)} km",
            )
        )

    # R4 – best economy of a single trip of at least 50 km.
    econ_candidates = [
        (t, econ)
        for t in trip_list
        if as_decimal(t.distance_km) >= MIN_ECONOMY_TRIP_KM
        and (econ := _trip_economy(t)) is not None
    ]
    if econ_candidates:
        best_trip, best_econ = min(econ_candidates, key=lambda pair: pair[1])
        items.append(
            Record(
                record_id="best_economy_trip",
                value=best_econ,
                unit="l/100 km",
                when=_local(best_trip.start, zone).date(),
                context=f"{round_distance(best_trip.distance_km)} km",
            )
        )

    # Monthly aggregates (by local month of the trip start).
    months: dict[tuple[int, int], list[Trip]] = {}
    for t in trip_list:
        months.setdefault(_month_key(_local(t.start, zone).date()), []).append(t)

    # R5 – most km in one month.
    if months:
        best_month_key = max(
            months, key=lambda key: sum((as_decimal(t.distance_km) for t in months[key]), _ZERO)
        )
        month_km = round_distance(
            sum((as_decimal(t.distance_km) for t in months[best_month_key]), _ZERO)
        )
        year, month = best_month_key
        items.append(
            Record(
                record_id="most_km_month",
                value=month_km,
                unit="km",
                when=date(year, month, 1),
                context=f"{_MONTH_NAMES_PL[month]} {year}",
            )
        )

    # R6 – best pooled economy of a month with at least 500 km.
    econ_months: list[tuple[tuple[int, int], Decimal]] = []
    for key, members in months.items():
        distance = sum((as_decimal(t.distance_km) for t in members), _ZERO)
        if distance < MIN_ECONOMY_MONTH_KM:
            continue
        fuel = sum((t.fuel_l for t in members), _ZERO)
        econ_months.append((key, round_consumption(fuel * _HUNDRED / distance)))
    if econ_months:
        best_key, best_econ = min(econ_months, key=lambda pair: pair[1])
        year, month = best_key
        items.append(
            Record(
                record_id="best_economy_month",
                value=best_econ,
                unit="l/100 km",
                when=date(year, month, 1),
                context=f"{_MONTH_NAMES_PL[month]} {year}",
            )
        )

    return Records(items, empty=not items)
