import type { ReadinessStatus } from "./visaReadiness";
import type { ApplicationStage, DocumentReviewStatus } from "../types/application";

export interface StatusDisplay {
  label: string;
  className: string;
}

const READINESS_DISPLAY: Record<ReadinessStatus, StatusDisplay> = {
  SATISFIED: { label: "Satisfied", className: "badge--green" },
  MISSING: { label: "Missing documents", className: "badge--red" },
  POTENTIAL_ISSUE: { label: "Potential issue", className: "badge--amber" },
  MANUAL_REVIEW_REQUIRED: { label: "Manual review required", className: "badge--purple" },
  NOT_YET_CHECKED: { label: "Not yet checked", className: "badge--slate" },
  RULE_UNVERIFIED: { label: "Rule unverified", className: "badge--orange" },
};

export function readinessStatusDisplay(status: ReadinessStatus): StatusDisplay {
  return READINESS_DISPLAY[status] ?? { label: status, className: "badge--slate" };
}

const STAGE_DISPLAY: Record<ApplicationStage, StatusDisplay> = {
  AWAITING_REVIEW: { label: "Awaiting review", className: "badge--blue" },
  IN_REVIEW: { label: "In review", className: "badge--amber" },
  COMPLETED: { label: "Completed", className: "badge--green" },
};

export function stageDisplay(stage: ApplicationStage): StatusDisplay {
  return STAGE_DISPLAY[stage] ?? { label: stage, className: "badge--slate" };
}

const REVIEW_DISPLAY: Record<DocumentReviewStatus, StatusDisplay> = {
  UPLOADED: { label: "Uploaded (not verified)", className: "badge--slate" },
  REVIEWED_OK: { label: "Reviewed OK", className: "badge--green" },
  NEEDS_REUPLOAD: { label: "Needs re-upload", className: "badge--red" },
};

export function reviewStatusDisplay(status: DocumentReviewStatus): StatusDisplay {
  return REVIEW_DISPLAY[status] ?? { label: status, className: "badge--slate" };
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return "—";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return parsed.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}
