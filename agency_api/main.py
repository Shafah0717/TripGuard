"""TripGuard Agency API — shared backend for the Android app and agency website.

Privacy boundary: endpoints here never accept or store visa documents, raw OCR
text, or OCR-derived personal fields. Only explicitly consented contact details,
visa route, optional preparation summary, and a consent record are persisted.
"""

from __future__ import annotations

import os
import random
import string
from contextlib import asynccontextmanager, contextmanager
from datetime import datetime, timezone
from functools import lru_cache

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import Session, selectinload

from .db import (
    DB_LOCK,
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
from .schemas import (
    AgencyInquiryResponse,
    AgencyNoteResponse,
    BookingResponse,
    CreateBookingRequest,
    CreateInquiryRequest,
    CreateSlotRequest,
    InquiryCreated,
    InquiryNoteRequest,
    InquiryStatusRequest,
    LoginRequest,
    MessageResponse,
    PatchBookingRequest,
    PatchSlotRequest,
    SlotResponse,
    TokenResponse,
    TravellerInquiryResponse,
)
from .security import (
    RateLimiter,
    create_access_token,
    decode_access_token,
    hash_password,
    verify_password,
)
from .booking_validation import (
    ACTIVE_BOOKING_STATUSES,
    booking_can_transition,
    inquiry_can_transition,
    remaining_capacity,
    slot_status,
    validate_slot_edit,
)

JWT_SECRET = os.environ.get("JWT_SECRET", "tripguard-dev-secret-change-me")


@lru_cache(maxsize=1)
def _engine_and_session():
    path = os.environ.get("DATABASE_PATH", "data/tripguard.db")
    init_db(path)
    return create_engine_and_session(path)


@contextmanager
def db_session():
    engine, SessionLocal = _engine_and_session()
    with SessionLocal() as session:
        yield session


def _commit_locked(session: Session):
    with DB_LOCK:
        session.commit()


def generate_reference(prefix: str) -> str:
    suffix = "".join(random.choices(string.ascii_uppercase + string.digits, k=6))
    return f"{prefix}-{suffix}"


def generate_traveller_token() -> str:
    return "".join(random.choices(string.ascii_letters + string.digits, k=24))


def _delete_inquiry_cascade(session: Session, inquiry: Inquiry) -> None:
    """Removes an inquiry and everything scoped to it.

    Bookings and attached slots are deleted explicitly (their foreign keys to
    inquiries are NOT NULL / non-cascading), while notes and the consent record
    are removed by the ORM cascade on [Inquiry].
    """
    slot_ids = [
        slot.id
        for slot in session.query(AppointmentSlot).filter(AppointmentSlot.inquiry_id == inquiry.id)
    ]
    if slot_ids:
        session.query(Booking).filter(Booking.slot_id.in_(slot_ids)).delete(
            synchronize_session=False
        )
        session.query(AppointmentSlot).filter(AppointmentSlot.inquiry_id == inquiry.id).delete(
            synchronize_session=False
        )
    session.query(Booking).filter(Booking.inquiry_id == inquiry.id).delete(
        synchronize_session=False
    )
    session.delete(inquiry)


def _as_naive_utc(value: datetime) -> datetime:
    """Convert any ISO datetime to naive-UTC for consistent comparisons."""
    if value.tzinfo is not None:
        return value.astimezone(timezone.utc).replace(tzinfo=None)
    return value


# ---------------------------------------------------------------------------
# Auth helpers
# ---------------------------------------------------------------------------

def _bearer_token(request: Request) -> str | None:
    header = request.headers.get("Authorization", "")
    if header.startswith("Bearer "):
        return header[len("Bearer "):].strip() or None
    return None


def require_staff(request: Request, role: str | None = None) -> dict:
    token = _bearer_token(request)
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authentication required.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    payload = decode_access_token(token, JWT_SECRET)
    if not payload:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired credentials.",
        )
    if role and payload.get("role") != role:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Forbidden.")
    return payload


