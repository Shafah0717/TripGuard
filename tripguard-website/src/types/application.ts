import type { ApplicantProfile } from "./visa";

/**
 * Review state of an uploaded document. Uploading a file does NOT verify it;
 * a human reviewer must explicitly mark it reviewed before it can satisfy a
 * requirement.
 */
export type DocumentReviewStatus =
  | "UPLOADED"
  | "REVIEWED_OK"
  | "NEEDS_REUPLOAD";

export interface UploadedDocument {
  id: string;
  /** Dataset document id this upload is intended to satisfy, if known. */
  requirementId: string | null;
  fileName: string;
  uploadedAt: string;
  reviewStatus: DocumentReviewStatus;
  reviewerComment?: string;
}

export interface ReviewerNote {
  id: string;
  author: string;
  createdAt: string;
  body: string;
  /** Demo-only marker; notes are never persisted or sent. */
  kind: "note" | "info_request";
}

export type ApplicationStage =
  | "AWAITING_REVIEW"
  | "IN_REVIEW"
  | "COMPLETED";

export interface VisaApplication {
  id: string;
  /** Identifier shown to staff (e.g. masked passport or client reference). */
  applicantIdentifier: string;
  profile: Pick<ApplicantProfile, "nationality" | "passport_type" | "purpose">;
  visaCategoryId: string;
  plannedTravelDate: string | null;
  passportExpiryDate: string | null;
  submittedAt: string;
  stage: ApplicationStage;
  documents: UploadedDocument[];
  notes: ReviewerNote[];
  /** Marker that records this is synthetic demo data, not a real case. */
  isDemo: boolean;
}
