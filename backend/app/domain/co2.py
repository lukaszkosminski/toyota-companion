"""CO2 footprint of burned gasoline (PRD F51, SPEC §51)."""

from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal

from .models import as_decimal

#: Approximate CO2 emitted by burning one litre of gasoline: 2.3 kg (Natural Resources Canada,
#: "Learn the facts: Fuel consumption and CO2": "Burning 1 L of gasoline produces approximately
#: 2.3 kg of CO2"). Combustion only; the same value lives in `core/domain/Co2Ops.kt`.
CO2_KG_PER_LITER_GASOLINE = Decimal("2.3")

_ZERO = Decimal(0)
_TENTH = Decimal("0.1")


def co2_kg(fuel_l: Decimal | float | int | str) -> Decimal:
    """CO2 from ``fuel_l`` litres of gasoline in kilograms, one decimal, ROUND_HALF_UP.

    Mirrors ``co2Kg`` in ``core/domain/Co2Ops.kt`` (PRD F51, SPEC §51). Negative litres are a
    programming error.
    """
    litres = as_decimal(fuel_l)
    if litres < _ZERO:
        raise ValueError("fuel must not be negative")
    return (litres * CO2_KG_PER_LITER_GASOLINE).quantize(_TENTH, rounding=ROUND_HALF_UP)