def require_staff_dep(request: Request) -> dict:
    return require_staff(request)


# ---------------------------------------------------------------------------
# Serializers
# ---------------------------------------------------------------------------

def slot_inquiry_reference(session: Session, slot: AppointmentSlot) -> str | None:
    if slot.inquiry_id is None:
        return None
    inquiry = session.get(Inquiry, slot.inquiry_id)
    return inquiry.reference if inquiry else None


def slot_response(
    slot: AppointmentSlot,
    bookings: list[Booking],
    inquiry_reference: str | None = None,
) -> SlotResponse:
    return SlotResponse(
        id=slot.id,
        reference=slot.reference,
        start_at=slot.start_at,
        end_at=slot.end_at,
        timezone_name=slot.timezone_name,
        location=slot.location,
        capacity=slot.capacity,
        published=slot.published,
        closed=slot.closed,
        notes=slot.notes,
        inquiry_id=slot.inquiry_id,
        inquiry_reference=inquiry_reference,
        status=slot_status(slot, bookings),
        bookings_count=sum(
            1
            for b in bookings
            if b.slot_id == slot.id and b.status in ACTIVE_BOOKING_STATUSES
        ),
        remaining_capacity=remaining_capacity(slot, bookings),
    )


def booking_response(
    booking: Booking,
    slot: AppointmentSlot | None,
    inquiry: Inquiry | None,
) -> BookingResponse:
    return BookingResponse(
        id=booking.id,
        reference=booking.reference,
        status=booking.status,
        inquiry_id=booking.inquiry_id,
        slot_id=booking.slot_id,
        created_at=booking.created_at,
        cancelled_at=booking.cancelled_at,
        agency_note=booking.agency_note,
        inquiry_reference=inquiry.reference if inquiry else "",
        slot_start_at=slot.start_at if slot else None,
        slot_end_at=slot.end_at if slot else None,
        slot_timezone=slot.timezone_name if slot else None,
        slot_location=slot.location if slot else None,
    )


def inquiry_to_agency_response(session: Session, inquiry: Inquiry) -> AgencyInquiryResponse:
    active_booking = (
        session.query(Booking)
        .filter(
            Booking.inquiry_id == inquiry.id,
            Booking.status.in_(ACTIVE_BOOKING_STATUSES),
        )
        .order_by(Booking.created_at.desc())
        .first()
    )
    slots_offered = (
        session.query(func.count(AppointmentSlot.id))
        .filter(AppointmentSlot.inquiry_id == inquiry.id)
        .scalar()
        or 0
    )
    return AgencyInquiryResponse(
        id=inquiry.id,
        reference=inquiry.reference,
        status=inquiry.status,
        traveller_name=inquiry.traveller_name,
        contact_email=inquiry.contact_email,
        contact_phone=inquiry.contact_phone,
        visa_route=inquiry.visa_route,
        message=inquiry.message,
        prep_summary=inquiry.prep_summary,
        missing_doc_count=inquiry.missing_doc_count,
        issue_count=inquiry.issue_count,
        internal_note=inquiry.internal_note,
        created_at=inquiry.created_at,
        updated_at=inquiry.updated_at,
        booking_reference=active_booking.reference if active_booking else None,
        booking_status=active_booking.status if active_booking else None,
        slots_offered=slots_offered,
    )


# ---------------------------------------------------------------------------
# App setup
# ---------------------------------------------------------------------------

@asynccontextmanager
async def lifespan(app: FastAPI):
    _engine_and_session()
    yield


