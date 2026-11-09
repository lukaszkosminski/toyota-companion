"""Tests for app.domain.budget: budget_progress (PRD F48, SPEC §49)."""

from decimal import Decimal

import pytest

from app.domain import budget_progress

D = Decimal


def test_fuel_and_expenses_add_up_against_the_limit() -> None:
    p = budget_progress(D("342.45"), D("250.50"), D("800"))
    assert p.limit_pln == D("800.00")
    assert p.fuel_pln == D("342.45")
    assert p.expenses_pln == D("250.50")
    assert p.spent_pln == D("592.95")
    assert p.remaining_pln == D("207.05")
    assert p.percent_used == 74
    assert p.over_budget is False


def test_nothing_spent_is_zero_percent_and_the_whole_limit_left() -> None:
    p = budget_progress(None, D("0"), D("500"))
    assert p.fuel_pln == D("0.00")
    assert p.spent_pln == D("0.00")
    assert p.remaining_pln == D("500.00")
    assert p.percent_used == 0
    assert p.over_budget is False


def test_a_missing_fuel_cost_counts_as_zero() -> None:
    p = budget_progress(None, D("120.00"), D("500"))
    assert p.spent_pln == D("120.00")
    assert p.percent_used == 24
    assert p.over_budget is False


def test_over_budget_keeps_a_negative_remaining_and_a_percent_above_hundred() -> None:
    p = budget_progress(D("90.00"), D("60.00"), D("100"))
    assert p.spent_pln == D("150.00")
    assert p.remaining_pln == D("-50.00")
    assert p.percent_used == 150
    assert p.over_budget is True


def test_exactly_the_limit_is_not_over_budget() -> None:
    p = budget_progress(D("60.00"), D("40.00"), D("100"))
    assert p.remaining_pln == D("0.00")
    assert p.percent_used == 100
    assert p.over_budget is False


def test_rounding_is_half_up() -> None:
    p = budget_progress(D("0.005"), D("0.005"), D("3"))
    assert p.fuel_pln == D("0.01")
    assert p.expenses_pln == D("0.01")
    assert p.spent_pln == D("0.02")
    assert p.percent_used == 1


def test_a_non_positive_limit_is_rejected() -> None:
    with pytest.raises(ValueError):
        budget_progress(None, D("1.00"), D("0"))
    with pytest.raises(ValueError):
        budget_progress(None, D("1.00"), D("-5"))


def test_a_limit_that_rounds_to_zero_is_rejected() -> None:
    # 0.004 > 0, but rounds to 0.00; dividing by it would be a zero divisor.
    with pytest.raises(ValueError):
        budget_progress(None, D("1.00"), D("0.004"))


def test_percent_rounds_half_up() -> None:
    assert budget_progress(D("1.00"), D("0"), D("200")).percent_used == 1
    assert budget_progress(D("199.00"), D("0"), D("200")).percent_used == 100
    assert budget_progress(D("201.00"), D("0"), D("200")).percent_used == 101


def test_a_limit_of_one_grosz_is_allowed() -> None:
    p = budget_progress(None, D("0.01"), D("0.01"))
    assert p.limit_pln == D("0.01")
    assert p.remaining_pln == D("0.00")
    assert p.percent_used == 100
    assert p.over_budget is False


def test_over_by_one_grosz_is_over_budget() -> None:
    p = budget_progress(None, D("100.01"), D("100"))
    assert p.remaining_pln == D("-0.01")
    assert p.percent_used == 100
    assert p.over_budget is True


def test_negative_expenses_or_fuel_are_rejected() -> None:
    with pytest.raises(ValueError):
        budget_progress(None, D("-1.00"), D("100"))
    with pytest.raises(ValueError):
        budget_progress(D("-1.00"), D("1.00"), D("100"))
    with pytest.raises(ValueError):
        budget_progress(None, D("-0.004"), D("100"))
    with pytest.raises(ValueError):
        budget_progress(D("-0.004"), D("1.00"), D("100"))
