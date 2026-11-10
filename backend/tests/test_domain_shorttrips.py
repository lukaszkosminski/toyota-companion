"""Tests for app.domain.shorttrips: length bands, excess and cold start (PRD F50, SPEC §50)."""

from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest

from app.domain import (
    LengthBand,
    StartKind,
    Trip,
    TripCost,
    length_band_of,
    short_trip_summary,
    trip_start_kinds,
)

D = Decimal
BASE = datetime(2026, 10, 1, 8, 0, tzinfo=UTC)


def drive(
    index: int,
    km: float,
    fuel: str,
    ev: float | None = None,
    gap_days: float = 1.0,
    trip_id: str | None = None,
) -> Trip:
    """A trip of ten minutes, ``gap_days`` after the previous one of the same helper index chain."""
    start = BASE + timedelta(days=index * gap_days)
    return Trip(trip_id or f"t{index}", start, start + timedelta(minutes=10), km, D(fuel), ev)


def many(count: int, km: float, fuel: str, first: int = 0, ev: float | None = None) -> list[Trip]:
    return [drive(first + i, km, fuel, ev) for i in range(count)]


def priced(trips: list[Trip], per_trip: str, source: str = "refuel") -> list[TripCost]:
    return [TripCost(t.id, D(per_trip), None, "r1", source) for t in trips]  # type: ignore[arg-type]


def band(summary, which: LengthBand):
    return next(row for row in summary.bands if row.band is which)


def test_band_edges_include_the_lower_bound() -> None:
    assert length_band_of(0.1) is LengthBand.UNDER_2
    assert length_band_of(1.999) is LengthBand.UNDER_2
    assert length_band_of(2.0) is LengthBand.FROM_2
    assert length_band_of(4.999) is LengthBand.FROM_2
    assert length_band_of(5.0) is LengthBand.FROM_5
    assert length_band_of(9.999) is LengthBand.FROM_5
    assert length_band_of(10.0) is LengthBand.FROM_10
    assert length_band_of(24.999) is LengthBand.FROM_10
    assert length_band_of(25.0) is LengthBand.FROM_25
    assert length_band_of(D("300")) is LengthBand.FROM_25


def test_a_distance_below_the_shortest_trip_is_rejected() -> None:
    with pytest.raises(ValueError):
        length_band_of(0.099)
    with pytest.raises(ValueError):
        length_band_of(float("nan"))
    with pytest.raises(ValueError):
        length_band_of(float("inf"))


def test_broken_trips_are_left_out_and_counted() -> None:
    trips = [
        drive(0, 0.099, "0.01"),
        drive(1, float("nan"), "0.1"),
        drive(2, 5.0, "-0.1"),
        drive(3, 5.0, "0.3"),
    ]
    summary = short_trip_summary(trips, [])
    assert summary.left_out == 3
    assert summary.used_trips == 1
    assert [row.band for row in summary.bands] == [LengthBand.FROM_5]


def test_litres_per_100km_is_the_sum_over_the_sum_not_the_mean_of_the_rates() -> None:
    # 20.0, 6.67, 10.0, 10.0, 10.0 l/100 km: the mean of the rates is 11.33, the sum over the
    # sum is 10.00.
    trips = [
        drive(0, 0.5, "0.1"),
        drive(1, 1.5, "0.1"),
        drive(2, 1.0, "0.1"),
        drive(3, 1.0, "0.1"),
        drive(4, 1.0, "0.1"),
    ]
    row = band(short_trip_summary(trips, []), LengthBand.UNDER_2)
    assert row.l_per_100km == D("10.00")
    assert row.distance_km == D("5.0")
    assert row.fuel_l == D("0.500")


def test_a_trip_with_zero_litres_stays_in_the_sums() -> None:
    trips = many(5, 1.0, "0")
    row = band(short_trip_summary(trips, []), LengthBand.UNDER_2)
    assert row.trips == 5
    assert row.l_per_100km == D("0.00")
    trips = many(4, 1.0, "0.2") + [drive(4, 1.0, "0")]
    assert band(short_trip_summary(trips, []), LengthBand.UNDER_2).l_per_100km == D("16.00")