app = FastAPI(
    title="TripGuard Agency API",
    version="1.0.0",
    description=(
        "Shared backend for the TripGuard Android app and the agency dashboard. "
        "Handles contact inquiries, appointment slots and concurrency-safe bookings."
    ),
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://127.0.0.1:5173"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

inquiry_limiter = RateLimiter(
    max_events=int(os.environ.get("RATE_LIMIT_INQUIRIES", "5")), window_seconds=60
)
booking_limiter = RateLimiter(
    max_events=int(os.environ.get("RATE_LIMIT_BOOKINGS", "10")), window_seconds=60
)
login_limiter = RateLimiter(max_events=50, window_seconds=60)


def rate_limit(limiter: RateLimiter, bucket: str):
    if not limiter.allow(bucket):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many requests. Please wait and try again.",
        )


def client_bucket(request: Request, prefix: str) -> str:
    client = request.client.host if request.client else "anonymous"
    return f"{prefix}:{client}"


@app.exception_handler(OperationalError)
async def sqlite_lock_handler(request: Request, exc: OperationalError):
    return JSONResponse(
        status_code=503,
        content={"detail": "The booking system is busy. Please try again shortly."},
    )


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


# ---------------------------------------------------------------------------
# Agency auth
# ---------------------------------------------------------------------------

@app.post("/api/agency/login", response_model=TokenResponse)
def agency_login(body: LoginRequest):
    rate_limit(login_limiter, f"login:{body.username}")
    with db_session() as session:
        user = session.execute(
            select(AgencyUser).where(AgencyUser.username == body.username)
        ).scalar_one_or_none()
        if (
            user is None
            or not user.is_active
            or not verify_password(body.password, user.password_hash)
        ):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid username or password.",
            )
        token = create_access_token(user.username, user.role, JWT_SECRET)
        return TokenResponse(access_token=token, role=user.role)


# ---------------------------------------------------------------------------
# Traveller (public, token-scoped) endpoints
# ---------------------------------------------------------------------------

@app.post(
    "/api/inquiries",
    response_model=InquiryCreated,
    status_code=status.HTTP_201_CREATED,
)
def create_inquiry(body: CreateInquiryRequest, request: Request):
    rate_limit(inquiry_limiter, client_bucket(request, "inquiry"))

    consent_includes_summary = "PREP_SUMMARY" in body.consent.categories
    stored_summary = (
        body.prep_summary
        if body.include_prep_summary and consent_includes_summary
        else None
    )

    with db_session() as session:
        for text_value, label in (
            (body.message, "message"),
            (body.prep_summary or "", "prep_summary"),
        ):
            lowered = text_value.lower()
            for banned in ("%pdf", "data:image", "base64,"):
                if banned in lowered:
                    raise HTTPException(
                        status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                        detail=f"The {label} field cannot contain document content.",
                    )

        now = utcnow()
        inquiry = Inquiry(
            reference=generate_reference("INQ"),
            status="NEW",
            traveller_name=body.traveller_name,
            contact_email=body.contact_email,
            contact_phone=body.contact_phone,
            visa_route=body.visa_route,
            message=body.message,
            prep_summary=stored_summary,
            missing_doc_count=body.missing_doc_count,
            issue_count=body.issue_count,
            traveller_token=generate_traveller_token(),
        )
        session.add(inquiry)
        session.flush()

        session.add(
            ConsentRecord(
                inquiry_id=inquiry.id,
                version=body.consent.version,
                consented_at=body.consent.consented_at or now,
                categories=",".join(sorted(set(body.consent.categories))),
                shared_prep_summary=consent_includes_summary,
            )
        )
        try:
            _commit_locked(session)
        except IntegrityError:
            session.rollback()
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Could not save the inquiry. Please try again.",
            )
        return InquiryCreated(
            id=inquiry.id,
            reference=inquiry.reference,
            status=inquiry.status,
            traveller_token=inquiry.traveller_token,
            created_at=inquiry.created_at,
        )


def _inquiry_by_token(session: Session, token: str) -> Inquiry:
    inquiry = session.execute(
        select(Inquiry).where(Inquiry.traveller_token == token)
    ).scalar_one_or_none()
    if inquiry is None:
        raise HTTPException(status_code=404, detail="Inquiry not found.")
    return inquiry


