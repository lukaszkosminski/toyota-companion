"""The period summary notification (PRD F54, SPEC §54).

Mirrors ``core/domain/PeriodSummaryOps.kt``. Pure: dates and the hour come in, nothing is read from
the phone here.
"""

from __future__ import annotations

import math
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, timedelta, tzinfo
from decimal import ROUND_HALF_UP, Decimal
from enum import Enum

from .models import Trip, TripCost, as_decimal, round_money
from .tripstats import aggregate_electric_share

#: Quiet hours: the notification is sent from SUMMARY_FROM_HOUR up to, not including,
#: SUMMARY_UNTIL_HOUR (local time).
SUMMARY_FROM_HOUR = 8
SUMMARY_UNTIL_HOUR = 22

_ZERO = Decimal(0)
_HUNDRED = Decimal(100)
_TENTH = Decimal("0.1")
_CENT = Decimal("0.01")


class SummaryPeriod(Enum):
    """The period the summary covers."""

    WEEK = "week"
    MONTH = "month"


@dataclass(frozen=True)
class SummaryWindow:
    """
    A period of local dates: ``from_`` inclusive, ``until`` exclusive. The key names it:
    ``week:2026-11-02`` (the Monday) or ``month:2026-10``.
    """

    period: SummaryPeriod
    from_: date
    until: date

    @property
    def key(self) -> str:
        if self.period is SummaryPeriod.WEEK:
            return f"week:{self.from_.isoformat()}"
        return f"month:{self.from_.year:04d}-{self.from_.month:02d}"


def last_completed_window(period: SummaryPeriod, today: date) -> SummaryWindow:
    """
    The last period that is completely over on ``today``: the week before this Monday, or the month
    before this one.
    """
    if period is SummaryPeriod.WEEK:
        this_monday = today - timedelta(days=today.weekday())
        return SummaryWindow(period, this_monday - timedelta(weeks=1), this_monday)
    this_month = today.replace(day=1)
    previous_month = (this_month - timedelta(days=1)).replace(day=1)
    return SummaryWindow(period, previous_month, this_month)


def previous_window(window: SummaryWindow) -> SummaryWindow:
    """The period of the same kind right before ``window``, for the comparison."""
    if window.period is SummaryPeriod.WEEK:
        return SummaryWindow(window.period, window.from_ - timedelta(weeks=1), window.from_)
    before = (window.from_ - timedelta(days=1)).replace(day=1)
    return SummaryWindow(window.period, before, window.from_)


@dataclass(frozen=True)
class PeriodTotals:
    """The sums of one period; ``fuel_cost_pln`` is ``None`` without a priced trip, never 0 zl."""

    trips: int
    distance_km: Decimal
    fuel_l: Decimal
    fuel_cost_pln: Decimal | None
    expenses_pln: Decimal
    l_per_100km: Decimal | None
    ev_share_pct: Decimal | None

    @property
    def cost_pln(self) -> Decimal:
        """Fuel plus the other expenses; the fuel counts as 0 without a price."""
        return (self.fuel_cost_pln or _ZERO) + self.expenses_pln

    @property
    def is_empty(self) -> bool:
        """A period with neither a trip nor an expense has nothing to tell."""
        return self.trips == 0 and self.expenses_pln == _ZERO


def period_totals(
    trips: Iterable[Trip],
    costs: Iterable[TripCost],
    expenses: Iterable[tuple[date, Decimal]],
    window: SummaryWindow,
    zone: tzinfo,
) -> PeriodTotals:
    """
    The totals of ``window`` in ``zone``: a trip belongs to the local date of its start, an expense
    to its date.
    """

    def in_window(day: date) -> bool:
        return window.from_ <= day < window.until

    cost_by_trip = {cost.trip_id: cost for cost in costs}
    usable = [
        trip
        for trip in trips
        if in_window(trip.start.astimezone(zone).date())
        and math.isfinite(trip.distance_km)
        and trip.distance_km > 0
        and trip.fuel_l >= _ZERO
    ]
    km = sum((as_decimal(trip.distance_km) for trip in usable), _ZERO)
    fuel = sum((trip.fuel_l for trip in usable), _ZERO)
    fuel_cost: Decimal | None = None
    for trip in usable:
        cost = cost_by_trip.get(trip.id)
        if cost is not None and cost.cost_pln is not None and cost.source != "none":
            fuel_cost = cost.cost_pln if fuel_cost is None else fuel_cost + cost.cost_pln
    expenses_sum = sum((amount for day, amount in expenses if in_window(day)), _ZERO)
    return PeriodTotals(
        trips=len(usable),
        distance_km=km.quantize(_TENTH, rounding=ROUND_HALF_UP),
        fuel_l=fuel.quantize(_TENTH, rounding=ROUND_HALF_UP),
        fuel_cost_pln=round_money(fuel_cost) if fuel_cost is not None else None,
        expenses_pln=round_money(expenses_sum),
        l_per_100km=(fuel * _HUNDRED / km).quantize(_CENT, rounding=ROUND_HALF_UP)
        if km > _ZERO
        else None,
        ev_share_pct=_one_place(aggregate_electric_share(usable)),
    )


def _one_place(value: Decimal | None) -> Decimal | None:
    return value.quantize(_TENTH, rounding=ROUND_HALF_UP) if value is not None else None


def percent_change_of(now: Decimal | None, before: Decimal | None) -> Decimal | None:
    """
    The change of ``now`` against ``before`` in whole percent, HALF_UP; ``None`` without a positive
    base.
    """
    if now is None or before is None or before <= _ZERO:
        return None
    return ((now - before) * _HUNDRED / before).quantize(Decimal(1), rounding=ROUND_HALF_UP)


@dataclass(frozen=True)
class SummaryNothing:
    """No summary is wanted: the switch is off."""


@dataclass(frozen=True)
class SummaryWait:
    """Quiet hours: nothing is sent or marked; the next daytime synchronization sends it."""


@dataclass(frozen=True)
class SummaryBaseline:
    """First run or a changed period: ``key`` is remembered without a notification."""

    key: str


@dataclass(frozen=True)
class SummaryPost:
    """The last completed period has not been sent yet and it is daytime."""

    window: SummaryWindow


SummaryDecision = SummaryNothing | SummaryWait | SummaryBaseline | SummaryPost


def summary_decision(
    period: SummaryPeriod | None, last_key: str | None, today: date, hour: int
) -> SummaryDecision:
    """
    The decision for one synchronization. Only the last completed period is ever sent; ``hour`` is
    the local hour of the phone.
    """
    if period is None:
        return SummaryNothing()
    window = last_completed_window(period, today)
    if last_key is None:
        return SummaryBaseline(window.key)
    if last_key == window.key:
        return SummaryNothing()
    if hour < SUMMARY_FROM_HOUR or hour >= SUMMARY_UNTIL_HOUR:
        return SummaryWait()
    return SummaryPost(window)
