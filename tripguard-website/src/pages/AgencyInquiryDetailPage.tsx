import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  addInquiryNote,
  createSlot,
  deleteInquiry,
  fetchInquiries,
  fetchInquiryNotes,
  fetchInquirySlots,
  updateInquiryStatus,
} from "../services/agencyApi";
import type { AgencyInquiry, AgencyNoteDto, AgencySlot, InquiryStatus } from "../types/agency";
import { InquiryStatusBadge, BookingStatusBadge, SlotStatusBadge } from "../components/AgencyStatusBadge";
import { formatDateTime } from "../utils/statusDisplay";
import { formatSlotUtc } from "../utils/agencyDisplay";

const ALLOWED_TRANSITIONS: Record<InquiryStatus, InquiryStatus[]> = {
  NEW: ["CONTACTED", "SLOT_OFFERED", "CLOSED"],
  CONTACTED: ["SLOT_OFFERED", "CLOSED"],
  SLOT_OFFERED: ["BOOKED", "CLOSED"],
  BOOKED: ["CLOSED"],
  CLOSED: [],
};

interface OfferFormState {
  date: string;
  startTime: string;
  endTime: string;
  timezone: string;
  location: string;
  capacity: number;
  notes: string;
}

const EMPTY_OFFER: OfferFormState = {
  date: "",
  startTime: "09:00",
  endTime: "09:30",
  timezone: "Asia/Dubai",
  location: "TripGuard Dubai Office, Sheikh Zayed Road",
  capacity: 1,
  notes: "",
};