@app.get("/api/inquiries/{token}", response_model=TravellerInquiryResponse)
def get_inquiry_by_token(
    token: str,
    x_traveller_token: str | None = Header(default=None, alias="X-Traveller-Token"),
):
    supplied = x_traveller_token or token
    rate_limit(booking_limiter, f"inq-read:{supplied[:6]}")
    with db_session() as session:
        inquiry = _inquiry_by_token(session, supplied)
        return TravellerInquiryResponse(
            id=inquiry.id,
            reference=inquiry.reference,
            status=inquiry.status,
            visa_route=inquiry.visa_route,
            traveller_name=inquiry.traveller_name,
            created_at=inquiry.created_at,
            updated_at=inquiry.updated_at,
        )


@app.get("/api/inquiries/{token}/slots", response_model=list[SlotResponse])
def slots_for_inquiry(
    token: str,
    x_traveller_token: str | None = Header(default=None, alias="X-Traveller-Token"),
):
    supplied = x_traveller_token or token
    with db_session() as session:
        inquiry = _inquiry_by_token(session, supplied)
        bookings = session.query(Booking).all()
        # MVP rule: a traveller only sees appointment slots the agency offered
        # to *this* inquiry. The shared/general pool is not surfaced in the app.
        slots = (
            session.query(AppointmentSlot)
            .filter(
                AppointmentSlot.published.is_(True),
                AppointmentSlot.closed.is_(False),
                AppointmentSlot.inquiry_id == inquiry.id,
            )
            .order_by(AppointmentSlot.start_at.asc())
            .all()
        )
        return [
            slot_response(slot, bookings)
            for slot in slots
            if slot_status(slot, bookings) == "AVAILABLE"
        ]


@app.post(
    "/api/inquiries/{token}/bookings",
    response_model=BookingResponse,
    status_code=status.HTTP_201_CREATED,
)
def create_booking(
    token: str,
    body: CreateBookingRequest,
    request: Request,
    x_traveller_token: str | None = Header(default=None, alias="X-Traveller-Token"),
):
    supplied = x_traveller_token or token
    rate_limit(booking_limiter, client_bucket(request, "booking"))

    with db_session() as session:
        # --- Atomic section: one global lock covers validation + reservation --
        with DB_LOCK:
            inquiry = _inquiry_by_token(session, supplied)

            existing = session.execute(
                select(Booking).where(
                    Booking.idempotency_key == body.idempotency_key,
                    Booking.inquiry_id == inquiry.id,
                    Booking.status != "CANCELLED",
                )
            ).scalar_one_or_none()
            if existing:
                slot = session.get(AppointmentSlot, existing.slot_id)
                return booking_response(existing, slot, inquiry)

            active_for_inquiry = (
                session.query(Booking)
                .filter(
                    Booking.inquiry_id == inquiry.id,
                    Booking.status != "CANCELLED",
                )
                .first()
            )
            if active_for_inquiry:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="This inquiry already has an active booking.",
                )

            if body.slot_id is None:
                raise HTTPException(
                    status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                    detail="slot_id is required.",
                )

            slot = session.execute(
                select(AppointmentSlot).where(
                    AppointmentSlot.id == body.slot_id,
                    AppointmentSlot.published.is_(True),
                    AppointmentSlot.closed.is_(False),
                )
            ).scalar_one_or_none()
            now = utcnow()
            if slot is None or slot.end_at <= now:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=(
                        "This slot is no longer available. "
                        "Please choose another slot."
                    ),
                )

            active_count = (
                session.query(Booking)
                .filter(Booking.slot_id == slot.id, Booking.status != "CANCELLED")
                .count()
            )
            if active_count >= slot.capacity:
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail="This slot just filled up. Please choose another slot.",
                )

            booking = Booking(
                reference=generate_reference("BKG"),
                status="CONFIRMED",
                idempotency_key=body.idempotency_key,
                inquiry_id=inquiry.id,
                slot_id=slot.id,
            )
            session.add(booking)
            try:
                session.commit()
            except IntegrityError:
                # A concurrent writer won the unique constraints; report conflict.
                session.rollback()
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=(
                        "This slot was just taken by another traveller. "
                        "Please choose another slot."
                    ),
                )

            inquiry.status = "BOOKED"
            session.add(
                AgencyNote(
                    inquiry_id=inquiry.id,
                    author="system",
                    body=f"Booking {booking.reference} created via the app.",
                )
            )
            _commit_locked(session)
            return booking_response(booking, slot, inquiry)


