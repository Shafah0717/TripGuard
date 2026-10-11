"""CLI seed script: python -m agency_api.seed_dev [--db PATH].

Inserts clearly-labelled synthetic demo records (a staff login, inquiries,
slots, and bookings). Only ever run explicitly against a development database;
never executed on server startup.
"""

from __future__ import annotations

import argparse
import os
import sys
from datetime import timedelta

from .db import (
    AgencyNote,
    AgencyUser,
    AppointmentSlot,
    Booking,
    ConsentRecord,
    Inquiry,
    create_engine_and_session,
    init_db,
    utcnow,
)
from .main import generate_reference, generate_traveller_token, hash_password  # type: ignore


def seed(db_path: str) -> bool:
    init_db(db_path)
    engine, SessionLocal = create_engine_and_session(db_path)
    with SessionLocal() as session:
        if session.query(AgencyUser).count() > 0:
            session.rollback()
            return False

        session.add(
            AgencyUser(
                username="agency_admin",
                password_hash=hash_password("tripguard-demo-password"),
                role="ADMIN",
            )
        )

        now = utcnow()
        base = now.replace(minute=0, second=0, microsecond=0) + timedelta(hours=1)

        def slot(days_ahead: int, hour: int, capacity: int, published: bool, closed: bool = False):
            start = base + timedelta(days=days_ahead, hours=hour)
            return AppointmentSlot(
                reference=generate_reference("SLT"),
                start_at=start,
                end_at=start + timedelta(minutes=30),
                timezone_name="Asia/Dubai",
                location="TripGuard Dubai Office, Sheikh Zayed Road",
                capacity=capacity,
                published=published,
                closed=closed,
                notes="Please arrive 5 minutes early.",
            )

        slot_published_1 = slot(2, 0, capacity=2, published=True)
        slot_published_2 = slot(3, 2, capacity=1, published=True)
        slot_unpublished = slot(5, 4, capacity=1, published=False)
        slot_past = slot(-1, 3, capacity=1, published=True)
        session.add_all([slot_published_1, slot_published_2, slot_unpublished, slot_past])

        def inquiry(
            name: str,
            status: str,
            days_ago: int,
            with_prep: bool,
            route: str = "tourist_30_day_single_entry",
        ) -> Inquiry:
            return Inquiry(
                reference=generate_reference("INQ"),
                status=status,
                traveller_name=name,
                contact_email=f"{name.split()[0].lower()}@example.com",
                visa_route=route,
                message="I would like to ask about appointment availability.",
                prep_summary=("2 missing documents, 1 potential issue." if with_prep else None),
                missing_doc_count=(2 if with_prep else 0),
                issue_count=(1 if with_prep else 0),
                traveller_token=generate_traveller_token(),
                created_at=now - timedelta(days=days_ago),
            )

        inq_new = inquiry("Demo Traveller A", "NEW", 1, with_prep=True)
        inq_contacted = inquiry("Demo Traveller B", "CONTACTED", 2, with_prep=False)
        inq_booked = inquiry("Demo Traveller C", "BOOKED", 3, with_prep=False)
        session.add_all([inq_new, inq_contacted, inq_booked])
        session.flush()

        for inq, with_summary in ((inq_new, True), (inq_contacted, False), (inq_booked, False)):
            session.add(
                ConsentRecord(
                    inquiry_id=inq.id,
                    version="1.0",
                    consented_at=inq.created_at,
                    categories="CONTACT_DETAILS,INQUIRY_DETAILS" + (",PREP_SUMMARY" if with_summary else ""),
                    shared_prep_summary=with_summary,
                )
            )

        confirmed = Booking(
            reference=generate_reference("BKG"),
            status="CONFIRMED",
            idempotency_key="demo-confirmed-key",
            inquiry_id=inq_booked.id,
            slot_id=slot_published_1.id,
            created_at=now - timedelta(days=2),
        )
        cancelled = Booking(
            reference=generate_reference("BKG"),
            status="CANCELLED",
            idempotency_key="demo-cancelled-key",
            inquiry_id=inq_contacted.id,
            slot_id=slot_past.id,
            created_at=now - timedelta(days=3),
            cancelled_at=now - timedelta(days=2, hours=-1),
        )
        session.add_all([confirmed, cancelled])

        session.add_all(
            [
                AgencyNote(
                    inquiry_id=inq_new.id,
                    author="Demo Staff",
                    body="Synthetic demo note — called traveller, awaiting documents.",
                ),
            ]
        )

        session.commit()
        print(f"Seeded demo data into {db_path}.")
        print("  Staff login: agency_admin / tripguard-demo-password")
        return True


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed demo data for the TripGuard Agency API.")
    parser.add_argument("--db", default=os.environ.get("DATABASE_PATH", "data/tripguard.db"))
    args = parser.parse_args()
    os.makedirs(os.path.dirname(args.db) or ".", exist_ok=True)
    if not seed(args.db):
        print(f"{args.db} already contains data; nothing seeded.")
        sys.exit(1)


if __name__ == "__main__":
    main()