export function AgencyInquiryDetailPage() {
  const { inquiryId } = useParams();
  const navigate = useNavigate();
  const id = Number(inquiryId);

  const [inquiry, setInquiry] = useState<AgencyInquiry | null>(null);
  const [notes, setNotes] = useState<AgencyNoteDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [noteDraft, setNoteDraft] = useState("");
  const [author, setAuthor] = useState("Agency staff");
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<InquiryStatus | null>(null);
  const [offeredSlots, setOfferedSlots] = useState<AgencySlot[]>([]);
  const [offerForm, setOfferForm] = useState<OfferFormState>(EMPTY_OFFER);
  const [offerError, setOfferError] = useState<string | null>(null);
  const [offering, setOffering] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [detail, noteList, slotList] = await Promise.all([
        fetchInquiries().then(
          (all) => all.find((item) => item.id === id) ?? null,
        ),
        fetchInquiryNotes(id).catch(() => [] as AgencyNoteDto[]),
        fetchInquirySlots(id).catch(() => [] as AgencySlot[]),
      ]);
      setInquiry(detail);
      setNotes(noteList);
      setOfferedSlots(slotList);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not load inquiry.");
    } finally {
      setLoading(false);
    }
  }, [id]);

  // Refresh periodically so a booking confirmed in the traveller's app shows up
  // here without a manual reload (spec Phase 6).
  const refresh = useCallback(async () => {
    try {
      const [detail, slotList] = await Promise.all([
        fetchInquiries().then((all) => all.find((item) => item.id === id) ?? null),
        fetchInquirySlots(id).catch(() => [] as AgencySlot[]),
      ]);
      if (detail) setInquiry(detail);
      setOfferedSlots(slotList);
    } catch {
      /* keep the last good state; a manual Refresh is always available */
    }
  }, [id]);

  useEffect(() => {
    void load();
    const timer = setInterval(() => void refresh(), 15000);
    return () => clearInterval(timer);
  }, [load, refresh]);

  async function offerSlot(event: React.FormEvent) {
    event.preventDefault();
    setOfferError(null);
    setOffering(true);
    try {
      await createSlot({
        start_at: `${offerForm.date}T${offerForm.startTime}:00Z`,
        end_at: `${offerForm.date}T${offerForm.endTime}:00Z`,
        timezone_name: offerForm.timezone,
        location: offerForm.location,
        capacity: offerForm.capacity,
        published: true,
        notes: offerForm.notes.trim() || undefined,
        inquiry_id: id,
      });
      setOfferForm(EMPTY_OFFER);
      await load();
    } catch (caught) {
      setOfferError(caught instanceof Error ? caught.message : "Could not offer the slot.");
    } finally {
      setOffering(false);
    }
  }

  async function changeStatus(target: InquiryStatus) {
    if (!inquiry) return;
    setActionError(null);
    try {
      await updateInquiryStatus(inquiry.id, target);
      setInquiry({ ...inquiry, status: target });
      setConfirmTarget(null);
    } catch (caught) {
      setActionError(caught instanceof Error ? caught.message : "Update failed.");
    }
  }

  async function saveNote() {
    if (!inquiry || !noteDraft.trim()) return;
    setActionError(null);
    try {
      const updated = await addInquiryNote(inquiry.id, noteDraft.trim(), author.trim() || "Agency staff");
      setInquiry(updated);
      const refreshed = await fetchInquiryNotes(inquiry.id).catch(() => []);
      setNotes(refreshed);
      setNoteDraft("");
    } catch (caught) {
      setActionError(caught instanceof Error ? caught.message : "Could not save the note.");
    }
  }

  async function removeInquiry() {
    if (!inquiry) return;
    const confirmed = window.confirm(
      `Delete ${inquiry.reference} and its linked slots and bookings? This cannot be undone.`,
    );
    if (!confirmed) return;
    setActionError(null);
    try {
      await deleteInquiry(inquiry.id);
      navigate("/dashboard/inquiries");
    } catch (caught) {
      setActionError(caught instanceof Error ? caught.message : "Could not delete the inquiry.");
    }
  }

  if (loading) return <div className="card">Loading inquiry…</div>;
  if (error || !inquiry) {
    return (
      <div className="card">
        <div className="notice notice--error">
          <p style={{ color: "inherit", margin: 0 }}>
            {error ?? "Inquiry not found."}
          </p>
        </div>
        <Link className="btn btn--secondary" style={{ marginTop: "0.75rem" }} to="/dashboard/inquiries">
          Back to inquiries
        </Link>
      </div>
    );
  }

  const allowed = ALLOWED_TRANSITIONS[inquiry.status] ?? [];

  return (
    <>
      <div className="page__header">
        <div>
          <h1 className="mono">{inquiry.reference}</h1>
          <p className="muted">
            From the TripGuard app · created {formatDateTime(inquiry.created_at)}
          </p>
        </div>
        <div className="actions-row">
          <button className="btn btn--secondary" onClick={() => void refresh()}>
            Refresh
          </button>
          <button className="btn btn--danger" onClick={() => void removeInquiry()}>
            Delete inquiry
          </button>
          <button className="btn btn--secondary" onClick={() => navigate(-1)}>
            Back
          </button>
        </div>
      </div>

      {actionError ? (
        <div className="notice notice--error" style={{ marginBottom: "1rem" }}>
          <p style={{ color: "inherit", margin: 0 }}>{actionError}</p>
        </div>
      ) : null}

      <div className="detail-grid">
        <div>
          <div className="card">
            <div className="card__header">
              <h2 className="card__title">Inquiry</h2>
              <InquiryStatusBadge status={inquiry.status} />
            </div>
            <div className="info-grid">
              <div>
                <div className="info-item__label">Traveller</div>
                <div className="info-item__value">{inquiry.traveller_name}</div>
              </div>
              <div>
                <div className="info-item__label">Email</div>
                <div className="info-item__value">
                  {inquiry.contact_email ?? "—"}
                </div>
              </div>
              <div>
                <div className="info-item__label">Phone</div>
                <div className="info-item__value">
                  {inquiry.contact_phone ?? "—"}
                </div>
              </div>
              <div>
                <div className="info-item__label">Visa route</div>
                <div className="info-item__value">{inquiry.visa_route}</div>
              </div>
              {inquiry.booking_reference ? (
                <>
                  <div>
                    <div className="info-item__label">Booking</div>
                    <div className="info-item__value mono">
                      <Link to="/dashboard/calendar">{inquiry.booking_reference}</Link>
                    </div>
                    <div style={{ marginTop: "0.25rem" }}>
                      {inquiry.booking_status ? (
                        <BookingStatusBadge status={inquiry.booking_status} />
                      ) : null}
                    </div>
                  </div>
                </>
              ) : null}
            </div>
            <div className="divider" />
            <div className="info-item__label">Message</div>
            <p style={{ marginTop: "0.3rem" }}>{inquiry.message}</p>
          </div>

          <div className="card">
            <h2>Consented preparation summary</h2>
            {inquiry.prep_summary ? (
              <>
                <div className="notice notice--info">
                  <p style={{ color: "inherit", margin: 0 }}>
                    Traveller-reported local preparation status. Informational
                    only — it is not a visa eligibility assessment and documents
                    are not attached.
                  </p>
                </div>
                <p style={{ marginTop: "0.75rem" }}>{inquiry.prep_summary}</p>
                <p className="small muted">
                  Missing documents: {inquiry.missing_doc_count} · flagged issues:{" "}
                  {inquiry.issue_count}
                </p>
              </>
            ) : (
              <p className="muted">
                The traveller chose not to share a preparation summary.
              </p>
            )}
          </div>

          <div className="card">
            <h2>Internal notes</h2>
            {notes.length === 0 ? (
              <p className="muted">No notes recorded yet.</p>
            ) : (
              <ul className="list">
                {notes.map((note) => (
                  <li key={note.id} className="list__item">
                    <div className="row row--between">
                      <strong className="small">{note.author}</strong>
                      <span className="small muted">
                        {formatDateTime(note.created_at)}
                      </span>
                    </div>
                    <p style={{ margin: "0.25rem 0 0" }}>{note.body}</p>
                  </li>
                ))}
              </ul>
            )}
            <div className="divider" />
            <div className="textarea-row">
              <div className="field">
                <label htmlFor="author">Author</label>
                <input
                  id="author"
                  value={author}
                  onChange={(event) => setAuthor(event.target.value)}
                />
              </div>
              <div className="field">
                <label htmlFor="note">Add internal note</label>
                <textarea
                  id="note"
                  rows={3}
                  value={noteDraft}
                  onChange={(event) => setNoteDraft(event.target.value)}
                  placeholder="e.g. Called traveller, confirmed appointment interest."
                />
              </div>
              <button className="btn" onClick={() => void saveNote()} disabled={!noteDraft.trim()}>
                Save note
              </button>
            </div>
          </div>
        </div>

        <div>
          <div className="card">
            <h2>Offer appointment slots</h2>
            <p className="small muted">
              Publish one or more times for this traveller. Published slots appear
              in their TripGuard app on refresh, and the inquiry becomes
              slots&nbsp;offered.
            </p>

            {offeredSlots.length === 0 ? (
              <p className="muted">No slots offered to this inquiry yet.</p>
            ) : (
              <ul className="list">
                {offeredSlots.map((slot) => (
                  <li key={slot.id} className="list__item">
                    <div className="row row--between">
                      <span className="mono small">{formatSlotUtc(slot.start_at)}</span>
                      <SlotStatusBadge status={slot.status} />
                    </div>
                    <div className="small muted">
                      → {formatSlotUtc(slot.end_at)} · {slot.location}
                    </div>
                    <div className="small muted">
                      {slot.bookings_count}/{slot.capacity} booked ·{" "}
                      {slot.remaining_capacity} free
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {offerError ? (
              <div className="notice notice--error">
                <p style={{ color: "inherit", margin: 0 }}>{offerError}</p>
              </div>
            ) : null}

            <div className="divider" />
            <form onSubmit={offerSlot} className="stack">
              <div className="field">
                <label htmlFor="offer-date">Date</label>
                <input
                  id="offer-date"
                  type="date"
                  required
                  value={offerForm.date}
                  onChange={(event) => setOfferForm({ ...offerForm, date: event.target.value })}
                />
              </div>
              <div className="row">
                <div className="field">
                  <label htmlFor="offer-start">Start</label>
                  <input
                    id="offer-start"
                    type="time"
                    required
                    value={offerForm.startTime}
                    onChange={(event) => setOfferForm({ ...offerForm, startTime: event.target.value })}
                  />
                </div>
                <div className="field">
                  <label htmlFor="offer-end">End</label>
                  <input
                    id="offer-end"
                    type="time"
                    required
                    value={offerForm.endTime}
                    onChange={(event) => setOfferForm({ ...offerForm, endTime: event.target.value })}
                  />
                </div>
              </div>
              <div className="field">
                <label htmlFor="offer-tz">Time zone</label>
                <select
                  id="offer-tz"
                  value={offerForm.timezone}
                  onChange={(event) => setOfferForm({ ...offerForm, timezone: event.target.value })}
                >
                  <option value="Asia/Dubai">Asia/Dubai (Gulf Standard Time)</option>
                  <option value="Asia/Kolkata">Asia/Kolkata (IST)</option>
                </select>
              </div>
              <div className="field">
                <label htmlFor="offer-location">Location / meeting method</label>
                <input
                  id="offer-location"
                  required
                  value={offerForm.location}
                  onChange={(event) => setOfferForm({ ...offerForm, location: event.target.value })}
                />
              </div>
              <div className="field">
                <label htmlFor="offer-capacity">Capacity</label>
                <input
                  id="offer-capacity"
                  type="number"
                  min={1}
                  max={50}
                  required
                  value={offerForm.capacity}
                  onChange={(event) =>
                    setOfferForm({ ...offerForm, capacity: Number(event.target.value) })
                  }
                />
              </div>
              <div className="field">
                <label htmlFor="offer-notes">Instructions for the traveller (optional)</label>
                <input
                  id="offer-notes"
                  value={offerForm.notes}
                  onChange={(event) => setOfferForm({ ...offerForm, notes: event.target.value })}
                />
              </div>
              <button className="btn" type="submit" disabled={offering}>
                {offering ? "Publishing…" : "Publish slot to this inquiry"}
              </button>
            </form>
          </div>

          <div className="card">
            <h2>Update status</h2>
            <p className="small muted">
              Current: <InquiryStatusBadge status={inquiry.status} />
            </p>
            {allowed.length === 0 ? (
              <p className="muted">This inquiry is closed; no further changes.</p>
            ) : (
              <div className="stack">
                {allowed.map((target) => (
                  <button
                    key={target}
                    className="btn btn--secondary"
                    onClick={() => setConfirmTarget(target)}
                  >
                    Mark as {target.replace(/_/g, " ").toLowerCase()}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="card">
            <h2>Privacy boundary</h2>
            <p className="small muted">
              Only consented contact details, visa route, message and (optionally)
              the preparation summary are shown here. Passport images, ticket,
              insurance, and bank-statement documents as well as OCR-derived
              fields never leave the traveller's device.
            </p>
          </div>
        </div>
      </div>

      {confirmTarget ? (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="card modal-card">
            <h3>Change status to {confirmTarget.replace(/_/g, " ").toLowerCase()}?</h3>
            <p className="small muted">
              The traveller sees the updated status in the app on next refresh.
            </p>
            <div className="actions-row">
              <button className="btn" onClick={() => void changeStatus(confirmTarget)}>
                Confirm
              </button>
              <button className="btn btn--secondary" onClick={() => setConfirmTarget(null)}>
                Cancel
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