@app.get("/api/bookings/{booking_reference}", response_model=BookingResponse)
def get_booking(
    booking_reference: str,
    x_traveller_token: str = Header(alias="X-Traveller-Token"),
):
    rate_limit(booking_limiter, f"bkg-read:{booking_reference[:6]}")
    with db_session() as session:
        booking = session.execute(
            select(Booking)
            .options(selectinload(Booking.slot), selectinload(Booking.inquiry))
            .where(Booking.reference == booking_reference)
        ).scalar_one_or_none()
        if booking is None:
            raise HTTPException(status_code=404, detail="Booking not found.")
        if booking.inquiry.traveller_token != x_traveller_token:
            raise HTTPException(status_code=404, detail="Booking not found.")
        return booking_response(booking, booking.slot, booking.inquiry)


@app.post("/api/bookings/{booking_reference}/cancel", response_model=BookingResponse)
def cancel_booking(
    booking_reference: str,
    request: Request,
    x_traveller_token: str = Header(alias="X-Traveller-Token"),
):
    rate_limit(booking_limiter, client_bucket(request, "cancel"))
    with db_session() as session:
        booking = session.execute(
            select(Booking)
            .options(selectinload(Booking.inquiry))
            .where(Booking.reference == booking_reference)
        ).scalar_one_or_none()
        if booking is None or booking.inquiry.traveller_token != x_traveller_token:
            raise HTTPException(status_code=404, detail="Booking not found.")
        if not booking_can_transition(booking.status, "CANCELLED"):
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=f"A {booking.status.lower()} booking cannot be cancelled.",
            )
        booking.status = "CANCELLED"
        booking.cancelled_at = utcnow()
        booking.inquiry.status = "SLOT_OFFERED"
        session.add(
            AgencyNote(
                inquiry_id=booking.inquiry_id,
                author="system",
                body=f"Booking {booking.reference} cancelled by the traveller.",
            )
        )
        _commit_locked(session)
        slot = session.get(AppointmentSlot, booking.slot_id)
        return booking_response(booking, slot, booking.inquiry)


@app.delete("/api/inquiries/{token}", response_model=MessageResponse)
def delete_inquiry(
    token: str,
    x_traveller_token: str | None = Header(default=None, alias="X-Traveller-Token"),
):
    """Lets the traveller withdraw an inquiry from the app (token-scoped)."""
    supplied = x_traveller_token or token
    with db_session() as session:
        inquiry = session.query(Inquiry).filter(Inquiry.traveller_token == supplied).one_or_none()
        if inquiry is None:
            raise HTTPException(status_code=404, detail="Inquiry not found.")
        reference = inquiry.reference
        _delete_inquiry_cascade(session, inquiry)
        _commit_locked(session)
        return MessageResponse(message=f"Inquiry {reference} withdrawn.")


# ---------------------------------------------------------------------------
# Agency protected endpoints
# ---------------------------------------------------------------------------

@app.get("/api/agency/inquiries", response_model=list[AgencyInquiryResponse])
def agency_list_inquiries(
    status_filter: str | None = Query(default=None, alias="status"),
    search: str | None = Query(default=None, min_length=1),
    staff: dict = Depends(require_staff_dep),
):
    with db_session() as session:
        query = session.query(Inquiry).order_by(Inquiry.created_at.desc())
        if status_filter:
            query = query.filter(Inquiry.status == status_filter.upper())
        if search:
            like = f"%{search.lower()}%"
            query = query.filter(func.lower(Inquiry.reference).like(like))
        inquiries = query.limit(500).all()
        return [inquiry_to_agency_response(session, inquiry) for inquiry in inquiries]