def test_a_band_shows_its_rates_from_five_trips() -> None:
    four = band(short_trip_summary(many(4, 3.0, "0.2"), []), LengthBand.FROM_2)
    assert four.trips == 4
    assert four.l_per_100km is None
    assert four.cost_per_100km is None
    assert four.ev_share_pct is None
    five = band(short_trip_summary(many(5, 3.0, "0.2"), []), LengthBand.FROM_2)
    assert five.l_per_100km == D("6.67")


def test_shares_of_trips_and_km_are_whole_percent_half_up() -> None:
    trips = [drive(0, 1.0, "0.1"), drive(1, 10.0, "0.4"), drive(2, 10.0, "0.4")]
    summary = short_trip_summary(trips, [])
    short = band(summary, LengthBand.UNDER_2)
    long = band(summary, LengthBand.FROM_10)
    assert (short.trip_share_pct, long.trip_share_pct) == (D("33"), D("67"))
    assert (short.km_share_pct, long.km_share_pct) == (D("5"), D("95"))
    assert summary.short_trips == 1
    assert summary.short_trip_share_pct == D("33")
    assert summary.short_km_share_pct == D("5")


def test_electric_share_is_cut_to_the_distance_of_the_trip() -> None:
    trips = [
        drive(0, 2.0, "0.1", ev=3.0),
        drive(1, 2.0, "0.1", ev=1.0),
        drive(2, 2.0, "0.1", ev=None),
        drive(3, 2.0, "0.1", ev=0.0),
        drive(4, 2.0, "0.1", ev=0.0),
    ]
    assert band(short_trip_summary(trips, []), LengthBand.FROM_2).ev_share_pct == D("30")
    assert band(short_trip_summary(many(5, 2.0, "0.1"), []), LengthBand.FROM_2).ev_share_pct is None


def test_cost_per_100km_is_only_from_priced_trips_and_never_zero() -> None:
    trips = many(5, 1.0, "0.1")
    assert band(short_trip_summary(trips, []), LengthBand.UNDER_2).cost_per_100km is None
    # An amount with the source "none" is not a price.
    assert (
        band(
            short_trip_summary(trips, priced(trips, "0.50", "none")), LengthBand.UNDER_2
        ).cost_per_100km
        is None
    )
    assert band(
        short_trip_summary(trips, priced(trips, "0.50")), LengthBand.UNDER_2
    ).cost_per_100km == D("50.00")
    # Unpriced trips add their km to the band, not to the price: 2.5 zl over 5 priced km.
    mixed = priced(trips[:5], "0.50")
    more = trips + [drive(9, 1.0, "0.1")]
    assert band(short_trip_summary(more, mixed), LengthBand.UNDER_2).cost_per_100km == D("50.00")


def test_the_reference_is_all_trips_from_10_km_together() -> None:
    trips = many(5, 10.0, "0.4") + many(5, 30.0, "1.2", first=5)
    summary = short_trip_summary(trips, [])
    assert summary.reference is not None
    assert summary.reference.trips == 10
    assert summary.reference.distance_km == D("200.0")
    assert summary.reference.l_per_100km == D("4.00")
    assert summary.reference.cost_per_100km is None
    only_short = short_trip_summary(many(12, 1.0, "0.1"), [])
    assert only_short.reference is None
    assert band(only_short, LengthBand.UNDER_2).excess_l is None


def test_the_excess_needs_ten_trips_in_the_band_and_in_the_reference() -> None:
    reference = many(10, 30.0, "1.2", first=20)
    assert (
        band(
            short_trip_summary(many(10, 1.0, "0.2") + reference, []), LengthBand.UNDER_2
        ).excess_l_per_100km
        is not None
    )
    assert (
        band(
            short_trip_summary(many(9, 1.0, "0.2") + reference, []), LengthBand.UNDER_2
        ).excess_l_per_100km
        is None
    )
    nine_reference = many(9, 30.0, "1.2", first=20)
    assert (
        band(
            short_trip_summary(many(10, 1.0, "0.2") + nine_reference, []), LengthBand.UNDER_2
        ).excess_l_per_100km
        is None
    )
    # The reference bands themselves have no excess.
    assert (
        band(
            short_trip_summary(many(10, 1.0, "0.2") + reference, []), LengthBand.FROM_25
        ).excess_l_per_100km
        is None
    )


