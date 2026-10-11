"""Database layer for the TripGuard Agency API.

SQLite with WAL and a busy timeout; access is serialised through a global lock
(``DB_LOCK``) plus ``BEGIN IMMEDIATE`` transactions so the optimistic capacity
check and the reservation insert inside ``create_booking`` are one atomic step
even with concurrent writers. Model columns and indexes follow the contract in
``scrolls/api-contract.md``. No column in this module stores documents, raw OCR
text, or OCR-derived personal document fields.
"""

from __future__ import annotations

import threading
from datetime import datetime, timezone

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    create_engine,
    event,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship, sessionmaker

DATABASE_PATH = "data/tripguard.db"
DB_LOCK = threading.RLock()


def utcnow() -> datetime:
    """Naive-UTC clock. SQLite stores datetimes without offsets, so every
    comparison in this service uses the same naive-UTC convention."""
    return datetime.now(timezone.utc).replace(tzinfo=None)


class Base(DeclarativeBase):
    pass


class AgencyUser(Base):
    """Agency staff login. Identity and role only — never traveller data."""

    __tablename__ = "agency_users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    role: Mapped[str] = mapped_column(String(16), default="STAFF", nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, nullable=False
    )


class Inquiry(Base):
    """A consented contact request raised from the TripGuard app."""

    __tablename__ = "inquiries"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    reference: Mapped[str] = mapped_column(String(32), unique=True, nullable=False)
    status: Mapped[str] = mapped_column(String(16), default="NEW", nullable=False)
    traveller_name: Mapped[str] = mapped_column(String(120), nullable=False)
    contact_email: Mapped[str | None] = mapped_column(String(254), nullable=True)
    contact_phone: Mapped[str | None] = mapped_column(String(32), nullable=True)
    visa_route: Mapped[str] = mapped_column(String(80), nullable=False)
    message: Mapped[str] = mapped_column(Text, nullable=False)
    # Optional, explicitly consented preparation summary. Informational only.
    prep_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    missing_doc_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    issue_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    internal_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Public scoping token for traveller follow-up access (not auth-grade alone).
    traveller_token: Mapped[str] = mapped_column(String(48), unique=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, onupdate=utcnow, nullable=False
    )

    consent: Mapped["ConsentRecord | None"] = relationship(
        back_populates="inquiry", uselist=False, cascade="all, delete-orphan"
    )
    bookings: Mapped[list["Booking"]] = relationship(back_populates="inquiry")
    notes: Mapped[list["AgencyNote"]] = relationship(
        back_populates="inquiry", cascade="all, delete-orphan"
    )
    offered_slots: Mapped[list["AppointmentSlot"]] = relationship(
        back_populates="inquiry"
    )


Index("ix_inquiries_status_created", Inquiry.status, Inquiry.created_at)


class ConsentRecord(Base):
    """Which data categories the traveller explicitly agreed to share."""

    __tablename__ = "consent_records"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    inquiry_id: Mapped[int] = mapped_column(
        ForeignKey("inquiries.id", ondelete="CASCADE"), unique=True, nullable=False
    )
    version: Mapped[str] = mapped_column(String(16), nullable=False)
    consented_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, nullable=False
    )
    categories: Mapped[str] = mapped_column(Text, nullable=False)  # CSV of category codes
    shared_prep_summary: Mapped[bool] = mapped_column(Boolean, nullable=False)

    inquiry: Mapped[Inquiry] = relationship(back_populates="consent")


class AppointmentSlot(Base):
    """A bookable appointment window published by the agency.

    A slot may be targeted at one specific inquiry (``inquiry_id`` set) — the
    "offer appointment slots" flow — or left unassigned as a general pool slot.
    Travellers only ever see published slots that are either offered to their
    own inquiry or part of the unassigned general pool.
    """

    __tablename__ = "appointment_slots"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    reference: Mapped[str] = mapped_column(String(32), unique=True, nullable=False)
    # Nullable: NULL means a general (service-level) slot visible to all travellers.
    inquiry_id: Mapped[int | None] = mapped_column(
        ForeignKey("inquiries.id"), nullable=True, index=True
    )
    start_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    end_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    timezone_name: Mapped[str] = mapped_column(String(64), nullable=False)
    location: Mapped[str] = mapped_column(String(255), nullable=False)
    capacity: Mapped[int] = mapped_column(Integer, nullable=False)
    published: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    closed: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, onupdate=utcnow, nullable=False
    )

    bookings: Mapped[list["Booking"]] = relationship(back_populates="slot")
    inquiry: Mapped["Inquiry | None"] = relationship(back_populates="offered_slots")


