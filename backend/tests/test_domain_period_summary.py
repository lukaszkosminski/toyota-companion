"""
Tests for app.domain.period_summary (PRD F54, SPEC §54); the names mirror core
PeriodSummaryOpsTest.kt.
"""

from datetime import UTC, date, datetime, timedelta, tzinfo
from decimal import Decimal
from zoneinfo import ZoneInfo

from app.domain import Trip, TripCost
from app.domain.period_summary import (
    SummaryBaseline,
    SummaryNothing,
    SummaryPeriod,
    SummaryPost,
    SummaryWait,
    last_completed_window,
    percent_change_of,
    period_totals,
    previous_window,
    summary_decision,
)

D = Decimal
WARSAW: tzinfo = ZoneInfo("Europe/Warsaw")


def trip(ident: str, start: str, km: str, fuel: str, ev: float | None = None) -> Trip:
    begin = datetime.fromisoformat(start.replace("Z", "+00:00")).astimezone(UTC)
    return Trip(ident, begin, begin + timedelta(seconds=900), float(km), D(fuel), ev)


def test_the_week_is_the_last_completed_monday_to_sunday() -> None:
    # Wednesday 2026-11-11: the week before the Monday 2026-11-09 is 2026-11-02 … 2026-11-08.
    w = last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 11))
    assert (w.from_, w.until, w.key) == (date(2026, 11, 2), date(2026, 11, 9), "week:2026-11-02")


def test_on_a_monday_the_week_that_just_ended_is_the_one_before_it() -> None:
    assert last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 9)).from_ == date(2026, 11, 2)
    # Sunday 2026-11-08 still belongs to the week that has not ended.
    assert last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 8)).from_ == date(2026, 10, 26)


def test_the_week_key_is_its_monday_so_the_year_boundary_needs_no_iso_week() -> None:
    # 2026-12-28 is a Monday; the week 2026-12-28 … 2027-01-03 ends on Sunday 2027-01-03 (ISO week
    # 53 of 2026, then week 1 of 2027).
    w = last_completed_window(SummaryPeriod.WEEK, date(2027, 1, 4))
    assert w.key == "week:2026-12-28"
    assert w.until == date(2027, 1, 4)
    assert last_completed_window(SummaryPeriod.WEEK, date(2027, 1, 3)).key == "week:2026-12-21"


def test_the_month_is_the_previous_calendar_month_and_crosses_the_year() -> None:
    w = last_completed_window(SummaryPeriod.MONTH, date(2026, 1, 5))
    assert (w.from_, w.until, w.key) == (date(2025, 12, 1), date(2026, 1, 1), "month:2025-12")
    assert last_completed_window(SummaryPeriod.MONTH, date(2026, 11, 1)).key == "month:2026-10"


def test_the_previous_window_has_the_same_length() -> None:
    week = last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 11))
    assert previous_window(week).from_ == date(2026, 10, 26)
    assert previous_window(week).until == date(2026, 11, 2)
    # In March the last completed month is February; the one before it is January.
    february = last_completed_window(SummaryPeriod.MONTH, date(2026, 3, 2))
    assert february.from_ == date(2026, 2, 1)
    january = previous_window(february)
    assert (january.from_, january.until) == (date(2026, 1, 1), date(2026, 2, 1))


def test_a_trip_belongs_to_the_local_day_of_its_start_in_the_zone_of_the_phone() -> None:
    # 2026-11-01 23:30 UTC is 2026-11-02 00:30 in Warsaw: the first trip of the week 2026-11-02 …
    # 08.
    w = last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 11))
    trips = [trip("a", "2026-11-01T23:30:00Z", "10", "0.6")]
    assert period_totals(trips, [], [], w, WARSAW).trips == 1
    assert period_totals(trips, [], [], w, UTC).trips == 0


def test_the_day_of_the_time_change_is_counted_once_in_its_week() -> None:
    # 2026-10-25 is the Sunday the clocks go back in Warsaw: 23:30 UTC on the 25th is 00:30 on the
    # Monday, so that trip is the next week.
    sunday_week = last_completed_window(SummaryPeriod.WEEK, date(2026, 10, 26))
    monday_week = last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 2))
    assert monday_week.from_ == date(2026, 10, 26)
    trips = [
        trip("sun", "2026-10-25T00:30:00Z", "10", "0.6"),
        trip("mon", "2026-10-25T23:30:00Z", "10", "0.6"),
    ]
    assert period_totals(trips, [], [], sunday_week, WARSAW).trips == 1
    assert period_totals(trips, [], [], monday_week, WARSAW).trips == 1


