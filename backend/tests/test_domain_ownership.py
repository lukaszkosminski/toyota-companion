"""Tests for app.domain.ownership: ownership_cost, ownership_months (PRD F38, SPEC §39)."""

from decimal import Decimal

import pytest

from app.domain import ownership_cost, ownership_months

D = Decimal


def test_fuel_and_expenses_add_up_per_km_and_month() -> None:
    cost = ownership_cost(D("1239.60"), D("4807.0"), D("1500.00"), 8)
    assert cost is not None
    assert cost.total_pln == D("2739.60")
    assert cost.fuel_pln == D("1239.60")
    assert cost.expenses_pln == D("1500.00")
    assert cost.per_km_pln == D("0.57")
    assert cost.per_100km_pln == D("56.99")
    assert cost.per_month_pln == D("342.45")
    assert cost.fuel_share_pct == D("45")


def test_without_expenses_it_is_the_fuel_cost() -> None:
    cost = ownership_cost(D("100.00"), 400.0, D("0"), 2)
    assert cost is not None
    assert cost.total_pln == D("100.00")
    assert cost.per_100km_pln == D("25.00")
    assert cost.fuel_share_pct == D("100")


def test_without_a_fuel_price_only_expenses_count_and_no_share() -> None:
    cost = ownership_cost(None, 400.0, D("250.50"), 3)
    assert cost is not None
    assert cost.total_pln == D("250.50")
    assert cost.fuel_pln == D("0")
    assert cost.fuel_share_pct is None
    assert cost.per_month_pln == D("83.50")


def test_nothing_to_count_is_none() -> None:
    assert ownership_cost(None, 400.0, D("0"), 3) is None
    assert ownership_cost(D("0"), 400.0, D("0"), 3) is None


def test_no_distance_and_no_months_leave_the_rates_out() -> None:
    cost = ownership_cost(None, 0, D("99.99"), 0)
    assert cost is not None
    assert cost.per_km_pln is None
    assert cost.per_100km_pln is None
    assert cost.per_month_pln is None


def test_rounding_is_half_up() -> None:
    cost = ownership_cost(D("0.005"), 1, D("0"), 1)
    assert cost is not None
    assert cost.total_pln == D("0.01")
    cost = ownership_cost(D("10.00"), 3, D("0"), 3)
    assert cost is not None
    assert cost.per_km_pln == D("3.33")
    assert cost.per_month_pln == D("3.33")
    cost = ownership_cost(D("1.00"), 1, D("1.00"), 1)
    assert cost is not None
    assert cost.fuel_share_pct == D("50")
    cost = ownership_cost(D("1.00"), 1, D("2.00"), 1)
    assert cost is not None
    assert cost.fuel_share_pct == D("33")


def test_negative_expenses_are_rejected() -> None:
    with pytest.raises(ValueError):
        ownership_cost(D("1.00"), 1, D("-1.00"), 1)


def test_months_from_first_activity_to_december() -> None:
    assert ownership_months(2025, 4, 2026, 11) == 9
    assert ownership_months(2025, 1, 2026, 11) == 12


def test_months_in_the_current_year_end_this_month() -> None:
    assert ownership_months(2026, 4, 2026, 11) == 8
    assert ownership_months(2026, 11, 2026, 11) == 1


def test_months_of_a_future_year_or_a_later_first_month_are_zero() -> None:
    assert ownership_months(2027, 1, 2026, 11) == 0
    assert ownership_months(2026, 12, 2026, 11) == 0


def test_months_outside_one_to_twelve_are_rejected() -> None:
    with pytest.raises(ValueError):
        ownership_months(2026, 0, 2026, 11)
    with pytest.raises(ValueError):
        ownership_months(2026, 1, 2026, 13)
