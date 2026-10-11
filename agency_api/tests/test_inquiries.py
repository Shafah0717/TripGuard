"""Inquiry + consent tests (acceptance criteria 1, 2, 3)."""


def test_inquiry_requires_valid_payload(client):
    # Missing consent entirely -> validation error, nothing stored.
    response = client.post(
        "/api/inquiries",
        json={
            "traveller_name": "No Consent",
            "contact_email": "nc@example.com",
            "visa_route": "tourist_30_day_single_entry",
            "message": "Hello",
        },
    )
    assert response.status_code == 422


def test_inquiry_without_inquiry_details_category_rejected(client):
    response = client.post(
        "/api/inquiries",
        json={
            "traveller_name": "Partial Consent",
            "contact_email": "pc@example.com",
            "visa_route": "tourist_30_day_single_entry",
            "message": "Hello",
            "consent": {"version": "1.0", "categories": ["CONTACT_DETAILS"]},
        },
    )
    assert response.status_code == 422


def test_create_inquiry_with_consent(client, make_inquiry):
    reference, token, _ = make_inquiry()
    assert reference.startswith("INQ-")
    assert len(token) >= 8

    listed = client.get(
        "/api/agency/inquiries", headers={"Authorization": "Bearer " + _staff_token()}
    )
    assert listed.status_code == 200
    refs = [item["reference"] for item in listed.json()]
    assert reference in refs


def _staff_token() -> str:
    from agency_api.security import create_access_token
    import os

    return create_access_token("agency_admin", "ADMIN", os.environ["JWT_SECRET"])


def test_traveller_can_read_own_inquiry(client, make_inquiry):
    reference, token, _ = make_inquiry()
    response = client.get(
        f"/api/inquiries/{token}", headers={"X-Traveller-Token": token}
    )
    assert response.status_code == 200
    assert response.json()["reference"] == reference


def test_traveller_token_scoping(client, make_inquiry):
    _, token, _ = make_inquiry()
    # Wrong token must not reveal the inquiry.
    response = client.get(
        "/api/inquiries/WRONGTOKEN123", headers={"X-Traveller-Token": "WRONGTOKEN123"}
    )
    assert response.status_code == 404


def test_prep_summary_optional(client, make_inquiry):
    ref_without, token_without, _ = make_inquiry(include_prep=False)
    ref_with, token_with, _ = make_inquiry(include_prep=True)

    staff = {"Authorization": "Bearer " + _staff_token()}
    listed = {item["reference"]: item for item in client.get("/api/agency/inquiries", headers=staff).json()}
    assert listed[ref_without]["prep_summary"] is None
    assert listed[ref_with]["prep_summary"] == "1 missing document."


def test_inquiry_rejects_document_looking_payload(client):
    response = client.post(
        "/api/inquiries",
        json={
            "traveller_name": "Doc Sneak",
            "contact_email": "ds@example.com",
            "visa_route": "tourist_30_day_single_entry",
            "message": "%PDF-1.4 fake passport scan",
            "consent": {
                "version": "1.0",
                "categories": ["CONTACT_DETAILS", "INQUIRY_DETAILS"],
            },
        },
    )
    assert response.status_code == 422


def test_invalid_status_transition_rejected(client, staff_headers, make_inquiry):
    make_inquiry()
    listed = client.get("/api/agency/inquiries", headers=staff_headers).json()
    inquiry_id = listed[0]["id"]
    # NEW -> BOOKED is not a valid transition without contacting first.
    response = client.patch(
        f"/api/agency/inquiries/{inquiry_id}",
        headers=staff_headers,
        json={"status": "BOOKED"},
    )
    assert response.status_code == 409
