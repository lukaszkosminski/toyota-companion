"""Monthly budget: fuel plus non-fuel expenses against one limit (PRD F48, SPEC §49)."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

from .models import as_decimal, round_money

_HUNDRED = Decimal(100)
_ZERO = Decimal(0)
_WHOLE = Decimal(1)


@dataclass(frozen=True)
class BudgetProgress:
    """One month against the owner's budget (docs/SPEC.md §49).

    ``fuel_pln`` is the sum of the priced trips of the month (0 when none is priced),
    ``expenses_pln`` the stored non-fuel expenses of the month. ``remaining_pln`` may be
    negative, ``percent_used`` is a whole percent and may exceed 100; ``over_budget`` is
    ``spent > limit``, so exactly the limit is not over it.
    """

    limit_pln: Decimal
    fuel_pln: Decimal
    expenses_pln: Decimal
    spent_pln: Decimal
    remaining_pln: Decimal
    percent_used: int
    over_budget: bool


def budget_progress(
    fuel_cost: Decimal | None,
    expenses: Decimal | float | int | str,
    limit_pln: Decimal | float | int | str,
) -> BudgetProgress:
    """The month's fuel plus expenses against ``limit_pln``.

    Money is rounded to 0.01 zl and the percent to a whole percent, all ROUND_HALF_UP.
    A missing ``fuel_cost`` counts as 0 (the month may still have expenses); a
    non-positive limit and negative amounts are a programming error -- the settings and
    the forms only accept positive values.
    """
    limit = as_decimal(limit_pln)
    if limit <= _ZERO:
        raise ValueError("limit must be positive")
    expenses_raw = as_decimal(expenses)
    if expenses_raw < _ZERO:
        raise ValueError("expenses must not be negative")
    fuel_raw = as_decimal(fuel_cost) if fuel_cost is not None else _ZERO
    if fuel_raw < _ZERO:
        raise ValueError("fuel cost must not be negative")
    # Check the raw values before rounding, so -0.004 is rejected too. After rounding,
    # a tiny limit could become 0.00 and the percent would divide by zero.
    limit_pln = round_money(limit)
    if limit_pln <= _ZERO:
        raise ValueError("limit must not round to zero")
    expenses_pln = round_money(expenses_raw)
    fuel_pln = round_money(fuel_raw)
    spent_pln = round_money(fuel_pln + expenses_pln)
    remaining_pln = round_money(limit_pln - spent_pln)
    percent_used = int((spent_pln * _HUNDRED / limit_pln).quantize(_WHOLE, rounding=ROUND_HALF_UP))
    return BudgetProgress(
        limit_pln=limit_pln,
        fuel_pln=fuel_pln,
        expenses_pln=expenses_pln,
        spent_pln=spent_pln,
        remaining_pln=remaining_pln,
        percent_used=percent_used,
        over_budget=spent_pln > limit_pln,
    )