def test_the_totals_sum_km_and_litres_and_the_rate_is_the_sum_over_the_sum() -> None:
    w = last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 11))
    trips = [
        trip("a", "2026-11-03T08:00:00Z", "1", "0.1"),
        trip("b", "2026-11-04T08:00:00Z", "1", "0.1"),
        trip("c", "2026-11-05T08:00:00Z", "8", "0.6"),
    ]
    t = period_totals(trips, [], [], w, WARSAW)
    assert t.distance_km == D("10.0")
    assert t.fuel_l == D("0.8")
    assert t.l_per_100km == D("8.00")


def test_zero_litre_trips_count_and_broken_trips_do_not() -> None:
    w = last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 11))
    trips = [
        trip("electric", "2026-11-03T08:00:00Z", "10", "0"),
        trip("zero", "2026-11-04T08:00:00Z", "0", "0.5"),
        trip("negative", "2026-11-05T08:00:00Z", "5", "-0.1"),
    ]
    t = period_totals(trips, [], [], w, WARSAW)
    assert t.trips == 1
    assert t.l_per_100km == D("0.00")


def test_the_fuel_cost_is_none_without_a_price_and_the_expenses_are_added() -> None:
    w = last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 11))
    trips = [trip("a", "2026-11-03T08:00:00Z", "10", "0.6")]
    expenses = [(date(2026, 11, 4), D("17.10"))]
    unpriced = period_totals(trips, [], expenses, w, WARSAW)
    assert unpriced.fuel_cost_pln is None
    assert unpriced.cost_pln == D("17.10")
    priced = period_totals(
        trips, [TripCost("a", D("3.70"), D("6.20"), "r", "refuel")], expenses, w, WARSAW
    )
    assert priced.fuel_cost_pln == D("3.70")
    assert priced.cost_pln == D("20.80")


def test_the_electric_share_is_taken_from_the_window() -> None:
    w = last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 11))
    trips = [
        trip("a", "2026-11-03T08:00:00Z", "10", "0.6", ev=4.0),
        trip("b", "2026-11-04T08:00:00Z", "10", "0.6"),
    ]
    # Share over the trips that have electric data: 4 km of 10 km is 40 %; the trip without data is
    # not in the share.
    assert period_totals(trips, [], [], w, WARSAW).ev_share_pct == D("40.0")
    assert period_totals([], [], [(date(2026, 11, 4), D("5"))], w, WARSAW).ev_share_pct is None


def test_a_period_with_nothing_is_empty_and_one_with_an_expense_is_not() -> None:
    w = last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 11))
    assert period_totals([], [], [], w, WARSAW).is_empty
    assert not period_totals([], [], [(date(2026, 11, 4), D("5"))], w, WARSAW).is_empty


def test_the_percent_change_needs_a_positive_base() -> None:
    assert percent_change_of(D("112"), D("100")) == D("12")
    assert percent_change_of(D("95"), D("100")) == D("-5")
    assert percent_change_of(D("10"), D("0")) is None
    assert percent_change_of(None, D("100")) is None


def test_off_is_nothing_and_a_first_run_is_a_baseline() -> None:
    assert isinstance(summary_decision(None, None, date(2026, 11, 11), 10), SummaryNothing)
    assert summary_decision(SummaryPeriod.WEEK, None, date(2026, 11, 11), 10) == SummaryBaseline(
        "week:2026-11-02"
    )


def test_the_same_period_is_sent_once() -> None:
    assert isinstance(
        summary_decision(SummaryPeriod.WEEK, "week:2026-11-02", date(2026, 11, 11), 10),
        SummaryNothing,
    )


def test_quiet_hours_wait_and_the_daytime_hours_post_the_last_completed_period() -> None:
    assert isinstance(
        summary_decision(SummaryPeriod.WEEK, "week:2026-10-26", date(2026, 11, 11), 7), SummaryWait
    )
    assert isinstance(
        summary_decision(SummaryPeriod.WEEK, "week:2026-10-26", date(2026, 11, 11), 22), SummaryWait
    )
    post = summary_decision(SummaryPeriod.WEEK, "week:2026-10-26", date(2026, 11, 11), 8)
    assert post == SummaryPost(last_completed_window(SummaryPeriod.WEEK, date(2026, 11, 11)))
    assert isinstance(
        summary_decision(SummaryPeriod.WEEK, "week:2026-10-26", date(2026, 11, 11), 21), SummaryPost
    )


def test_only_the_last_completed_period_is_sent_not_the_missed_ones() -> None:
    # Last report 2026-10-12; the phone was off for weeks: the decision names 2026-11-02 only.
    decision = summary_decision(SummaryPeriod.WEEK, "week:2026-10-12", date(2026, 11, 11), 12)
    assert isinstance(decision, SummaryPost)
    assert decision.window.key == "week:2026-11-02"