Index(
    "ix_slots_start_published",
    AppointmentSlot.start_at,
    AppointmentSlot.published,
)

Index(
    "ix_slots_inquiry_published",
    AppointmentSlot.inquiry_id,
    AppointmentSlot.published,
)


class Booking(Base):
    __tablename__ = "bookings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    reference: Mapped[str] = mapped_column(String(32), unique=True, nullable=False)
    status: Mapped[str] = mapped_column(String(16), default="CONFIRMED", nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(64), nullable=False)
    inquiry_id: Mapped[int] = mapped_column(
        ForeignKey("inquiries.id"), nullable=False, index=True
    )
    slot_id: Mapped[int] = mapped_column(
        ForeignKey("appointment_slots.id"), nullable=False, index=True
    )
    agency_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, nullable=False
    )
    cancelled_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, onupdate=utcnow, nullable=False
    )

    inquiry: Mapped[Inquiry] = relationship(back_populates="bookings")
    slot: Mapped["AppointmentSlot"] = relationship(back_populates="bookings")

    __table_args__ = (
        UniqueConstraint("inquiry_id", "slot_id", name="uq_booking_inquiry_slot"),
        Index(
            "uq_active_booking_per_inquiry",
            inquiry_id,
            unique=True,
            sqlite_where=(status.not_in(["CANCELLED"])),
        ),
    )


Index(
    "uq_active_idempotency_key",
    Booking.idempotency_key,
    unique=True,
    sqlite_where=(Booking.status.not_in(["CANCELLED"])),
)


class AgencyNote(Base):
    __tablename__ = "agency_notes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    inquiry_id: Mapped[int] = mapped_column(
        ForeignKey("inquiries.id", ondelete="CASCADE"), nullable=False, index=True
    )
    author: Mapped[str] = mapped_column(String(64), nullable=False)
    body: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, nullable=False
    )

    inquiry: Mapped[Inquiry] = relationship(back_populates="notes")


def create_engine_and_session(database_path: str = DATABASE_PATH):
    """Engine/session factory. ``check_same_thread=False`` is safe here because
    every transaction is serialised through ``DB_LOCK``."""
    engine = create_engine(
        f"sqlite:///{database_path}",
        connect_args={"check_same_thread": False, "timeout": 30},
    )

    @event.listens_for(engine, "connect")
    def _set_sqlite_pragma(dbapi_connection, _record):
        cursor = dbapi_connection.cursor()
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA busy_timeout=30000")
        cursor.close()

    return engine, sessionmaker(bind=engine, expire_on_commit=False)


def _run_lightweight_migrations(engine) -> None:
    """Add columns introduced after the first release to already-created DBs.

    The project ships without Alembic; ``Base.metadata.create_all`` only creates
    missing tables and never alters existing ones. These additive-only statements
    keep existing development databases usable without a destructive reset.
    """
    from sqlalchemy import inspect, text

    inspector = inspect(engine)
    if "appointment_slots" not in inspector.get_table_names():
        return
    columns = {col["name"] for col in inspector.get_columns("appointment_slots")}
    with engine.begin() as conn:
        if "inquiry_id" not in columns:
            conn.execute(
                text("ALTER TABLE appointment_slots ADD COLUMN inquiry_id INTEGER")
            )
        conn.execute(
            text(
                "CREATE INDEX IF NOT EXISTS ix_slots_inquiry_published "
                "ON appointment_slots (inquiry_id, published)"
            )
        )


def init_db(database_path: str = DATABASE_PATH):
    engine, _ = create_engine_and_session(database_path)
    Base.metadata.create_all(engine)
    _run_lightweight_migrations(engine)
    return engine
