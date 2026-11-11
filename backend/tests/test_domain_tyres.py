"""Tests for app.domain.tyres (PRD F56, SPEC §56); the names mirror core TyreOpsTest.kt."""

from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

from app.domain import Trip
from app.domain.tyres import (
    TYRE_MAX_KM,
    TyreChange,
    TyreDue,
    TyreSet,
    TyreThreshold,
    check_tyre_change,
    due_tyre_reminders,
    format_tyre_changes,
    format_tyre_reminder_keys,
    format_tyre_thresholds,
    parse_tyre_changes,
    parse_tyre_number,
    parse_tyre_reminder_keys,
    parse_tyre_thresholds,
    tyre_keys_without_change,
    tyre_reminder_key,
    tyre_usage,
    without_last_tyre_change,
)

D = Decimal
WARSAW = ZoneInfo("Europe/Warsaw")


def trip(ident: str, start: str, km: str) -> Trip:
    begin = datetime.fromisoformat(start.replace("Z", "+00:00")).astimezone(UTC)
    return Trip(ident, begin, begin + timedelta(seconds=900), float(km), D("0.5"), None)


def test_parse_skips_broken_entries_and_keeps_the_first_of_a_date() -> None:
    parsed = parse_tyre_changes(
        "2026-04-15|S|1234;junk;2026-04-15|W|;2025-10-20|W|;2026-13-01|S|;2026-01-02|X|"
    )
    assert [c.day for c in parsed] == [date(2025, 10, 20), date(2026, 4, 15)]
    assert parsed[0].set is TyreSet.WINTER
    assert parsed[-1].start_odometer_km == 1234


def test_parse_garbage_is_no_changes() -> None:
    assert parse_tyre_changes(None) == []
    assert parse_tyre_changes("") == []
    assert parse_tyre_changes("!!!;;") == []
    assert parse_tyre_changes("2026-04-15|S|-5") == []


def test_format_round_trips() -> None:
    changes = [
        TyreChange(date(2026, 4, 15), TyreSet.SUMMER, 1234),
        TyreChange(date(2025, 10, 20), TyreSet.WINTER, None),
    ]
    assert parse_tyre_changes(format_tyre_changes(changes)) == sorted(changes, key=lambda c: c.day)


def test_thresholds_parse_and_garbage_is_absent() -> None:
    t = parse_tyre_thresholds("S:6:15000;W:x:-1")
    assert t[TyreSet.SUMMER] == TyreThreshold(6, 15000)
    assert t[TyreSet.WINTER] == TyreThreshold(None, None)
    assert parse_tyre_thresholds(None)[TyreSet.SUMMER] == TyreThreshold(None, None)
    assert format_tyre_thresholds(t) == "S:6:15000;W::"


def test_reminder_keys_parse_and_format_back() -> None:
    assert parse_tyre_reminder_keys(" a,b,, ") == {"a", "b"}
    assert parse_tyre_reminder_keys(None) == set()
    assert format_tyre_reminder_keys({"b", "a"}) == "a,b"


def test_usage_assigns_a_trip_to_the_set_of_its_start_date() -> None:
    changes = [
        TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None),
        TyreChange(date(2026, 10, 20), TyreSet.WINTER, None),
    ]
    usage = tyre_usage(
        changes,
        [trip("a", "2026-04-20T08:00:00Z", "10.0"), trip("b", "2026-10-25T08:00:00Z", "20.0")],
        WARSAW,
    )
    assert usage.sets[0].trips == 1
    assert usage.sets[0].km == D("10.0")
    assert usage.sets[1].km == D("20.0")


def test_a_trip_on_the_change_day_belongs_to_the_new_set_in_the_zone_of_the_phone() -> None:
    # 2026-10-19 23:30 UTC is 2026-10-20 00:30 in Warsaw: the first day of the winter set.
    changes = [
        TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None),
        TyreChange(date(2026, 10, 20), TyreSet.WINTER, None),
    ]
    trips = [trip("a", "2026-10-19T23:30:00Z", "7.0")]
    assert tyre_usage(changes, trips, WARSAW).sets[1].trips == 1
    assert tyre_usage(changes, trips, UTC).sets[1].trips == 0


def test_trips_before_the_first_change_are_counted_apart() -> None:
    changes = [TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None)]
    usage = tyre_usage(
        changes,
        [trip("old", "2026-03-01T08:00:00Z", "30.0"), trip("new", "2026-04-16T08:00:00Z", "5.0")],
        WARSAW,
    )
    assert usage.before_first_trips == 1
    assert usage.before_first_km == D("30.0")
    assert usage.sets[0].km == D("5.0")


def test_the_set_on_now_runs_to_today_and_the_earlier_one_ends_at_the_next_change() -> None:
    changes = [
        TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None),
        TyreChange(date(2026, 10, 20), TyreSet.WINTER, None),
    ]
    usage = tyre_usage(changes, [], WARSAW)
    assert usage.sets[0].until == date(2026, 10, 20)
    assert usage.sets[1].until is None


