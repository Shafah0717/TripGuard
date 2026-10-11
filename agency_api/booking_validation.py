from __future__ import annotations

from datetime import datetime

from .db import Booking, Inquiry, utcnow

ACTIVE_BOOKING_STATUSES = {"CONFIRMED", "PENDING_CONFIRMATION", "COMPLETED"}

VALID_INQUIRY_STATUS_TRANSITIONS: dict[str, set[str]] = {
    "NEW": {"CONTACTED", "SLOT_OFFERED", "CLOSED"},
    "CONTACTED": {"SLOT_OFFERED", "CLOSED"},
    "SLOT_OFFERED": {"BOOKED", "CLOSED"},
    "BOOKED": {"CLOSED"},
    "CLOSED": set(),
}

VALID_BOOKING_TRANSITIONS: dict[str, set[str]] = {
    "CONFIRMED": {"CANCELLED", "COMPLETED"},
    "PENDING_CONFIRMATION": {"CONFIRMED", "CANCELLED"},
    "CANCELLED": set(),
    "COMPLETED": set(),
}


class SlotStatus:
    AVAILABLE = "AVAILABLE"
    FULL = "FULL"
    CLOSED = "CLOSED"
    UNPUBLISHED = "UNPUBLISHED"
    IN_PAST = "IN_PAST"


def confirmed_count(slot_id: int, bookings: list[Booking]) -> int:
    """Number of active bookings holding capacity on a slot."""
    return sum(
        1
        for b in bookings
        if b.slot_id == slot_id
        and b.status in ACTIVE_BOOKING_STATUSES
    )


def slot_status(slot, bookings: list[Booking], now: datetime | None = None) -> str:
    now = now or utcnow()
    if slot.closed:
        return SlotStatus.CLOSED
    if not slot.published:
        return SlotStatus.UNPUBLISHED
    if slot.end_at <= now:
        return SlotStatus.IN_PAST
    if confirmed_count(slot.id, bookings) >= slot.capacity:
        return SlotStatus.FULL
    return SlotStatus.AVAILABLE


def remaining_capacity(slot, bookings: list[Booking]) -> int:
    return max(0, slot.capacity - confirmed_count(slot.id, bookings))


def validate_slot_edit(
    slot,
    existing_bookings: list[Booking],
    *,
    new_capacity: int | None = None,
    new_start: datetime | None = None,
) -> str | None:
    """Return a rejection reason, or ``None`` when the edit is permitted.

    Agency edits must never strand existing confirmed bookings: capacity cannot
    drop below the number of live bookings, and start times cannot be moved such
    that the booked window is cut short.
    """
    active = confirmed_count(slot.id, existing_bookings)
    if new_capacity is not None and new_capacity < active:
        return (
            f"capacity {new_capacity} is below the {active} confirmed "
            "booking(s) already held on this slot"
        )
    if new_start is not None and new_start > slot.start_at and active > 0:
        return "start time cannot be moved later while bookings exist"
    return None


def inquiry_can_transition(current: str, target: str) -> bool:
    return target in VALID_INQUIRY_STATUS_TRANSITIONS.get(current, set())


def booking_can_transition(current: str, target: str) -> bool:
    return target in VALID_BOOKING_TRANSITIONS.get(current, set())


def inquiry_reference_for(inquiry: Inquiry) -> str:
    return inquiry.reference
