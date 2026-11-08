"""Total cost of ownership: fuel plus the other expenses (PRD F38, SPEC §39, ADR-020)."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

from .models import as_decimal, round_money

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)
_WHOLE = Decimal(1)


@dataclass(frozen=True)
class OwnershipCost:
    """What the car cost in a period, fuel and everything else (docs/SPEC.md §39).

    ``fuel_pln`` is the cost of the priced trips only (0 when none is priced, then
    ``fuel_share_pct`` is ``None``). Per-km values are ``None`` without distance,
    ``per_month_pln`` without months.
    """

    total_pln: Decimal
    fuel_pln: Decimal
    expenses_pln: Decimal
    per_km_pln: Decimal | None
    per_100km_pln: Decimal | None
    per_month_pln: Decimal | None
    fuel_share_pct: Decimal | None


def ownership_cost(
    fuel_cost: Decimal | None,
    distance_km: Decimal | float | int | str,
    expenses: Decimal,
    months: int,
) -> OwnershipCost | None:
    """Fuel plus expenses of one period; ``None`` when there is neither a fuel cost nor an expense.

    ``fuel_cost`` is ``None`` when no trip of the period has a price. Money is rounded
    to 0.01 zl and the fuel share to a whole percent, all ROUND_HALF_UP. Negative
    expenses are a programming error (the form only takes positive amounts).
    """
    expenses_pln = round_money(expenses)
    if expenses_pln < _ZERO:
        raise ValueError("expenses must not be negative")
    fuel_pln = round_money(fuel_cost) if fuel_cost is not None else None
    if (fuel_pln is None or fuel_pln <= _ZERO) and expenses_pln == _ZERO:
        return None
    total = (fuel_pln or _ZERO) + expenses_pln
    km = as_decimal(distance_km)
    per_km = round_money(total / km) if km > _ZERO else None
    per_100km = round_money(total * _HUNDRED / km) if km > _ZERO else None
    per_month = round_money(total / months) if months > 0 else None
    share = (
        (fuel_pln * _HUNDRED / total).quantize(_WHOLE, rounding=ROUND_HALF_UP)
        if fuel_pln is not None and total > _ZERO
        else None
    )
    return OwnershipCost(
        total_pln=total,
        fuel_pln=fuel_pln or _ZERO,
        expenses_pln=expenses_pln,
        per_km_pln=per_km,
        per_100km_pln=per_100km,
        per_month_pln=per_month,
        fuel_share_pct=share,
    )


def ownership_months(year: int, first_month: int, today_year: int, today_month: int) -> int:
    """Months of ``year`` the monthly average is over (docs/SPEC.md §39).

    From ``first_month`` (the first month of the year with a trip or an expense) to
    December, or to the current month in the current year; 0 for a future year. A car
    bought in April is not averaged over January-March.
    """
    if not 1 <= first_month <= 12 or not 1 <= today_month <= 12:
        raise ValueError("months are 1..12")
    if year > today_year:
        return 0
    last = today_month if year == today_year else 12
    return max(0, last - first_month + 1)