@app.get("/api/agency/inquiries/{inquiry_id}", response_model=AgencyInquiryResponse)
def agency_get_inquiry(inquiry_id: int, staff: dict = Depends(require_staff_dep)):
    with db_session() as session:
        inquiry = session.get(Inquiry, inquiry_id)
        if inquiry is None:
            raise HTTPException(status_code=404, detail="Inquiry not found.")
        return inquiry_to_agency_response(session, inquiry)


@app.delete("/api/agency/inquiries/{inquiry_id}", response_model=MessageResponse)
def agency_delete_inquiry(inquiry_id: int, staff: dict = Depends(require_staff_dep)):
    with db_session() as session:
        inquiry = session.get(Inquiry, inquiry_id)
        if inquiry is None:
            raise HTTPException(status_code=404, detail="Inquiry not found.")
        reference = inquiry.reference
        _delete_inquiry_cascade(session, inquiry)
        _commit_locked(session)
        return MessageResponse(message=f"Inquiry {reference} deleted.")


@app.patch("/api/agency/inquiries/{inquiry_id}", response_model=AgencyInquiryResponse)
def agency_update_inquiry(
    inquiry_id: int,
    body: InquiryStatusRequest | InquiryNoteRequest,
    staff: dict = Depends(require_staff_dep),
):
    with db_session() as session:
        inquiry = session.get(Inquiry, inquiry_id)
        if inquiry is None:
            raise HTTPException(status_code=404, detail="Inquiry not found.")

        if isinstance(body, InquiryStatusRequest):
            target = body.status.upper()
            if not inquiry_can_transition(inquiry.status, target):
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=f"Status change from {inquiry.status} to {target} is not allowed.",
                )
            inquiry.status = target
        elif isinstance(body, InquiryNoteRequest):
            session.add(
                AgencyNote(inquiry_id=inquiry.id, author=body.author, body=body.note)
            )
        else:
            raise HTTPException(status_code=422, detail="Unsupported update.")
        session.commit()
        return inquiry_to_agency_response(session, inquiry)


@app.get(
    "/api/agency/inquiries/{inquiry_id}/notes",
    response_model=list[AgencyNoteResponse],
)
def agency_list_notes(inquiry_id: int, staff: dict = Depends(require_staff_dep)):
    with db_session() as session:
        inquiry = session.get(Inquiry, inquiry_id)
        if inquiry is None:
            raise HTTPException(status_code=404, detail="Inquiry not found.")
        return (
            session.query(AgencyNote)
            .filter(AgencyNote.inquiry_id == inquiry_id)
            .order_by(AgencyNote.created_at.desc())
            .all()
        )


@app.get("/api/agency/slots", response_model=list[SlotResponse])
def agency_list_slots(staff: dict = Depends(require_staff_dep)):
    with db_session() as session:
        bookings = session.query(Booking).all()
        slots = session.query(AppointmentSlot).order_by(AppointmentSlot.start_at).all()
        return [
            slot_response(slot, bookings, slot_inquiry_reference(session, slot))
            for slot in slots
        ]


@app.get(
    "/api/agency/inquiries/{inquiry_id}/slots",
    response_model=list[SlotResponse],
)
def agency_inquiry_slots(inquiry_id: int, staff: dict = Depends(require_staff_dep)):
    """Every slot explicitly offered to one inquiry (published or not)."""
    with db_session() as session:
        inquiry = session.get(Inquiry, inquiry_id)
        if inquiry is None:
            raise HTTPException(status_code=404, detail="Inquiry not found.")
        bookings = session.query(Booking).all()
        slots = (
            session.query(AppointmentSlot)
            .filter(AppointmentSlot.inquiry_id == inquiry_id)
            .order_by(AppointmentSlot.start_at)
            .all()
        )
        return [slot_response(slot, bookings, inquiry.reference) for slot in slots]


