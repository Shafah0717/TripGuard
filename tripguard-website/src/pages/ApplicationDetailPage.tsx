import { useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { RequirementsGate } from "../components/RequirementsGate";
import { DemoBanner } from "../components/DemoBanner";
import { StatusBadge } from "../components/StatusBadge";
import {
  formatDate,
  formatDateTime,
  reviewStatusDisplay,
  stageDisplay,
} from "../utils/statusDisplay";
import {
  evaluateApplicationReadiness,
  type CheckResult,
  type RequirementResult,
} from "../utils/visaReadiness";
import { getRequirementsForCategory } from "../services/visaRequirementsService";
import { useApplications } from "../state/ApplicationsStore";
import type { VisaRequirementsDataset, VisaType } from "../types/visa";
import type {
  ApplicationStage,
  DocumentReviewStatus,
  VisaApplication,
} from "../types/application";

const STAGES: ApplicationStage[] = ["AWAITING_REVIEW", "IN_REVIEW", "COMPLETED"];

function InfoItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="info-item__label">{label}</div>
      <div className="info-item__value">{value}</div>
    </div>
  );
}

function RequirementRow({ result }: { result: RequirementResult }) {
  return (
    <li className="list__item">
      <div className="row row--between">
        <strong>{result.requirementName}</strong>
        <StatusBadge status={result.status} />
      </div>
      <p className="small" style={{ margin: "0.35rem 0 0" }}>
        {result.reason}
      </p>
      {result.uploadedDocuments.length > 0 ? (
        <p className="small muted" style={{ margin: "0.25rem 0 0" }}>
          Files: {result.uploadedDocuments.map((doc) => doc.fileName).join(", ")}
        </p>
      ) : null}
      {result.unverifiedRuleIds.length > 0 ? (
        <p className="tag-source" style={{ margin: "0.25rem 0 0" }}>
          Unverified rule(s): {result.unverifiedRuleIds.join(", ")}
        </p>
      ) : null}
      {result.sourceIds.length > 0 ? (
        <p className="tag-source" style={{ margin: "0.15rem 0 0" }}>
          Sources: {result.sourceIds.join(", ")}
        </p>
      ) : null}
    </li>
  );
}

function CheckRow({ check }: { check: CheckResult }) {
  return (
    <li className="list__item">
      <div className="row row--between">
        <strong>{check.label}</strong>
        <StatusBadge status={check.status} />
      </div>
      <p className="small" style={{ margin: "0.35rem 0 0" }}>
        {check.detail}
      </p>
    </li>
  );
}

function DocumentInventory({
  application,
  visaType,
  onReview,
}: {
  application: VisaApplication;
  visaType: VisaType;
  onReview: (documentId: string, status: DocumentReviewStatus) => void;
}) {
  const nameByRequirement = useMemo(() => {
    const map = new Map<string, string>();
    for (const doc of visaType.documents) map.set(doc.id, doc.name);
    for (const doc of visaType.conditional_documents) map.set(doc.id, doc.name);
    return map;
  }, [visaType]);

  if (application.documents.length === 0) {
    return (
      <p className="empty-state">No documents have been uploaded for this application.</p>
    );
  }

  return (
    <ul className="list">
      {application.documents.map((document) => {
        const review = reviewStatusDisplay(document.reviewStatus);
        const matchedName = document.requirementId
          ? nameByRequirement.get(document.requirementId)
          : undefined;
        return (
          <li className="list__item" key={document.id}>
            <div className="row row--between">
              <div>
                <strong>{document.fileName}</strong>
                <div className="small muted">
                  {document.requirementId
                    ? `Maps to: ${matchedName ?? document.requirementId}`
                    : "Not linked to a known requirement"}
                </div>
                <div className="small muted">
                  Uploaded {formatDateTime(document.uploadedAt)}
                </div>
              </div>
              <span className={`badge ${review.className}`}>{review.label}</span>
            </div>
            <div className="actions-row">
              <ReviewButton
                label="Mark reviewed"
                disabled={document.reviewStatus === "REVIEWED_OK"}
                onClick={() => onReview(document.id, "REVIEWED_OK")}
              />
              <ReviewButton
                label="Needs re-upload"
                variant="danger"
                disabled={document.reviewStatus === "NEEDS_REUPLOAD"}
                onClick={() => onReview(document.id, "NEEDS_REUPLOAD")}
              />
              <ReviewButton
                label="Reset to uploaded"
                variant="secondary"
                disabled={document.reviewStatus === "UPLOADED"}
                onClick={() => onReview(document.id, "UPLOADED")}
              />
            </div>
          </li>
        );
      })}
    </ul>
  );
}

