import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  AgencyApiError,
  AgencyNetworkError,
  createSlot,
  fetchInquiries,
  fetchSlotBookings,
  fetchSlots,
  updateSlot,
} from "../services/agencyApi";
import type { AgencyBooking, AgencyInquiry, AgencySlot } from "../types/agency";
import { SlotStatusBadge } from "../components/AgencyStatusBadge";
import { bookingStatusDisplay } from "../utils/agencyDisplay";
import { formatSlotUtc } from "../utils/agencyDisplay";

interface SlotFormState {
  date: string;
  startTime: string;
  endTime: string;
  timezone: string;
  location: string;
  capacity: number;
  notes: string;
  inquiryId: string;
}

const EMPTY_FORM: SlotFormState = {
  date: "",
  startTime: "09:00",
  endTime: "09:30",
  timezone: "Asia/Dubai",
  location: "TripGuard Dubai Office, Sheikh Zayed Road",
  capacity: 1,
  notes: "",
  inquiryId: "",
};

export function AgencySlotsPage() {
  const [slots, setSlots] = useState<AgencySlot[]>([]);
  const [inquiries, setInquiries] = useState<AgencyInquiry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState<SlotFormState>(EMPTY_FORM);
  const [formError, setFormError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [confirmCloseId, setConfirmCloseId] = useState<number | null>(null);
  const [viewBookingsId, setViewBookingsId] = useState<number | null>(null);
  const [slotBookings, setSlotBookings] = useState<AgencyBooking[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSlots(await fetchSlots());
      setInquiries(await fetchInquiries());
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not load slots.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    const timer = setInterval(() => void load(), 15000);
    return () => clearInterval(timer);
  }, [load]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setFormError(null);
    setCreating(true);
    try {
      await createSlot({
        // Stored as naive wall time in the agency timezone; sent with Z so the
        // backend normalises to naive-UTC.
        start_at: `${form.date}T${form.startTime}:00Z`,
        end_at: `${form.date}T${form.endTime}:00Z`,
        timezone_name: form.timezone,
        location: form.location,
        capacity: form.capacity,
        published: true,
        notes: form.notes.trim() || undefined,
        inquiry_id: form.inquiryId ? Number(form.inquiryId) : undefined,
      });
      setForm(EMPTY_FORM);
      await load();
    } catch (caught) {
      setFormError(
        caught instanceof AgencyApiError || caught instanceof AgencyNetworkError
          ? caught.message
          : "Could not create the slot.",
      );
    } finally {
      setCreating(false);
    }
  }

  async function togglePublish(slot: AgencySlot) {
    setError(null);
    try {
      await updateSlot(slot.id, { published: !slot.published });
      await load();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Update failed.");
    }
  }

  async function closeSlot(id: number) {
    try {
      await updateSlot(id, { closed: true });
      setConfirmCloseId(null);
      await load();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not close slot.");
      setConfirmCloseId(null);
    }
  }

  async function openBookings(slot: AgencySlot) {
    setViewBookingsId(slot.id);
    try {
      setSlotBookings(await fetchSlotBookings(slot.id));
    } catch {
      setSlotBookings([]);
    }
  }

  return (
    <>
      <div className="page__header">
        <div>
          <h1>Appointment slots</h1>
          <p className="muted">
            Slots linked to a traveller inquiry appear in that traveller's app
            instantly. General (unlinked) slots never reach the app — link an
            inquiry so published times are delivered to the mobile client.
          </p>
        </div>
        <button className="btn btn--secondary" onClick={() => void load()}>
          Refresh
        </button>
      </div>

      {error ? (
        <div className="notice notice--error" style={{ marginBottom: "1rem" }}>
          <p style={{ color: "inherit", margin: 0 }}>{error}</p>
        </div>
      ) : null}

      <div className="detail-grid">
        <div className="card">
          <h2>Slots</h2>
          {loading ? <p>Loading…</p> : null}
          {!loading && slots.length === 0 ? (
            <p className="muted">No slots created yet. Use the form to add one.</p>
          ) : null}
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>When (agency tz)</th>
                  <th>Location</th>
                  <th>Linked inquiry</th>
                  <th>Capacity</th>
                  <th>Status</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {slots.map((slot) => (
                  <tr key={slot.id}>
                    <td>
                      <div className="mono small">{formatSlotUtc(slot.start_at)}</div>
                      <div className="mono small muted">
                        → {formatSlotUtc(slot.end_at)} · {slot.timezone_name}
                      </div>
                    </td>
                    <td className="small">{slot.location}</td>
                    <td className="small">
                      {slot.inquiry_reference ? (
                        <Link
                          className="mono small"
                          to={`/dashboard/inquiries/${slot.inquiry_id}`}
                        >
                          {slot.inquiry_reference}
                        </Link>
                      ) : (
                        <span className="muted small">General (not in app)</span>
                      )}
                    </td>
                    <td className="small">
                      {slot.bookings_count}/{slot.capacity} booked
                      <div className="muted small">{slot.remaining_capacity} free</div>
                    </td>
                    <td>
                      <SlotStatusBadge status={slot.status} />
                    </td>
                    <td>
                      <div className="actions-row">
                        {!slot.closed ? (
                          <button
                            className="btn btn--secondary btn--small"
                            onClick={() => void togglePublish(slot)}
                          >
                            {slot.published ? "Unpublish" : "Publish"}
                          </button>
                        ) : null}
                        {!slot.closed && slot.bookings_count === 0 ? (
                          <button
                            className="btn btn--danger btn--small"
                            onClick={() => setConfirmCloseId(slot.id)}
                          >
                            Close
                          </button>
                        ) : null}
                        <button
                          className="btn btn--secondary btn--small"
                          onClick={() => void openBookings(slot)}
                        >
                          Bookings
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
                {!loading && slots.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="empty-state">
                      No appointment slots yet.
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
          </div>
        </div>

        <div className="card">
          <h2>Create slot</h2>
          {formError ? (
            <div className="notice notice--error">
              <p style={{ color: "inherit", margin: 0 }}>{formError}</p>
            </div>
          ) : null}
          <form onSubmit={submit} className="stack">
            <div className="field">
              <label htmlFor="slot-date">Date</label>
              <input
                id="slot-date"
                type="date"
                required
                value={form.date}
                onChange={(event) => setForm({ ...form, date: event.target.value })}
              />
            </div>
            <div className="row">
              <div className="field">
                <label htmlFor="slot-start">Start</label>
                <input
                  id="slot-start"
                  type="time"
                  required
                  value={form.startTime}
                  onChange={(event) => setForm({ ...form, startTime: event.target.value })}
                />
              </div>
              <div className="field">
                <label htmlFor="slot-end">End</label>
                <input
                  id="slot-end"
                  type="time"
                  required
                  value={form.endTime}
                  onChange={(event) => setForm({ ...form, endTime: event.target.value })}
                />
              </div>
            </div>
            <div className="field">
              <label htmlFor="slot-tz">Time zone</label>
              <select
                id="slot-tz"
                value={form.timezone}
                onChange={(event) => setForm({ ...form, timezone: event.target.value })}
              >
                <option value="Asia/Dubai">Asia/Dubai (Gulf Standard Time)</option>
                <option value="Asia/Kolkata">Asia/Kolkata (IST)</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor="slot-inquiry">Offer this time to</label>
              <select
                id="slot-inquiry"
                value={form.inquiryId}
                onChange={(event) => setForm({ ...form, inquiryId: event.target.value })}
              >
                <option value="">
                  Link to a traveller inquiry (needed to reach the app)
                </option>
                {inquiries
                  .filter(
                    (inquiry) =>
                      inquiry.status !== "CLOSED" && inquiry.status !== "BOOKED",
                  )
                  .map((inquiry) => (
                    <option key={inquiry.id} value={String(inquiry.id)}>
                      {inquiry.reference} — {inquiry.traveller_name} (
                      {inquiry.status})
                    </option>
                  ))}
              </select>
              <p className="field__hint">
                A slot without a linked inquiry is general and is never shown in the
                mobile app.
              </p>
            </div>
            <div className="field">
              <label htmlFor="slot-location">Location / meeting method</label>
              <input
                id="slot-location"
                required
                value={form.location}
                onChange={(event) => setForm({ ...form, location: event.target.value })}
              />
            </div>
            <div className="field">
              <label htmlFor="slot-capacity">Capacity</label>
              <input
                id="slot-capacity"
                type="number"
                min={1}
                max={50}
                required
                value={form.capacity}
                onChange={(event) =>
                  setForm({ ...form, capacity: Number(event.target.value) })
                }
              />
            </div>
            <div className="field">
              <label htmlFor="slot-notes">Instructions for travellers (optional)</label>
              <input
                id="slot-notes"
                value={form.notes}
                onChange={(event) => setForm({ ...form, notes: event.target.value })}
              />
            </div>
            <button className="btn" type="submit" disabled={creating}>
              {creating ? "Creating…" : "Create and publish"}
            </button>
          </form>
        </div>
      </div>

      {confirmCloseId !== null ? (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="card modal-card">
            <h3>Close this slot?</h3>
            <p className="small muted">
              The slot will no longer appear in travellers' apps. Bookings already
              confirmed on it remain valid — changes to those are handled in the
              calendar.
            </p>
            <div className="actions-row">
              <button className="btn btn--danger" onClick={() => void closeSlot(confirmCloseId)}>
                Close slot
              </button>
              <button className="btn btn--secondary" onClick={() => setConfirmCloseId(null)}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {viewBookingsId !== null ? (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="card modal-card">
            <h3>Bookings for this slot</h3>
            {slotBookings.length === 0 ? (
              <p className="muted">No bookings on this slot.</p>
            ) : (
              <ul className="list">
                {slotBookings.map((booking) => (
                  <li key={booking.id} className="list__item">
                    <div className="row row--between">
                      <span className="mono small">{booking.reference}</span>
                      <span className={`badge ${bookingStatusDisplay(booking.status).className}`}>
                        {bookingStatusDisplay(booking.status).label}
                      </span>
                    </div>
                    <div className="small muted">
                      Inquiry {booking.inquiry_reference}
                    </div>
                  </li>
                ))}
              </ul>
            )}
            <div className="actions-row">
              <Link className="btn btn--secondary" to="/dashboard/calendar">
                Open calendar
              </Link>
              <button className="btn btn--secondary" onClick={() => setViewBookingsId(null)}>
                Close
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
