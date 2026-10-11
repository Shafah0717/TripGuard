import os
import tempfile

import pytest

_test_dir = tempfile.mkdtemp(prefix="tripguard-test-")
os.environ.setdefault("JWT_SECRET", "test-secret")
os.environ["RATE_LIMIT_INQUIRIES"] = "1000"
os.environ["RATE_LIMIT_BOOKINGS"] = "1000"

from fastapi.testclient import TestClient  # noqa: E402

import agency_api.main as main_module  # noqa: E402
from agency_api.db import init_db  # noqa: E402
from agency_api.security import create_access_token  # noqa: E402

JWT_SECRET = os.environ["JWT_SECRET"]


@pytest.fixture()
def client(tmp_path):
    """Fresh isolated database per test so inquirys never leak across tests."""
    db_path = str(tmp_path / "test.db")
    os.environ["DATABASE_PATH"] = db_path
    main_module._engine_and_session.cache_clear()
    init_db(db_path)
    with TestClient(main_module.app) as test_client:
        yield test_client
    main_module._engine_and_session.cache_clear()


@pytest.fixture()
def staff_headers():
    token = create_access_token("agency_admin", "ADMIN", JWT_SECRET)
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture()
def make_inquiry(client):
    """Factory: create an inquiry and return (reference, token, payload)."""

    def _make(
        name: str = "Test Traveller",
        include_prep: bool = False,
        message: str = "Please help me book an appointment.",
    ):
        payload = {
            "traveller_name": name,
            "contact_email": "traveller@example.com",
            "visa_route": "tourist_30_day_single_entry",
            "message": message,
            "include_prep_summary": include_prep,
            "consent": {
                "version": "1.0",
                "categories": ["CONTACT_DETAILS", "INQUIRY_DETAILS"],
            },
        }
        if include_prep:
            payload["prep_summary"] = "1 missing document."
            payload["consent"]["categories"] = [
                "CONTACT_DETAILS",
                "INQUIRY_DETAILS",
                "PREP_SUMMARY",
            ]
        response = client.post("/api/inquiries", json=payload)
        assert response.status_code == 201, response.text
        data = response.json()
        return data["reference"], data["traveller_token"], payload

    return _make


@pytest.fixture()
def make_slot(client, staff_headers):
    """Factory: create a published agency slot."""

    def _make(capacity: int = 1, published: bool = True):
        response = client.post(
            "/api/agency/slots",
            headers=staff_headers,
            json={
                "start_at": "2026-11-20T09:00:00Z",
                "end_at": "2026-11-20T09:30:00Z",
                "timezone_name": "Asia/Dubai",
                "location": "Dubai office",
                "capacity": capacity,
                "published": published,
            },
        )
        assert response.status_code == 201, response.text
        return response.json()

    return _make
