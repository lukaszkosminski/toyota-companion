"""Tests for app.domain.ownership (PRD F38, F55, SPEC §39, §55).

Covers ownership_cost, ownership_months and value_loss_in_year.
"""

from datetime import date
from decimal import Decimal

import pytest

from app.domain import ownership_cost, ownership_months, value_loss_in_year

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


def test_year_before_purchase_is_zero() -> None:
    got = value_loss_in_year(D("10000"), date(2024, 1, 1), D("8000"), date(2025, 1, 1), 2023)
    assert got == D("0.00")


def test_year_of_purchase_counts_days_from_purchase() -> None:
    # 1000 zl over 366 days from 1 June 2023 to 1 June 2024; 2023 has 214 of those days.
    got = value_loss_in_year(D("1000"), date(2023, 6, 1), D("0"), date(2024, 6, 1), 2023)
    assert got == D("584.70")


def test_year_with_the_whole_period_is_the_loss() -> None:
    got = value_loss_in_year(D("10000"), date(2024, 1, 1), D("8000"), date(2025, 1, 1), 2024)
    assert got == D("2000.00")


def test_year_after_the_valuation_is_zero() -> None:
    got = value_loss_in_year(D("10000"), date(2024, 1, 1), D("8000"), date(2025, 1, 1), 2025)
    assert got == D("0.00")


def test_leap_february_enters_the_daily_rate() -> None:
    # 290 zl over the 29 days of February 2024: the leap day is part of the rate.
    got = value_loss_in_year(D("290"), date(2024, 2, 1), D("0"), date(2024, 3, 1), 2024)
    assert got == D("290.00")


def test_value_at_or_above_the_purchase_is_zero() -> None:
    got = value_loss_in_year(D("100000"), date(2024, 1, 1), D("120000"), date(2025, 1, 1), 2024)
    assert got == D("0.00")


def test_valuation_date_equal_to_purchase_is_zero() -> None:
    got = value_loss_in_year(D("100000"), date(2024, 1, 1), D("50000"), date(2024, 1, 1), 2024)
    assert got == D("0.00")


def test_invalid_valuation_dates_and_prices_are_rejected() -> None:
    with pytest.raises(ValueError):
        value_loss_in_year(D("0"), date(2024, 1, 1), D("0"), date(2025, 1, 1), 2024)
    with pytest.raises(ValueError):
        value_loss_in_year(D("1000"), date(2024, 1, 1), D("-1"), date(2025, 1, 1), 2024)
    with pytest.raises(ValueError):
        value_loss_in_year(D("1000"), date(2024, 1, 1), D("500"), date(2023, 12, 31), 2024)


def test_ownership_with_loss_keeps_cash_and_adds_its_own_rows() -> None:
    cost = ownership_cost(D("1239.60"), D("4807.0"), D("1500.00"), 8, value_loss_pln=D("1000"))
    assert cost is not None
    assert cost.total_pln == D("2739.60")
    assert cost.value_loss_pln == D("1000.00")
    assert cost.value_loss_per_km_pln == D("0.21")
    assert cost.total_with_loss_pln == D("3739.60")
    assert cost.total_with_loss_per_km_pln == D("0.78")


def test_ownership_with_only_the_loss_is_not_none() -> None:
    cost = ownership_cost(None, D("0"), D("0"), 0, value_loss_pln=D("123.45"))
    assert cost is not None
    assert cost.total_pln == D("0")
    assert cost.total_with_loss_pln == D("123.45")
    assert cost.per_km_pln is None


def test_negative_loss_is_rejected() -> None:
    with pytest.raises(ValueError):
        ownership_cost(None, D("0"), D("0"), 0, value_loss_pln=D("-1"))
