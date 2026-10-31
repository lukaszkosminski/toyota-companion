"""Pure domain model for toyota-companion: no I/O, no pytoyoda, stdlib only."""

from .cost import compute_trip_cost, recompute_costs
from .economy import MIN_COMPARABLE_DISTANCE_KM, fuel_economy_intervals
from .models import (
    DEFAULT_TANK_CAPACITY_L,
    EconomyInterval,
    EconomyStatus,
    FuelReading,
    LitersSource,
    Refuel,
    RefuelStatus,
    Trip,
    TripCost,
    TripCostSource,
    round_consumption,
    round_distance,
    round_liters,
    round_money,
    round_percent,
    round_price,
)
from .refuel import (
    confirm_refuel,
    detect_refuel,
    dismiss_refuel,
    estimate_refuel_liters,
    tank_price_after_refuel,
)

__all__ = [
    "DEFAULT_TANK_CAPACITY_L",
    "MIN_COMPARABLE_DISTANCE_KM",
    "EconomyInterval",
    "EconomyStatus",
    "FuelReading",
    "LitersSource",
    "Refuel",
    "RefuelStatus",
    "Trip",
    "TripCost",
    "TripCostSource",
    "compute_trip_cost",
    "confirm_refuel",
    "detect_refuel",
    "dismiss_refuel",
    "estimate_refuel_liters",
    "fuel_economy_intervals",
    "recompute_costs",
    "round_consumption",
    "round_distance",
    "round_liters",
    "round_money",
    "round_percent",
    "round_price",
    "tank_price_after_refuel",
]
