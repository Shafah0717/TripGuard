"""End-to-end agency workflow tests.

These exercise the exact HTTP contract the Android app and the agency website
use against the shared backend: a traveller inquiry is created, the agency
offers inquiry-scoped slots, the traveller books one atomically, and the booking
becomes visible to the agency with matching references.
"""

from __future__ import annotations


def _offer_slot(client, staff_headers, inquiry_id: int, *, capacity: int = 1, hour: int = 9):
    response = client.post(
        "/api/agency/slots",
        headers=staff_headers,
        json={
            "start_at": f"2027-03-10T{hour:02d}:00:00Z",
            "end_at": f"2027-03-10T{hour:02d}:30:00Z",
            "timezone_name": "Asia/Dubai",
            "location": "TripGuard Dubai Office",
            "capacity": capacity,
            "published": True,
            "notes": "Bring your passport.",
            "inquiry_id": inquiry_id,
        },
    )
    assert response.status_code == 201, response.text
    return response.json()


def test_full_inquiry_to_confirmed_booking(client, staff_headers, make_inquiry):
    reference, token, _ = make_inquiry(name="End To End")

    # The agency sees the new inquiry.
    listed = client.get("/api/agency/inquiries", headers=staff_headers).json()
    inquiry = next(i for i in listed if i["reference"] == reference)

    # Offer two slots scoped to this inquiry.
    first = _offer_slot(client, staff_headers, inquiry["id"], capacity=1, hour=9)
    second = _offer_slot(client, staff_headers, inquiry["id"], capacity=1, hour=11)

    assert first["inquiry_id"] == inquiry["id"]
    assert first["inquiry_reference"] == reference

    # Publishing a slot moves the inquiry to SLOT_OFFERED.
    detail = client.get(
        f"/api/agency/inquiries/{inquiry['id']}", headers=staff_headers
    ).json()
    assert detail["status"] == "SLOT_OFFERED"
    assert detail["slots_offered"] == 2

    # Agency can list the offered slots for the inquiry.
    offered = client.get(
        f"/api/agency/inquiries/{inquiry['id']}/slots", headers=staff_headers
    ).json()
    assert {s["id"] for s in offered} == {first["id"], second["id"]}

    # Traveller sees the published slots.
    slots = client.get(
        f"/api/inquiries/{token}/slots", headers={"X-Traveller-Token": token}
    ).json()
    assert {s["id"] for s in slots} >= {first["id"], second["id"]}

    # Traveller confirms one slot.
    booking = client.post(
        f"/api/inquiries/{token}/bookings",
        headers={"X-Traveller-Token": token},
        json={
            "slot_id": first["id"],
            "travellers_token": token,
            "idempotency_key": "e2e-flow-key",
        },
    )
    assert booking.status_code == 201, booking.text
    booking = booking.json()
    assert booking["status"] == "CONFIRMED"
    assert booking["inquiry_reference"] == reference

    # The inquiry is now BOOKED and shows the booking reference to the agency.
    detail = client.get(
        f"/api/agency/inquiries/{inquiry['id']}", headers=staff_headers
    ).json()
    assert detail["status"] == "BOOKED"
    assert detail["booking_reference"] == booking["reference"]
    assert detail["booking_status"] == "CONFIRMED"

    # The confirmed booking is visible in the agency bookings list with matching
    # references and appointment time.
    agency_bookings = client.get("/api/agency/bookings", headers=staff_headers).json()
    match = next(b for b in agency_bookings if b["reference"] == booking["reference"])
    assert match["inquiry_reference"] == reference
    assert match["slot_start_at"] == first["start_at"]
    assert match["slot_location"] == "TripGuard Dubai Office"

    # The traveller can reopen the booking and sees the same state.
    reopened = client.get(
        f"/api/bookings/{booking['reference']}",
        headers={"X-Traveller-Token": token},
    ).json()
    assert reopened["reference"] == booking["reference"]
    assert reopened["status"] == "CONFIRMED"


def test_idempotent_confirmation_creates_one_booking(client, staff_headers, make_inquiry):
    reference, token, _ = make_inquiry(name="Idempotent")
    listed = client.get("/api/agency/inquiries", headers=staff_headers).json()
    inquiry = next(i for i in listed if i["reference"] == reference)
    slot = _offer_slot(client, staff_headers, inquiry["id"])

    payload = {
        "slot_id": slot["id"],
        "travellers_token": token,
        "idempotency_key": "stable-key",
    }
    first = client.post(
        f"/api/inquiries/{token}/bookings",
        headers={"X-Traveller-Token": token},
        json=payload,
    )
    second = client.post(
        f"/api/inquiries/{token}/bookings",
        headers={"X-Traveller-Token": token},
        json=payload,
    )
    assert first.status_code == 201 and second.status_code == 201
    assert first.json()["reference"] == second.json()["reference"]

    all_bookings = client.get("/api/agency/bookings", headers=staff_headers).json()
    on_inquiry = [b for b in all_bookings if b["inquiry_id"] == inquiry["id"]]
    assert len(on_inquiry) == 1