function ReviewButton({
  label,
  onClick,
  disabled,
  variant = "secondary",
}: {
  label: string;
  onClick: () => void;
  disabled?: boolean;
  variant?: "secondary" | "danger" | "primary";
}) {
  const variantClass =
    variant === "danger"
      ? "btn btn--small btn--danger"
      : variant === "primary"
        ? "btn btn--small"
        : "btn btn--small btn--secondary";
  return (
    <button type="button" className={variantClass} onClick={onClick} disabled={disabled}>
      {label}
    </button>
  );
}

function RequirementDefinition({
  requirement,
}: {
  requirement: VisaType["documents"][number];
}) {
  const specs = requirement.specifications;
  return (
    <li className="list__item">
      <div className="row row--between">
        <strong>{requirement.name}</strong>
        <span className="badge badge--slate">
          {requirement.mandatory ? "Mandatory" : "Optional"}
        </span>
      </div>
      {specs.other_conditions?.length ? (
        <p className="small" style={{ margin: "0.35rem 0 0" }}>
          {specs.other_conditions.join(" ")}
        </p>
      ) : null}
      {requirement.evidence_notes.length > 0 ? (
        <p className="tag-source" style={{ margin: "0.25rem 0 0" }}>
          Evidence: {requirement.evidence_notes.join(" ")}
        </p>
      ) : null}
      {requirement.verification_rules.length > 0 ? (
        <ul className="small" style={{ margin: "0.35rem 0 0", paddingLeft: "1.1rem" }}>
          {requirement.verification_rules.map((rule) => (
            <li key={rule.id}>
              <span className="mono">{rule.rule_type}</span> — {rule.description}{" "}
              {rule.human_review_required ? (
                <span className="badge badge--orange">human review</span>
              ) : null}
            </li>
          ))}
        </ul>
      ) : null}
      {requirement.source_ids.length > 0 ? (
        <p className="tag-source" style={{ margin: "0.25rem 0 0" }}>
          Sources: {requirement.source_ids.join(", ")}
        </p>
      ) : null}
    </li>
  );
}

