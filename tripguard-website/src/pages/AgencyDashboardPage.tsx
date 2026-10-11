import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  fetchDashboardStats,
  fetchInquiries,
} from "../services/agencyApi";
import type { AgencyInquiry, DashboardStats } from "../types/agency";
import { StatCard } from "../components/StatCard";
import { InquiryStatusBadge } from "../components/AgencyStatusBadge";
import { formatDateTime } from "../utils/statusDisplay";

interface AgencyDashboardState {
  stats: DashboardStats | null;
  inquiries: AgencyInquiry[];
  loading: boolean;
  error: string | null;
}

export function AgencyDashboardPage() {
  const [state, setState] = useState<AgencyDashboardState>({
    stats: null,
    inquiries: [],
    loading: true,
    error: null,
  });

  const load = useCallback(async () => {
    setState((prev) => ({ ...prev, loading: true, error: null }));
    try {
      const [stats, inquiries] = await Promise.all([
        fetchDashboardStats(),
        fetchInquiries(),
      ]);
      setState({ stats, inquiries: inquiries.slice(0, 5), loading: false, error: null });
    } catch (caught) {
      setState((prev) => ({
        ...prev,
        loading: false,
        error:
          caught instanceof Error
            ? caught.message
            : "Could not load the dashboard.",
      }));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <>
      <div className="page__header">
        <div>
          <h1>Dashboard</h1>
          <p className="muted">
            Live inquiry, slot and appointment activity from the shared TripGuard
            backend.
          </p>
        </div>
        <button className="btn btn--secondary" onClick={() => void load()}>
          Refresh
        </button>
      </div>

      {state.loading ? <div className="card">Loading dashboard…</div> : null}

      {state.error && !state.loading ? (
        <div className="card">
          <div className="notice notice--error">
            <p style={{ color: "inherit", margin: 0 }}>{state.error}</p>
          </div>
          <button className="btn" style={{ marginTop: "0.75rem" }} onClick={() => void load()}>
            Retry
          </button>
        </div>
      ) : null}

      {state.stats && !state.error ? (
        <>
          <div className="stats-grid">
            <StatCard label="New inquiries" value={state.stats.new_inquiries} />
            <StatCard
              label="Awaiting response"
              value={state.stats.inquiries_awaiting_response}
              hint="New or contacted, not yet booked or closed"
            />
            <StatCard
              label="Upcoming appointments"
              value={state.stats.upcoming_appointments}
            />
            <StatCard
              label="Today's appointments"
              value={state.stats.todays_appointments}
            />
            <StatCard
              label="Cancelled bookings"
              value={state.stats.cancelled_bookings}
            />
          </div>

          <div className="card">
            <div className="card__header">
              <h2 className="card__title">Latest inquiries</h2>
              <Link className="small" to="/dashboard/inquiries">
                View all
              </Link>
            </div>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Reference</th>
                    <th>Traveller</th>
                    <th>Visa route</th>
                    <th>Status</th>
                    <th>Created</th>
                  </tr>
                </thead>
                <tbody>
                  {state.inquiries.map((inquiry) => (
                    <tr key={inquiry.id}>
                      <td>
                        <Link
                          className="mono"
                          to={`/dashboard/inquiries/${inquiry.id}`}
                        >
                          {inquiry.reference}
                        </Link>
                      </td>
                      <td>{inquiry.traveller_name}</td>
                      <td>{inquiry.visa_route}</td>
                      <td>
                        <InquiryStatusBadge status={inquiry.status} />
                      </td>
                      <td className="small muted">
                        {formatDateTime(inquiry.created_at)}
                      </td>
                    </tr>
                  ))}
                  {state.inquiries.length === 0 ? (
                    <tr>
                      <td colSpan={5} className="empty-state">
                        No inquiries yet. New app inquiries appear here
                        automatically.
                      </td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
          </div>
        </>
      ) : null}
    </>
  );
}
