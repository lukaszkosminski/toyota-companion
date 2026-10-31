"""Tests for app.domain.tripstats: electric share (F11) and period stats (F10), SPEC §16-§17."""

from datetime import UTC, datetime, timedelta
from decimal import Decimal
from zoneinfo import ZoneInfo

from app.domain import (
    Trip,
    TripCost,
    aggregate_electric_share,
    electric_share,
    electric_trip_ranking,
    period_stats,
    season_of,
    year_over_year,
)

D = Decimal
WARSAW = ZoneInfo("Europe/Warsaw")


def utc(year: int, month: int, day: int, hour: int = 10, minute: int = 0) -> datetime:
    return datetime(year, month, day, hour, minute, tzinfo=UTC)


def trip(
    tid: str,
    km: float = 10.0,
    ev: float | None = 4.0,
    fuel: str = "0.500",
    start: datetime | None = None,
) -> Trip:
    begin = start or utc(2026, 10, 5)
    return Trip(
        id=tid,
        start=begin,
        end=begin + timedelta(minutes=30),
        distance_km=km,
        fuel_l=D(fuel),
        ev_distance_km=ev,
    )


def cost(tid: str, pln: str | None = "3.10", source: str = "refuel") -> TripCost:
    return TripCost(
        trip_id=tid,
        cost_pln=D(pln) if pln is not None else None,
        price_per_liter=D("6.190") if pln is not None else None,
        refuel_id=None,
        source=source,  # type: ignore[arg-type]
    )


class TestElectricShare:
    def test_share_of_trip_with_ev_distance(self) -> None:
        share = electric_share(trip("t", km=12.0, ev=7.35))
        assert share.share_pct == D("61.3")
        assert share.ev_distance_km == D("7.4")
        assert share.distance_km == D("12.0")
        assert share.inconsistent is False

    def test_share_of_trip_without_hdc_is_none(self) -> None:
        share = electric_share(trip("t", ev=None))
        assert share.share_pct is None
        assert share.ev_distance_km is None

    def test_share_never_exceeds_one_hundred(self) -> None:
        share = electric_share(trip("t", km=5.0, ev=5.04))
        assert share.share_pct == D("100")
        assert share.inconsistent is True

    def test_share_of_zero_distance_is_none(self) -> None:
        assert electric_share(trip("t", km=0.0, ev=0.0)).share_pct is None

    def test_aggregate_ignores_trips_without_ev(self) -> None:
        trips = [
            trip("a", km=10.0, ev=6.0),
            trip("b", km=30.0, ev=None),
            trip("c", km=10.0, ev=2.0),
        ]
        assert aggregate_electric_share(trips) == D("40.0")

    def test_aggregate_none_when_no_ev_data(self) -> None:
        assert aggregate_electric_share([trip("a", ev=None)]) is None


class TestElectricRanking:
    def test_ranking_orders_by_share_desc(self) -> None:
        trips = [trip("low", ev=1.0), trip("high", ev=9.0), trip("mid", ev=5.0)]
        assert [row.trip_id for row in electric_trip_ranking(trips).rows] == ["high", "mid", "low"]

    def test_ranking_skips_short_trips(self) -> None:
        ranking = electric_trip_ranking([trip("short", km=0.9, ev=0.9), trip("ok", km=2.0, ev=1.0)])
        assert [row.trip_id for row in ranking.rows] == ["ok"]
        assert ranking.too_short == 1

    def test_ranking_breaks_tie_by_distance_then_id(self) -> None:
        trips = [
            trip("b", km=10.0, ev=5.0),
            trip("long", km=20.0, ev=10.0),
            trip("a", km=10.0, ev=5.0),
        ]
        assert [row.trip_id for row in electric_trip_ranking(trips).rows] == ["long", "a", "b"]

    def test_ranking_limit(self) -> None:
        trips = [trip(f"t{index:02d}", ev=float(index % 10)) for index in range(25)]
        assert len(electric_trip_ranking(trips, limit=10).rows) == 10

    def test_ranking_counts_reported_separately(self) -> None:
        ranking = electric_trip_ranking(
            [trip("none", ev=None), trip("short", km=0.5, ev=0.1), trip("ok")]
        )
        assert (ranking.without_data, ranking.too_short, len(ranking.rows)) == (1, 1, 1)


