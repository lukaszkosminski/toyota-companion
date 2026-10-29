"""Tests for app.domain.refuel: detect_refuel, confirm_refuel, dismiss_refuel."""

from dataclasses import FrozenInstanceError
from datetime import datetime
from decimal import Decimal

import pytest

from app.domain import (
    DEFAULT_TANK_CAPACITY_L,
    FuelReading,
    Refuel,
    confirm_refuel,
    detect_refuel,
    dismiss_refuel,
    tank_price_after_refuel,
)

TS_PREV = datetime(2026, 10, 1, 8, 0, 0)
TS_CUR = datetime(2026, 10, 1, 12, 0, 0)


def reading(ts: datetime, level: int, odo: float) -> FuelReading:
    return FuelReading(ts=ts, level_pct=level, odometer_km=odo)


def pending_refuel(**overrides) -> Refuel:
    base = dict(
        id="refuel-x",
        ts=TS_CUR,
        odometer_km=12345.0,
        level_before=15,
        level_after=90,
        liters=None,
        price_per_liter=None,
        total_cost=None,
        status="pending",
    )
    base.update(overrides)
    return Refuel(**base)


# ---------------------------------------------------------------- detect_refuel


class TestDetectRefuel:
    def test_jump_equal_to_min_detected(self):
        prev = reading(TS_PREV, 10, 100.0)
        cur = reading(TS_CUR, 18, 140.0)  # jump == 8 == default min
        refuel = detect_refuel(prev, cur)
        assert refuel is not None
        assert refuel.status == "pending"

    def test_detected_refuel_fields(self):
        prev = reading(TS_PREV, 15, 100.0)
        cur = reading(TS_CUR, 90, 140.5)
        refuel = detect_refuel(prev, cur)
        assert refuel.ts == cur.ts
        assert refuel.odometer_km == cur.odometer_km
        assert refuel.level_before == 15
        assert refuel.level_after == 90
        assert refuel.liters is None
        assert refuel.price_per_liter is None
        assert refuel.total_cost is None
        assert refuel.status == "pending"

    def test_jump_below_min_returns_none(self):
        prev = reading(TS_PREV, 10, 100.0)
        cur = reading(TS_CUR, 17, 140.0)  # jump 7 < 8
        assert detect_refuel(prev, cur) is None

    def test_no_jump_returns_none(self):
        prev = reading(TS_PREV, 60, 100.0)
        cur = reading(TS_CUR, 55, 140.0)  # level dropped (driving)
        assert detect_refuel(prev, cur) is None

    def test_custom_min_jump_pct(self):
        prev = reading(TS_PREV, 10, 100.0)
        cur = reading(TS_CUR, 17, 140.0)  # jump 7
        assert detect_refuel(prev, cur, min_jump_pct=7) is not None
        assert detect_refuel(prev, cur, min_jump_pct=8) is None

    def test_id_is_deterministic_from_cur_ts(self):
        prev = reading(TS_PREV, 10, 100.0)
        cur = reading(TS_CUR, 90, 140.0)
        first = detect_refuel(prev, cur)
        second = detect_refuel(prev, cur)
        assert first.id == second.id
        assert first.id == "refuel-2026-10-01T12:00:00Z"

    def test_id_uses_canonical_utc_for_aware_timestamp(self):
        from datetime import UTC

        prev = reading(datetime(2026, 10, 1, 10, 0, 0, tzinfo=UTC), 10, 100.0)
        cur = reading(datetime(2026, 10, 1, 12, 0, 0, tzinfo=UTC), 90, 140.0)
        refuel = detect_refuel(prev, cur)
        assert refuel is not None
        assert refuel.id == "refuel-2026-10-01T12:00:00Z"

    def test_id_keeps_millisecond_precision(self):
        from datetime import UTC

        prev = reading(datetime(2026, 10, 1, 10, 0, 0, tzinfo=UTC), 10, 100.0)
        cur = reading(datetime(2026, 10, 1, 12, 0, 0, 123000, tzinfo=UTC), 90, 140.0)
        refuel = detect_refuel(prev, cur)
        assert refuel is not None
        assert refuel.id == "refuel-2026-10-01T12:00:00.123Z"

    def test_cur_before_prev_raises(self):
        prev = reading(TS_CUR, 10, 100.0)
        cur = reading(TS_PREV, 90, 140.0)
        with pytest.raises(ValueError):
            detect_refuel(prev, cur)

    def test_cur_equal_prev_raises(self):
        prev = reading(TS_PREV, 10, 100.0)
        cur = reading(TS_PREV, 90, 140.0)
        with pytest.raises(ValueError):
            detect_refuel(prev, cur)

    def test_timestamp_check_wins_over_small_jump(self):
        # even without a refuel jump, non-increasing timestamps are invalid
        prev = reading(TS_CUR, 50, 100.0)
        cur = reading(TS_PREV, 50, 90.0)
        with pytest.raises(ValueError):
            detect_refuel(prev, cur)


# ---------------------------------------------------------------- confirm_refuel

D = Decimal