def test_slot_capacity_one_rejects_second_booking(client, staff_headers, make_inquiry):
    _, token_a, _ = make_inquiry(name="Traveller A")
    ref_b, token_b, _ = make_inquiry(name="Traveller B")
    listed = client.get("/api/agency/inquiries", headers=staff_headers).json()
    inquiry_b = next(i for i in listed if i["reference"] == ref_b)
    slot = _offer_slot(client, staff_headers, inquiry_b["id"], capacity=1)

    booked = client.post(
        f"/api/inquiries/{token_a}/bookings",
        headers={"X-Traveller-Token": token_a},
        json={"slot_id": slot["id"], "travellers_token": token_a, "idempotency_key": "a1"},
    )
    assert booked.status_code == 201

    # A general/targeted slot made full by any traveller must reject the next one.
    again = client.post(
        f"/api/inquiries/{token_b}/bookings",
        headers={"X-Traveller-Token": token_b},
        json={"slot_id": slot["id"], "travellers_token": token_b, "idempotency_key": "b1"},
    )
    assert again.status_code == 409
    assert "choose another slot" in again.json()["detail"].lower()


def test_only_inquiry_scoped_slots_are_visible_to_travellers(client, staff_headers, make_inquiry):
    ref_a, token_a, _ = make_inquiry(name="Owner A")
    _, token_b, _ = make_inquiry(name="Other B")
    listed = client.get("/api/agency/inquiries", headers=staff_headers).json()
    inquiry_a = next(i for i in listed if i["reference"] == ref_a)

    scoped = _offer_slot(client, staff_headers, inquiry_a["id"])
    general = client.post(
        "/api/agency/slots",
        headers=staff_headers,
        json={
            "start_at": "2027-03-12T09:00:00Z",
            "end_at": "2027-03-12T09:30:00Z",
            "timezone_name": "Asia/Dubai",
            "location": "General pool",
            "capacity": 1,
            "published": True,
        },
    ).json()

    slots_a = client.get(
        f"/api/inquiries/{token_a}/slots", headers={"X-Traveller-Token": token_a}
    ).json()
    slots_b = client.get(
        f"/api/inquiries/{token_b}/slots", headers={"X-Traveller-Token": token_b}
    ).json()

    ids_a = {s["id"] for s in slots_a}
    ids_b = {s["id"] for s in slots_b}

    # MVP rule: travellers only see slots offered to their own inquiry. The
    # general/shared pool is agency-side only and never surfaced in the app.
    assert scoped["id"] in ids_a
    assert scoped["id"] not in ids_b
    assert general["id"] not in ids_a
    assert general["id"] not in ids_b


def test_agency_delete_removes_inquiry_and_its_slots(client, staff_headers, make_inquiry):
    ref_a, token_a, _ = make_inquiry(name="To Delete A")
    ref_b, _, _ = make_inquiry(name="Keep B")
    listed = client.get("/api/agency/inquiries", headers=staff_headers).json()
    inquiry_a = next(i for i in listed if i["reference"] == ref_a)
    inquiry_b = next(i for i in listed if i["reference"] == ref_b)

    slot = _offer_slot(client, staff_headers, inquiry_a["id"])
    client.post(
        f"/api/inquiries/{token_a}/bookings",
        headers={"X-Traveller-Token": token_a},
        json={"slot_id": slot["id"], "travellers_token": token_a, "idempotency_key": "del-1"},
    )

    response = client.delete(
        f"/api/agency/inquiries/{inquiry_a['id']}", headers=staff_headers
    )
    assert response.status_code == 200
    assert ref_a in response.json()["message"]

    # The inquiry no longer exists, and the slot/booking went with it.
    assert client.get(
        f"/api/agency/inquiries/{inquiry_a['id']}", headers=staff_headers
    ).status_code == 404
    assert client.get(
        f"/api/inquiries/{token_a}/slots", headers={"X-Traveller-Token": token_a}
    ).status_code == 404
    all_slots = client.get("/api/agency/slots", headers=staff_headers).json()
    assert all(s["id"] != slot["id"] for s in all_slots)

    # Other inquiries are untouched.
    assert client.get(
        f"/api/agency/inquiries/{inquiry_b['id']}", headers=staff_headers
    ).status_code == 200


def test_traveller_withdraw_removes_inquiry(client, staff_headers, make_inquiry):
    ref, token, _ = make_inquiry(name="Withdraw Me")
    listed = client.get("/api/agency/inquiries", headers=staff_headers).json()
    inquiry = next(i for i in listed if i["reference"] == ref)
    _offer_slot(client, staff_headers, inquiry["id"])

    response = client.delete(
        f"/api/inquiries/{token}", headers={"X-Traveller-Token": token}
    )
    assert response.status_code == 200
    assert client.get(
        f"/api/inquiries/{token}", headers={"X-Traveller-Token": token}
    ).status_code == 404
    assert client.get(
        f"/api/agency/inquiries/{inquiry['id']}", headers=staff_headers
    ).status_code == 404


def test_agency_endpoints_reject_unauthenticated_access(client, staff_headers, make_inquiry):
    ref, _, _ = make_inquiry(name="Private")
    listed = client.get("/api/agency/inquiries", headers=staff_headers).json()
    inquiry = next(i for i in listed if i["reference"] == ref)

    assert client.get("/api/agency/inquiries").status_code == 401
    assert client.get(f"/api/agency/inquiries/{inquiry['id']}").status_code == 401
    assert client.get(f"/api/agency/inquiries/{inquiry['id']}/slots").status_code == 401
    assert client.get("/api/agency/bookings").status_code == 401
    assert client.get("/api/agency/dashboard").status_code == 401
    assert client.delete(f"/api/agency/inquiries/{inquiry['id']}").status_code == 401
