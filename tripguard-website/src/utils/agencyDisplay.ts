import type {
  BookingStatus,
  InquiryStatus,
  SlotStatus,
} from "../types/agency";
import type { StatusDisplay } from "./statusDisplay";

const INQUIRY_DISPLAY: Record<InquiryStatus, StatusDisplay> = {
  NEW: { label: "New", className: "badge--blue" },
  CONTACTED: { label: "Contacted", className: "badge--amber" },
  SLOT_OFFERED: { label: "Slot offered", className: "badge--purple" },
  BOOKED: { label: "Booked", className: "badge--green" },
  CLOSED: { label: "Closed", className: "badge--slate" },
};

const BOOKING_DISPLAY: Record<BookingStatus, StatusDisplay> = {
  CONFIRMED: { label: "Confirmed", className: "badge--green" },
  PENDING_CONFIRMATION: { label: "Pending confirmation", className: "badge--amber" },
  CANCELLED: { label: "Cancelled", className: "badge--red" },
  COMPLETED: { label: "Completed", className: "badge--slate" },
};

const SLOT_DISPLAY: Record<SlotStatus, StatusDisplay> = {
  AVAILABLE: { label: "Available", className: "badge--green" },
  FULL: { label: "Full", className: "badge--amber" },
  CLOSED: { label: "Closed", className: "badge--slate" },
  UNPUBLISHED: { label: "Draft (not published)", className: "badge--orange" },
  IN_PAST: { label: "Past", className: "badge--slate" },
};

export function inquiryStatusDisplay(status: InquiryStatus): StatusDisplay {
  return INQUIRY_DISPLAY[status] ?? { label: status, className: "badge--slate" };
}

export function bookingStatusDisplay(status: BookingStatus): StatusDisplay {
  return BOOKING_DISPLAY[status] ?? { label: status, className: "badge--slate" };
}

export function slotStatusDisplay(status: SlotStatus): StatusDisplay {
  return SLOT_DISPLAY[status] ?? { label: status, className: "badge--slate" };
}

/**
 * The API stores naive-UTC datetimes; slot `start_at`/`end_at` are created by
 * staff in `timezone_name` (typically Asia/Dubai). Display them as UTC wall
 * time with the slot's own timezone label so travellers and staff see the
 * same agreed time.
 */
export function formatSlotUtc(value: string | null | undefined): string {
  if (!value) return "—";
  const parsed = new Date(value.endsWith("Z") ? value : `${value}Z`);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    timeZone: "UTC",
  });
}

export function formatTimeUtc(value: string | null | undefined): string {
  if (!value) return "—";
  const parsed = new Date(value.endsWith("Z") ? value : `${value}Z`);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    timeZone: "UTC",
  });
}

export function slotDateKey(value: string): string {
  return value.slice(0, 10);
}