def _mark_slots_offered(
    session: Session, inquiry: Inquiry, slot_reference: str
) -> None:
    """Move an inquiry to ``SLOT_OFFERED`` once it has a published slot.

    Idempotent: re-offering slots to an already-offered inquiry only records a
    note and never regresses a booking/closed status.
    """
    if inquiry_can_transition(inquiry.status, "SLOT_OFFERED"):
        inquiry.status = "SLOT_OFFERED"
    session.add(
        AgencyNote(
            inquiry_id=inquiry.id,
            author="system",
            body=f"Appointment slot {slot_reference} offered to the traveller.",
        )
    )
    session.commit()


@app.post(
    "/api/agency/slots",
    response_model=SlotResponse,
    status_code=status.HTTP_201_CREATED,
)
def agency_create_slot(body: CreateSlotRequest, staff: dict = Depends(require_staff_dep)):
    with db_session() as session:
        inquiry = None
        if body.inquiry_id is not None:
            inquiry = session.get(Inquiry, body.inquiry_id)
            if inquiry is None:
                raise HTTPException(status_code=404, detail="Inquiry not found.")

        slot = AppointmentSlot(
            reference=generate_reference("SLT"),
            inquiry_id=body.inquiry_id,
            start_at=_as_naive_utc(body.start_at),
            end_at=_as_naive_utc(body.end_at),
            timezone_name=body.timezone_name,
            location=body.location,
            capacity=body.capacity,
            published=body.published,
            notes=body.notes,
        )
        session.add(slot)
        try:
            session.commit()
        except IntegrityError:
            session.rollback()
            raise HTTPException(status_code=409, detail="Could not create slot.")

        if inquiry is not None and slot.published:
            _mark_slots_offered(session, inquiry, slot.reference)
        return slot_response(
            slot, [], inquiry.reference if inquiry else None
        )


@app.patch("/api/agency/slots/{slot_id}", response_model=SlotResponse)
def agency_update_slot(
    slot_id: int,
    body: PatchSlotRequest,
    staff: dict = Depends(require_staff_dep),
):
    with db_session() as session:
        slot = session.get(AppointmentSlot, slot_id)
        if slot is None:
            raise HTTPException(status_code=404, detail="Slot not found.")
        bookings = session.query(Booking).filter(Booking.slot_id == slot_id).all()

        reason = validate_slot_edit(
            slot,
            bookings,
            new_capacity=body.capacity,
            new_start=body.start_at,
        )
        if reason:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=reason)

        new_start = body.start_at if body.start_at is not None else slot.start_at
        new_end = body.end_at if body.end_at is not None else slot.end_at
        new_start = _as_naive_utc(new_start)
        new_end = _as_naive_utc(new_end)
        if new_end <= new_start:
            raise HTTPException(status_code=422, detail="end_at must be after start_at")

        for field_name, value in (
            ("start_at", _as_naive_utc(body.start_at) if body.start_at else None),
            ("end_at", _as_naive_utc(body.end_at) if body.end_at else None),
            ("timezone_name", body.timezone_name),
            ("location", body.location),
            ("capacity", body.capacity),
            ("published", body.published),
            ("closed", body.closed),
            ("notes", body.notes),
        ):
            if value is not None:
                setattr(slot, field_name, value)
        session.commit()

        inquiry = session.get(Inquiry, slot.inquiry_id) if slot.inquiry_id else None
        if inquiry is not None and slot.published and not slot.closed:
            _mark_slots_offered(session, inquiry, slot.reference)
        all_bookings = session.query(Booking).all()
        return slot_response(
            slot, all_bookings, inquiry.reference if inquiry else None
        )


