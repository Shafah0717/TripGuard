import { Link } from "react-router-dom";
import { RequirementsGate } from "../components/RequirementsGate";
import { DemoBanner } from "../components/DemoBanner";
import { StatCard } from "../components/StatCard";
import { StatusBadge } from "../components/StatusBadge";
import { stageDisplay, formatDate, formatDateTime } from "../utils/statusDisplay";
import { useApplications } from "../state/ApplicationsStore";
import { useReadinessReports } from "../hooks/useReadinessReports";
import type { VisaRequirementsDataset } from "../types/visa";

function DashboardContent({ dataset }: { dataset: VisaRequirementsDataset }) {
  const { applications } = useApplications();
  const reports = useReadinessReports(dataset, applications);

  const total = applications.length;
  const awaitingReview = applications.filter(
    (application) => application.stage === "AWAITING_REVIEW",
  ).length;
  const withMissing = applications.filter(
    (application) => (reports.get(application.id)?.missingDocuments.length ?? 0) > 0,
  ).length;
  const manualReview = applications.filter((application) => {
    const report = reports.get(application.id);
    if (!report) return false;
    return (
      report.overallStatus === "MANUAL_REVIEW_REQUIRED" ||
      report.unverifiedCategoryItems.length > 0 ||
      !report.scope.supported
    );
  }).length;

  const recent = [...applications]
    .sort(
      (a, b) => new Date(b.submittedAt).getTime() - new Date(a.submittedAt).getTime(),
    )
    .slice(0, 5);

  return (
    <>
      <DemoBanner />
      <div className="page__header">
        <div>
          <h1>Agency Dashboard</h1>
          <p className="muted">
            UAE tourist visa document-readiness review for Indian ordinary passport holders.
          </p>
        </div>
        <Link className="btn" to="/applications">
          View all applications
        </Link>
      </div>

      <div className="stats-grid">
        <StatCard label="Total applications" value={total} />
        <StatCard label="Awaiting review" value={awaitingReview} />
        <StatCard
          label="Missing documents"
          value={withMissing}
          hint="At least one required document missing"
        />
        <StatCard
          label="Manual review"
          value={manualReview}
          hint="Unverified rules or out-of-scope"
        />
      </div>

      <div className="card">
        <div className="card__header">
          <h2 className="card__title">Recent applications</h2>
          <Link className="small" to="/applications">
            View all
          </Link>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Application</th>
                <th>Applicant</th>
                <th>Visa category</th>
                <th>Stage</th>
                <th>Readiness</th>
                <th>Submitted</th>
              </tr>
            </thead>
            <tbody>
              {recent.map((application) => {
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
                    <td>{report?.visaCategoryName ?? application.visaCategoryId}</td>
                    <td>
                      <span className={`badge ${stage.className}`}>{stage.label}</span>
                    </td>
                    <td>{report ? <StatusBadge status={report.overallStatus} /> : "—"}</td>
                    <td className="small muted">{formatDateTime(application.submittedAt)}</td>
                  </tr>
                );
              })}
              {recent.length === 0 ? (
                <tr>
                  <td colSpan={6} className="empty-state">
                    No applications available.
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
        <p className="small muted" style={{ marginTop: "0.75rem" }}>
          Dataset {dataset.dataset_id} v{dataset.schema_version} · last verified{" "}
          {formatDate(dataset.research_metadata.last_verified_at)} · status{" "}
          {dataset.research_metadata.research_status}.
        </p>
      </div>
    </>
  );
}

export function DashboardPage() {
  return (
    <RequirementsGate>
      {(dataset) => <DashboardContent dataset={dataset} />}
    </RequirementsGate>
  );
}