class TestPeriodStats:
    def test_period_stats_year_sums_distance_and_cost(self) -> None:
        trips = [
            trip("a", km=100.0, ev=40.0, fuel="4.000", start=utc(2025, 6, 1)),
            trip("b", km=50.0, ev=10.0, fuel="3.000", start=utc(2025, 9, 1)),
            trip("c", km=20.0, ev=None, fuel="1.000", start=utc(2026, 2, 1)),
        ]
        costs = [cost("a", "24.76"), cost("b", "18.57"), cost("c", None, "none")]
        [y2025, y2026] = period_stats(trips, costs, WARSAW, "year")
        assert (y2025.year, y2025.season, y2025.trips) == (2025, None, 2)
        assert y2025.distance_km == D("150.0")
        assert y2025.fuel_l == D("7.000")
        assert y2025.l_per_100km == D("4.67")
        assert y2025.cost_pln == D("43.33")
        assert y2025.cost_per_100km == D("28.89")
        assert y2025.ev_share_pct == D("33.3")
        assert y2025.average_trip_km == D("75.0")
        assert y2026.trips_without_cost == 1

    def test_period_stats_cost_none_without_prices(self) -> None:
        [only] = period_stats([trip("a")], [cost("a", None, "none")], WARSAW, "year")
        assert only.cost_pln is None
        assert only.cost_per_100km is None
        assert only.trips_without_cost == 1

    def test_period_stats_price_per_km_none_when_cost_none(self) -> None:
        [only] = period_stats([trip("a")], [], WARSAW, "year")
        assert only.cost_per_100km is None
        assert only.l_per_100km == D("5.00")

    def test_default_price_counts_as_a_cost(self) -> None:
        [only] = period_stats([trip("a")], [cost("a", "3.10", "default")], WARSAW, "year")
        assert only.cost_pln == D("3.10")

    def test_season_boundaries_by_local_date(self) -> None:
        assert [season_of(month) for month in (12, 1, 2, 3, 6, 9, 11)] == [
            "winter",
            "winter",
            "winter",
            "spring",
            "summer",
            "autumn",
            "autumn",
        ]

    def test_december_belongs_to_the_winter_of_the_next_year(self) -> None:
        trips = [trip("dec", start=utc(2025, 12, 15)), trip("jan", start=utc(2026, 1, 15))]
        [winter] = period_stats(trips, [], WARSAW, "season")
        assert (winter.year, winter.season, winter.trips) == (2026, "winter", 2)

    def test_season_of_trip_at_midnight_in_other_zone(self) -> None:
        # 2026-02-28 23:30 UTC is already 1 March in Warsaw: spring, not winter.
        [spring] = period_stats([trip("t", start=utc(2026, 2, 28, 23, 30))], [], WARSAW, "season")
        assert spring.season == "spring"

    def test_periods_are_ordered_oldest_first(self) -> None:
        trips = [
            trip("s", start=utc(2026, 7, 1)),
            trip("w", start=utc(2026, 1, 1)),
            trip("a", start=utc(2025, 10, 1)),
        ]
        seasons = [(p.year, p.season) for p in period_stats(trips, [], WARSAW, "season")]
        assert seasons == [(2025, "autumn"), (2026, "winter"), (2026, "summer")]


class TestYearOverYear:
    def test_year_over_year_uses_comparable_window(self) -> None:
        trips = [
            trip("old_in", km=30.0, start=utc(2025, 2, 10)),
            trip("old_out", km=500.0, start=utc(2025, 11, 1)),
            trip("new", km=40.0, start=utc(2026, 3, 1)),
        ]
        result = year_over_year(trips, [], WARSAW)
        assert result is not None
        current, previous = result
        assert (current.year, current.distance_km) == (2026, D("40.0"))
        assert (previous.year, previous.distance_km) == (2025, D("30.0"))

    def test_year_over_year_none_when_no_overlap(self) -> None:
        assert year_over_year([trip("only", start=utc(2026, 3, 1))], [], WARSAW) is None

    def test_year_over_year_none_without_trips(self) -> None:
        assert year_over_year([], [], WARSAW) is None
