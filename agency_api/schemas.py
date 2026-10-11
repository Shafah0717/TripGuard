from __future__ import annotations

import re
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic import BaseModel


EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
PHONE_RE = re.compile(r"^\+?[0-9][0-9\s\-()]{5,20}$")
TZ_RE = re.compile(r"^[A-Za-z_]+/[A-Za-z0-9_+\-]+$")


class ConsentPayload(BaseModel):
    version: str = "1.0"
    categories: list[str]
    consented_at: datetime | None = None


class CreateInquiryRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    traveller_name: str = Field(min_length=2, max_length=120)
    contact_email: str | None = Field(default=None, max_length=254)
    contact_phone: str | None = Field(default=None, max_length=32)
    visa_route: str = Field(min_length=2, max_length=80)
    message: str = Field(min_length=3, max_length=2000)
    prep_summary: str | None = Field(default=None, max_length=2000)
    missing_doc_count: int = Field(default=0, ge=0, le=99)
    issue_count: int = Field(default=0, ge=0, le=99)
    include_prep_summary: bool = False
    consent: ConsentPayload

    @model_validator(mode="after")
    def check_contact_and_consent(self) -> "CreateInquiryRequest":
        if not self.contact_email and not self.contact_phone:
            raise ValueError("at least one contact method (email or phone) is required")
        if self.contact_email and not EMAIL_RE.match(self.contact_email):
            raise ValueError("contact_email is not a valid address")
        if self.contact_phone and not PHONE_RE.match(self.contact_phone):
            raise ValueError("contact_phone is not a valid number")
        if "INQUIRY_DETAILS" not in self.consent.categories:
            raise ValueError('consent must include the "INQUIRY_DETAILS" category')
        if self.include_prep_summary and "PREP_SUMMARY" not in self.consent.categories:
            raise ValueError(
                'including a prep summary requires the "PREP_SUMMARY" consent category'
            )
        return self


class InquiryCreated(BaseModel):
    id: int
    reference: str
    status: str
    traveller_token: str
    created_at: datetime


class TravellerInquiryResponse(BaseModel):
    id: int
    reference: str
    status: str
    visa_route: str
    traveller_name: str
    created_at: datetime
    updated_at: datetime


class AgencyInquiryResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    reference: str
    status: str
    traveller_name: str
    contact_email: str | None
    contact_phone: str | None
    visa_route: str
    message: str
    prep_summary: str | None
    missing_doc_count: int
    issue_count: int
    internal_note: str | None
    created_at: datetime
    updated_at: datetime
    booking_reference: str | None = None
    booking_status: str | None = None
    slots_offered: int = 0


class InquiryNoteRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    note: str = Field(min_length=1, max_length=2000)
    author: str = Field(min_length=1, max_length=64)


class InquiryStatusRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    status: str


class CreateSlotRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    start_at: datetime
    end_at: datetime
    timezone_name: str = Field(min_length=3, max_length=64)
    location: str = Field(min_length=2, max_length=255)
    capacity: int = Field(ge=1, le=50)
    published: bool = False
    notes: str | None = Field(default=None, max_length=1000)
    # When set, the slot is offered to that specific inquiry; NULL means a
    # general pool slot visible to every traveller.
    inquiry_id: int | None = Field(default=None, ge=1)

    @field_validator("timezone_name")
    @classmethod
    def tz_shape(cls, v: str) -> str:
        if not TZ_RE.match(v):
            raise ValueError("timezone_name must look like Region/City")
        return v

    @model_validator(mode="after")
    def check_window(self) -> "CreateSlotRequest":
        if self.end_at <= self.start_at:
            raise ValueError("end_at must be after start_at")
        return self


class PatchSlotRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    start_at: datetime | None = None
    end_at: datetime | None = None
    timezone_name: str | None = Field(default=None, min_length=3, max_length=64)
    location: str | None = Field(default=None, min_length=2, max_length=255)
    capacity: int | None = Field(default=None, ge=1, le=50)
    published: bool | None = None
    closed: bool | None = None
    notes: str | None = Field(default=None, max_length=1000)


class SlotResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    reference: str
    start_at: datetime
    end_at: datetime
    timezone_name: str
    location: str
    capacity: int
    published: bool
    closed: bool
    notes: str | None
    inquiry_id: int | None = None
    inquiry_reference: str | None = None
    status: str = "AVAILABLE"
    bookings_count: int = 0
    remaining_capacity: int = 0


class CreateBookingRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    travellers_token: str = Field(min_length=8, max_length=48)
    slot_id: int
    idempotency_key: str = Field(min_length=1, max_length=64)


class BookingResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    reference: str
    status: str
    inquiry_id: int
    slot_id: int
    created_at: datetime
    cancelled_at: datetime | None
    agency_note: str | None
    inquiry_reference: str = ""
    slot_start_at: datetime | None = None
    slot_end_at: datetime | None = None
    slot_timezone: str | None = None
    slot_location: str | None = None


class PatchBookingRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    status: str | None = None
    note: str | None = Field(default=None, max_length=1000)


class AgencyNoteResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: int
    author: str
    body: str
    created_at: datetime


class LoginRequest(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)
    username: str = Field(min_length=1, max_length=64)
    password: str = Field(min_length=1, max_length=128)


class TokenResponse(BaseModel):
    access_token: str
    token_type: Literal["bearer"] = "bearer"
    role: str


class MessageResponse(BaseModel):
    message: str
