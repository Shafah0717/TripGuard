import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { RequirementsGate } from "../components/RequirementsGate";
import { DemoBanner } from "../components/DemoBanner";
import { StatusBadge } from "../components/StatusBadge";
import { stageDisplay, formatDateTime } from "../utils/statusDisplay";
import { getVisaCategories } from "../services/visaRequirementsService";
import { READINESS_STATUSES, type ReadinessStatus } from "../utils/visaReadiness";
import { useApplications } from "../state/ApplicationsStore";
import { useReadinessReports } from "../hooks/useReadinessReports";
import type { VisaRequirementsDataset } from "../types/visa";
import { readinessStatusDisplay } from "../utils/statusDisplay";

function ApplicationsContent({ dataset }: { dataset: VisaRequirementsDataset }) {
  const { applications } = useApplications();
  const reports = useReadinessReports(dataset, applications);
  const categories = getVisaCategories(dataset);

  const [query, setQuery] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("all");
  const [readinessFilter, setReadinessFilter] = useState<ReadinessStatus | "all">("all");

  const filtered = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return applications.filter((application) => {
      const matchesQuery =
        normalized.length === 0 ||
        application.id.toLowerCase().includes(normalized) ||
        application.applicantIdentifier.toLowerCase().includes(normalized);
      const matchesCategory =
        categoryFilter === "all" || application.visaCategoryId === categoryFilter;
      const report = reports.get(application.id);
      const matchesReadiness =
        readinessFilter === "all" || report?.overallStatus === readinessFilter;
      return matchesQuery && matchesCategory && matchesReadiness;
    });
  }, [applications, query, categoryFilter, readinessFilter, reports]);

  return (
    <>
      <DemoBanner />
      <div className="page__header">
        <div>
          <h1>Applications</h1>
          <p className="muted">
            Search and filter visa applications by ID, applicant, category, and readiness.
          </p>
        </div>
      </div>

      <div className="filters">
        <div className="field">
          <label htmlFor="search">Search</label>
          <input
            id="search"
            type="search"
            placeholder="Application ID or applicant identifier"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </div>
        <div className="field">
          <label htmlFor="category">Visa category</label>
          <select
            id="category"
            value={categoryFilter}
            onChange={(event) => setCategoryFilter(event.target.value)}
          >
            <option value="all">All categories</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.displayName}
                {category.supported ? "" : " (unsupported)"}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="readiness">Readiness</label>
          <select
            id="readiness"
            value={readinessFilter}
            onChange={(event) =>
              setReadinessFilter(event.target.value as ReadinessStatus | "all")
            }
          >
            <option value="all">All statuses</option>
            {READINESS_STATUSES.map((status) => (
              <option key={status} value={status}>
                {readinessStatusDisplay(status).label}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Application</th>
              <th>Applicant</th>
              <th>Nationality</th>
              <th>Visa category</th>
              <th>Readiness</th>
              <th>Missing</th>
              <th>Stage</th>
              <th>Submitted</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((application) => {
              const report = reports.get(application.id);
              const stage = stageDisplay(application.stage);
              return (
                <tr key={application.id}>
                  <td>
                    <Link className="mono" to={`/applications/${application.id}`}>
                      {application.id}
                    </Link>
                  </td>
                  <td>{application.applicantIdentifier}</td>
                  <td>{application.profile.nationality}</td>
                  <td>{report?.visaCategoryName ?? application.visaCategoryId}</td>
                  <td>{report ? <StatusBadge status={report.overallStatus} /> : "—"}</td>
                  <td>
                    {report && report.missingDocuments.length > 0 ? (
                      <span className="badge badge--red">
                        {report.missingDocuments.length} missing
                      </span>
                    ) : (
                      <span className="muted small">None</span>
                    )}
                  </td>
                  <td>
                    <span className={`badge ${stage.className}`}>{stage.label}</span>
                  </td>
                  <td className="small muted">{formatDateTime(application.submittedAt)}</td>
                </tr>
              );
            })}
            {filtered.length === 0 ? (
              <tr>
                <td colSpan={8} className="empty-state">
                  No applications match the current filters.
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>
      <p className="small muted" style={{ marginTop: "0.75rem" }}>
        Showing {filtered.length} of {applications.length} demo applications.
      </p>
    </>
  );
}

export function ApplicationsPage() {
  return (
    <RequirementsGate>
      {(dataset) => <ApplicationsContent dataset={dataset} />}
    </RequirementsGate>
  );
}
