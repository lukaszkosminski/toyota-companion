"""Tests for app.domain.planned: the trip cost calculator (PRD F52, SPEC §52); names mirror core
PlannedTripOpsTest.kt."""

from decimal import Decimal

from app.domain.planned import (
    PlannedBadKm,
    PlannedRange,
    PlannedTooFew,
    PlanPopulation,
    PlanSample,
    TrafficClass,
    most_driven_band,
    planned_trip_cost,
)

D = Decimal


def sample(km: str, fuel: str, band: TrafficClass = TrafficClass.FLOWING) -> PlanSample:
    return PlanSample(D(km), D(fuel), band)


def ten_trips() -> list[PlanSample]:
    """Ten trips of 10 km with rates 4.0, 4.5 … 8.5 l/100 km: the quartiles are 5.0 and 7.0."""
    return [sample("10", str(D(40 + 5 * i) / D(100))) for i in range(10)]


def twelve_trips(km: str = "10") -> list[PlanSample]:
    """Twelve trips of 10 km with rates 1.0 … 12.0: the quartiles are 3.0 (index 2) and 9.0 (index
    8)."""
    return [sample(km, str(D(i) / D(10))) for i in range(1, 13)]


def rng(result: object) -> PlannedRange:
    assert isinstance(result, PlannedRange), result
    return result


def test_below_two_km_is_no_sample() -> None:
    samples = [sample("10", "0.5") for _ in range(9)] + [sample("1.5", "0.1") for _ in range(5)]
    assert planned_trip_cost(samples, D("10"), TrafficClass.FLOWING, None) == PlannedTooFew(9)


def test_fewer_than_ten_trips_of_the_kind_give_no_range() -> None:
    assert planned_trip_cost([], D("10"), TrafficClass.FLOWING, None) == PlannedTooFew(0)
    slow = [sample("10", "0.5", TrafficClass.SLOW) for _ in range(10)]
    assert planned_trip_cost(slow, D("10"), TrafficClass.FLOWING, None) == PlannedTooFew(0)


def test_ten_trips_give_a_range() -> None:
    r = rng(planned_trip_cost(ten_trips(), D("10"), TrafficClass.FLOWING, None))
    assert (r.trips, r.kind_trips, r.population) == (10, 10, PlanPopulation.SIMILAR_LENGTH)


def test_quartiles_by_index_of_sorted_rates() -> None:
    ten = rng(planned_trip_cost(ten_trips(), D("10"), TrafficClass.FLOWING, None))
    assert (ten.litres_low, ten.litres_high) == (D("0.5"), D("0.7"))
    twelve = rng(planned_trip_cost(twelve_trips(), D("10"), TrafficClass.FLOWING, None))
    assert (twelve.litres_low, twelve.litres_high) == (D("0.3"), D("0.9"))


def test_litres_round_to_a_tenth_half_up() -> None:
    # 15 km: 15 * 3.0 / 100 = 0.45 -> 0.5 and 15 * 9.0 / 100 = 1.35 -> 1.4.
    r = rng(planned_trip_cost(twelve_trips(), D("15"), TrafficClass.FLOWING, None))
    assert (r.litres_low, r.litres_high) == (D("0.5"), D("1.4"))


def test_money_is_whole_zloty_half_up() -> None:
    # 0.5 l at 5.00 zl is 2.50 zl -> 3 (half up, not to even); 0.7 l at 5.00 zl is 3.50 zl -> 4.
    r = rng(planned_trip_cost(ten_trips(), D("10"), TrafficClass.FLOWING, D("5.00")))
    assert (r.cost_low, r.cost_high) == (D("3"), D("4"))


def test_without_a_price_there_is_no_cost_and_the_litres_stay() -> None:
    r = rng(planned_trip_cost(ten_trips(), D("10"), TrafficClass.FLOWING, None))
    assert (r.cost_low, r.cost_high) is not None and r.cost_low is None and r.cost_high is None
    assert r.litres_low == D("0.5")


def test_zero_litre_trips_stay_in_the_population() -> None:
    zeros = [sample("10", "0") for _ in range(10)]
    r = rng(planned_trip_cost(zeros, D("10"), TrafficClass.FLOWING, D("6.00")))
    assert r.trips == 10
    assert r.litres_low == D("0.0")
    assert r.cost_high == D("0")


def test_the_similar_length_population_is_used_when_it_has_ten_trips() -> None:
    samples = ten_trips() + [sample("30", "1.0") for _ in range(10)]
    r = rng(planned_trip_cost(samples, D("10"), TrafficClass.FLOWING, None))
    assert (r.population, r.trips, r.kind_trips) == (PlanPopulation.SIMILAR_LENGTH, 10, 20)
    assert r.litres_low == D("0.5")


def test_the_whole_kind_is_used_when_fewer_than_ten_trips_are_of_a_similar_length() -> None:
    samples = [sample("30", "0.3") for _ in range(12)]
    r = rng(planned_trip_cost(samples, D("10"), TrafficClass.FLOWING, None))
    assert (r.population, r.trips, r.kind_trips) == (PlanPopulation.WHOLE_KIND, 12, 12)
    assert r.litres_low == D("0.1")


def test_a_planned_distance_outside_the_limits_is_rejected() -> None:
    assert planned_trip_cost(ten_trips(), D("0"), TrafficClass.FLOWING, None) == PlannedBadKm()
    assert planned_trip_cost(ten_trips(), D("-1"), TrafficClass.FLOWING, None) == PlannedBadKm()
    assert planned_trip_cost(ten_trips(), D("5001"), TrafficClass.FLOWING, None) == PlannedBadKm()
    assert isinstance(
        planned_trip_cost(ten_trips(), D("5000"), TrafficClass.FLOWING, None), PlannedRange
    )


def test_the_default_kind_is_the_one_with_the_most_trips() -> None:
    samples = (
        [sample("10", "0.5", TrafficClass.SLOW) for _ in range(3)]
        + [sample("10", "0.5", TrafficClass.CITY) for _ in range(5)]
        + [sample("10", "0.5", TrafficClass.FLOWING) for _ in range(5)]
    )
    assert most_driven_band(samples) is TrafficClass.CITY
    assert most_driven_band([]) is None
