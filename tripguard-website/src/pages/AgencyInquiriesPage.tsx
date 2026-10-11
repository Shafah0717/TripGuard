import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  deleteInquiry,
  fetchInquiries,
} from "../services/agencyApi";
import type { AgencyInquiry, InquiryStatus } from "../types/agency";
import { InquiryStatusBadge } from "../components/AgencyStatusBadge";
import { formatDateTime } from "../utils/statusDisplay";

const STATUS_OPTIONS: (InquiryStatus | "")[] = [
  "",
  "NEW",
  "CONTACTED",
  "SLOT_OFFERED",
  "BOOKED",
  "CLOSED",
];

export function AgencyInquiriesPage() {
  const [inquiries, setInquiries] = useState<AgencyInquiry[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<InquiryStatus | "">("");
  const [search, setSearch] = useState("");
  const [deletingId, setDeletingId] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchInquiries({
        status: statusFilter || undefined,
        search: search.trim() || undefined,
      });
      setInquiries(data);
    } catch (caught) {
      setError(
        caught instanceof Error ? caught.message : "Could not load inquiries.",
      );
    } finally {
      setLoading(false);
    }
  }, [statusFilter, search]);

  useEffect(() => {
    const handle = setTimeout(() => void load(), 250);
    return () => clearTimeout(handle);
  }, [load]);

  const remove = useCallback(async (inquiry: AgencyInquiry) => {
    const confirmed = window.confirm(
      `Delete ${inquiry.reference} and its linked slots and bookings? This cannot be undone.`,
    );
    if (!confirmed) return;
    setDeletingId(inquiry.id);
    setError(null);
    try {
      await deleteInquiry(inquiry.id);
      setInquiries((current) => current.filter((item) => item.id !== inquiry.id));
    } catch (caught) {
      setError(
        caught instanceof Error ? caught.message : "Could not delete the inquiry.",
      );
    } finally {
      setDeletingId(null);
    }
  }, []);

  const visible = useMemo(
    () =>
      [...inquiries].sort(
        (a, b) =>
          new Date(b.created_at).getTime() - new Date(a.created_at).getTime(),
      ),
    [inquiries],
  );

  return (
    <>
      <div className="page__header">
        <div>
          <h1>Inquiries</h1>
          <p className="muted">
            Consent-based contact requests from the TripGuard Android app.
            Documents stay on travellers' devices and are never visible here.
          </p>
        </div>
      </div>

      <div className="card">
        <div className="filters">
          <div className="field">
            <label htmlFor="search">Search by reference</label>
            <input
              id="search"
              placeholder="e.g. INQ-ABC123"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
          </div>
          <div className="field">
            <label htmlFor="status">Status</label>
            <select
              id="status"
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value as InquiryStatus | "")}
            >
              {STATUS_OPTIONS.map((option) => (
                <option key={option || "all"} value={option}>
                  {option === "" ? "All statuses" : option.replace(/_/g, " ").toLowerCase()}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>&nbsp;</label>
            <button className="btn btn--secondary" onClick={() => void load()}>
              Refresh
            </button>
          </div>
        </div>

        {error ? (
          <div className="notice notice--error">
            <p style={{ color: "inherit", margin: 0 }}>{error}</p>
            <button className="btn" style={{ marginTop: "0.5rem" }} onClick={() => void load()}>
              Retry
            </button>
          </div>
        ) : null}

        {loading ? <p>Loading inquiries…</p> : null}

        {!loading && !error ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Reference</th>
                  <th>Traveller</th>
                  <th>Contact</th>
                  <th>Visa route</th>
                  <th>Status</th>
                  <th>Booking</th>
                  <th>Created</th>
                  <th aria-label="Actions"></th>
                </tr>
              </thead>
              <tbody>
                {visible.map((inquiry) => (
                  <tr key={inquiry.id}>
                    <td>
                      <Link className="mono" to={`/dashboard/inquiries/${inquiry.id}`}>
                        {inquiry.reference}
                      </Link>
                    </td>
                    <td>{inquiry.traveller_name}</td>
                    <td className="small">
                      {inquiry.contact_email ?? inquiry.contact_phone ?? "—"}
                    </td>
                    <td className="small">{inquiry.visa_route}</td>
                    <td>
                      <InquiryStatusBadge status={inquiry.status} />
                    </td>
                    <td>
                      {inquiry.booking_reference ? (
                        <span className="mono small">{inquiry.booking_reference}</span>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td className="small muted">{formatDateTime(inquiry.created_at)}</td>
                    <td className="small">
                      <button
                        className="btn btn--danger btn--small"
                        disabled={deletingId === inquiry.id}
                        onClick={() => void remove(inquiry)}
                      >
                        {deletingId === inquiry.id ? "Deleting…" : "Delete"}
                      </button>
                    </td>
                  </tr>
                ))}
                {visible.length === 0 ? (
                  <tr>
                    <td colSpan={7} className="empty-state">
                      No inquiries match the current filters.
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
          </div>
        ) : null}
      </div>
    </>
  );
}
