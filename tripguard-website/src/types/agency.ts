/**
 * Response shapes of the TripGuard Agency API as consumed by the dashboard.
 * Mirrors the schemas documented in the repository-level API contract.
 */

export type InquiryStatus =
  | "NEW"
  | "CONTACTED"
  | "SLOT_OFFERED"
  | "BOOKED"
  | "CLOSED";

export type BookingStatus =
  | "CONFIRMED"
  | "PENDING_CONFIRMATION"
  | "CANCELLED"
  | "COMPLETED";

export type SlotStatus =
  | "AVAILABLE"
  | "FULL"
  | "CLOSED"
  | "UNPUBLISHED"
  | "IN_PAST";

export interface AgencyInquiry {
  id: number;
  reference: string;
  status: InquiryStatus;
  traveller_name: string;
  contact_email: string | null;
  contact_phone: string | null;
  visa_route: string;
  message: string;
  prep_summary: string | null;
  missing_doc_count: number;
  issue_count: number;
  internal_note: string | null;
  created_at: string;
  updated_at: string;
  booking_reference: string | null;
  booking_status: BookingStatus | null;
  slots_offered: number;
}

export interface AgencySlot {
  id: number;
  reference: string;
  /** Naive-UTC timestamp, e.g. "2026-11-20T09:00:00". */
  start_at: string;
  end_at: string;
  timezone_name: string;
  location: string;
  capacity: number;
  published: boolean;
  closed: boolean;
  notes: string | null;
  /** When set, the slot was offered to a specific inquiry. */
  inquiry_id: number | null;
  inquiry_reference: string | null;
  status: SlotStatus;
  bookings_count: number;
  remaining_capacity: number;
}

export interface AgencyBooking {
  id: number;
  reference: string;
  status: BookingStatus;
  inquiry_id: number;
  slot_id: number;
  created_at: string;
  cancelled_at: string | null;
  agency_note: string | null;
  inquiry_reference: string;
  slot_start_at: string | null;
  slot_end_at: string | null;
  slot_timezone: string | null;
  slot_location: string | null;
}

export interface DashboardStats {
  new_inquiries: number;
  inquiries_awaiting_response: number;
  inquiries_with_slots_offered: number;
  inquiries_booked: number;
  inquiries_closed: number;
  confirmed_bookings: number;
  cancelled_bookings: number;
  upcoming_appointments: number;
  todays_appointments: number;
}

export interface AgencyNoteDto {
  id: number;
  author: string;
  body: string;
  created_at: string;
}
