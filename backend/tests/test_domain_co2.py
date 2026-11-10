"""Tests for app.domain.co2: co2_kg (PRD F51, SPEC §51)."""

from decimal import Decimal

import pytest

from app.domain import co2_kg

D = Decimal


def test_co2_of_one_litre_is_the_constant() -> None:
    assert co2_kg(D("1")) == D("2.3")


def test_co2_of_ten_litres_is_ten_times_the_constant() -> None:
    assert co2_kg(D("10")) == D("23.0")


def test_co2_rounds_to_one_tenth_of_a_kilogram_half_up() -> None:
    # 0.5 l * 2.3 = 1.15 kg, ROUND_HALF_UP to one decimal -> 1.2 kg.
    assert co2_kg(D("0.5")) == D("1.2")
    assert co2_kg(D("0.4")) == D("0.9")


def test_co2_tie_rounds_half_up_not_to_even() -> None:
    # 1.5 l * 2.3 = 3.45 kg: ROUND_HALF_UP gives 3.5, banker's rounding would give 3.4.
    assert co2_kg(D("1.5")) == D("3.5")
    assert co2_kg(D("2.5")) == D("5.8")


def test_co2_of_a_realistic_month() -> None:
    # 52.371 l * 2.3 = 120.4533 kg -> 120.5 kg (litres come in 0.001 l steps).
    assert co2_kg(D("52.371")) == D("120.5")
    assert co2_kg(D("1234.567")) == D("2839.5")


def test_co2_of_zero_litres_is_zero() -> None:
    assert co2_kg(D("0")) == D("0.0")


def test_negative_litres_are_rejected() -> None:
    with pytest.raises(ValueError):
        co2_kg(D("-0.1"))
    with pytest.raises(ValueError):
        co2_kg(D("-0.004"))
