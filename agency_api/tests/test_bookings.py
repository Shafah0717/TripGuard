"""Booking, slot and concurrency tests (acceptance criteria 4-13)."""
import threading

from fastapi.testclient import TestClient  # noqa: F401


def test_agency_login_flow(client):
    # Wrong password rejected.
    bad = client.post(
        "/api/agency/login",
        json={"username": "nobody", "password": "wrong"},
    )
    assert bad.status_code == 401


def test_agency_endpoints_require_auth(client):
    assert client.get("/api/agency/inquiries").status_code == 401
    assert client.get("/api/agency/slots").status_code == 401
    assert client.get("/api/agency/bookings").status_code == 401
    assert client.post(
        "/api/agency/slots",
        json={},
    ).status_code == 401


def test_create_and_list_slots(client, staff_headers, make_slot):
    slot = make_slot(capacity=2)
    assert slot["published"] is True
    assert slot["status"] == "AVAILABLE"
    listed = client.get("/api/agency/slots", headers=staff_headers).json()
    assert any(s["id"] == slot["id"] for s in listed)


def test_unpublished_slot_not_bookable_by_traveller(client, staff_headers, make_inquiry, make_slot):
    _, token, _ = make_inquiry()
    hidden = make_slot(capacity=1, published=False)
    listed = client.get(
        f"/api/inquiries/{token}/slots", headers={"X-Traveller-Token": token}
    ).json()
    assert all(s["id"] != hidden["id"] for s in listed)


def test_booking_success_and_conflict(client, staff_headers, make_inquiry, make_slot):
    slot = make_slot(capacity=1)
    ref1, token1, _ = make_inquiry(name="Alice")
    ref2, token2, _ = make_inquiry(name="Bob")

    first = client.post(
        f"/api/inquiries/{token1}/bookings",
        headers={"X-Traveller-Token": token1},
        json={"slot_id": slot["id"], "travellers_token": token1, "idempotency_key": "key-a"},
    )
    assert first.status_code == 201, first.text
    assert first.json()["status"] == "CONFIRMED"

    # Second traveller hits full capacity -> clear conflict, no invalid booking.
    second = client.post(
        f"/api/inquiries/{token2}/bookings",
        headers={"X-Traveller-Token": token2},
        json={"slot_id": slot["id"], "travellers_token": token2, "idempotency_key": "key-b"},
    )
    assert second.status_code == 409
    assert "choose another slot" in second.json()["detail"].lower()


def test_idempotency_prevents_duplicate_booking(client, staff_headers, make_inquiry, make_slot):
    slot = make_slot(capacity=2)
    ref, token, _ = make_inquiry()
    payload = {"slot_id": slot["id"], "travellers_token": token, "idempotency_key": "same-key"}

    first = client.post(
        f"/api/inquiries/{token}/bookings",
        headers={"X-Traveller-Token": token},
        json=payload,
    )
    assert first.status_code == 201
    second = client.post(
        f"/api/inquiries/{token}/bookings",
        headers={"X-Traveller-Token": token},
        json=payload,
    )
    # Same idempotency key returns the original booking, not a duplicate.
    assert second.status_code == 201
    assert second.json()["reference"] == first.json()["reference"]

    listed = client.get("/api/agency/bookings", headers=staff_headers).json()
    matches = [b for b in listed if b["inquiry_id"] == first.json()["inquiry_id"]]
    assert len(matches) == 1


def test_stale_slot_conflict(client, staff_headers, make_inquiry, make_slot):
    _, token, _ = make_inquiry()
    missing = client.post(
        f"/api/inquiries/{token}/bookings",
        headers={"X-Traveller-Token": token},
        json={"slot_id": 999999, "travellers_token": token, "idempotency_key": "k1"},
    )
    assert missing.status_code == 409
    assert "no longer available" in missing.json()["detail"].lower()


