import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { fetchBookings, updateBooking } from "../services/agencyApi";
import type { AgencyBooking } from "../types/agency";
import { BookingStatusBadge } from "../components/AgencyStatusBadge";
import { formatSlotUtc } from "../utils/agencyDisplay";
import { formatDateTime } from "../utils/statusDisplay";

const FILTERS: { key: string; label: string; match: (b: AgencyBooking) => boolean }[] = [
  { key: "all", label: "All", match: () => true },
  { key: "confirmed", label: "Confirmed", match: (b) => b.status === "CONFIRMED" },
  { key: "pending", label: "Pending", match: (b) => b.status === "PENDING_CONFIRMATION" },
  { key: "completed", label: "Completed", match: (b) => b.status === "COMPLETED" },
  { key: "cancelled", label: "Cancelled", match: (b) => b.status === "CANCELLED" },
];

export function AgencyBookingsPage() {
  const [bookings, setBookings] = useState<AgencyBooking[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState("all");
  const [confirmCancelId, setConfirmCancelId] = useState<number | null>(null);
  const [noteDrafts, setNoteDrafts] = useState<Record<number, string>>({});
  const [actionError, setActionError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setBookings(await fetchBookings());
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Could not load bookings.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const active = FILTERS.find((f) => f.key === filter) ?? FILTERS[0];
  const visible = bookings
    .filter(active.match)
    .sort((a, b) => (b.slot_start_at ?? "").localeCompare(a.slot_start_at ?? ""));

  async function cancelBooking(id: number) {
    setActionError(null);
    try {
      await updateBooking(id, { status: "CANCELLED" });
      setConfirmCancelId(null);
      await load();
    } catch (caught) {
      setActionError(caught instanceof Error ? caught.message : "Cancellation failed.");
      setConfirmCancelId(null);
    }
  }

  async function saveNote(booking: AgencyBooking) {
    const draft = noteDrafts[booking.id]?.trim();
    if (!draft) return;
    setActionError(null);
    try {
      await updateBooking(booking.id, { note: draft });
      setNoteDrafts((prev) => ({ ...prev, [booking.id]: "" }));
      await load();
    } catch (caught) {
      setActionError(caught instanceof Error ? caught.message : "Could not save the note.");
    }
  }

  return (
    <>
      <div className="page__header">
        <div>
          <h1>Bookings</h1>
          <p className="muted">
            Manage booking status and internal notes. Cancelling a booking
            restores the slot capacity for other travellers.
          </p>
        </div>
        <button className="btn btn--secondary" onClick={() => void load()}>
          Refresh
        </button>
      </div>

      <div className="row" style={{ marginBottom: "1rem" }}>
        {FILTERS.map((f) => (
          <button
            key={f.key}
            className={`btn btn--small ${filter === f.key ? "" : "btn--secondary"}`}
            onClick={() => setFilter(f.key)}
          >
            {f.label}
          </button>
        ))}
      </div>

      {actionError ? (
        <div className="notice notice--error" style={{ marginBottom: "1rem" }}>
          <p style={{ color: "inherit", margin: 0 }}>{actionError}</p>
        </div>
      ) : null}

      {error ? (
        <div className="card">
          <div className="notice notice--error">
            <p style={{ color: "inherit", margin: 0 }}>{error}</p>
            <button className="btn" style={{ marginTop: "0.5rem" }} onClick={() => void load()}>
              Retry
            </button>
          </div>
        </div>
      ) : null}

      {loading ? <div className="card">Loading bookings…</div> : null}

      {!loading && !error ? (
        <div className="stack">
          {visible.map((booking) => (
            <div key={booking.id} className="card">
              <div className="card__header">
                <div>
                  <h2 className="card__title mono">{booking.reference}</h2>
                  <p className="small muted" style={{ margin: 0 }}>
                    Inquiry{" "}
                    <Link className="mono" to="/dashboard/inquiries">
                      {booking.inquiry_reference}
                    </Link>{" "}
                    · booked {formatDateTime(booking.created_at)}
                  </p>
                </div>
                <BookingStatusBadge status={booking.status} />
              </div>
              <div className="info-grid">
                <div>
                  <div className="info-item__label">Slot</div>
                  <div className="info-item__value small">
                    {formatSlotUtc(booking.slot_start_at)}
                  </div>
                  <div className="small muted">{booking.slot_timezone}</div>
                </div>
                <div>
                  <div className="info-item__label">Location</div>
                  <div className="info-item__value small">{booking.slot_location ?? "—"}</div>
                </div>
                {booking.cancelled_at ? (
                  <div>
                    <div className="info-item__label">Cancelled</div>
                    <div className="info-item__value small">
                      {formatDateTime(booking.cancelled_at)}
                    </div>
                  </div>
                ) : null}
              </div>
              {booking.status === "CONFIRMED" ? (
                <div className="actions-row">
                  <button
                    className="btn btn--danger btn--small"
                    onClick={() => setConfirmCancelId(booking.id)}
                  >
                    Cancel booking
                  </button>
                </div>
              ) : null}
              <div className="divider" />
              <div className="textarea-row">
                <div className="field">
                  <label htmlFor={`note-${booking.id}`}>Agency note</label>
                  <textarea
                    id={`note-${booking.id}`}
                    rows={2}
                    placeholder={booking.agency_note ?? "Add an internal note…"}
                    value={noteDrafts[booking.id] ?? ""}
                    onChange={(event) =>
                      setNoteDrafts((prev) => ({
                        ...prev,
                        [booking.id]: event.target.value,
                      }))
                    }
                  />
                </div>
                <button
                  className="btn btn--secondary btn--small"
                  onClick={() => void saveNote(booking)}
                  disabled={!noteDrafts[booking.id]?.trim()}
                >
                  Save note
                </button>
              </div>
            </div>
          ))}
          {visible.length === 0 ? (
            <div className="card">
              <p className="empty-state">No matching bookings.</p>
            </div>
          ) : null}
        </div>
      ) : null}

      {confirmCancelId !== null ? (
        <div className="modal-backdrop" role="dialog" aria-modal="true">
          <div className="card modal-card">
            <h3>Cancel this booking?</h3>
            <p className="small muted">
              The traveller's app will show the cancellation on next refresh and
              the slot capacity becomes available again.
            </p>
            <div className="actions-row">
              <button className="btn btn--danger" onClick={() => void cancelBooking(confirmCancelId)}>
                Cancel booking
              </button>
              <button className="btn btn--secondary" onClick={() => setConfirmCancelId(null)}>
                Keep booking
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}