def test_no_fitting_means_no_usage_and_no_reminder() -> None:
    usage = tyre_usage([], [trip("a", "2026-04-20T08:00:00Z", "10.0")], WARSAW)
    assert usage.sets == ()
    assert usage.before_first_trips == 1
    assert due_tyre_reminders(usage, parse_tyre_thresholds("S:1:1"), date(2026, 12, 1)) == []


def test_reminder_months_count_full_months_from_the_change() -> None:
    usage = tyre_usage([TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None)], [], WARSAW)
    thresholds = parse_tyre_thresholds("S:6:")
    assert due_tyre_reminders(usage, thresholds, date(2026, 10, 14)) == []
    due = due_tyre_reminders(usage, thresholds, date(2026, 10, 15))
    assert [r.due for r in due] == [TyreDue.MONTHS]
    assert due[0].months == 6


def test_reminder_km_uses_the_archive_of_the_set() -> None:
    changes = [
        TyreChange(date(2025, 10, 20), TyreSet.WINTER, None),
        TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None),
    ]
    trips = [
        trip("w", "2026-01-10T08:00:00Z", "9000.0"),
        trip("s", "2026-05-02T08:00:00Z", "6000.0"),
    ]
    usage = tyre_usage(changes, trips, WARSAW)
    assert due_tyre_reminders(usage, parse_tyre_thresholds("S::7000"), date(2026, 6, 1)) == []
    due = due_tyre_reminders(usage, parse_tyre_thresholds("S::5000"), date(2026, 6, 1))
    assert due[0].due is TyreDue.KM


def test_no_threshold_means_no_reminder() -> None:
    usage = tyre_usage(
        [TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None)],
        [trip("a", "2026-04-20T08:00:00Z", "99999.0")],
        WARSAW,
    )
    assert due_tyre_reminders(usage, parse_tyre_thresholds("W:1:1"), date(2027, 1, 1)) == []
    assert due_tyre_reminders(usage, parse_tyre_thresholds(None), date(2027, 1, 1)) == []


def test_only_the_set_on_now_is_reminded() -> None:
    changes = [
        TyreChange(date(2025, 10, 20), TyreSet.WINTER, None),
        TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None),
    ]
    usage = tyre_usage(changes, [], WARSAW)
    assert due_tyre_reminders(usage, parse_tyre_thresholds("W:1:"), date(2026, 6, 1)) == []


def test_reminder_key_is_per_set_and_fitting_day_and_kind() -> None:
    usage = tyre_usage([TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None)], [], WARSAW)
    due = due_tyre_reminders(usage, parse_tyre_thresholds("S:6:"), date(2026, 10, 15))[0]
    assert tyre_reminder_key(due) == "SUMMER:2026-04-15:MONTHS"


def test_a_future_fitting_is_not_due_yet() -> None:
    usage = tyre_usage([TyreChange(date(2026, 12, 1), TyreSet.SUMMER, None)], [], WARSAW)
    assert due_tyre_reminders(usage, parse_tyre_thresholds("S:1:"), date(2026, 11, 11)) == []


def test_a_new_fitting_must_be_today_or_earlier_and_after_the_last_one() -> None:
    existing = [TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None)]
    assert check_tyre_change(existing, date(2026, 11, 12), date(2026, 11, 11)) == "future"
    assert check_tyre_change(existing, date(2026, 4, 15), date(2026, 11, 11)) == "duplicate"
    assert check_tyre_change(existing, date(2026, 3, 1), date(2026, 11, 11)) == "before_last"
    assert check_tyre_change(existing, date(2026, 10, 20), date(2026, 11, 11)) == "ok"


def test_typed_numbers_are_checked_against_the_maximum() -> None:
    assert parse_tyre_number(" 15000 ", TYRE_MAX_KM) == 15000
    assert parse_tyre_number("abc", TYRE_MAX_KM) is None
    assert parse_tyre_number("300001", TYRE_MAX_KM) is None


def test_removing_removes_only_the_newest_fitting() -> None:
    changes = [
        TyreChange(date(2026, 10, 20), TyreSet.WINTER, None),
        TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None),
    ]
    assert [c.day for c in without_last_tyre_change(changes)] == [date(2026, 4, 15)]
    assert without_last_tyre_change([]) == []


def test_removing_a_change_forgets_only_its_own_reminder_keys() -> None:
    keys = {"SUMMER:2026-04-15:MONTHS", "SUMMER:2026-04-15:KM", "WINTER:2026-10-20:KM"}
    removed = TyreChange(date(2026, 4, 15), TyreSet.SUMMER, None)
    assert tyre_keys_without_change(keys, removed) == {"WINTER:2026-10-20:KM"}
    assert (
        tyre_keys_without_change(keys, TyreChange(date(2026, 4, 15), TyreSet.WINTER, None)) == keys
    )