class TestConfirmRefuelCombinations:
    def test_price_only(self):
        out = confirm_refuel(pending_refuel(), price_per_liter=D("6.19"))
        assert out.status == "confirmed"
        assert out.price_per_liter == D("6.190")
        assert out.liters is None
        assert out.total_cost is None
        assert out.liters_source is None

    def test_price_and_liters_computes_total(self):
        out = confirm_refuel(pending_refuel(), price_per_liter=D("6.19"), liters=D("0.600"))
        assert out.status == "confirmed"
        # 6.19 * 0.60 = 3.714 -> 3.71 (ROUND_HALF_UP to 0.01)
        assert out.total_cost == D("3.71")
        assert out.price_per_liter == D("6.190")
        assert out.liters == D("0.600")
        assert out.liters_source == "receipt"

    def test_price_and_total_computes_liters(self):
        out = confirm_refuel(pending_refuel(), price_per_liter=D("6.19"), total_cost=D("3.71"))
        assert out.status == "confirmed"
        # 3.71 / 6.19 = 0.59935... -> 0.599 (ROUND_HALF_UP to 0.001)
        assert out.liters == D("0.599")
        assert out.total_cost == D("3.71")
        assert out.liters_source == "derived"

    def test_liters_and_total_computes_price(self):
        out = confirm_refuel(pending_refuel(), liters=D("0.600"), total_cost=D("3.71"))
        assert out.status == "confirmed"
        # 3.71 / 0.60 = 6.1833... -> 6.183
        assert out.price_per_liter == D("6.183")
        assert out.liters_source == "receipt"

    def test_filled_to_full_is_preserved(self):
        out = confirm_refuel(
            pending_refuel(),
            price_per_liter=D("6.19"),
            liters=D("32.250"),
            filled_to_full=True,
        )
        assert out.filled_to_full is True
        assert out.liters_source == "receipt"

    def test_explicit_estimate_source(self):
        out = confirm_refuel(
            pending_refuel(),
            price_per_liter=D("6.19"),
            liters=D("32.250"),
            liters_source="estimate",
        )
        assert out.liters_source == "estimate"

    def test_liters_source_without_liters_raises(self):
        with pytest.raises(ValueError):
            confirm_refuel(pending_refuel(), price_per_liter=D("6.19"), liters_source="receipt")

    def test_filled_to_full_without_liters_raises(self):
        with pytest.raises(ValueError):
            confirm_refuel(pending_refuel(), price_per_liter=D("6.19"), filled_to_full=True)

    def test_price_only_is_rounded_to_0001(self):
        out = confirm_refuel(pending_refuel(), price_per_liter=D("6.1949"))
        assert out.price_per_liter == D("6.195")

    def test_price_rounding_half_up(self):
        # 2.469 / 2 = 1.2345 -> 1.235 (ROUND_HALF_UP at 0.001)
        out = confirm_refuel(pending_refuel(), liters=D("2.000"), total_cost=D("2.469"))
        assert out.price_per_liter == D("1.235")

    def test_total_rounding_half_up(self):
        # 6.19 * 0.65 = 4.0235 -> 4.02? HALF_UP on 0.005 boundary: 4.0235 -> 4.02
        out = confirm_refuel(pending_refuel(), price_per_liter=D("6.19"), liters=D("0.650"))
        assert out.total_cost == D("4.02")
        # exact half: 2.245 * 1 = 2.245 -> 2.25
        out2 = confirm_refuel(pending_refuel(), price_per_liter=D("2.245"), liters=D("1.000"))
        assert out2.total_cost == D("2.25")


class TestConfirmRefuelErrors:
    def test_no_values_raises(self):
        with pytest.raises(ValueError):
            confirm_refuel(pending_refuel())

    def test_liters_only_raises(self):
        with pytest.raises(ValueError):
            confirm_refuel(pending_refuel(), liters=D("0.6"))

    def test_total_only_raises(self):
        with pytest.raises(ValueError):
            confirm_refuel(pending_refuel(), total_cost=D("3.71"))

    def test_all_three_raises(self):
        with pytest.raises(ValueError):
            confirm_refuel(
                pending_refuel(),
                price_per_liter=D("6.19"),
                liters=D("0.6"),
                total_cost=D("3.71"),
            )

    @pytest.mark.parametrize(
        "kwargs",
        [
            {"price_per_liter": D("0")},
            {"price_per_liter": D("-6.19")},
            {"price_per_liter": D("6.19"), "liters": D("0")},
            {"price_per_liter": D("6.19"), "liters": D("-0.5")},
            {"price_per_liter": D("6.19"), "total_cost": D("0")},
            {"price_per_liter": D("6.19"), "total_cost": D("-1")},
            {"liters": D("0.5"), "total_cost": D("0")},
            {"liters": D("0"), "total_cost": D("3.71")},
            {"liters": D("0.5"), "total_cost": D("-3.71")},
        ],
    )
    def test_non_positive_values_raise(self, kwargs):
        with pytest.raises(ValueError):
            confirm_refuel(pending_refuel(), **kwargs)


