import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { fetchBookings } from "../services/agencyApi";
import type { AgencyBooking } from "../types/agency";
import { BookingStatusBadge } from "../components/AgencyStatusBadge";
import { formatSlotUtc, slotDateKey } from "../utils/agencyDisplay";
import { formatDateTime } from "../utils/statusDisplay";

type ViewMode = "upcoming" | "today" | "past" | "cancelled";

const VIEW_LABELS: Record<ViewMode, string> = {
  upcoming: "Upcoming",
  today: "Today",
  past: "Past",
  cancelled: "Cancelled",
};

export function AgencyCalendarPage() {
  const [bookings, setBookings] = useState<AgencyBooking[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<ViewMode>("upcoming");

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
    const timer = setInterval(() => void load(), 15000);
    return () => clearInterval(timer);
  }, [load]);

  const todayKey = new Date().toISOString().slice(0, 10);

  const sections = useMemo(() => {
    const isCancelled = (b: AgencyBooking) => b.status === "CANCELLED";
    const withDate = (b: AgencyBooking) => (b.slot_start_at ? slotDateKey(b.slot_start_at) : null);

    return {
      today: bookings.filter((b) => withDate(b) === todayKey && !isCancelled(b)),
      upcoming: bookings
        .filter((b) => {
          const key = withDate(b);
          return !isCancelled(b) && key !== null && key > todayKey;
        })
        .sort(
          (a, b) =>
            (a.slot_start_at ?? "").localeCompare(b.slot_start_at ?? ""),
        ),
      past: bookings
        .filter((b) => {
          const key = withDate(b);
          return !isCancelled(b) && key !== null && key < todayKey;
        })
        .sort(
          (a, b) =>
            (b.slot_start_at ?? "").localeCompare(a.slot_start_at ?? ""),
        ),
      cancelled: bookings.filter(isCancelled),
    } satisfies Record<ViewMode, AgencyBooking[]>;
  }, [bookings, todayKey]);

  const visible = sections[view];

  return (
    <>
      <div className="page__header">
        <div>
          <h1>Appointment calendar</h1>
          <p className="muted">
            All times shown in the slot's agency time zone. Every booking links
            back to its inquiry.
          </p>
        </div>
        <button className="btn btn--secondary" onClick={() => void load()}>
          Refresh
        </button>
      </div>

      <div className="row" style={{ marginBottom: "1rem" }}>
        {(Object.keys(VIEW_LABELS) as ViewMode[]).map((mode) => (
          <button
            key={mode}
            className={`btn btn--small ${view === mode ? "" : "btn--secondary"}`}
            onClick={() => setView(mode)}
          >
            {VIEW_LABELS[mode]} ({sections[mode].length})
          </button>
        ))}
      </div>

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

      {loading ? <div className="card">Loading calendar…</div> : null}

      {!loading && !error ? (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Booking</th>
                  <th>Inquiry</th>
                  <th>When</th>
                  <th>Location</th>
                  <th>Status</th>
                  <th>Booked at</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((booking) => (
                  <tr key={booking.id}>
                    <td className="mono small">{booking.reference}</td>
                    <td>
                      <Link className="mono small" to="/dashboard/inquiries">
                        {booking.inquiry_reference}
                      </Link>
                    </td>
                    <td className="small">
                      {booking.slot_start_at
                        ? `${formatSlotUtc(booking.slot_start_at)}${
                            booking.slot_end_at
                              ? ` – ${formatSlotUtc(booking.slot_end_at).slice(-5)}`
                              : ""
                          }`
                        : "—"}
                      {booking.slot_timezone ? (
                        <div className="muted small">{booking.slot_timezone}</div>
                      ) : null}
                    </td>
                    <td className="small">{booking.slot_location ?? "—"}</td>
                    <td>
                      <BookingStatusBadge status={booking.status} />
                    </td>
                    <td className="small muted">{formatDateTime(booking.created_at)}</td>
                  </tr>
                ))}
                {visible.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="empty-state">
                      No {VIEW_LABELS[view].toLowerCase()} appointments.
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
          </div>
        </div>
      ) : null}
    </>
  );
}