def test_capacity_two_concurrent(client, staff_headers, make_inquiry, make_slot):
    """Two threads racing for a capacity-2 slot produce exactly 2 bookings."""
    slot = make_slot(capacity=2)
    inq = [make_inquiry(name=f"Racer {i}") for i in range(4)]
    results: list[int] = []
    lock = threading.Lock()

    def book(idx: int):
        _, token, _ = inq[idx]
        response = client.post(
            f"/api/inquiries/{token}/bookings",
            headers={"X-Traveller-Token": token},
            json={"slot_id": slot["id"], "travellers_token": token, "idempotency_key": f"k{idx}"},
        )
        with lock:
            results.append(response.status_code)

    threads = [threading.Thread(target=book, args=(i,)) for i in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert results.count(201) == 2
    assert results.count(409) == 2

    listed = client.get("/api/agency/bookings", headers=staff_headers).json()
    on_slot = [b for b in listed if b["slot_id"] == slot["id"]]
    assert len(on_slot) == 2


def test_cancellation_restores_capacity(client, staff_headers, make_inquiry, make_slot):
    slot = make_slot(capacity=1)
    _, token1, _ = make_inquiry(name="First")
    _, token2, _ = make_inquiry(name="Second")

    booking = client.post(
        f"/api/inquiries/{token1}/bookings",
        headers={"X-Traveller-Token": token1},
        json={"slot_id": slot["id"], "travellers_token": token1, "idempotency_key": "c1"},
    ).json()

    conflict = client.post(
        f"/api/inquiries/{token2}/bookings",
        headers={"X-Traveller-Token": token2},
        json={"slot_id": slot["id"], "travellers_token": token2, "idempotency_key": "c2"},
    )
    assert conflict.status_code == 409

    cancel = client.post(
        f"/api/bookings/{booking['reference']}/cancel",
        headers={"X-Traveller-Token": token1},
    )
    assert cancel.status_code == 200
    assert cancel.json()["status"] == "CANCELLED"

    after = client.post(
        f"/api/inquiries/{token2}/bookings",
        headers={"X-Traveller-Token": token2},
        json={"slot_id": slot["id"], "travellers_token": token2, "idempotency_key": "c2"},
    )
    assert after.status_code == 201


def test_traveller_cannot_cancel_someone_elses_booking(
    client, staff_headers, make_inquiry, make_slot
):
    slot = make_slot(capacity=1)
    _, token1, _ = make_inquiry(name="Owner")
    _, token2, _ = make_inquiry(name="Intruder")
    booking = client.post(
        f"/api/inquiries/{token1}/bookings",
        headers={"X-Traveller-Token": token1},
        json={"slot_id": slot["id"], "travellers_token": token1, "idempotency_key": "o1"},
    ).json()

    response = client.post(
        f"/api/bookings/{booking['reference']}/cancel",
        headers={"X-Traveller-Token": token2},
    )
    assert response.status_code == 404


def test_booking_links_inquiry_and_appears_in_dashboard(
    client, staff_headers, make_inquiry, make_slot
):
    slot = make_slot(capacity=1)
    reference, token, _ = make_inquiry(name="Linked")
    response = client.post(
        f"/api/inquiries/{token}/bookings",
        headers={"X-Traveller-Token": token},
        json={"slot_id": slot["id"], "travellers_token": token, "idempotency_key": "l1"},
    ).json()
    assert response["inquiry_reference"] == reference

    inquiries = client.get(
        "/api/agency/inquiries",
        headers=staff_headers,
        params={"search": reference},
    ).json()
    assert len(inquiries) == 1
    assert inquiries[0]["booking_reference"] == response["reference"]

    dashboard = client.get("/api/agency/dashboard", headers=staff_headers).json()
    assert dashboard["confirmed_bookings"] >= 1


def test_slot_concurrent_booking_full_conflict(client):
    """Criterion 8 in a tighter race: 1 slot, three simultaneous attempts."""
    import threading

    staff = {"Authorization": "Bearer " + _staff_token()}
    make_slot = _make_slot_factory(client, staff)
    slot = make_slot(capacity=1)
    tokens = []
    for i in range(3):
        payload = {
            "traveller_name": f"Racer {i}",
            "contact_email": f"r{i}@example.com",
            "visa_route": "tourist_30_day_single_entry",
            "message": "Booking please.",
            "include_prep_summary": False,
            "consent": {
                "version": "1.0",
                "categories": ["CONTACT_DETAILS", "INQUIRY_DETAILS"],
            },
        }
        created = client.post("/api/inquiries", json=payload).json()
        tokens.append(created["traveller_token"])

    outcomes: list[int] = []
    lock = threading.Lock()

    def attempt(token: str, n: int):
        response = client.post(
            f"/api/inquiries/{token}/bookings",
            headers={"X-Traveller-Token": token},
            json={"slot_id": slot["id"], "travellers_token": token, "idempotency_key": f"race-{n}"},
        )
        with lock:
            outcomes.append(response.status_code)

    threads = [
        threading.Thread(target=attempt, args=(token, n))
        for n, token in enumerate(tokens)
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert outcomes.count(201) == 1
    assert outcomes.count(409) == 2


def _staff_token() -> str:
    from agency_api.security import create_access_token
    import os

    return create_access_token("agency_admin", "ADMIN", os.environ["JWT_SECRET"])


def _make_slot_factory(client, staff_headers):
    def _make(capacity: int, published: bool = True):
        response = client.post(
            "/api/agency/slots",
            headers=staff_headers,
            json={
                "start_at": "2026-11-21T10:00:00Z",
                "end_at": "2026-11-21T10:30:00Z",
                "timezone_name": "Asia/Dubai",
                "location": "Dubai office",
                "capacity": capacity,
                "published": published,
            },
        )
        assert response.status_code == 201
        return response.json()

    return _make


def test_cannot_reduce_capacity_below_bookings(client, staff_headers, make_inquiry, make_slot):
    slot = make_slot(capacity=2)
    _, token1, _ = make_inquiry(name="Holder A")
    _, token2, _ = make_inquiry(name="Holder B")
    for n, token in enumerate((token1, token2)):
        booked = client.post(
            f"/api/inquiries/{token}/bookings",
            headers={"X-Traveller-Token": token},
            json={"slot_id": slot["id"], "travellers_token": token, "idempotency_key": f"cap-{n}"},
        )
        assert booked.status_code == 201

    # Reducing capacity below the 2 confirmed bookings must be rejected.
    response = client.patch(
        f"/api/agency/slots/{slot['id']}",
        headers=staff_headers,
        json={"capacity": 1},
    )
    assert response.status_code == 409

    ok = client.patch(
        f"/api/agency/slots/{slot['id']}",
        headers=staff_headers,
        json={"capacity": 3},
    )
    assert ok.status_code == 200


def test_close_slot_makes_it_unavailable(client, staff_headers, make_slot, make_inquiry):
    slot = make_slot(capacity=1)
    reference, token, _ = make_inquiry(name="Closer")
    response = client.patch(
        f"/api/agency/slots/{slot['id']}",
        headers=staff_headers,
        json={"closed": True},
    )
    assert response.status_code == 200
    listed = client.get(
        f"/api/inquiries/{token}/slots", headers={"X-Traveller-Token": token}
    ).json()
    assert all(s["id"] != slot["id"] for s in listed)
