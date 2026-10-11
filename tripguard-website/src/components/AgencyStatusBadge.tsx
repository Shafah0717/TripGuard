import type { BookingStatus, InquiryStatus, SlotStatus } from "../types/agency";
import {
  bookingStatusDisplay,
  inquiryStatusDisplay,
  slotStatusDisplay,
} from "../utils/agencyDisplay";

export function InquiryStatusBadge({ status }: { status: InquiryStatus }) {
  const display = inquiryStatusDisplay(status);
  return <span className={`badge ${display.className}`}>{display.label}</span>;
}

export function BookingStatusBadge({ status }: { status: BookingStatus }) {
  const display = bookingStatusDisplay(status);
  return <span className={`badge ${display.className}`}>{display.label}</span>;
}

export function SlotStatusBadge({ status }: { status: SlotStatus }) {
  const display = slotStatusDisplay(status);
  return <span className={`badge ${display.className}`}>{display.label}</span>;
}