@app.get("/api/agency/slots/{slot_id}/bookings", response_model=list[BookingResponse])
def agency_slot_bookings(slot_id: int, staff: dict = Depends(require_staff_dep)):
    with db_session() as session:
        slot = session.get(AppointmentSlot, slot_id)
        if slot is None:
            raise HTTPException(status_code=404, detail="Slot not found.")
        bookings = (
            session.query(Booking)
            .options(selectinload(Booking.inquiry))
            .filter(Booking.slot_id == slot_id)
            .order_by(Booking.created_at)
            .all()
        )
        return [booking_response(b, slot, b.inquiry) for b in bookings]


@app.get("/api/agency/bookings", response_model=list[BookingResponse])
def agency_list_bookings(
    staff: dict = Depends(require_staff_dep),
    date: str | None = Query(default=None, description="YYYY-MM-DD filter (slot date)"),
):
    with db_session() as session:
        bookings = (
            session.query(Booking)
            .options(selectinload(Booking.slot), selectinload(Booking.inquiry))
            .order_by(Booking.created_at.desc())
            .limit(1000)
            .all()
        )
        if date:
            bookings = [
                b for b in bookings
                if b.slot and b.slot.start_at.strftime("%Y-%m-%d") == date
            ]
        return [booking_response(b, b.slot, b.inquiry) for b in bookings]


@app.patch("/api/agency/bookings/{booking_id}", response_model=BookingResponse)
def agency_update_booking(
    booking_id: int,
    body: PatchBookingRequest,
    staff: dict = Depends(require_staff_dep),
):
    with db_session() as session:
        booking = session.get(Booking, booking_id)
        if booking is None:
            raise HTTPException(status_code=404, detail="Booking not found.")
        if body.status is not None:
            target = body.status.upper()
            if not booking_can_transition(booking.status, target):
                raise HTTPException(
                    status_code=status.HTTP_409_CONFLICT,
                    detail=f"Status change from {booking.status} to {target} is not allowed.",
                )
            booking.status = target
            if target == "CANCELLED":
                booking.cancelled_at = utcnow()
                inquiry = session.get(Inquiry, booking.inquiry_id)
                if inquiry:
                    inquiry.status = "SLOT_OFFERED"
        if body.note is not None:
            booking.agency_note = body.note
        session.commit()
        slot = session.get(AppointmentSlot, booking.slot_id)
        inquiry = session.get(Inquiry, booking.inquiry_id)
        return booking_response(booking, slot, inquiry)


@app.get("/api/agency/dashboard")
def agency_dashboard(staff: dict = Depends(require_staff_dep)) -> dict:
    with db_session() as session:
        now = utcnow()
        today = now.strftime("%Y-%m-%d")
        counts = {
            label: session.query(func.count(Inquiry.id)).filter(Inquiry.status == label).scalar()
            for label in ("NEW", "CONTACTED", "SLOT_OFFERED", "BOOKED", "CLOSED")
        }
        confirmed = (
            session.query(Booking)
            .options(selectinload(Booking.slot))
            .filter(Booking.status.in_(["CONFIRMED", "PENDING_CONFIRMATION"]))
            .all()
        )
        return {
            "new_inquiries": counts["NEW"],
            "inquiries_awaiting_response": counts["NEW"] + counts["CONTACTED"],
            "inquiries_with_slots_offered": counts["SLOT_OFFERED"],
            "inquiries_booked": counts["BOOKED"],
            "inquiries_closed": counts["CLOSED"],
            "confirmed_bookings": len([b for b in confirmed if b.status == "CONFIRMED"]),
            "cancelled_bookings": session.query(func.count(Booking.id))
            .filter(Booking.status == "CANCELLED")
            .scalar(),
            "upcoming_appointments": len(
                [b for b in confirmed if b.slot and b.slot.start_at.date() >= now.date()]
            ),
            "todays_appointments": len(
                [b for b in confirmed if b.slot and b.slot.start_at.strftime("%Y-%m-%d") == today]
            ),
        }