def test_the_excess_over_the_reference_in_rate_and_litres() -> None:
    # Band: 10 trips of 2 km with 0.2 l = 10.00 l/100 km; reference: 10 trips of 30 km with 1.2
    # l = 4.00 l/100 km.
    trips = many(10, 2.0, "0.2") + many(10, 30.0, "1.2", first=20)
    row = band(short_trip_summary(trips, []), LengthBand.FROM_2)
    assert row.l_per_100km == D("10.00")
    assert row.excess_l_per_100km == D("6.00")
    # 2.0 l burnt - 4.00/100 * 20 km = 1.2 l more.
    assert row.excess_l == D("1.20")


def test_the_excess_in_litres_uses_the_unrounded_reference_rate() -> None:
    # The reference rate is 4.145 l/100 km (shown 4.15). From the rate 4.15 the excess would be
    # 0.17 l, from the true rate 0.18 l.
    reference = many(10, 30.0, "1.2435", first=20)
    band_trips = many(10, 1.99, "0.1")
    row = band(short_trip_summary(band_trips + reference, []), LengthBand.UNDER_2)
    assert row.l_per_100km == D("5.03")
    assert row.excess_l_per_100km == D("0.88")
    assert row.excess_l == D("0.18")


def test_the_excess_in_money_is_litres_times_the_price_of_the_band() -> None:
    short = many(10, 2.0, "0.2")
    reference = many(10, 30.0, "1.2", first=20)
    costs = priced(short, "1.50") + priced(reference, "7.20")
    row = band(short_trip_summary(short + reference, costs), LengthBand.FROM_2)
    # The band paid 15.00 zl for 2.0 l = 7.50 zl/l; 1.2 l more = 9.00 zl.
    assert row.excess_l == D("1.20")
    assert row.excess_pln == D("9.00")
    assert band(short_trip_summary(short + reference, []), LengthBand.FROM_2).excess_pln is None


def test_a_negative_excess_is_kept_with_its_sign() -> None:
    # Short trips on the battery only: 0 l against 4.00 l/100 km of the reference.
    trips = many(10, 2.0, "0") + many(10, 30.0, "1.2", first=20)
    row = band(short_trip_summary(trips, []), LengthBand.FROM_2)
    assert row.excess_l_per_100km == D("-4.00")
    assert row.excess_l == D("-0.80")


def test_the_total_sums_only_the_bands_with_an_excess() -> None:
    reference = many(10, 30.0, "1.2", first=40)
    under = many(10, 1.0, "0.2", first=0)
    from_two = many(10, 2.0, "0.2", first=10)
    from_five = many(9, 6.0, "0.5", first=20)
    summary = short_trip_summary(under + from_two + from_five + reference, [])
    # UNDER_2: 2.0 l - 4.00/100 * 10 km = 1.6 l; FROM_2: 1.2 l; FROM_5 has 9 trips: no excess,
    # not in the total.
    assert summary.total_excess_l == D("2.80")
    assert summary.total_excess_pln is None
    assert summary.short_trips == 29
    none = short_trip_summary(from_five + reference, [])
    assert none.total_excess_l is None


def test_the_total_in_money_needs_a_price_in_every_counted_band() -> None:
    reference = many(10, 30.0, "1.2", first=40)
    under = many(10, 1.0, "0.2", first=0)
    from_two = many(10, 2.0, "0.2", first=10)
    all_priced = priced(under, "1.50") + priced(from_two, "1.50") + priced(reference, "7.20")
    summary = short_trip_summary(under + from_two + reference, all_priced)
    # 1.6 l * 7.50 zl/l + 1.2 l * 7.50 zl/l = 12.00 + 9.00 zl
    assert summary.total_excess_pln == D("21.00")
    partly = priced(under, "1.50") + priced(reference, "7.20")
    assert short_trip_summary(under + from_two + reference, partly).total_excess_pln is None


