"""Two sets of tyres: fittings, the km of each set and the reminder (PRD F56, SPEC §56).

Mirrors ``core/domain/TyreOps.kt``. Pure: the settings give text, the archive gives trips.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, tzinfo
from decimal import ROUND_HALF_UP, Decimal
from enum import Enum

from .models import Trip, as_decimal

TYRE_MAX_ODOMETER_KM = 2_000_000
TYRE_MAX_KM = 300_000
TYRE_MAX_MONTHS = 120

_ZERO = Decimal(0)
_TENTH = Decimal("0.1")


class TyreSet(Enum):
    """Summer or winter set; ``code`` is the letter kept in the settings text."""

    SUMMER = "S"
    WINTER = "W"


class TyreDue(Enum):
    """Which threshold fired."""

    MONTHS = "MONTHS"
    KM = "KM"


@dataclass(frozen=True)
class TyreChange:
    """One fitting: the day the set went on and the odometer that day, when typed."""

    day: date
    set: TyreSet
    start_odometer_km: int | None


@dataclass(frozen=True)
class TyreThreshold:
    """
    Reminder thresholds of a set: full months, km of the archive; ``None`` means no reminder of that
    kind.
    """

    months: int | None
    km: int | None


@dataclass(frozen=True)
class TyreSetUsage:
    """Trips of one fitting from its day up to ``until`` (``None`` for the set on now)."""

    change: TyreChange
    until: date | None
    trips: int
    km: Decimal


@dataclass(frozen=True)
class TyreUsage:
    """All fittings with their usage, and the trips older than the first fitting, counted apart."""

    sets: tuple[TyreSetUsage, ...]
    before_first_trips: int
    before_first_km: Decimal


@dataclass(frozen=True)
class TyreReminder:
    """A reminder for the set on now."""

    usage: TyreSetUsage
    due: TyreDue
    months: int
    km: Decimal


def _set_of(letter: str) -> TyreSet | None:
    return next((s for s in TyreSet if s.value == letter.strip()), None)


def parse_tyre_changes(text: str | None) -> list[TyreChange]:
    """
    The fittings from ``2026-04-15|S|1234;2025-10-20|W|``; a broken entry is skipped, a second
    fitting of a day dropped, oldest first.
    """
    if not text or not text.strip():
        return []
    parsed: list[TyreChange] = []
    for entry in text.split(";"):
        parts = entry.split("|")
        if len(parts) != 3:
            continue
        try:
            day = date.fromisoformat(parts[0].strip())
        except ValueError:
            continue
        set_ = _set_of(parts[1])
        if set_ is None:
            continue
        odo_text = parts[2].strip()
        odometer: int | None = None
        if odo_text:
            if not odo_text.isdigit() or int(odo_text) > TYRE_MAX_ODOMETER_KM:
                continue
            odometer = int(odo_text)
        parsed.append(TyreChange(day, set_, odometer))
    parsed.sort(key=lambda c: c.day)
    seen: set[date] = set()
    unique: list[TyreChange] = []
    for change in parsed:
        if change.day not in seen:
            seen.add(change.day)
            unique.append(change)
    return unique


def format_tyre_changes(changes: Iterable[TyreChange]) -> str:
    """The settings text of the fittings, oldest first."""
    ordered = sorted(changes, key=lambda c: c.day)
    return ";".join(
        f"{c.day.isoformat()}|{c.set.value}|{
            '' if c.start_odometer_km is None else c.start_odometer_km
        }"
        for c in ordered
    )


def parse_tyre_thresholds(text: str | None) -> dict[TyreSet, TyreThreshold]:
    """The thresholds from ``S:6:15000;W::``; missing or out-of-range values are ``None``."""
    result = {s: TyreThreshold(None, None) for s in TyreSet}
    if not text or not text.strip():
        return result
    for entry in text.split(";"):
        parts = entry.split(":")
        if len(parts) != 3:
            continue
        set_ = _set_of(parts[0])
        if set_ is None:
            continue
        months = _in_range(parts[1], TYRE_MAX_MONTHS)
        km = _in_range(parts[2], TYRE_MAX_KM)
        result[set_] = TyreThreshold(months, km)
    return result


def _in_range(text: str, maximum: int) -> int | None:
    value = text.strip()
    if not value.isdigit():
        return None
    number = int(value)
    return number if 1 <= number <= maximum else None


def format_tyre_thresholds(thresholds: dict[TyreSet, TyreThreshold]) -> str:
    """The settings text of the thresholds."""
    parts = []
    for set_ in TyreSet:
        t = thresholds.get(set_, TyreThreshold(None, None))
        parts.append(
            f"{set_.value}:{'' if t.months is None else t.months}:{'' if t.km is None else t.km}"
        )
    return ";".join(parts)


def parse_tyre_reminder_keys(text: str | None) -> set[str]:
    """The reminder keys already sent, from a comma-separated text."""
    if not text:
        return set()
    return {part.strip() for part in text.split(",") if part.strip()}


def format_tyre_reminder_keys(keys: Iterable[str]) -> str:
    """The comma-separated text of the keys."""
    return ",".join(sorted(keys))


def tyre_usage(changes: Iterable[TyreChange], trips: Iterable[Trip], zone: tzinfo) -> TyreUsage:
    """
    The trips of each fitting by the local day of their start in ``zone``; a trip with no positive
    distance is left out.
    """
    ordered = sorted(changes, key=lambda c: c.day)
    days = [
        (trip.start.astimezone(zone).date(), as_decimal(trip.distance_km))
        for trip in trips
        if trip.distance_km == trip.distance_km
        and trip.distance_km > 0
        and trip.distance_km != float("inf")
    ]
    sets: list[TyreSetUsage] = []
    for index, change in enumerate(ordered):
        until = ordered[index + 1].day if index + 1 < len(ordered) else None
        owned = [km for day, km in days if day >= change.day and (until is None or day < until)]
        sets.append(
            TyreSetUsage(
                change,
                until,
                len(owned),
                sum(owned, _ZERO).quantize(_TENTH, rounding=ROUND_HALF_UP),
            )
        )
    first = ordered[0].day if ordered else None
    before = [km for day, km in days if first is None or day < first]
    return TyreUsage(
        tuple(sets), len(before), sum(before, _ZERO).quantize(_TENTH, rounding=ROUND_HALF_UP)
    )


def due_tyre_reminders(
    usage: TyreUsage, thresholds: dict[TyreSet, TyreThreshold], today: date
) -> list[TyreReminder]:
    """
    The reminders due today: only the set on now, from its own day; no threshold or a future fitting
    gives none.
    """
    if not usage.sets or usage.sets[-1].until is not None:
        return []
    current = usage.sets[-1]
    if current.change.day > today:
        return []
    threshold = thresholds.get(current.change.set)
    if threshold is None:
        return []
    months = _full_months(current.change.day, today)
    due: list[TyreReminder] = []
    if threshold.months is not None and months >= threshold.months:
        due.append(TyreReminder(current, TyreDue.MONTHS, months, current.km))
    if threshold.km is not None and current.km >= Decimal(threshold.km):
        due.append(TyreReminder(current, TyreDue.KM, months, current.km))
    return due


def _full_months(start: date, end: date) -> int:
    months = (end.year - start.year) * 12 + (end.month - start.month)
    if end.day < start.day:
        months -= 1
    return months


def tyre_reminder_key(reminder: TyreReminder) -> str:
    """One key per set, fitting day and kind, such as ``SUMMER:2026-04-15:MONTHS``."""
    change = reminder.usage.change
    return f"{change.set.name}:{change.day.isoformat()}:{reminder.due.name}"


def check_tyre_change(existing: Iterable[TyreChange], day: date, today: date) -> str:
    """``ok``, ``future``, ``duplicate`` or ``before_last`` for a new fitting on ``day``."""
    changes = list(existing)
    if day > today:
        return "future"
    if any(c.day == day for c in changes):
        return "duplicate"
    if any(c.day > day for c in changes):
        return "before_last"
    return "ok"


def parse_tyre_number(text: str, maximum: int) -> int | None:
    """A typed whole number from 0 to ``maximum``; ``None`` for wrong text or a value above it."""
    value = text.strip()
    if not value or not value.lstrip("-").isdigit():
        return None
    number = int(value)
    return number if 0 <= number <= maximum else None


def without_last_tyre_change(changes: Iterable[TyreChange]) -> list[TyreChange]:
    """The fittings without the newest one; an empty list stays empty."""
    return sorted(changes, key=lambda c: c.day)[:-1]


def tyre_keys_without_change(keys: Iterable[str], change: TyreChange) -> set[str]:
    """The reminder keys without those of ``change``: a key starts with its set and fitting day."""
    prefix = f"{change.set.name}:{change.day.isoformat()}:"
    return {key for key in keys if not key.startswith(prefix)}