function DetailContent({
  dataset,
  application,
}: {
  dataset: VisaRequirementsDataset;
  application: VisaApplication;
}) {
  const { reviewDocument, addNote, setStage } = useApplications();
  const [noteText, setNoteText] = useState("");

  const report = useMemo(
    () => evaluateApplicationReadiness(dataset, application),
    [dataset, application],
  );
  const categoryRequirements = useMemo(
    () => getRequirementsForCategory(dataset, application.visaCategoryId),
    [dataset, application.visaCategoryId],
  );

  const stage = stageDisplay(application.stage);

  const handleReview = (documentId: string, status: DocumentReviewStatus) => {
    reviewDocument(application.id, documentId, status);
  };

  const submitNote = (kind: "note" | "info_request") => {
    const body = noteText.trim();
    if (!body) return;
    addNote(application.id, body, kind);
    setNoteText("");
  };

  return (
    <>
      <p>
        <Link className="small" to="/applications">
          ← Back to applications
        </Link>
      </p>
      <DemoBanner />

      <div className="page__header">
        <div>
          <h1>{application.id}</h1>
          <p className="muted">{application.applicantIdentifier}</p>
        </div>
        <div className="row">
          <span className={`badge ${stage.className}`}>{stage.label}</span>
          <StatusBadge status={report.overallStatus} />
        </div>
      </div>

      {report.disclaimer ? (
        <div className="notice notice--info" style={{ marginBottom: "1rem" }}>
          {report.disclaimer}
        </div>
      ) : null}

      <div className="detail-grid">
        <div>
          <div className="card">
            <h2 className="card__title">Applicant details</h2>
            <div className="info-grid">
              <InfoItem label="Applicant identifier" value={application.applicantIdentifier} />
              <InfoItem label="Nationality" value={application.profile.nationality} />
              <InfoItem label="Passport type" value={application.profile.passport_type} />
              <InfoItem label="Purpose" value={application.profile.purpose} />
              <InfoItem label="Visa category" value={report.visaCategoryName} />
              <InfoItem
                label="Planned travel date"
                value={formatDate(application.plannedTravelDate)}
              />
              <InfoItem
                label="Passport expiry"
                value={formatDate(application.passportExpiryDate)}
              />
              <InfoItem label="Submitted" value={formatDateTime(application.submittedAt)} />
            </div>
            {!report.scope.supported ? (
              <div className="notice notice--warn" style={{ marginTop: "0.85rem" }}>
                {report.scope.reason}
              </div>
            ) : null}
          </div>

          <div className="card">
            <div className="card__header">
              <h2 className="card__title">Uploaded document inventory</h2>
              <span className="small muted">
                Upload ≠ verified. A reviewer must confirm each document.
              </span>
            </div>
            {categoryRequirements ? (
              <DocumentInventory
                application={application}
                visaType={categoryRequirements.visaType}
                onReview={handleReview}
              />
            ) : (
              <p className="empty-state">Visa category not found in the dataset.</p>
            )}
          </div>

          <div className="card">
            <h2 className="card__title">Document readiness report</h2>
            <div className="section-title">
              <h3>All requirements</h3>
              <span className="badge badge--slate">{report.results.length}</span>
            </div>
            <ul className="list">
              {report.results.map((result) => (
                <RequirementRow key={result.requirementId} result={result} />
              ))}
              {report.results.length === 0 ? (
                <li className="list__item list__item--muted">
                  No requirements could be loaded for this category.
                </li>
              ) : null}
            </ul>

            <div className="section-title">
              <h3>Deterministic checks</h3>
            </div>
            <ul className="list">
              {report.checks.map((check) => (
                <CheckRow key={check.id} check={check} />
              ))}
            </ul>

            {report.unverifiedCategoryItems.length > 0 ? (
              <>
                <div className="section-title">
                  <h3>Unverified requirements (from dataset)</h3>
                  <span className="badge badge--orange">
                    {report.unverifiedCategoryItems.length}
                  </span>
                </div>
                <ul className="list">
                  {report.unverifiedCategoryItems.map((item) => (
                    <li className="list__item" key={item.id}>
                      <strong>{item.label}</strong>
                      <p className="small" style={{ margin: "0.35rem 0 0" }}>
                        {item.detail}
                      </p>
                      {item.sourceIds.length > 0 ? (
                        <p className="tag-source" style={{ margin: "0.15rem 0 0" }}>
                          Sources: {item.sourceIds.join(", ")}
                        </p>
                      ) : null}
                    </li>
                  ))}
                </ul>
              </>
            ) : null}
          </div>

          {categoryRequirements ? (
            <div className="card">
              <h2 className="card__title">Applicable requirements from the dataset</h2>
              <ul className="list">
                {categoryRequirements.documents.map((requirement) => (
                  <RequirementDefinition
                    key={requirement.id}
                    requirement={requirement}
                  />
                ))}
              </ul>
              {categoryRequirements.conditionalDocuments.length > 0 ? (
                <>
                  <div className="section-title">
                    <h3>Conditional documents</h3>
                  </div>
                  <ul className="list">
                    {categoryRequirements.conditionalDocuments.map((conditional) => (
                      <li className="list__item list__item--muted" key={conditional.id}>
                        <strong>{conditional.name}</strong>
                        <p className="small" style={{ margin: "0.35rem 0 0" }}>
                          {conditional.applicability.conditions.join(" ")}
                        </p>
                        <p className="tag-source" style={{ margin: "0.15rem 0 0" }}>
                          Status: {conditional.requirement_status}
                        </p>
                      </li>
                    ))}
                  </ul>
                </>
              ) : null}
            </div>
          ) : null}
        </div>

        <div>
          <div className="card">
            <h2 className="card__title">Readiness summary</h2>
            <div className="row row--between" style={{ marginBottom: "0.5rem" }}>
              <span className="muted">Overall</span>
              <StatusBadge status={report.overallStatus} />
            </div>
            <div className="row row--between" style={{ marginBottom: "0.5rem" }}>
              <span className="muted">Ready for submission</span>
              <span
                className={`badge ${report.readyForSubmission ? "badge--green" : "badge--red"}`}
              >
                {report.readyForSubmission ? "Yes" : "No"}
              </span>
            </div>
            <div className="row row--between" style={{ marginBottom: "0.5rem" }}>
              <span className="muted">Mandatory documents satisfied</span>
              <span
                className={`badge ${
                  report.allMandatoryDocumentsSatisfied ? "badge--green" : "badge--amber"
                }`}
              >
                {report.allMandatoryDocumentsSatisfied ? "Yes" : "Not yet"}
              </span>
            </div>
            <p className="small muted">
              Unverified rules prevent an application from being marked ready. This console
              never guarantees visa approval.
            </p>

            <div className="section-title">
              <h3>Recommended next actions</h3>
            </div>
            <ul className="list">
              {report.recommendedActions.map((action) => (
                <li className="list__item" key={action}>
                  {action}
                </li>
              ))}
            </ul>

            {report.missingDocuments.length > 0 ? (
              <>
                <div className="section-title">
                  <h3>Missing documents</h3>
                  <span className="badge badge--red">
                    {report.missingDocuments.length}
                  </span>
                </div>
                <ul className="list">
                  {report.missingDocuments.map((result) => (
                    <RequirementRow key={result.requirementId} result={result} />
                  ))}
                </ul>
              </>
            ) : null}
          </div>

          <div className="card">
            <h2 className="card__title">Stage</h2>
            <div className="actions-row">
              {STAGES.map((candidate) => (
                <button
                  key={candidate}
                  type="button"
                  className={`btn btn--small ${
                    application.stage === candidate ? "" : "btn--secondary"
                  }`}
                  disabled={application.stage === candidate}
                  onClick={() => setStage(application.id, candidate)}
                >
                  {stageDisplay(candidate).label}
                </button>
              ))}
            </div>
          </div>

          <div className="card">
            <h2 className="card__title">Reviewer notes</h2>
            <div className="textarea-row">
              <label htmlFor="note" className="small muted">
                Demo only — notes are kept in this browser session and are not saved or sent.
              </label>
              <textarea
                id="note"
                rows={3}
                value={noteText}
                placeholder="Add a reviewer note or information request…"
                onChange={(event) => setNoteText(event.target.value)}
              />
              <div className="actions-row">
                <button
                  type="button"
                  className="btn btn--small"
                  disabled={noteText.trim().length === 0}
                  onClick={() => submitNote("note")}
                >
                  Add note
                </button>
                <button
                  type="button"
                  className="btn btn--small btn--secondary"
                  disabled={noteText.trim().length === 0}
                  onClick={() => submitNote("info_request")}
                >
                  Request further information
                </button>
              </div>
            </div>
            <div className="divider" />
            {application.notes.length === 0 ? (
              <p className="empty-state">No reviewer notes yet.</p>
            ) : (
              <ul className="list">
                {application.notes.map((note) => (
                  <li className="list__item" key={note.id}>
                    <div className="row row--between">
                      <strong>{note.author}</strong>
                      <span
                        className={`badge ${
                          note.kind === "info_request" ? "badge--amber" : "badge--blue"
                        }`}
                      >
                        {note.kind === "info_request" ? "Info request" : "Note"}
                      </span>
                    </div>
                    <p className="small" style={{ margin: "0.35rem 0 0" }}>
                      {note.body}
                    </p>
                    <p className="tag-source" style={{ margin: "0.15rem 0 0" }}>
                      {formatDateTime(note.createdAt)}
                    </p>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {categoryRequirements ? (
            <div className="card">
              <h2 className="card__title">Sources</h2>
              <ul className="list">
                {categoryRequirements.sources.map((source) => (
                  <li className="list__item" key={source.id}>
                    <strong className="mono">{source.id}</strong> — {source.title}
                    <div className="small muted">{source.organization}</div>
                    <a
                      className="small"
                      href={source.url}
                      target="_blank"
                      rel="noreferrer"
                    >
                      {source.url}
                    </a>
                    <p className="tag-source" style={{ margin: "0.35rem 0 0" }}>
                      Accessed {source.accessed_at} · {source.source_type}
                    </p>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </div>
      </div>
    </>
  );
}

function DetailRoute({ dataset }: { dataset: VisaRequirementsDataset }) {
  const { applicationId } = useParams<{ applicationId: string }>();
  const { getApplication } = useApplications();
  const application = applicationId ? getApplication(applicationId) : undefined;

  if (!application) {
    return (
      <div className="card">
        <h2>Application not found</h2>
        <p>The requested application ID does not exist in the demo dataset.</p>
        <Link className="btn" to="/applications">
          Back to applications
        </Link>
      </div>
    );
  }

  return <DetailContent dataset={dataset} application={application} />;
}

export function ApplicationDetailPage() {
  return (
    <RequirementsGate>
      {(dataset) => <DetailRoute dataset={dataset} />}
    </RequirementsGate>
  );
}