def test_cold_and_warm_starts_follow_the_gap_since_the_previous_trip() -> None:
    first = datetime(2026, 10, 1, 8, 0, tzinfo=UTC)

    def after(gap: timedelta, ident: str, previous_end: datetime) -> Trip:
        return Trip(
            ident,
            previous_end + gap,
            previous_end + gap + timedelta(minutes=5),
            3.0,
            D("0.2"),
            None,
        )

    zero = Trip("a", first, first + timedelta(minutes=5), 3.0, D("0.2"), None)
    warm = after(timedelta(hours=2, minutes=59), "b", zero.end)
    cold = after(timedelta(hours=3), "c", warm.end)
    three_weeks = after(timedelta(days=21), "d", cold.end)
    hole = after(timedelta(days=21, seconds=1), "e", three_weeks.end)
    overlap = after(timedelta(minutes=-2), "f", hole.end)
    kinds = trip_start_kinds([zero, warm, cold, three_weeks, hole, overlap])
    assert kinds == {
        "a": StartKind.UNKNOWN,
        "b": StartKind.WARM,
        "c": StartKind.COLD,
        "d": StartKind.COLD,
        "e": StartKind.UNKNOWN,
        "f": StartKind.WARM,
    }


def test_the_previous_trip_is_any_trip_also_one_the_bands_leave_out() -> None:
    first = datetime(2026, 10, 1, 8, 0, tzinfo=UTC)
    tiny = Trip("tiny", first, first + timedelta(minutes=1), 0.05, D("0"), None)
    next_start = tiny.end + timedelta(hours=1)
    after = Trip("after", next_start, next_start + timedelta(minutes=5), 3.0, D("0.2"), None)
    assert trip_start_kinds([tiny, after])["after"] is StartKind.WARM
    # The tiny trip itself is not in the bands.
    assert short_trip_summary([tiny, after], []).left_out == 1


def test_start_kinds_do_not_depend_on_the_order_and_ties_break_by_id() -> None:
    a = drive(0, 3.0, "0.2", trip_id="a")
    b = drive(1, 3.0, "0.2", trip_id="b")
    same_start_1 = Trip(
        "x1",
        b.start + timedelta(days=1),
        b.start + timedelta(days=1, minutes=5),
        3.0,
        D("0.2"),
        None,
    )
    same_start_2 = Trip("x2", same_start_1.start, same_start_1.end, 3.0, D("0.2"), None)
    forward = trip_start_kinds([a, b, same_start_1, same_start_2])
    backward = trip_start_kinds([same_start_2, same_start_1, b, a])
    assert forward == backward
    assert forward["x1"] is StartKind.COLD
    # x2 follows x1 by (start, id) with an overlap of its whole length: warm.
    assert forward["x2"] is StartKind.WARM


def test_the_cold_split_needs_ten_trips_on_both_sides() -> None:
    cold = [drive(i * 2, 3.0, "0.30", gap_days=1.0) for i in range(10)]
    # Warm trips start 30 minutes after the cold one of the same index ended.
    warm = [
        Trip(
            f"w{i}",
            c.end + timedelta(minutes=30),
            c.end + timedelta(minutes=40),
            3.0,
            D("0.15"),
            None,
        )
        for i, c in enumerate(cold)
    ]
    row = band(short_trip_summary(cold + warm, []), LengthBand.FROM_2)
    assert row.cold_trips == 9 and row.warm_trips == 10 and row.unknown_start_trips == 1
    assert row.cold_split is None
    more = drive(20, 3.0, "0.30")
    row = band(short_trip_summary(cold + warm + [more], []), LengthBand.FROM_2)
    assert row.cold_trips == 10 and row.warm_trips == 10
    assert row.cold_split is not None
    assert row.cold_split.cold_l_per_100km == D("10.00")
    assert row.cold_split.warm_l_per_100km == D("5.00")
    assert row.cold_split.difference_l_per_100km == D("5.00")


def test_unknown_starts_are_in_the_band_but_not_in_the_split() -> None:
    trips = many(3, 3.0, "0.2")
    row = band(short_trip_summary(trips, []), LengthBand.FROM_2)
    assert row.trips == 3
    assert (row.cold_trips, row.warm_trips, row.unknown_start_trips) == (2, 0, 1)
    assert row.cold_split is None


def test_an_empty_list_or_only_broken_trips_gives_an_empty_summary() -> None:
    for trips in ([], [drive(0, 0.05, "0")]):
        summary = short_trip_summary(trips, [])
        assert summary.bands == ()
        assert summary.reference is None
        assert summary.short_trip_share_pct is None
        assert summary.short_km_share_pct is None
        assert summary.total_excess_l is None
        assert summary.used_trips == 0
    assert short_trip_summary([drive(0, 0.05, "0")], []).left_out == 1