class TestConfirmRefuelImmutability:
    def test_original_object_unchanged(self):
        original = pending_refuel()
        confirm_refuel(original, price_per_liter=D("6.19"))
        assert original.status == "pending"
        assert original.price_per_liter is None
        assert original.liters is None
        assert original.total_cost is None

    def test_returns_new_object(self):
        original = pending_refuel()
        out = confirm_refuel(original, price_per_liter=D("6.19"))
        assert out is not original
        assert out.id == original.id
        assert out.ts == original.ts
        assert out.level_before == original.level_before
        assert out.level_after == original.level_after

    def test_refuel_is_frozen(self):
        original = pending_refuel()
        with pytest.raises(FrozenInstanceError):
            original.status = "confirmed"  # type: ignore[misc]


# ---------------------------------------------------------------- tank price (ADR-005)


class TestTankPriceAfterRefuel:
    def _confirmed(
        self, *, ts=TS_CUR, level_before=20, level_after=80, price="6.19", liters="25.800"
    ):
        return confirm_refuel(
            pending_refuel(
                ts=ts,
                level_before=level_before,
                level_after=level_after,
            ),
            price_per_liter=D(price),
            liters=D(liters),
        )

    def test_first_refuel_uses_own_price(self):
        refuel = self._confirmed(price="6.19")
        assert tank_price_after_refuel(refuel) == D("6.190")

    def test_weighted_average(self):
        first = self._confirmed(price="5.99")
        second = self._confirmed(price="6.19")
        # (8.6 * 5.99 + 25.8 * 6.19) / 34.4 = 6.140
        assert tank_price_after_refuel(first) == D("5.990")
        assert tank_price_after_refuel(second, D("5.990")) == D("6.140")

    def test_estimate_liters_are_used(self):
        refuel = confirm_refuel(
            pending_refuel(level_before=20, level_after=80),
            price_per_liter=D("6.19"),
            liters=D("25.800"),
            liters_source="estimate",
        )
        assert tank_price_after_refuel(refuel, D("5.990")) == D("6.140")

    def test_price_only_uses_sensor_liters(self):
        # Only the price is entered; added = (80% - 20%) * 43 l = 25.8 l.
        refuel = confirm_refuel(
            pending_refuel(level_before=20, level_after=80), price_per_liter=D("6.19")
        )
        assert tank_price_after_refuel(refuel, D("5.990")) == D("6.140")

    def test_price_only_without_level_jump_is_ignored(self):
        refuel = confirm_refuel(
            pending_refuel(level_before=50, level_after=50), price_per_liter=D("6.19")
        )
        assert tank_price_after_refuel(refuel, D("5.990")) is None

    def test_weighted_average_stays_between_prices(self):
        # Receipt (30 l) disagrees with the sensor jump (25.8 l): the average is
        # over the fuel that mixed, (8.6 * 5.99 + 30 * 6.19) / 38.6 = 6.145.
        refuel = self._confirmed(price="6.19", liters="30")
        result = tank_price_after_refuel(refuel, D("5.990"))
        assert result == D("6.145")
        assert result is not None and D("5.99") <= result <= D("6.19")

    def test_invalid_level_after_is_ignored(self):
        refuel = self._confirmed(level_after=0)
        assert tank_price_after_refuel(refuel) is None

    def test_invalid_level_before_is_ignored(self):
        refuel = self._confirmed(level_before=101)
        assert tank_price_after_refuel(refuel) is None

    def test_non_confirmed_is_ignored(self):
        assert tank_price_after_refuel(pending_refuel()) is None

    def test_zero_previous_price_falls_back_to_own_price(self):
        refuel = self._confirmed(price="6.19")
        assert tank_price_after_refuel(refuel, D("0")) == D("6.190")

    def test_empty_tank_before_refuel_uses_own_price(self):
        refuel = self._confirmed(level_before=0, price="6.19")
        assert tank_price_after_refuel(refuel, D("5.990")) == D("6.190")

    def test_capacity_must_be_positive(self):
        refuel = self._confirmed()
        with pytest.raises(ValueError):
            tank_price_after_refuel(refuel, capacity_l=0)

    def test_default_capacity_is_43_liters(self):
        assert DEFAULT_TANK_CAPACITY_L == D("43")


# ---------------------------------------------------------------- dismiss_refuel


class TestDismissRefuel:
    def test_sets_dismissed(self):
        out = dismiss_refuel(pending_refuel())
        assert out.status == "dismissed"

    def test_other_fields_preserved(self):
        original = pending_refuel(price_per_liter=D("6.190"))
        out = dismiss_refuel(original)
        assert out is not original
        assert out.id == original.id
        assert out.price_per_liter == D("6.190")

    def test_original_unchanged(self):
        original = pending_refuel()
        dismiss_refuel(original)
        assert original.status == "pending"


def test_confirm_value_rounding_to_zero_raises_value_error():
    from datetime import datetime
    from decimal import Decimal

    import pytest

    from app.domain import Refuel, confirm_refuel

    r = Refuel("x", datetime(2026, 1, 1), 1.0, 10, 50, None, None, None, "pending")
    with pytest.raises(ValueError):
        confirm_refuel(r, liters=Decimal("0.0004"), total_cost=Decimal("10"))
